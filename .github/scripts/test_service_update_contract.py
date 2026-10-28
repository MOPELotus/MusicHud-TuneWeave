"""Release verifier guards for the standard-only client/service update contract."""
import io
import json
import tempfile
from pathlib import Path
import unittest
import zipfile
import build_release as release


class ServiceUpdateContracts(unittest.TestCase):
    def row(self, version='1.3.1', distribution='standard', branch='26.3', game_range='26.3'):
        return dict(id=branch.split('-')[-1], branch=branch, modules=['fabric', 'neoforge'], properties={}, publish=True,
                    sha='a' * 40, version=version, range=game_range, distribution=distribution)

    def jar(self, row, identity=None, loader='fabric'):
        raw = io.BytesIO()
        version = release.artifact_version(row)
        with zipfile.ZipFile(raw, 'w') as jar:
            jar.writestr('META-INF/musichud-distribution.properties', f'distribution={row["distribution"]}\nversion={version}\n')
            jar.writestr('indi/mopelotus/musichud/Product.class', b'fixture')
            if loader == 'fabric':
                jar.writestr('fabric.mod.json', json.dumps(dict(id='musichud_tuneweave', version=version)))
            else:
                jar.writestr('META-INF/neoforge.mods.toml', f'[[mods]]\nmodId="musichud_tuneweave"\nversion="{version}"\n')
            if identity is not None:
                jar.writestr('META-INF/musichud-update.properties', identity)
        return raw.getvalue()

    def identity(self, row, loader='fabric', profiles='future-http-v2'):
        text = (f'id=musichud_tuneweave\nversion={release.artifact_version(row)}\n'
                f'distribution={row["distribution"]}\nloader={loader}\nminecraft={row["range"]}\n')
        return text + ('' if profiles is None else 'service_profiles=' + profiles + '\n')

    def verify(self, raw, row, loader='fabric'):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'candidate.jar'
            path.write_bytes(raw)
            release.verify_jar(path, row, loader)

    def test_controlled_standard_identity_and_future_profiles_are_accepted_for_both_loaders(self):
        for loader in ('fabric', 'neoforge'):
            for version in ('1.3.1', '1.3.1.1'):
                row = self.row(version)
                release.validate_row(row)
                self.verify(self.jar(row, self.identity(row, loader), loader), row, loader)

    def test_numeric_contract_boundary_preserves_history_and_does_not_sort_1_10_as_1_1(self):
        for version in ('1.2.99', '1.3.0', '1.3.0-beta-3'):
            row = self.row(version)
            self.verify(self.jar(row), row)
            self.verify(self.jar(row, self.identity(row, profiles=None)), row)
        for version in ('1.3.1', '1.3.1-beta.1', '1.3.1.1', '1.3.1.1-beta.1', '1.3.10', '1.10.0', '2.0.0'):
            row = self.row(version)
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'Missing client update identity'):
                self.verify(self.jar(row), row)
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'Missing client service profiles'):
                self.verify(self.jar(row, self.identity(row, profiles=None)), row)

    def test_bad_profiles_wrong_identity_and_duplicate_fields_fail(self):
        row = self.row()
        for profiles in ('', 'x,x', 'UPPER', 'bad profile', 'x' * 65, ','.join(str(n) for n in range(17))):
            with self.subTest(profiles=profiles), self.assertRaisesRegex(ValueError, 'Invalid client service profiles'):
                self.verify(self.jar(row, self.identity(row, profiles=profiles)), row)
        for field, bad in [('loader=fabric', 'loader=neoforge'), ('minecraft=26.3', 'minecraft=26.2'),
                           ('distribution=standard', 'distribution=cf'), ('version=1.3.1+26.3', 'version=1.3.0+26.3')]:
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'identity mismatch'):
                self.verify(self.jar(row, self.identity(row).replace(field, bad)), row)
        with self.assertRaisesRegex(ValueError, 'Duplicate client update identity field'):
            self.verify(self.jar(row, self.identity(row) + 'service_profiles=other\n'), row)

    def test_range_artifacts_include_intermediate_games(self):
        for branch, game_range, games in [('1.21.6-1.21.8', '1.21.6-1.21.8', '1.21.6,1.21.7,1.21.8'),
                                          ('26.1', '26.1-26.1.2', '26.1,26.1.1,26.1.2')]:
            row = self.row(branch=branch, game_range=game_range)
            self.verify(self.jar(row, self.identity(row).replace('minecraft=' + game_range, 'minecraft=' + games)), row)

    def test_cf_has_no_service_profile_identity_even_on_historical_versions(self):
        for version in ('1.3.0', '1.3.1', '1.10.0'):
            row = self.row(version, 'cf')
            self.verify(self.jar(row, self.identity(row, profiles=None)), row)
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'Service profile declaration'):
                self.verify(self.jar(row, self.identity(row)), row)

    def test_new_standard_implementations_references_and_labels_are_rejected_recursively(self):
        for token in ('ServiceUpdateService', 'ServiceUpdateUi', 'ServiceUpdateCompatibility', 'DownloadSourceUi',
                      'DownloadSources', 'TuneWeaveCompatibility', 'ModServiceCompatibility', 'ServiceReleaseProfiles',
                      'ServiceUpdateHealth', 'ApiServerManager', 'SodaSignerSupport', 'SodaSignerProcess',
                      'musichud_tuneweave.update.service.title', 'service_profiles=tuneweave-http-v1'):
            for nested in (False, True):
                for name, content in [('retained.class', token.encode()), (token + '.class', b'fixture')]:
                    raw = io.BytesIO()
                    with zipfile.ZipFile(raw, 'w') as jar:
                        jar.writestr(name, content)
                    if nested:
                        outer = io.BytesIO()
                        with zipfile.ZipFile(outer, 'w') as jar:
                            jar.writestr('META-INF/jars/lib.jar', raw.getvalue())
                        raw = outer
                    with self.subTest(token=token, nested=nested), zipfile.ZipFile(raw) as jar, self.assertRaisesRegex(ValueError, 'Acquisition code'):
                        release.verify_cf_contents(jar)

    def test_plugin_is_not_subject_to_client_identity_contract(self):
        row = dict(id='plugin', branch='plugin', modules=['paper', 'velocity', 'bungeecord'], properties={},
                   publish=True, sha='a' * 40, version='1.3.1', range='', distribution='standard')
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as jar:
            jar.writestr('META-INF/musichud-distribution.properties', 'distribution=standard\nversion=1.3.1\n')
            jar.writestr('indi/mopelotus/musichud/Product.class', b'fixture')
            jar.writestr('plugin.yml', 'version: 1.3.1\n')
        self.verify(raw.getvalue(), row, 'paper')


if __name__ == '__main__':
    unittest.main()
