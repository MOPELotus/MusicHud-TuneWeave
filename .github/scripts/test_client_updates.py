import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import client_updates as updates

class CatalogTests(unittest.TestCase):
    def jar(self, folder, edition='standard', loader='fabric', game='26.3', version=None):
        version = version or ('1.4.0-beta.1' + ('-cf.1' if edition == 'cf' else '') + '+26.3')
        path = folder / f'musichud-tuneweave-{loader}-{version}.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr(updates.METADATA, f'id=musichud_tuneweave\nversion={version}\ndistribution={edition}\nloader={loader}\nminecraft={game}\n')
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
