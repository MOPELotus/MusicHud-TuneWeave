import copy
import base64
import json
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_release as release


def rewrite_checksums(folder):
    (folder / 'SHA256SUMS').write_text(''.join(
        f'{release.digest(path)}  {path.name}\n' for path in sorted(folder.iterdir()) if path.name != 'SHA256SUMS'))


def historical_bundle(folder):
    manifest = release.load_json(folder / 'build-manifest.json')
    manifest['entries'] = [row for row in manifest['entries'] if row['branch'] != '26.3']
    for artifact in manifest['artifacts']:
        if artifact['branch'] == '26.3':
            (folder / artifact['name']).unlink()
    manifest['artifacts'] = [a for a in manifest['artifacts'] if a['branch'] != '26.3']
    release.dump(folder / 'build-manifest.json', manifest)
    rewrite_checksums(folder)
    return manifest


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
                    if module in ('fabric', 'neoforge') and tuple(map(int, row['version'].split('-', 1)[0].split('.'))) >= (1, 3, 1):
                        games = {'1.21.6-1.21.8': '1.21.6,1.21.7,1.21.8',
                                 '26.1-26.1.2': '26.1,26.1.1,26.1.2'}.get(row['range'], row['range'].replace('-', ','))
                        edition = row.get('distribution', 'standard')
                        identity = (f'id=musichud_tuneweave\nversion={version}\ndistribution={edition}\n'
                                    f'loader={module}\nminecraft={games}\n')
                        if edition == 'standard':
                            identity += 'service_profiles=future-http-v2\n'
                        jar.writestr('META-INF/musichud-update.properties', identity)
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

    def test_complete_matrix_publishes_seventeen_jars_and_checksums(self):
        self.fixture()
        release.bundle(self.downloads, self.output)
        manifest = release.verify_bundle(self.output)
        self.assertEqual(17, len(list(self.output.glob('*.jar'))))
        self.assertEqual({'1.21.1', '1.21.8', '1.21.10', '1.21.11', '26.1.2', '26.2', '26.3', 'plugin'},
                         {a['endpoint'] for a in manifest['artifacts']})
        self.assertEqual(release.BRANCHES, {a['branch'] for a in manifest['artifacts']})

    def test_product_version_accepts_exactly_three_or_four_numeric_components(self):
        for version in ('1.3.1', '1.3.1.1', '1.3.1.1-beta.2'):
            release.validate_row(self.rows[0] | {'version': version})
        for version in ('1.3', '1.3.1.1.0', '1..3.1', '.1.3.1', '1.3.1.', '1.3.1.1+meta',
                        '1.3.1.1/path', '1.3.1.1\n', '１.3.1.1', '1.3.1.1-cf.1'):
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.validate_row(self.rows[0] | {'version': version})

    def test_mixed_version_review_bundle_keeps_all_jars_without_mislabelled_catalog(self):
        for edition in ('standard', 'cf'):
            with self.subTest(edition=edition):
                self.downloads = self.root / ('downloads-' + edition)
                self.output = self.root / ('release-' + edition)
                self.manifest['publish'] = False
                for row in self.rows:
                    row['version'] = '1.3.1.1' if row['branch'] in ('1.21.9-1.21.10', '1.21.11') else '1.3.1'
                    row['distribution'] = edition
                self.manifest['tag'] = 'v1.3.1' + ('-cf' if edition == 'cf' else '')
                self.fixture()
                release.bundle(self.downloads, self.output)
                self.assertEqual(17, len(list(self.output.glob('*.jar'))))
                self.assertFalse(list(self.output.glob('client-updates-*.json')))
                self.assertEqual(self.rows, release.verify_bundle(self.output)['entries'])
                with patch.dict(os.environ, {'GITHUB_REPOSITORY': 'test/repository', 'RELEASE_TAG': 'v1.3.1'}), \
                     patch.object(release.subprocess, 'run') as run:
                    with self.assertRaises(ValueError):
                        release.publish(self.output)
                    run.assert_not_called()

    def test_uniform_four_component_review_bundle_retains_strict_catalog(self):
        for edition in ('standard', 'cf'):
            with self.subTest(edition=edition):
                self.downloads = self.root / ('downloads-' + edition)
                self.output = self.root / ('release-' + edition)
                self.manifest.update(publish=False, tag='v1.3.1.1' + ('-cf' if edition == 'cf' else ''))
                for row in self.rows:
                    row.update(version='1.3.1.1', distribution=edition)
                self.fixture()
                release.bundle(self.downloads, self.output)
                release.verify_bundle(self.output)
                catalog = release.load_json(self.output / f'client-updates-{edition}.json')
                self.assertEqual(14, len(catalog['artifacts']))
                prefix = '1.3.1.1-cf.1+' if edition == 'cf' else '1.3.1.1+'
                self.assertTrue(all(a['version'].startswith(prefix) for a in catalog['artifacts']))

    def test_review_catalog_omission_does_not_accept_malformed_tags(self):
        self.manifest['publish'] = False
        self.fixture()
        for tag in ('v1.3', 'v1.3.1.1.1', 'v1.3.1/path', 'v1.3.1\n', '', 1311):
            manifest = self.manifest | {'tag': tag}
            release.dump(self.downloads / 'build-plan/build-plan.json', manifest)
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, 'Invalid bundle tag'):
                release.bundle(self.downloads, self.output)
            self.assertFalse(self.output.exists())

    def test_mixed_publication_is_rejected_before_output_creation(self):
        self.rows[0]['version'] = '1.3.1.1'
        self.fixture()
        with self.assertRaisesRegex(ValueError, 'Mixed release versions'):
            release.bundle(self.downloads, self.output)
        self.assertFalse(self.output.exists())

    def test_cf_bundle_versions_and_publication_boundary(self):
        for row in self.rows:
            row['distribution'] = 'cf'
        self.fixture()
        release.bundle(self.downloads, self.output)
        self.assertEqual(17, len(list(self.output.glob('*-cf*.jar'))))
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
        self.assertFalse(release.release_requested('push', 'refs/heads/26.3', False))
        self.assertTrue(release.release_requested('workflow_dispatch', 'refs/heads/26.3', True))
        self.assertTrue(release.release_requested('push', 'refs/tags/v1.3.0-beta-3', False))
        for event, ref in [('pull_request', 'refs/heads/26.3'), ('workflow_dispatch', 'refs/heads/26.2'),
                           ('workflow_dispatch', 'refs/heads/plugin')]:
            with self.assertRaises(ValueError): release.release_requested(event, ref, True)

    def test_plan_freezes_eight_branches_and_uses_new_default_version(self):
        shas = {branch: f'{index:040x}' for index, branch in enumerate(sorted(release.BRANCHES), 1)}
        for event, ref, base in [('push', 'refs/heads/26.3', ''), ('push', 'refs/heads/26.2', ''),
                                 ('pull_request', 'refs/pull/1/merge', '26.3'),
                                 ('push', 'refs/tags/v1.3.0-beta-4', '')]:
            source = '26.3' if base or ref.startswith('refs/tags/') else ref.removeprefix('refs/heads/')
            event_sha = 'f' * 40
            def api(repo, path):
                if path.startswith('git/ref/heads/'):
                    return {'object': {'sha': shas[path.removeprefix('git/ref/heads/')]}}
                sha = path.split('?ref=')[1]
                branch = source if sha == event_sha else next(b for b, s in shas.items() if s == sha)
                version = '1.3.0-beta-4' if branch == '26.3' or ref.startswith('refs/tags/') else '1.3.0-beta-3'
                values = f'mod_version={version}\nminecraft_version_range={branch if branch != "plugin" else ""}\n'
                return {'content': base64.b64encode(values.encode()).decode()}
            env = {'GITHUB_REPOSITORY': 'test/repository', 'GITHUB_EVENT_NAME': event, 'GITHUB_REF': ref,
                   'GITHUB_BASE_REF': base, 'GITHUB_SHA': event_sha, 'GITHUB_RUN_ID': '123',
                   'GITHUB_OUTPUT': str(self.root / 'outputs'), 'PUBLISH_REQUESTED': 'false',
                   'BUILD_DISTRIBUTION': 'standard'}
            with self.subTest(event=event, ref=ref), patch.dict(os.environ, env), \
                 patch.object(release, 'api', side_effect=api), patch.object(release, 'run', return_value='c' * 40), \
                 patch.object(release, 'load_json', return_value=copy.deepcopy(self.rows)), patch.object(release, 'dump') as dump:
                release.plan()
                manifest = dump.call_args.args[1]
                self.assertEqual('v1.3.0-beta-4', manifest['tag'])
                self.assertEqual(13, len(manifest['entries']))
                self.assertEqual(ref.startswith('refs/tags/'), manifest['publish'])
                self.assertEqual('c' * 40, manifest['controller'])
                self.assertEqual(3, sum('modernui_host' in row for row in manifest['entries']))
                for row in manifest['entries']:
                    self.assertEqual(event_sha if row['branch'] == source else shas[row['branch']], row['sha'])

    def test_historical_bundle_is_readable_but_cannot_be_a_new_release(self):
        self.fixture()
        release.bundle(self.downloads, self.output)
        historical_bundle(self.output)
        manifest = release.verify_bundle(self.output)
        self.assertEqual(15, len(manifest['artifacts']))
        self.assertEqual('a' * 40, release.release_target(manifest))
        with self.assertRaises(ValueError):
            release.validate_matrix(manifest['entries'])
        with patch.object(release, 'api') as api, patch.object(release.subprocess, 'run') as run:
            with self.assertRaises(ValueError):
                release.publish(self.output)
            api.assert_not_called()
            run.assert_not_called()
        release.dump(self.downloads / 'build-plan/build-plan.json', manifest)
        with self.assertRaises(ValueError):
            release.bundle(self.downloads, self.root / 'new-release')

    def test_historical_validation_still_rejects_incomplete_and_duplicate_matrices(self):
        legacy = [r for r in self.rows if r['branch'] != '26.3']
        release.validate_matrix(legacy, allow_legacy=True)
        for rows in (legacy[:-1], legacy[:-1] + [legacy[0]], self.rows[:-1]):
            with self.assertRaises(ValueError):
                release.validate_matrix(rows, allow_legacy=True)

    def test_release_stays_draft_until_all_uploads_succeed(self):
        next(r for r in self.rows if r['branch'] == '26.3')['sha'] = 'b' * 40
        self.fixture()
        release.bundle(self.downloads, self.output)
        env = {'GITHUB_REPOSITORY': 'test/repository', 'RELEASE_TAG': 'v1.3.0-beta-3', 'GITHUB_RUN_ID': '123'}
        with patch.dict(os.environ, env), patch.object(release, 'api', side_effect=[[], {'sha': 'b' * 40}]), \
             patch.object(release.subprocess, 'run') as run:
            release.publish(self.output)
            commands = [call.args[0] for call in run.call_args_list]
            self.assertEqual('api', commands[0][1])
            self.assertIn('sha=' + 'b' * 40, commands[0])
            self.assertEqual('b' * 40, commands[1][commands[1].index('--target') + 1])
            self.assertIn('--draft', commands[1])
            self.assertEqual('upload', commands[2][2])
            self.assertIn('--draft=false', commands[3])
            self.assertIn('--latest=false', commands[3])
        with patch.dict(os.environ, env), patch.object(release, 'api', side_effect=[[], {'sha': 'b' * 40}]), \
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


class ModernUIContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.row = {'id': '1.21.9', 'branch': '1.21.9-1.21.10', 'properties': {
            'modernui_fork_version': '1.21.9-3.13.0.9'}}
        self.row['modernui_host'] = release.modernui_host(self.row)
        (self.source / 'gradle.properties').write_text('modernui_fork_version=1.21.10-3.13.0.9')
        self.java = self.root / 'jdk21'
        (self.java / 'bin').mkdir(parents=True)
        (self.java / 'bin/java').touch()
        (self.java / 'release').write_text('JAVA_VERSION="21.0.9"')
        self.env = patch.dict(os.environ, {'JAVA_HOME_21_X64': str(self.java), 'JAVA_HOME': '/jdk25'})
        self.env.start()
        self.addCleanup(self.env.stop)

    def jar(self, folder, loader, *, endpoint='1.21.9', version='3.13.0.9', variant='standard', lower=None):
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f'ModernUI-{loader}-{endpoint}-3.13.0.9-universal.jar'
        upper = {'1.21.9': '1.21.10', '1.21.10': '1.21.11', '1.21.11': '1.21.12'}[endpoint]
        lower = lower or endpoint
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\nModernUI-Font-Variant: {variant}\n')
            if loader == 'Fabric':
                jar.writestr('fabric.mod.json', json.dumps({'id': 'modernui', 'version': version,
                    'depends': {'minecraft': f'>={lower} <{upper}'}}))
            else:
                jar.writestr('META-INF/neoforge.mods.toml', f'[[mods]]\nmodId="modernui"\nversion="{version}"\n'
                    f'[[dependencies.modernui]]\nmodId="minecraft"\nversionRange="[{lower}, {upper})"\n')
        return path

    def test_all_endpoints_use_reviewed_immutable_sources(self):
        rows = release.load_json(Path(__file__).parent.parent / 'build-matrix.json')
        for endpoint in ('1.21.9', '1.21.10', '1.21.11'):
            row = next(r for r in rows if r['id'] == endpoint)
            host = release.modernui_host(row)
            self.assertRegex(host['sha'], r'^[0-9a-f]{40}$')
            self.assertEqual(endpoint + '-3.13.0.9', host['revision'])
            release.validate_row(row | {'modernui_host': host})
            with self.assertRaisesRegex(ValueError, 'Unreviewed'):
                release.validate_row(row | {'modernui_host': host | {'sha': 'f' * 40}})
        nine = next(r for r in rows if r['id'] == '1.21.9')
        self.assertEqual('1.21.9-3.13.0.9', nine['properties']['modernui_fork_version'])
        self.assertNotIn('modernui_fabric_version', nine['properties'])
        self.assertIsNone(release.modernui_host(next(r for r in rows if r['id'] == '26.3')))

    def test_build_uses_pinned_checkout_jdk21_and_reuses_verified_jars(self):
        calls = []
        def execute(command, **kwargs):
            calls.append((command, kwargs))
            if command[:2] == ['git', 'init']:
                Path(command[2]).mkdir()
            if command[0] == './gradlew':
                for loader in ('Fabric', 'NeoForge'):
                    self.jar(kwargs['cwd'] / loader.lower() / 'build/libs', loader)
        with patch.object(release.subprocess, 'run', side_effect=execute), \
             patch.object(release, 'run', return_value=self.row['modernui_host']['sha']):
            jars = release.prepare_modernui(self.source, self.row)
            self.assertTrue(jars.is_absolute())
            self.assertEqual(jars, release.prepare_modernui(self.source, self.row))
        builds = [(cmd, kw) for cmd, kw in calls if cmd[0] == './gradlew']
        self.assertEqual(1, len(builds))
        command, kwargs = builds[0]
        self.assertIn('-Pfont_variant=standard', command)
        self.assertIn('--rerun-tasks', command)
        self.assertEqual(str(self.java), kwargs['env']['JAVA_HOME'])
        self.assertTrue(kwargs['env']['PATH'].startswith(str(self.java / 'bin') + os.pathsep))
        self.assertEqual('/jdk25', os.environ['JAVA_HOME'])
        fetch = next(cmd for cmd, kw in calls if cmd[:2] == ['git', 'fetch'])
        self.assertEqual(release.MODERNUI_REPOSITORY, fetch[-2])
        self.assertEqual(self.row['modernui_host']['sha'], fetch[-1])

    def test_client_build_receives_absolute_host_directory(self):
        row = next(r for r in release.load_json(Path(__file__).parent.parent / 'build-matrix.json') if r['id'] == '1.21.9')
        row.update(sha='a' * 40, version='1.3.1', range='1.21.9-1.21.10', modernui_host=self.row['modernui_host'])
        (self.source / 'gradle.properties').write_text('mod_version=1.3.1')
        jars = self.root / 'host jars'
        with patch.dict(os.environ, {'BUILD_ENTRY': json.dumps(row)}), \
             patch.object(release, 'run', return_value='a' * 40), \
             patch.object(release, 'prepare_modernui', return_value=jars), \
             patch.object(release, 'verify_jar'), patch.object(release, 'digest', return_value='b' * 64), \
             patch.object(release.shutil, 'copy2'), patch.object(release.subprocess, 'run') as run:
            release.build(self.source, self.root / 'artifacts')
        command = run.call_args.args[0]
        self.assertIn('-PmodernuiForkDirectory=' + str(jars), command)
        self.assertIn('-Pmodernui_fork_version=1.21.9-3.13.0.9', command)
        self.assertNotIn('-Pmodernui_fabric_version=PSsIJDQD', command)

    def test_wrong_checkout_or_incomplete_cache_is_never_reused(self):
        host = self.row['modernui_host']
        folder = self.root / '.modernui-hosts' / (host['revision'] + '-' + host['sha'])
        (folder / 'source').mkdir(parents=True)
        with patch.object(release, 'run', return_value='f' * 40):
            with self.assertRaisesRegex(ValueError, 'checkout mismatch'):
                release.prepare_modernui(self.source, self.row)
        with patch.object(release, 'run', return_value=host['sha']), patch.object(release.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'Missing or unsafe'):
                release.prepare_modernui(self.source, self.row)
            run.assert_not_called()

    def test_wrong_dependency_or_jdk_fails_before_any_network(self):
        for wrong in ('revision', 'jdk', 'missing_jdk'):
            with self.subTest(wrong=wrong):
                row = copy.deepcopy(self.row)
                if wrong == 'revision':
                    row['properties']['modernui_fork_version'] = '1.21.10-3.13.0.9'
                (self.java / 'release').write_text('JAVA_VERSION="25"' if wrong == 'jdk' else 'JAVA_VERSION="21"')
                with patch.dict(os.environ, {'JAVA_HOME_21_X64': '' if wrong == 'missing_jdk' else str(self.java)}), \
                     patch.object(release.subprocess, 'run') as run:
                    with self.assertRaises(ValueError):
                        release.prepare_modernui(self.source, row)
                    run.assert_not_called()

    def test_failed_host_build_propagates_without_old_host_fallback(self):
        def execute(command, **kwargs):
            if command[:2] == ['git', 'init']:
                Path(command[2]).mkdir()
            if command[0] == './gradlew':
                raise release.subprocess.CalledProcessError(1, command)
        with patch.object(release.subprocess, 'run', side_effect=execute), \
             patch.object(release, 'run', return_value=self.row['modernui_host']['sha']):
            with self.assertRaises(release.subprocess.CalledProcessError):
                release.prepare_modernui(self.source, self.row)
        self.assertFalse(list(self.root.glob('.modernui-hosts/*/jars')))

    def test_metadata_rejects_wrong_loader_version_range_variant_and_unsafe_paths(self):
        for loader in ('Fabric', 'NeoForge'):
            for endpoint in ('1.21.9', '1.21.10', '1.21.11'):
                release.verify_modernui_jar(self.jar(self.root, loader, endpoint=endpoint), endpoint, loader)
            for invalid in ({'version': '3.12.0'}, {'variant': 'misans'}, {'lower': '1.21.10'}):
                with self.subTest(loader=loader, invalid=invalid), self.assertRaises(ValueError):
                    release.verify_modernui_jar(self.jar(self.root, loader, **invalid), '1.21.9', loader)
            missing = self.root / 'missing.jar'
            with self.assertRaises(ValueError):
                release.verify_modernui_jar(missing, '1.21.9', loader)
            link = self.root / ('link-' + loader + '.jar')
            link.symlink_to(self.jar(self.root, loader))
            with self.assertRaises(ValueError):
                release.verify_modernui_jar(link, '1.21.9', loader)


class ScopedReleaseContracts(unittest.TestCase):
    def setUp(self):
        self.helper = ReleaseContracts()
        self.helper.setUp()
        self.addCleanup(self.helper.doCleanups)
        h = self.helper
        self.real_notes = release.scoped_release_notes
        self.notes_patch = patch.object(release, 'scoped_release_notes', return_value='Reviewed fixture release notes')
        self.notes_patch.start()
        self.addCleanup(self.notes_patch.stop)
        h.manifest.update(tag=release.HOTFIX_TAG, release_scope=release.HOTFIX_SCOPE,
                          controller='a' * 40, distribution='standard')
        for row in h.rows:
            row['version'] = '1.3.1.1' if row['branch'] in release.HOTFIX_BRANCHES else '1.3.1'
            host = release.modernui_host(row)
            if host:
                row['modernui_host'] = host

    def bundle(self):
        h = self.helper
        h.fixture()
        release.bundle(h.downloads, h.output)
        return release.verify_bundle(h.output)

    def test_scope_builds_all_endpoints_but_publishes_exactly_four_jars_and_catalog_entries(self):
        h = self.helper
        manifest = self.bundle()
        self.assertEqual(13, len(manifest['entries']))
        self.assertEqual(13, len(manifest['matrix_builds']))
        self.assertEqual(4, len(list(h.output.glob('*.jar'))))
        self.assertEqual({'1.21.10', '1.21.11'}, {a['endpoint'] for a in manifest['artifacts']})
        catalog = release.load_json(h.output / 'client-updates-standard.json')
        self.assertEqual(release.HOTFIX_TAG, catalog['tag'])
        self.assertEqual(4, len(catalog['artifacts']))
        self.assertTrue(all(a['version'].startswith('1.3.1.1+') for a in catalog['artifacts']))
        self.assertEqual('a' * 40, release.release_target(manifest))

    def test_scope_cannot_skip_unrelated_endpoint_builds(self):
        h = self.helper
        h.fixture()
        (h.downloads / 'build-26.2/build.json').unlink()
        with self.assertRaises(FileNotFoundError):
            release.bundle(h.downloads, h.output)
        self.assertFalse(h.output.exists())

    def test_scope_rejects_unknown_scope_wrong_version_host_tag_controller_and_edition(self):
        h = self.helper
        for mutation in ('scope', 'version', 'host', 'tag', 'controller', 'distribution', 'row_edition', 'split_source', 'unselected_version', 'publish_endpoint', 'range'):
            manifest = copy.deepcopy(h.manifest)
            row = next(r for r in manifest['entries'] if r['id'] == '1.21.9')
            if mutation == 'scope': manifest['release_scope'] = '../hotfix'
            elif mutation == 'version': row['version'] = '1.3.1'
            elif mutation == 'host': row['modernui_host']['sha'] = 'f' * 40
            elif mutation == 'tag': manifest['tag'] = 'v1.3.1.2'
            elif mutation == 'controller': manifest['controller'] = 'b' * 40
            elif mutation == 'split_source': row['sha'] = 'b' * 40
            elif mutation == 'unselected_version':
                next(r for r in manifest['entries'] if r['id'] == '26.2')['version'] = '999.0.0'
            elif mutation == 'publish_endpoint':
                row['publish'] = True
                next(r for r in manifest['entries'] if r['id'] == '1.21.10')['publish'] = False
            elif mutation == 'range': row['range'] = '1.21.10'
            elif mutation == 'row_edition':
                for r in manifest['entries']: r['distribution'] = 'cf'
            else: manifest['distribution'] = 'cf'
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                release.release_rows(manifest)

    def test_scope_rejects_swapped_endpoint_branch_mapping(self):
        manifest = copy.deepcopy(self.helper.manifest)
        ten = next(r for r in manifest['entries'] if r['id'] == '1.21.10')
        eleven = next(r for r in manifest['entries'] if r['id'] == '1.21.11')
        ten['branch'], eleven['branch'] = eleven['branch'], ten['branch']
        ten['range'], eleven['range'] = eleven['range'], ten['range']
        for row in (ten, eleven):
            row['modernui_host'] = release.modernui_host(row)
        with self.assertRaisesRegex(ValueError, 'endpoint branch mapping'):
            release.release_rows(manifest)

    def test_scope_catalog_is_mandatory_after_checksum_repair(self):
        h = self.helper
        self.bundle()
        (h.output / 'client-updates-standard.json').unlink()
        rewrite_checksums(h.output)
        with self.assertRaisesRegex(ValueError, 'Missing scoped standard updater catalog'):
            release.verify_bundle(h.output)

    def test_scope_manifest_requires_complete_matrix_and_exact_artifact_provenance(self):
        h = self.helper
        manifest = self.bundle()
        for mutation in ('missing_matrix', 'matrix_source', 'artifact_source', 'matrix_module', 'matrix_hash', 'matrix_size'):
            changed = copy.deepcopy(manifest)
            if mutation == 'missing_matrix': changed.pop('matrix_builds')
            elif mutation == 'matrix_source': changed['matrix_builds'][0]['entry']['sha'] = 'b' * 40
            elif mutation == 'matrix_module': changed['matrix_builds'][0]['artifacts'][0]['module'] = 'paper'
            elif mutation == 'matrix_hash': changed['matrix_builds'][0]['artifacts'][0]['sha256'] = 'not-a-hash'
            elif mutation == 'matrix_size': changed['matrix_builds'][0]['artifacts'][0]['size'] = -1
            else: changed['artifacts'][0]['sha'] = 'b' * 40
            release.dump(h.output / 'build-manifest.json', changed)
            rewrite_checksums(h.output)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                release.verify_bundle(h.output)

    def test_scope_publication_requires_dependency_verification_before_tag_mutation(self):
        h = self.helper
        self.bundle()
        env = {'GITHUB_REPOSITORY': 'test/repository', 'RELEASE_TAG': release.HOTFIX_TAG, 'GITHUB_RUN_ID': '123',
               'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/26.3', 'GITHUB_SHA': 'a' * 40}
        with patch.dict(os.environ, env), patch.object(release, 'run', return_value='a' * 40), patch.object(release, 'verify_modernui_releases', side_effect=ValueError('missing fork')) as verify, \
             patch.object(release, 'api') as api, patch.object(release.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'missing fork'):
                release.publish(h.output)
            verify.assert_called_once()
            api.assert_not_called()
            run.assert_not_called()
        with patch.dict(os.environ, env), patch.object(release, 'run', return_value='a' * 40), patch.object(release, 'verify_modernui_releases'), \
             patch.object(release, 'api', side_effect=[[], {'sha': 'a' * 40}]), patch.object(release.subprocess, 'run') as run:
            release.publish(h.output)
            self.assertIn('--draft', run.call_args_list[1].args[0])
            self.assertEqual(['gh', 'release', 'upload'], run.call_args_list[-1].args[0][:3])

    def test_scope_success_uploads_all_assets_and_keeps_release_draft_for_runtime_acceptance(self):
        h = self.helper
        self.bundle()
        env = {'GITHUB_REPOSITORY': 'test/repository', 'RELEASE_TAG': release.HOTFIX_TAG, 'GITHUB_RUN_ID': '123',
               'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/26.3', 'GITHUB_SHA': 'a' * 40}
        with patch.dict(os.environ, env), patch.object(release, 'run', return_value='a' * 40), \
             patch.object(release, 'verify_modernui_releases'), \
             patch.object(release, 'api', side_effect=[[], {'sha': 'a' * 40}]), \
             patch.object(release.subprocess, 'run') as run, patch('builtins.print') as output:
            release.publish(h.output)
        commands = [call.args[0] for call in run.call_args_list]
        create = next(command for command in commands if command[:3] == ['gh', 'release', 'create'])
        self.assertIn('--draft', create)
        self.assertFalse(any(command[:3] == ['gh', 'release', 'edit'] for command in commands))
        self.assertFalse(any('--draft=false' in command for command in commands))
        upload = next(command for command in commands if command[:3] == ['gh', 'release', 'upload'])
        self.assertEqual({str(path) for path in h.output.iterdir()}, set(upload[6:]))
        output.assert_called_once_with('Scoped release uploaded as draft; awaiting runtime acceptance before publication.')
        self.assertIn('draft pending runtime acceptance', (h.output / 'RELEASE_NOTES.md').read_text())

    def test_scope_publication_rejects_rebound_checkout_and_non_dispatch_context(self):
        h = self.helper
        self.bundle()
        env = {'GITHUB_REPOSITORY': 'test/repository', 'RELEASE_TAG': release.HOTFIX_TAG, 'GITHUB_RUN_ID': '123',
               'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/26.3', 'GITHUB_SHA': 'a' * 40}
        for mutation in ('checkout', 'event', 'ref', 'github_sha'):
            values = env.copy()
            if mutation == 'event': values['GITHUB_EVENT_NAME'] = 'push'
            elif mutation == 'ref': values['GITHUB_REF'] = 'refs/heads/1.21.11'
            elif mutation == 'github_sha': values['GITHUB_SHA'] = 'b' * 40
            with self.subTest(mutation=mutation), patch.dict(os.environ, values), \
                 patch.object(release, 'run', return_value=('b' if mutation == 'checkout' else 'a') * 40), \
                 patch.object(release, 'api') as api, patch.object(release.subprocess, 'run') as run:
                with self.assertRaisesRegex(ValueError, 'controller provenance mismatch'):
                    release.publish(h.output)
                api.assert_not_called()
                run.assert_not_called()

    def test_scope_notes_are_required_and_append_only_matrix_provenance(self):
        h = self.helper
        with patch.object(Path, 'is_file', return_value=False), self.assertRaisesRegex(ValueError, 'Missing reviewed'):
            self.real_notes(h.manifest)
        self.bundle()
        text = (h.output / 'RELEASE_NOTES.md').read_text()
        self.assertIn('Reviewed fixture release notes', text)
        self.assertIn('SHA256SUMS', text)
        self.assertIn('matrix_builds', text)
        self.assertNotIn('Hangar', text)
        self.assertNotIn('Proxy networks', text)
        self.assertNotIn('plus Paper', text)

    def test_scoped_plan_requires_explicit_default_dispatch_and_freezes_full_matrix(self):
        h = self.helper
        branches = {branch: f'{n:040x}' for n, branch in enumerate(sorted(release.BRANCHES), 1)}
        branches['26.3'] = 'a' * 40
        def api(repo, path):
            if path.startswith('git/ref/heads/'):
                return {'object': {'sha': branches[path.removeprefix('git/ref/heads/')]}}
            sha = path.split('?ref=')[1]
            branch = next(b for b, s in branches.items() if s == sha)
            version = '1.3.1.1' if branch in release.HOTFIX_BRANCHES else '1.3.1'
            values = f'mod_version={version}\nminecraft_version_range={branch if branch != "plugin" else ""}\n'
            return {'content': base64.b64encode(values.encode()).decode()}
        env = {'GITHUB_REPOSITORY': 'test/repository', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'GITHUB_REF': 'refs/heads/26.3', 'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123',
               'GITHUB_OUTPUT': str(h.root / 'outputs'), 'PUBLISH_REQUESTED': 'true',
               'BUILD_DISTRIBUTION': 'standard', 'RELEASE_SCOPE': release.HOTFIX_SCOPE}
        with patch.dict(os.environ, env), patch.object(release, 'api', side_effect=api), \
             patch.object(release, 'run', return_value='a' * 40), \
             patch.object(release, 'load_json', return_value=copy.deepcopy(h.rows)), patch.object(release, 'dump') as dump:
            release.plan()
            manifest = dump.call_args.args[1]
            self.assertEqual(13, len(manifest['entries']))
            self.assertEqual(release.HOTFIX_TAG, manifest['tag'])
            self.assertEqual(release.HOTFIX_SCOPE, manifest['release_scope'])
        for event, ref in [('push', 'refs/tags/v1.3.1.1'), ('workflow_dispatch', 'refs/heads/1.21.11'),
                           ('pull_request', 'refs/heads/26.3')]:
            with patch.dict(os.environ, env | {'GITHUB_EVENT_NAME': event, 'GITHUB_REF': ref, 'PUBLISH_REQUESTED': 'false'}), \
                 self.subTest(event=event, ref=ref), self.assertRaises(ValueError):
                release.plan()

    def test_modernui_releases_require_pinned_public_sources_and_all_asset_hashes(self):
        h = self.helper
        assets_by_tag = {}
        source_files = {}
        for endpoint in release.MODERNUI_HOSTS:
            revision = endpoint + '-3.13.0.9'
            assets = []
            for loader in ('Fabric', 'NeoForge'):
                path = ModernUIContracts.jar(self, h.root / 'mui', loader, endpoint=endpoint)
                source_files[path.name] = path
                assets.append({'name': path.name, 'digest': 'sha256:' + release.digest(path)})
                misans = path.with_name(path.name.replace('-universal.jar', '-universal-misans.jar'))
                with zipfile.ZipFile(path) as original, zipfile.ZipFile(misans, 'w') as jar:
                    for name in original.namelist():
                        content = original.read(name)
                        if name == 'META-INF/MANIFEST.MF':
                            content = content.replace(b'Variant: standard', b'Variant: misans')
                        jar.writestr(name, content)
                source_files[misans.name] = misans
                assets.append({'name': misans.name, 'digest': 'sha256:' + release.digest(misans)})
            assets_by_tag[revision] = {'draft': False, 'tag_name': revision, 'assets': assets}
        def api(repo, path):
            tag = path.split('/')[-1]
            if path.startswith('commits/'):
                return {'sha': release.MODERNUI_HOSTS[tag.removesuffix('-3.13.0.9')]}
            return assets_by_tag[tag]
        def download(command, **kwargs):
            release.shutil.copy2(source_files[command[-1]], Path(command[command.index('--dir') + 1]) / command[-1])
        with patch.object(release, 'api', side_effect=api), patch.object(release.subprocess, 'run', side_effect=download):
            self.assertEqual(3, len(release.verify_modernui_releases(h.manifest)))
            assets_by_tag['1.21.9-3.13.0.9']['assets'][0]['digest'] = 'sha256:' + '0' * 64
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                release.verify_modernui_releases(h.manifest)
            assets_by_tag['1.21.9-3.13.0.9']['draft'] = True
            with self.assertRaisesRegex(ValueError, 'source mismatch'):
                release.verify_modernui_releases(h.manifest)


if __name__ == '__main__':
    unittest.main()
