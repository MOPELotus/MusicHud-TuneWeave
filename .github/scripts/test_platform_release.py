import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import Mock, patch
from urllib.error import HTTPError

import platform_release as platform
import build_release as release
import test_build_release as fixtures


class PlatformContracts(unittest.TestCase):
    def test_hangar_creates_missing_beta_channel_as_unstable(self):
        client = Mock(token='test-session')
        internal = Mock()
        internal.request.side_effect = [[{'name': 'Release', 'color': '#22c55e'}], None,
                                        [{'name': 'Beta', 'color': '#eab308'}]]
        with patch.object(platform, 'Client', return_value=internal):
            platform.ensure_hangar_channel(client, {'id': 7059}, 'Beta')
        call = internal.request.call_args_list[1]
        self.assertEqual('channels/7059/create', call.args[0])
        self.assertEqual(['UNSTABLE'], json.loads(call.kwargs['data'])['flags'])

    def test_hangar_existing_channel_is_not_changed(self):
        internal = Mock()
        internal.request.return_value = [{'name': 'Beta', 'color': '#eab308'}]
        with patch.object(platform, 'Client', return_value=internal):
            platform.ensure_hangar_channel(Mock(token='test'), {'id': 7059}, 'Beta')
        self.assertEqual(1, internal.request.call_count)

    def test_hangar_channel_permission_failure_is_actionable(self):
        internal = Mock()
        internal.request.side_effect = [[{'name': 'Release', 'color': '#22c55e'}], platform.ApiError('hangar_internal', 403)]
        with patch.object(platform, 'Client', return_value=internal), self.assertRaisesRegex(RuntimeError, 'cannot create.*HTTP 403'):
            platform.ensure_hangar_channel(Mock(token='test'), {'id': 7059}, 'Beta')

    def test_publication_is_not_allowed_from_pull_requests(self):
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_REF': 'refs/pull/1/merge',
                                     'GITHUB_REPOSITORY': platform.REPOSITORY}):
            with self.assertRaisesRegex(ValueError, 'default-branch dispatch'):
                platform.publish_platform('curseforge', Path('missing'), 'v1.3.0-beta-3', {}, Path('missing'))

    def test_only_new_default_dispatch_can_start_platform_publication(self):
        for branch in ('26.2', 'plugin', '26.3'):
            with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/' + branch,
                                         'GITHUB_REPOSITORY': platform.REPOSITORY}), \
                 patch.object(platform, 'verify_platform_bundle', side_effect=RuntimeError('verification started')) as verify:
                with self.assertRaisesRegex(RuntimeError if branch == '26.3' else ValueError,
                                            'verification started' if branch == '26.3' else 'default-branch dispatch'):
                    platform.publish_platform('curseforge', Path('missing'), 'v1.3.0-beta-3', {}, Path('missing'))
                self.assertEqual(branch == '26.3', verify.called)

    def test_prepared_runs_and_receipts_from_both_controller_generations_remain_usable(self):
        raw = io.BytesIO()
        data = {'platform': 'curseforge', 'tag': 'v1.3.0-beta-3', 'files': {'file': {'status': 'uploaded', 'file_id': 123}}}
        with zipfile.ZipFile(raw, 'w') as archive:
            archive.writestr('receipt.json', json.dumps(data))
        metadata = {'total_count': 1, 'artifacts': [{'id': 1, 'expired': False, 'workflow_run': {'id': 123}}]}
        for branch in ('26.2', '26.3'):
            run = {'head_branch': branch, 'event': 'workflow_dispatch', 'conclusion': 'success',
                   'path': '.github/workflows/platforms.yml'}
            with self.subTest(branch=branch), patch.object(release, 'api', return_value=run):
                platform.validate_dispatch('v1.3.0-beta-3', 'all', '123')
            # A failed publication can contain successful uploads that must not be repeated.
            with patch.object(release, 'api', side_effect=[metadata, run | {'conclusion': 'failure'}]), \
                 patch.object(platform.subprocess, 'run', return_value=Mock(returncode=0, stdout=raw.getvalue())):
                self.assertEqual(data['files'], platform.previous_receipts('curseforge', data['tag']))
        with patch.object(release, 'api', side_effect=[metadata, run | {'head_branch': 'plugin'}]), \
             patch.object(platform.subprocess, 'run') as download, self.assertRaisesRegex(ValueError, 'Untrusted'):
            platform.previous_receipts('curseforge', data['tag'])
        download.assert_not_called()

    def test_retry_validation_rejects_foreign_or_failed_prepared_run(self):
        for changes in ({'head_branch': 'plugin'}, {'event': 'pull_request'}, {'conclusion': 'failure'},
                        {'path': '.github/workflows/other.yml'}):
            run = {'head_branch': '26.2', 'event': 'workflow_dispatch', 'conclusion': 'success',
                   'path': '.github/workflows/platforms.yml'} | changes
            with patch.object(release, 'api', return_value=run), self.assertRaisesRegex(ValueError, 'successful default-branch'):
                platform.validate_dispatch('v1.3.0-beta-3', 'all', '123')
        for value in ('../1', '0', '1\n2'):
            with self.assertRaises(ValueError):
                platform.validate_dispatch('v1.3.0-beta-3', 'all', value)

    def test_uncertain_and_confirmed_receipts_survive_failed_retry(self):
        with tempfile.TemporaryDirectory() as folder:
            receipt = platform.Receipt('curseforge', 'v1.3.0-beta-3', Path(folder))
            with patch('builtins.print'):
                receipt.record('file', 'uploaded', sha256='a' * 64, file_id=123)
                receipt.failure('file', ValueError('different artifact bytes'))
                self.assertEqual('uploaded', receipt.data['files']['file']['status'])
                self.assertEqual(123, receipt.data['files']['file']['file_id'])
                receipt.record('other', 'pending', sha256='b' * 64)
                receipt.failure('other', platform.ApiError('curseforge', 503))
                receipt.failure('other', ValueError('cannot retry'))
                self.assertEqual('uncertain', receipt.data['files']['other']['status'])

    def test_multipart_contains_exact_final_filename_and_file_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'musichud-tuneweave-paper-1.3.0-beta-3.jar'
            path.write_bytes(b'PK\x03\x04binary\x00payload')
            data, content_type = platform.multipart('versionUpload', {'version': '1.3.0-beta-3'}, [('files', path)])
            self.assertIn(('filename="' + path.name + '"').encode(), data)
            self.assertIn(path.read_bytes(), data)
            self.assertIn('multipart/form-data; boundary=', content_type)

    def test_release_tags_are_bounded(self):
        for tag in ('v1.3.0-beta-3', 'v2.0.0'):
            platform.validate_tag(tag)
        for tag in ('--help', 'v1.0.0-cf', 'v1.0.0/../../x', 'v1.0.0\nnext', None):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                platform.validate_tag(tag)

    def test_api_paths_cannot_replace_origin_or_traverse(self):
        client = platform.Client('modrinth', 'test-token')
        for path in ('https://other.example/', '//other.example/', '../version', 'project/../version',
                     'project\\version', None):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'Invalid API path'):
                client.request(path)

    def test_control_characters_in_credential_are_rejected_without_echoing_it(self):
        with self.assertRaisesRegex(ValueError, '^Missing or invalid platform credential$'):
            platform.Client('modrinth', 'private-token\r\nInjected: value')

    def test_upload_failure_is_not_automatically_retried_or_logged(self):
        client = platform.Client('modrinth', 'test-token')
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError('https://api.modrinth.com/v2/version', 503,
            'sensitive upstream text', {}, io.BytesIO(b'private-token'))
        with self.assertRaisesRegex(platform.ApiError, '^modrinth API returned HTTP 503$'):
            client.request('version', method='POST', data=b'file')
        self.assertEqual(1, client.opener.open.call_count)

    def test_server_error_diagnostic_redacts_the_credential(self):
        client = platform.Client('hangar', 'private-token')
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError('https://hangar.papermc.io/api/v1/authenticate', 401,
            'Denied', {}, io.BytesIO(b'{"message":"Invalid apiKey private-token"}'))
        with self.assertRaises(platform.ApiError) as raised:
            client.request('authenticate', method='POST', data=b'body', authenticated=False)
        self.assertNotIn('private-token', str(raised.exception))
        self.assertIn('[redacted]', str(raised.exception))

    def test_redirect_does_not_forward_authorization(self):
        client = platform.Client('curseforge', 'test-token')
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError('https://minecraft.curseforge.com/api/game/versions',
            302, 'Moved', {'Location': 'https://other.example/'}, io.BytesIO())
        with self.assertRaisesRegex(platform.ApiError, 'HTTP 302'):
            client.request('game/versions')
        self.assertEqual(1, client.opener.open.call_count)
        self.assertIsNone(platform.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example/'))

    def test_temporary_read_failure_is_bounded(self):
        client = platform.Client('modrinth', 'test-token')
        client.opener = Mock()
        client.opener.open.side_effect = HTTPError('https://api.modrinth.com/v2/project/example',
            503, 'Unavailable', {}, io.BytesIO())
        with patch.object(platform.time, 'sleep'), self.assertRaises(platform.ApiError):
            client.request('project/example')
        self.assertEqual(3, client.opener.open.call_count)


class RuntimeArtifactContracts(unittest.TestCase):
    def setUp(self):
        helper = fixtures.ReleaseContracts()
        helper.setUp()
        self.addCleanup(helper.doCleanups)
        helper.manifest['repository'] = platform.REPOSITORY
        helper.fixture()
        for row in helper.rows:
            folder = helper.downloads / ('build-' + row['id'])
            manifest = release.load_json(folder / 'build.json')
            for artifact in manifest['artifacts']:
                module = artifact['module']
                jar_path = folder / artifact['name']
                major = 69 if row['branch'].startswith('26.') else 65
                code = b'\xca\xfe\xba\xbe\x00\x00' + major.to_bytes(2, 'big')
                with zipfile.ZipFile(jar_path, 'a') as jar:
                    jar.writestr(platform.MAIN_CLASSES[module], code)
                    jar.writestr('indi/mopelotus/musichud/MusicHud.class', code)
                    jar.writestr('META-INF/musichud-registries.core.properties', 'core=test')
                    if module in ('fabric', 'neoforge'):
                        jar.writestr('META-INF/musichud-registries.common.properties', 'common=test')
                        jar.writestr('musichud_tuneweave.mixins.json', '{}')
                artifact['sha256'] = release.digest(jar_path)
            release.dump(folder / 'build.json', manifest)
        release.bundle(helper.downloads, helper.output)
        self.helper = helper

    def test_runtime_bundle_has_exact_client_and_plugin_builds(self):
        manifest = platform.verify_standard(self.helper.output, 'v1.3.0-beta-3')
        rows = platform.cf_rows(manifest, {})
        self.assertEqual(17, len(manifest['artifacts']))
        self.assertEqual(7, len(rows))
        self.assertTrue(all(r['distribution'] == 'cf' and r['branch'] != 'plugin' for r in rows))

    def test_historical_runtime_bundle_preserves_plugin_version_metadata(self):
        legacy = fixtures.historical_bundle(self.helper.output)
        manifest = platform.verify_standard(self.helper.output, legacy['tag'])
        self.assertEqual(15, len(manifest['artifacts']))
        self.assertEqual(6, len(platform.cf_rows(manifest, {})))
        row = next(r for r in manifest['entries'] if r['branch'] == 'plugin')
        available = set(platform.PLUGIN_GAMES) | {'Paper', 'Server'}
        for current in (False, True):
            context = self.helper.manifest if current else manifest
            games = platform.PLUGIN_GAMES if current else platform.LEGACY_PLUGIN_GAMES
            data = platform.modrinth_metadata(row, 'paper', 'project', {}, context)
            self.assertEqual(games, data['game_versions'])
            cf = platform.curseforge_metadata(row, 'paper', available, context)
            self.assertEqual(games + ['Paper', 'Server'], cf['gameVersionNames'])
        path = self.helper.output / release.filename(row, 'paper')
        remote = {'id': 'old', 'loaders': ['paper'], 'game_versions': platform.LEGACY_PLUGIN_GAMES,
                  'files': [{'filename': path.name, 'primary': True, 'hashes': {'sha512': platform.file_hash(path)}}]}
        self.assertIs(remote, platform.existing_modrinth_version([remote], path, row, 'paper', {}, manifest))
        with self.assertRaisesRegex(ValueError, 'metadata conflict'):
            platform.existing_modrinth_version([remote], path, row, 'paper', {}, self.helper.manifest)

    def test_current_and_historical_cf_bundles_require_every_client_jar(self):
        original_cf_rows = platform.cf_rows
        for legacy in (False, True):
            if legacy:
                fixtures.historical_bundle(self.helper.output)
            root = self.helper.root / ('legacy' if legacy else 'current')
            platform.shutil.copytree(self.helper.output, root / 'standard')
            downloads = root / 'downloads'
            downloads.mkdir()
            manifest = platform.verify_standard(root / 'standard', 'v1.3.0-beta-3')
            for row in original_cf_rows(manifest, {}):
                folder = downloads / ('cf-' + row['id'])
                folder.mkdir()
                artifacts = []
                for module in row['modules']:
                    standard = root / 'standard' / release.filename(row | {'distribution': 'standard'}, module)
                    path = folder / release.filename(row, module)
                    with zipfile.ZipFile(standard) as source, zipfile.ZipFile(path, 'w') as target:
                        for name in source.namelist():
                            content = source.read(name)
                            if name in ('META-INF/musichud-distribution.properties', 'fabric.mod.json', 'META-INF/neoforge.mods.toml'):
                                content = content.replace(row['version'].encode(), (row['version'] + '-cf.1').encode())
                                content = content.replace(b'distribution=standard', b'distribution=cf')
                            target.writestr(name, content)
                    artifacts.append({'module': module, 'name': path.name, 'sha256': release.digest(path)})
                release.dump(folder / 'build.json', {'entry': row, 'artifacts': artifacts})
            with self.subTest(legacy=legacy), patch.object(platform, 'cf_rows', side_effect=lambda m: original_cf_rows(m, {})), \
                 patch('builtins.print'):
                platform.assemble(root, downloads, manifest['tag'])
                _, cf = platform.verify_platform_bundle(root, manifest['tag'])
                self.assertEqual(12 if legacy else 14, len(cf['artifacts']))
                cf['artifacts'][-1] = cf['artifacts'][0]
                release.dump(root / 'cf/cf-manifest.json', cf)
                with self.assertRaisesRegex(ValueError, 'filename mismatch'):
                    platform.verify_platform_bundle(root, manifest['tag'])

    def test_26_3_metadata_links_fork_without_incorrect_external_dependencies(self):
        row = next(r for r in self.helper.rows if r['branch'] == '26.3')
        available = set(platform.PLUGIN_GAMES) | {'Fabric', 'NeoForge', 'Client'}
        for module in ('fabric', 'neoforge'):
            data = platform.modrinth_metadata(row, module, 'project', {'fabric-api': 'fabric'})
            self.assertEqual(['26.3'], data['game_versions'])
            self.assertEqual([{'project_id': 'fabric', 'dependency_type': 'required'}] if module == 'fabric' else [], data['dependencies'])
            self.assertIn('ModernUI-MC/releases/tag/26.3-3.13.0.9', data['changelog'])
            self.assertIn('standard (-universal.jar) or MiSans (-universal-misans.jar)', data['changelog'])
            self.assertIn('never install both', data['changelog'])
            self.assertNotIn('Forge Config API Port', data['changelog'])
            cf = platform.curseforge_metadata(row | {'distribution': 'cf'}, module, available)
            self.assertEqual([306612] if module == 'fabric' else [],
                             [d['projectID'] for d in cf.get('relations', {}).get('projects', [])])

    def test_hangar_uses_current_platform_versions_and_keeps_historical_versions(self):
        row = next(r for r in self.helper.rows if r['branch'] == 'plugin')
        downloads = {name: {'fileInfo': {'sha256Hash': release.digest(self.helper.output / release.filename(row, module))}}
                     for name, module in (('PAPER', 'paper'), ('VELOCITY', 'velocity'))}
        project = {'namespace': {'owner': 'MOPELotus', 'slug': 'MusicHud-TuneWeave'}, 'visibility': 'PUBLIC'}
        for legacy in (False, True):
            manifest = (fixtures.historical_bundle(self.helper.output) if legacy else self.helper.manifest)
            client = Mock()
            client.request.side_effect = [project, platform.ApiError('hangar', 404), {'url': 'result'},
                                          {'downloads': downloads}, project]
            with patch.object(platform, 'hangar_session'), patch.object(platform, 'ensure_hangar_channel'), \
                 patch.object(platform, 'multipart', return_value=(b'body', 'test')) as multipart:
                platform.publish_hangar(client, manifest, self.helper.output,
                                        {'hangar': 'MOPELotus/MusicHud-TuneWeave', 'hangar_channel': 'Beta'}, Mock(data={}))
            data = multipart.call_args.args[1]
            self.assertEqual(platform.LEGACY_PLUGIN_GAMES if legacy else platform.PLUGIN_GAMES,
                             data['platformDependencies']['PAPER'])
            self.assertEqual(['3.4'] if legacy else ['3.4', '4.2.0'], data['platformDependencies']['VELOCITY'])

    def test_cf_revisions_are_complete_and_bound_to_original_release_sources(self):
        manifest = platform.verify_standard(self.helper.output, 'v1.3.0-beta-3')
        selected = {r['branch']: {'base': r['sha'], 'sha': 'b' * 40}
                    for r in manifest['entries'] if r['publish'] and r['branch'] != 'plugin'}
        rows = platform.cf_rows(manifest, {manifest['tag']: selected})
        self.assertTrue(all(r['sha'] == 'b' * 40 and r['release_source_sha'] == 'a' * 40 for r in rows))
        self.assertTrue(all('-cf.1+' in release.filename(r, 'fabric') for r in rows))
        selected['26.2']['base'] = 'c' * 40
        with self.assertRaisesRegex(ValueError, 'does not match'):
            platform.cf_rows(manifest, {manifest['tag']: selected})
        del selected['26.2']
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            platform.cf_rows(manifest, {manifest['tag']: selected})

    def test_sources_or_intermediate_names_cannot_be_uploaded(self):
        row = self.helper.rows[0]
        for suffix in ('-sources', '-dev', '-plain', '-all', '-javadoc'):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, 'exact deployment'):
                platform.verify_runtime_jar(Path('musichud-tuneweave-fabric-1.3.0-beta-3' + suffix + '.jar'), row, 'fabric')

    def test_standard_jar_is_rejected_for_cf_route(self):
        row = self.helper.rows[0]
        path = self.helper.downloads / ('build-' + row['id']) / release.filename(row, 'fabric')
        cf_row = row | {'distribution': 'cf'}
        copy = path.with_name(release.filename(cf_row, 'fabric'))
        copy.write_bytes(path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'marker mismatch'):
            platform.verify_runtime_jar(copy, cf_row, 'fabric')

    def test_loader_provenance_cannot_be_changed_even_with_rehashed_manifest(self):
        path = self.helper.output / 'build-manifest.json'
        manifest = release.load_json(path)
        manifest['artifacts'][0]['module'] = 'paper'
        release.dump(path, manifest)
        sums = self.helper.output / 'SHA256SUMS'
        lines = sums.read_text().splitlines()
        sums.write_text('\n'.join(release.digest(path) + '  build-manifest.json'
                                 if line.endswith('  build-manifest.json') else line for line in lines) + '\n')
        with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
            platform.verify_standard(self.helper.output, 'v1.3.0-beta-3')

    def test_modrinth_keeps_matching_file_and_rejects_unrecognized_collision(self):
        row = next(r for r in self.helper.rows if r['id'] == '26.2')
        path = self.helper.output / release.filename(row, 'fabric')
        remote = {'id': 'version', 'loaders': ['fabric'], 'game_versions': ['26.2'],
                  'files': [{'filename': path.name, 'primary': True, 'hashes': {'sha512': platform.file_hash(path)}}]}
        self.assertIs(remote, platform.existing_modrinth_version([remote], path, row, 'fabric', {}))
        remote['files'][0]['hashes']['sha512'] = 'a' * 128
        with self.assertRaisesRegex(ValueError, 'differs from verified'):
            platform.existing_modrinth_version([remote], path, row, 'fabric', {})
        config = {'existing_modrinth_files': {path.name: {'version_id': 'version', 'sha512': 'a' * 128}}}
        self.assertIs(remote, platform.existing_modrinth_version([remote], path, row, 'fabric', config))
        remote['loaders'] = ['neoforge']
        with self.assertRaisesRegex(ValueError, 'metadata conflict'):
            platform.existing_modrinth_version([remote], path, row, 'fabric', config)

    def test_client_and_plugin_dependencies_are_not_confused(self):
        deps = {'modern-ui': 'modern', 'modernui-mc-mvus': 'mvus', 'fabric-api': 'fabric', 'forge-config-api-port': 'port'}
        plugin = next(r for r in self.helper.rows if r['id'] == 'plugin')
        data = platform.modrinth_metadata(plugin, 'paper', 'project', deps)
        self.assertEqual([], data['dependencies'])
        self.assertNotIn('environment', data)
        row = next(r for r in self.helper.rows if r['id'] == '26.2')
        data = platform.modrinth_metadata(row, 'fabric', 'project', deps)
        self.assertEqual('client_only_server_optional', data['environment'])
        self.assertEqual([{'project_id': 'fabric', 'dependency_type': 'required'}], data['dependencies'])
        self.assertIn('ModernUI-MC/releases/tag/26.2-3.13.0.9', data['changelog'])
        row = next(r for r in self.helper.rows if r['id'] == '1.21.11')
        data = platform.modrinth_metadata(row, 'neoforge', 'project', deps)
        self.assertEqual([{'project_id': 'mvus', 'dependency_type': 'required'}], data['dependencies'])
        for row in self.helper.rows:
            for module in row['modules']:
                data = platform.modrinth_metadata(row, module, 'project', deps)
                self.assertLessEqual(len(data['version_number']), 32)
                self.assertLessEqual(len(data['name']), 64)

    def test_curseforge_metadata_is_beta_auto_release_and_correct_loader(self):
        row = next(r for r in self.helper.rows if r['id'] == '1.21.8') | {'distribution': 'cf'}
        available = set(platform.PLUGIN_GAMES) | {'Fabric', 'NeoForge', 'Client', 'Server'}
        data = platform.curseforge_metadata(row, 'fabric', available)
        self.assertEqual('beta', data['releaseType'])
        self.assertFalse(data['isMarkedForManualRelease'])
        self.assertIn('-cf.1+', data['displayName'])
        self.assertEqual(['1.21.6', '1.21.7', '1.21.8', 'Fabric', 'Client'], data['gameVersionNames'])
        self.assertEqual({352491, 306612, 547434}, {d['projectID'] for d in data['relations']['projects']})
        self.assertTrue(all(type(d['projectID']) is int for d in data['relations']['projects']))
        plugin = next(r for r in self.helper.rows if r['id'] == 'plugin')
        self.assertNotIn('relations', platform.curseforge_metadata(plugin, 'paper', available))
        external = next(r for r in self.helper.rows if r['id'] == '26.2') | {'distribution': 'cf'}
        self.assertNotIn('relations', platform.curseforge_metadata(external, 'neoforge', available))
        with self.assertRaisesRegex(ValueError, 'loader tag'):
            platform.curseforge_metadata(row, 'neoforge', available - {'NeoForge'})


if __name__ == '__main__':
    unittest.main()
