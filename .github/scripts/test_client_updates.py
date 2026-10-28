import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import client_updates as updates

class CatalogTests(unittest.TestCase):
    def jar(self, folder, edition='standard', loader='fabric', game='26.3', version=None, profiles=None):
        version = version or ('1.4.0-beta.1' + ('-cf.1' if edition == 'cf' else '') + '+26.3')
        path = folder / f'musichud-tuneweave-{loader}-{version}.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            profile_line = '' if profiles is None else 'service_profiles=' + profiles + '\n'
            archive.writestr(updates.METADATA, f'id=musichud_tuneweave\nversion={version}\ndistribution={edition}\nloader={loader}\nminecraft={game}\n' + profile_line)
        return path

    def test_editions_and_prereleases(self):
        for edition in ('standard', 'cf'):
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                path = self.jar(folder, edition)
                data = updates.catalog(folder, 'v1.4.0-beta.1', edition)
                self.assertEqual(data['artifacts'][0]['distribution'], edition)
                self.assertEqual(data['artifacts'][0]['file'], path.name)
                self.assertEqual(data['artifacts'][0]['size'], path.stat().st_size)
                self.assertEqual(data['artifacts'][0]['sha256'], updates.release.digest(path))
                with self.assertRaises(ValueError):
                    updates.catalog(folder, 'v1.4.0-beta.1', 'cf' if edition == 'standard' else 'standard')

    def test_four_component_catalog_keeps_tag_filename_and_identity_consistent(self):
        for edition in ('standard', 'cf'):
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                version = '1.3.1.1' + ('-cf.1' if edition == 'cf' else '') + '+1.21.11'
                path = self.jar(folder, edition, game='1.21.11', version=version)
                self.assertEqual(version, updates.catalog(folder, 'v1.3.1.1', edition)['artifacts'][0]['version'])
                with self.assertRaisesRegex(ValueError, 'Updater version mismatch'):
                    updates.catalog(folder, 'v1.3.1', edition)
                path.rename(folder / path.name.replace('1.3.1.1', '1.3.1'))
                with self.assertRaisesRegex(ValueError, 'Updater version mismatch'):
                    updates.catalog(folder, 'v1.3.1.1', edition)

    def test_catalog_tags_reject_other_numeric_lengths_and_unsafe_input(self):
        for tag in ('v1.3', 'v1.3.1.1.1', 'v1..3.1', 'v1.3.1.1\n', 'v1.3.1.1/path', 'v１.3.1.1', None):
            with tempfile.TemporaryDirectory() as directory, self.subTest(tag=tag), self.assertRaisesRegex(ValueError, 'Invalid catalog tag'):
                updates.catalog(Path(directory), tag, 'standard')

    def test_invalid_identity_and_ambiguous_game_fail_closed(self):
        for game in ('26.3,26.3', '../26.3', ''):
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                self.jar(folder, game=game)
                with self.assertRaises(ValueError):
                    updates.catalog(folder, 'v1.4.0-beta.1', 'standard')
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            self.jar(folder)
            self.jar(folder, version='1.4.0-beta.1+26.2')
            with self.assertRaises(ValueError):
                updates.catalog(folder, 'v1.4.0-beta.1', 'standard')

    def test_target_service_profiles_come_from_verified_jar_identity(self):
        for profiles in ('tuneweave-http-v1', 'tuneweave-http-v1,tuneweave-http-v2'):
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                self.jar(folder, profiles=profiles)
                row = updates.catalog(folder, 'v1.4.0-beta.1', 'standard')['artifacts'][0]
                self.assertEqual(profiles.split(','), row['service_profiles'])
        for profiles in ('', 'x,x', '../bad'):
            with tempfile.TemporaryDirectory() as directory:
                folder = Path(directory)
                self.jar(folder, profiles=profiles)
                with self.assertRaises(ValueError):
                    updates.catalog(folder, 'v1.4.0-beta.1', 'standard')

    def test_historical_jar_has_no_update_offer(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            with zipfile.ZipFile(folder / 'historical.jar', 'w') as archive:
                archive.writestr('fabric.mod.json', '{}')
            self.assertEqual(updates.catalog(folder, 'v1.4.0', 'standard')['artifacts'], [])

class MirrorTests(unittest.TestCase):
    def test_no_publication_without_default_branch_dispatch(self):
        from unittest.mock import patch
        with patch.dict('os.environ', {}, clear=True):
            with self.assertRaises(ValueError):
                updates.mirror_cf(Path('not-used'), 'v1.4.0', Path('not-used'))

    def test_uploads_jars_before_catalog_without_overwriting(self):
        from unittest.mock import patch
        import os
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); cf = root / 'cf'; cf.mkdir()
            artifact = CatalogTests().jar(cf, edition='cf')
            calls = []
            def api(repo, route):
                if route.startswith('releases/tags/'):
                    return dict(draft=False, tag_name='v1.4.0-beta.1', id=1)
                if route.startswith('commits/'):
                    return dict(sha='a' * 40)
                return []
            with patch.dict(os.environ, dict(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/26.3', GITHUB_REPOSITORY=updates.REPOSITORY)), \
                 patch('platform_release.verify_platform_bundle', return_value=({}, {})), \
                 patch.object(updates.release, 'release_target', return_value='a' * 40), \
                 patch.object(updates.release, 'api', side_effect=api), \
                 patch.object(updates.subprocess, 'run', side_effect=lambda command, **kw: calls.append(command)):
                updates.mirror_cf(root, 'v1.4.0-beta.1', root / 'out')
            self.assertEqual(len(calls), 2)
            self.assertEqual(Path(calls[0][4]).name, artifact.name)
            self.assertEqual(Path(calls[1][4]).name, 'client-updates-cf.json')
            self.assertTrue(all('--clobber' not in call for call in calls))
