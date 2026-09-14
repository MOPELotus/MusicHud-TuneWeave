import io
import json
from pathlib import Path
import unittest
import zipfile
from unittest.mock import Mock, patch
from urllib.error import HTTPError

import platform_release as platform
import build_release as release
import test_build_release as fixtures


class PlatformContracts(unittest.TestCase):
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
        rows = platform.cf_rows(manifest)
        self.assertEqual(15, len(manifest['artifacts']))
        self.assertEqual(6, len(rows))
        self.assertTrue(all(r['distribution'] == 'cf' and r['branch'] != 'plugin' for r in rows))

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


if __name__ == '__main__':
    unittest.main()
