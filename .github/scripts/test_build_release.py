import copy
import json
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_release as release


class ReleaseContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.downloads = self.root / 'downloads'
        self.output = self.root / 'release'
        self.rows = json.loads((Path(__file__).parent.parent / 'build-matrix.json').read_text())
        ranges = {b: b for b in release.BRANCHES} | {'26.1': '26.1-26.1.2', 'plugin': ''}
        for row in self.rows:
            row.update(sha='a' * 40, version='1.3.0-beta-3', range=ranges[row['branch']])
        self.manifest = {'repository': 'test/repository', 'controller': 'a' * 40,
                         'tag': 'v1.3.0-beta-3', 'publish': True, 'run_id': '123', 'entries': self.rows}

    def fixture(self):
        plan = self.downloads / 'build-plan'
        plan.mkdir(parents=True)
        release.dump(plan / 'build-plan.json', self.manifest)
        for row in self.rows:
            folder = self.downloads / ('build-' + row['id'])
            folder.mkdir()
            artifacts = []
            for module in row['modules']:
                name = release.filename(row, module)
                path = folder / name
                version = release.artifact_version(row)
                with zipfile.ZipFile(path, 'w') as jar:
                    jar.writestr('META-INF/musichud-distribution.properties',
                                 'distribution=' + row.get('distribution', 'standard') + '\nversion=' + version + '\n')
                    jar.writestr('indi/mopelotus/musichud/Test.class', b'fixture')
                    if module == 'fabric':
                        jar.writestr('fabric.mod.json', json.dumps({'id': 'musichud_tuneweave', 'version': version}))
                    elif module == 'neoforge':
                        jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="musichud_tuneweave"\nversion="' + version + '"\n')
                    elif module == 'velocity':
                        jar.writestr('velocity-plugin.json', json.dumps({'id': 'musichud_tuneweave', 'version': version}))
                    else:
                        jar.writestr('plugin.yml' if module == 'paper' else 'bungee.yml', "version: '" + version + "'\n")
                artifacts.append({'name': name, 'module': module, 'sha256': release.digest(path)})
            release.dump(folder / 'build.json', {'entry': row, 'artifacts': artifacts})

    def test_complete_matrix_publishes_fifteen_jars_and_checksums(self):
        self.fixture()
        release.bundle(self.downloads, self.output)
        manifest = release.verify_bundle(self.output)
        self.assertEqual(15, len(list(self.output.glob('*.jar'))))
        self.assertEqual({'1.21.1', '1.21.8', '1.21.10', '1.21.11', '26.1.2', '26.2', 'plugin'},
                         {a['endpoint'] for a in manifest['artifacts']})
        self.assertEqual(release.BRANCHES, {a['branch'] for a in manifest['artifacts']})

    def test_cf_bundle_versions_and_publication_boundary(self):
        for row in self.rows:
            row['distribution'] = 'cf'
        self.fixture()
        release.bundle(self.downloads, self.output)
        self.assertEqual(15, len(list(self.output.glob('*-cf*.jar'))))
        with patch.object(release.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'review artifacts only'):
                release.publish(self.output)
            run.assert_not_called()

    def test_cf_nested_acquisition_and_forged_marker_are_rejected(self):
        for row in self.rows:
            row['distribution'] = 'cf'
        self.fixture()
        row = self.rows[0]
        path = self.downloads / ('build-' + row['id']) / release.filename(row, 'fabric')
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as jar:
            jar.writestr('Acquisition.class', b'ApiServerFetcher')
        with zipfile.ZipFile(path, 'a') as jar:
            jar.writestr('META-INF/jars/hidden.jar', nested.getvalue())
        with self.assertRaisesRegex(ValueError, 'Acquisition code'):
            release.verify_jar(path, row, 'fabric')
        standard = row | {'distribution': 'standard'}
        with self.assertRaisesRegex(ValueError, 'marker mismatch'):
            release.verify_jar(path, standard, 'fabric')

    def test_unsafe_and_mixed_distributions_are_rejected(self):
        for value in ('CF', '', '../cf', 'cf\n-Pother=x', '--init-script=x', None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.validate_row(self.rows[0] | {'distribution': value})
        with self.assertRaisesRegex(ValueError, 'Base version'):
            release.validate_row(self.rows[0] | {'version': '1.3.0-beta-3-cf'})
        self.rows[0]['distribution'] = 'cf'
        with self.assertRaisesRegex(ValueError, 'Mixed distributions'):
            release.validate_matrix(self.rows)

    def test_cf_rejects_youtube_classes_nested_archives_and_retained_references(self):
        for name, content in (
                ('com/sedmelluq/discord/lavaplayer/source/youtube/YoutubeAccessTokenTracker.class', b'bytecode'),
                ('retained.class', b'com/sedmelluq/discord/lavaplayer/source/youtube/YoutubeAudioSourceManager'),
                ('reflection.class', b'com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager'),
                ('com/sedmelluq/discord/lavaplayer/source/AudioSourceManagers.class', b'bytecode')):
            for nested in (False, True):
                with self.subTest(name=name, nested=nested):
                    raw = io.BytesIO()
                    with zipfile.ZipFile(raw, 'w') as jar:
                        jar.writestr(name, content)
                    if nested:
                        wrapper = io.BytesIO()
                        with zipfile.ZipFile(wrapper, 'w') as jar:
                            jar.writestr('META-INF/jars/library.jar', raw.getvalue())
                        raw = wrapper
                    with zipfile.ZipFile(raw) as jar, self.assertRaisesRegex(ValueError, 'YouTube'):
                        release.verify_cf_contents(jar)

    def test_cf_source_override_rejects_unrelated_game_changes(self):
        row = self.rows[0] | {'distribution': 'cf', 'release_source_sha': 'b' * 40}
        files = 'gradle/distribution.gradle\ngradle/verify-distribution.gradle\ngradle/CfAudioSmoke.java'
        with patch.object(release.subprocess, 'run'), patch.object(release, 'run', return_value=files):
            release.verify_cf_source(self.root, row)
        with patch.object(release.subprocess, 'run'), patch.object(release, 'run', return_value=files + '\ncommon/src/Main.java'):
            with self.assertRaisesRegex(ValueError, 'only the reviewed packaging'):
                release.verify_cf_source(self.root, row)

    def test_missing_neoforge_or_modified_jar_fails_before_bundle_creation(self):
        self.fixture()
        row = self.rows[0]
        jar = self.downloads / ('build-' + row['id']) / release.filename(row, 'neoforge')
        jar.unlink()
        with self.assertRaises(ValueError):
            release.bundle(self.downloads, self.output)
        self.assertFalse(self.output.exists())

    def test_wrong_source_revision_is_rejected(self):
        self.fixture()
        path = self.downloads / ('build-' + self.rows[0]['id']) / 'build.json'
        data = release.load_json(path)
        data['entry']['sha'] = 'b' * 40
        release.dump(path, data)
        with self.assertRaises(ValueError):
            release.bundle(self.downloads, self.output)

    def test_corrupt_artifact_and_extra_files_are_rejected(self):
        self.fixture()
        folder = self.downloads / ('build-' + self.rows[0]['id'])
        next(folder.glob('*.jar')).write_bytes(b'corrupt')
        with self.assertRaises(ValueError):
            release.bundle(self.downloads, self.output)

    def test_checksum_path_traversal_and_unlisted_files_are_rejected(self):
        self.fixture()
        release.bundle(self.downloads, self.output)
        (self.output / 'extra.txt').write_text('not in checksums')
        with self.assertRaises(ValueError):
            release.verify_bundle(self.output)
        (self.output / 'extra.txt').unlink()
        with (self.output / 'SHA256SUMS').open('a') as file:
            file.write('a' * 64 + '  ../secret\n')
        with self.assertRaises(ValueError):
            release.verify_bundle(self.output)

    def test_matrix_rejects_duplicate_missing_and_unsafe_build_controls(self):
        for mutation in ('duplicate', 'missing_loader', 'property', 'revision', 'publish'):
            rows = copy.deepcopy(self.rows)
            if mutation == 'duplicate': rows[1]['id'] = rows[0]['id']
            elif mutation == 'missing_loader': rows[0]['modules'] = ['fabric']
            elif mutation == 'property': rows[0]['properties'] = {'init-script': '/tmp/injected'}
            elif mutation == 'revision': rows[0]['sha'] = '--upload-pack=anything'
            else: rows[0]['publish'] = True
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                release.validate_matrix(rows)

    def test_pull_requests_and_version_branch_dispatch_cannot_publish(self):
        self.assertFalse(release.release_requested('pull_request', 'refs/pull/1/merge', False))
        self.assertFalse(release.release_requested('push', 'refs/heads/26.2', False))
        self.assertTrue(release.release_requested('push', 'refs/tags/v1.3.0-beta-3', False))
        for event, ref in [('pull_request', 'refs/heads/26.2'), ('workflow_dispatch', 'refs/heads/plugin')]:
            with self.assertRaises(ValueError): release.release_requested(event, ref, True)

    def test_release_stays_draft_until_all_uploads_succeed(self):
        self.fixture()
        release.bundle(self.downloads, self.output)
        env = {'GITHUB_REPOSITORY': 'test/repository', 'RELEASE_TAG': 'v1.3.0-beta-3', 'GITHUB_RUN_ID': '123'}
        with patch.dict(os.environ, env), patch.object(release, 'api', side_effect=[[], {'sha': 'a' * 40}]), \
             patch.object(release.subprocess, 'run') as run:
            release.publish(self.output)
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual('api', commands[0][1])
            self.assertIn('--draft', commands[1])
            self.assertEqual('upload', commands[2][2])
            self.assertIn('--draft=false', commands[3])
            self.assertIn('--latest=false', commands[3])
        with patch.dict(os.environ, env), patch.object(release, 'api', side_effect=[[], {'sha': 'a' * 40}]), \
             patch.object(release.subprocess, 'run', side_effect=[None, None, RuntimeError('upload failed')]) as run:
            with self.assertRaises(RuntimeError): release.publish(self.output)
            self.assertEqual(3, run.call_count, 'must never publish after partial upload')

    def test_read_only_metadata_retries_are_bounded(self):
        failure = release.subprocess.CalledProcessError(1, ['gh', 'api'])
        with patch.object(release, 'run', side_effect=[failure, '{"sha":"ok"}']) as run, patch.object(release.time, 'sleep'):
            self.assertEqual({'sha': 'ok'}, release.api('test/repository', 'commits/main'))
            self.assertEqual(2, run.call_count)
        with patch.object(release, 'run', side_effect=failure) as run, patch.object(release.time, 'sleep'):
            with self.assertRaises(release.subprocess.CalledProcessError):
                release.api('test/repository', 'commits/main')
            self.assertEqual(4, run.call_count)

    def test_existing_wrong_tag_cannot_create_a_release(self):
        self.fixture()
        release.bundle(self.downloads, self.output)
        env = {'GITHUB_REPOSITORY': 'test/repository', 'RELEASE_TAG': 'v1.3.0-beta-3', 'GITHUB_RUN_ID': '123'}
        with patch.dict(os.environ, env), patch.object(release, 'api', side_effect=[
                [{'ref': 'refs/tags/v1.3.0-beta-3'}], {'sha': 'b' * 40}]), patch.object(release.subprocess, 'run') as run:
            with self.assertRaises(ValueError): release.publish(self.output)
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
