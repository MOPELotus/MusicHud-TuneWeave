import copy
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import build_release as release
import companion_release as companion
import test_build_release as fixtures_module


class CompanionReleaseContracts(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = dict(branch='companion', sha='b' * 40, version='0.2.0')

    def package(self, folder=None, extra=None):
        folder = folder or self.root
        folder.mkdir(parents=True, exist_ok=True)
        name = 'Companion-0.2.0-windows-x64.zip'
        archive = folder / name
        identity = dict(schemaVersion=1, version='0.2.0', protocolVersion=1, sourceCommit='b' * 40)
        with zipfile.ZipFile(archive, 'w') as bundle:
            bundle.writestr(companion.ENTRY, b'MZfixture')
            bundle.writestr('Companion/app/app.jar', b'fixture')
            bundle.writestr('Companion/runtime/bin/java.dll', b'fixture')
            bundle.writestr('Companion/licenses/LICENSE', 'fixture')
            bundle.writestr('Companion/companion.json', json.dumps(identity))
            if extra:
                bundle.writestr(extra, 'unsafe')
        metadata = identity | dict(platform='windows-x64', archive=name, sha256=release.digest(archive),
                                   size=archive.stat().st_size, entryPoint=companion.ENTRY)
        release.dump(folder / 'companion-manifest.json', metadata)
        return metadata

    def test_identity_and_catalog(self):
        metadata = self.package()
        self.assertEqual(metadata, companion.verify(self.root, self.source))
        catalog = companion.catalog(metadata, 'v1.3.1')
        self.assertEqual('0.2.0', catalog['version'])
        self.assertEqual(1, catalog['protocolVersion'])
        self.assertEqual('https://github.com/MOPELotus/MusicHud-TuneWeave/releases/download/v1.3.1/Companion-0.2.0-windows-x64.zip',
                         catalog['platforms']['windows-x64']['url'])

    def test_reject_tampered_identity_size_checksum_and_source(self):
        original = self.package()
        for key, value in [('version', '9.0.0'), ('protocolVersion', 2), ('protocolVersion', True),
                           ('sourceCommit', 'c' * 40), ('size', 0), ('size', 999), ('size', True),
                           ('sha256', 'c' * 64), ('archive', '../Companion.zip'), ('entryPoint', 'cmd.exe')]:
            with self.subTest(key=key, value=value):
                release.dump(self.root / 'companion-manifest.json', original | {key: value})
                with self.assertRaises(ValueError):
                    companion.verify(self.root, self.source)

    def test_reject_zip_traversal_duplicates_and_wrong_layout(self):
        for entry in ('../evil', 'Companion/../evil', '/Companion/evil', 'Companion/C:evil',
                      'Companion\\evil', 'companion/COMPANION.EXE', 'Companion/app/app.jar'):
            with self.subTest(entry=entry):
                self.package(extra=entry)
                with self.assertRaises(ValueError):
                    companion.verify(self.root, self.source)

    def test_bundle_and_platform_safe_metadata_partition(self):
        fixtures = fixtures_module.ReleaseContracts()
        fixtures.setUp()
        self.addCleanup(fixtures.doCleanups)
        fixtures.manifest['companion'] = self.source
        fixtures.fixture()
        self.package(fixtures.downloads / 'build-companion')
        release.bundle(fixtures.downloads, fixtures.output)
        manifest = release.verify_bundle(fixtures.output)
        self.assertEqual(self.source, manifest['companion'])
        self.assertEqual(17, len(manifest['artifacts']))
        self.assertTrue(all(a['name'].endswith('.jar') for a in manifest['artifacts']))
        self.assertTrue((fixtures.output / 'companion-latest.json').is_file())
        self.assertIn('Companion 0.2.0', (fixtures.output / 'RELEASE_NOTES.md').read_text())

    def test_cf_scoped_and_unplanned_files_rejected(self):
        metadata = self.package()
        release.dump(self.root / 'companion-latest.json', companion.catalog(metadata, 'v1.3.1'))
        for manifest in ({}, {'distribution': 'cf', 'companion': self.source},
                         {'release_scope': release.HOTFIX_SCOPE, 'companion': self.source},
                         {'entries': [{'distribution': 'cf'}], 'companion': self.source}):
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                companion.verify_release(self.root, manifest)

    def test_catalog_tamper_rejected(self):
        metadata = self.package()
        release.dump(self.root / 'companion-latest.json', companion.catalog(metadata, 'v9.9.9'))
        with self.assertRaises(ValueError):
            companion.verify_release(self.root, dict(companion=self.source, tag='v1.3.1'))
