#!/usr/bin/env python3
"""Inspect platform projects and publish verified deployment artifacts."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import zipfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, HTTPRedirectHandler, build_opener

import build_release as release

BASES = {
    'modrinth': 'https://api.modrinth.com/v2/',
    'modrinth_v3': 'https://api.modrinth.com/v3/',
    'curseforge': 'https://minecraft.curseforge.com/api/',
    'hangar': 'https://hangar.papermc.io/api/v1/',
    'hangar_internal': 'https://hangar.papermc.io/api/internal/',
}
REPOSITORY = 'MOPELotus/MusicHud-TuneWeave'
GAME_VERSIONS = {
    '1.21.1': ['1.21.1'],
    '1.21.6-1.21.8': ['1.21.6', '1.21.7', '1.21.8'],
    '1.21.9-1.21.10': ['1.21.9', '1.21.10'],
    '1.21.11': ['1.21.11'],
    '26.1-26.1.2': ['26.1', '26.1.1', '26.1.2'],
    '26.2': ['26.2'],
    '26.3': ['26.3'],
}
LEGACY_PLUGIN_GAMES = ['1.21.1', '1.21.6', '1.21.7', '1.21.8', '1.21.9', '1.21.10',
                '1.21.11', '26.1', '26.1.1', '26.1.2', '26.2']
PLUGIN_GAMES = LEGACY_PLUGIN_GAMES + ['26.3']
MODERNUI_FORKS = {'26.2': '26.2-3.13.0.7', '26.3': '26.3-3.13.0.7'}
PREPARATION_BRANCHES = {'26.2', release.DEFAULT_BRANCH}
MAIN_CLASSES = {
    'fabric': 'indi/mopelotus/musichud/fabric/CommonInitializer.class',
    'neoforge': 'indi/mopelotus/musichud/neoforge/CommonInitializer.class',
    'paper': 'indi/mopelotus/musichud/paper/CommonInitializer.class',
    'velocity': 'indi/mopelotus/musichud/velocity/VelocityInitializer.class',
    'bungeecord': 'indi/mopelotus/musichud/bungeecord/BungeeInitializer.class',
}


def game_versions(row, manifest=None):
    if row['range']:
        return GAME_VERSIONS[row['range']]
    if manifest is not None and not any(r['branch'] == release.DEFAULT_BRANCH for r in manifest['entries']):
        return LEGACY_PLUGIN_GAMES
    return PLUGIN_GAMES


def validate_tag(tag):
    release.require(isinstance(tag, str) and tag.startswith('v') and
                    release.VERSION.fullmatch(tag[1:]) and not tag.endswith('-cf'), 'Invalid standard release tag')


def verify_runtime_jar(path, row, module):
    release.require(path.name == release.filename(row, module), 'Not the exact deployment JAR filename')
    release.verify_jar(path, row, module)
    with zipfile.ZipFile(path) as jar:
        required = [MAIN_CLASSES[module], 'indi/mopelotus/musichud/MusicHud.class',
                    'META-INF/musichud-registries.core.properties']
        if module in ('fabric', 'neoforge'):
            required += ['META-INF/musichud-registries.common.properties', 'musichud_tuneweave.mixins.json']
        for name in required:
            release.require(name in jar.namelist(), 'Missing runtime entry point or packaged shared module: ' + name)
            if name.endswith('.class'):
                content = jar.read(name)
                release.require(content[:4] == b'\xca\xfe\xba\xbe', 'Invalid runtime class')
                expected_java = 69 if row['branch'].startswith('26.') else 65
                release.require(int.from_bytes(content[6:8], 'big') == expected_java, 'Wrong Java bytecode target')


def verify_standard(folder, tag):
    validate_tag(tag)
    manifest = release.verify_bundle(folder)
    release.require(manifest['repository'] == REPOSITORY and manifest['tag'] == tag,
                    'Release repository or tag mismatch')
    rows = manifest['entries']
    release.require(all(row.get('distribution', 'standard') == 'standard' and row['version'] == tag[1:]
                        for row in rows), 'Expected matching standard release versions')
    expected = {release.filename(row, module): (row, module) for row in rows if row['publish']
                for module in row['modules']}
    release.require(len(expected) == release.artifact_count(rows) and
                    {a['name'] for a in manifest['artifacts']} == set(expected),
                    'Duplicate or missing release artifacts')
    for artifact in manifest['artifacts']:
        release.require(artifact['name'] in expected, 'Unexpected deployment artifact')
        row, module = expected[artifact['name']]
        release.require((artifact['module'], artifact['branch'], artifact['sha'], artifact['endpoint']) ==
                        (module, row['branch'], row['sha'], row['id']), 'Artifact provenance mismatch')
        verify_runtime_jar(folder / artifact['name'], row, module)
    return manifest


def cf_rows(manifest, overrides=None):
    if overrides is None:
        overrides = release.load_json(Path(__file__).parent.parent / 'cf-source-revisions.json')
    selected = overrides.get(manifest['tag'], {})
    rows = [row | {'distribution': 'cf'} for row in manifest['entries']
            if row['publish'] and row['branch'] != 'plugin']
    release.require(not selected or set(selected) == {r['branch'] for r in rows}, 'Incomplete CF source revisions')
    for row in rows:
        if row['branch'] in selected:
            revision = selected[row['branch']]
            release.require(set(revision) == {'base', 'sha'} and revision['base'] == row['sha'] and
                            isinstance(revision['sha'], str) and release.SHA.fullmatch(revision['sha']) and
                            revision['sha'] != row['sha'], 'CF source revision does not match the release')
            row.update(release_source_sha=row['sha'], sha=revision['sha'])
            release.validate_row(row)
    return rows


def prepare(tag, output):
    validate_tag(tag)
    info = release.api(REPOSITORY, 'releases/tags/' + tag)
    release.require(not info['draft'] and info['tag_name'] == tag, 'GitHub release is not published')
    output.mkdir(parents=True, exist_ok=False)
    standard = output / 'standard'
    subprocess.run(['gh', 'release', 'download', tag, '--repo', REPOSITORY, '--dir', str(standard),
                    '--pattern', '*.jar', '--pattern', 'SHA256SUMS', '--pattern', 'build-manifest.json',
                    '--pattern', 'license', '--pattern', 'RELEASE_NOTES.md'], check=True)
    manifest = verify_standard(standard, tag)
    target = release.release_target(manifest)
    release.require(release.api(REPOSITORY, 'commits/' + tag)['sha'] == target, 'Release tag/source mismatch')
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('matrix=' + json.dumps({'include': cf_rows(manifest)}, separators=(',', ':')) + '\n')
    print(f"Verified {len(manifest['artifacts'])} standard deployment JARs; CF builds use frozen, reviewed packaging revisions.")


def assemble(bundle, downloads, tag):
    manifest = verify_standard(bundle / 'standard', tag)
    folder = bundle / 'cf'
    folder.mkdir(exist_ok=False)
    artifacts = []
    for row in cf_rows(manifest):
        source = downloads / ('cf-' + row['id'])
        build = release.load_json(source / 'build.json')
        release.require(build['entry'] == row, 'CF build differs from frozen release source')
        names = {release.filename(row, m) for m in row['modules']}
        release.require({p.name for p in source.iterdir()} == names | {'build.json'}, 'Unexpected CF build file')
        release.require(len(build['artifacts']) == 2 and {a['name'] for a in build['artifacts']} == names,
                        'Incomplete CF client build')
        for artifact in build['artifacts']:
            module = artifact['module']
            release.require(module in row['modules'] and artifact['name'] == release.filename(row, module),
                            'CF artifact loader mismatch')
            path = source / artifact['name']
            release.require(release.digest(path) == artifact['sha256'], 'CF artifact checksum mismatch')
            verify_runtime_jar(path, row, module)
            shutil.copy2(path, folder / path.name)
            artifacts.append(artifact | {'row': row})
    release.dump(folder / 'cf-manifest.json', {'tag': tag, 'artifacts': artifacts})
    verify_platform_bundle(bundle, tag)
    print(f"Verified platform bundle: {len(manifest['artifacts'])} standard JARs and {len(artifacts)} CF client JARs.")


def verify_platform_bundle(folder, tag):
    manifest = verify_standard(folder / 'standard', tag)
    cf = release.load_json(folder / 'cf/cf-manifest.json')
    expected = {release.filename(row, m): (row, m) for row in cf_rows(manifest) for m in row['modules']}
    release.require(cf['tag'] == tag and len(cf['artifacts']) == len(expected), 'Incomplete CF manifest')
    release.require({a['name'] for a in cf['artifacts']} == set(expected), 'CF manifest filename mismatch')
    release.require({p.name for p in (folder / 'cf').iterdir()} == set(expected) | {'cf-manifest.json'},
                    'Unlisted CF files')
    for artifact in cf['artifacts']:
        row, module = expected[artifact['name']]
        release.require(artifact['row'] == row and artifact['module'] == module, 'CF source/loader mismatch')
        path = folder / 'cf' / artifact['name']
        release.require(release.digest(path) == artifact['sha256'], 'CF deployment checksum mismatch')
        verify_runtime_jar(path, row, module)
    return manifest, cf


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Do not forward platform credentials to a redirected origin.
        return None


class ApiError(RuntimeError):
    def __init__(self, platform, status, detail=''):
        self.platform, self.status = platform, status
        super().__init__(f'{platform} API returned HTTP {status}' + (': ' + detail if detail else ''))


class Client:
    def __init__(self, platform, token):
        release.require(platform in BASES and isinstance(token, str) and bool(token) and
                        not any(ord(c) < 32 for c in token), 'Missing or invalid platform credential')
        self.platform, self.token = platform, token
        self.opener = build_opener(NoRedirect())

    def request(self, path, method='GET', data=None, content_type=None, authenticated=True):
        release.require(isinstance(path, str) and not path.startswith('/') and
                        '://' not in path and '..' not in path and '\\' not in path,
                        'Invalid API path')
        headers = {'User-Agent': 'MusicHud-TuneWeave/1.0 (github.com/MOPELotus/MusicHud-TuneWeave)',
                   'Accept': 'application/json'}
        if authenticated:
            headers['X-Api-Token' if self.platform == 'curseforge' else 'Authorization'] = self.token
        if content_type:
            headers['Content-Type'] = content_type
        req = Request(BASES[self.platform] + path, data=data, headers=headers, method=method)
        for attempt in range(3):
            try:
                with self.opener.open(req, timeout=90) as response:
                    raw = response.read()
                return json.loads(raw) if raw else None
            except HTTPError as error:
                if method == 'GET' and error.code in (429, 500, 502, 503, 504) and attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                # Keep only a bounded diagnostic field, with credentials removed.
                detail = ''
                try:
                    problem = json.loads(error.read(8192))
                    if isinstance(problem, dict):
                        detail = next((problem[k] for k in ('errorMessage', 'description', 'message', 'error')
                                       if isinstance(problem.get(k), str)), '')
                    secrets = [self.token, self.token.removeprefix('HangarAuth ')] + [
                        os.environ.get(k, '') for k in ('MODRINTH_TOKEN', 'CURSEFORGE_TOKEN', 'HANGAR_API_TOKEN')]
                    for secret in secrets:
                        if secret:
                            detail = detail.replace(secret, '[redacted]')
                    detail = ' '.join(detail.split())[:400]
                except (ValueError, OSError):
                    pass
                raise ApiError(self.platform, error.code, detail) from None
            except (URLError, TimeoutError):
                if method == 'GET' and attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                raise RuntimeError(f'{self.platform} API network error; check remote state before retrying a write') from None


def inspect_projects(config, output):
    output.mkdir(parents=True, exist_ok=True)
    report = {}
    # Initialize independently so one unavailable platform does not hide the others.
    for platform, secret in (('modrinth', 'MODRINTH_TOKEN'), ('curseforge', 'CURSEFORGE_TOKEN'), ('hangar', 'HANGAR_API_TOKEN')):
        try:
            client = Client(platform, os.environ.get(secret, ''))
            if platform == 'modrinth':
                project = client.request('project/' + config['modrinth'])
                versions = client.request('project/' + project['id'] + '/version')
                report[platform] = {'project': {k: project.get(k) for k in (
                    'id', 'slug', 'title', 'status', 'requested_status', 'loaders', 'game_versions')},
                    'versions': [{k: v.get(k) for k in ('id', 'name', 'version_number', 'status',
                        'loaders', 'game_versions', 'environment', 'files', 'dependencies')} for v in versions]}
            elif platform == 'curseforge':
                versions = client.request('game/versions')
                report[platform] = {'upload_api_authenticated': True, 'game_version_count': len(versions),
                                    'mods_project': config['curseforge_mods'], 'plugins_project': config['curseforge_plugins']}
            else:
                session = client.request('authenticate', method='POST',
                    data=urlencode({'apiKey': client.token}).encode(),
                    content_type='application/x-www-form-urlencoded', authenticated=False)
                token = session['token']
                release.require(isinstance(token, str) and '\n' not in token and '\r' not in token, 'Invalid Hangar session')
                if os.environ.get('GITHUB_ACTIONS') == 'true':
                    print('::add-mask::' + token)
                client.token = 'HangarAuth ' + token
                project = client.request('projects/' + config['hangar'])
                versions = client.request('projects/' + config['hangar'] + '/versions?limit=100')
                report[platform] = {'project': {k: project.get(k) for k in (
                    'id', 'name', 'namespace', 'visibility', 'settings')}, 'versions': versions}
        except (ApiError, RuntimeError, ValueError, KeyError) as error:
            report[platform] = {'error': str(error)}
    release.dump(output / 'platform-inspection.json', report)
    print(json.dumps(report, indent=2))


def multipart(metadata_name, metadata, files):
    boundary = 'musichud-' + uuid.uuid4().hex
    parts = []
    def add(headers, data):
        parts.append(('--' + boundary + '\r\n' + headers + '\r\n\r\n').encode() + data + b'\r\n')
    release.require(re.fullmatch(r'[A-Za-z]+', metadata_name), 'Invalid multipart field')
    add(f'Content-Disposition: form-data; name="{metadata_name}"\r\nContent-Type: application/json',
        json.dumps(metadata).encode())
    for field, path in files:
        release.require(re.fullmatch(r'[A-Za-z]+', field) and
                        re.fullmatch(r'[A-Za-z0-9_.+-]+\.jar', path.name), 'Invalid multipart file')
        add(f'Content-Disposition: form-data; name="{field}"; filename="{path.name}"\r\n'
            'Content-Type: application/java-archive', path.read_bytes())
    return b''.join(parts) + ('--' + boundary + '--\r\n').encode(), 'multipart/form-data; boundary=' + boundary


def file_hash(path, algorithm='sha512'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def changelog(row, module):
    text = (f"MusicHud TuneWeave {row['version']} beta for {module}. "
            'Shared playback, music queues, lyrics and HUD improvements.\n\n')
    if module in ('fabric', 'neoforge'):
        modern = ('https://github.com/MOPELotus/ModernUI-MC/releases/tag/' + MODERNUI_FORKS[row['branch']]
                  if row['branch'] in MODERNUI_FORKS else
                  'https://modrinth.com/mod/modernui-mc-mvus' if row['branch'] in ('1.21.9-1.21.10', '1.21.11')
                  else 'https://modrinth.com/mod/modern-ui')
        text += f"Requires the matching [ModernUI build]({modern}) for this Minecraft version and loader. "
        if row['branch'] in MODERNUI_FORKS:
            text += f"Install the {MODERNUI_FORKS[row['branch']]} fork manually; it is currently distributed through GitHub. "
        if module == 'fabric':
            text += 'Fabric API is required. '
            if row['branch'] not in MODERNUI_FORKS:
                text += 'ModernUI also requires Forge Config API Port on Fabric. '
        if row.get('distribution') == 'cf':
            text += ('\n\nCF edition: TuneWeave download/update functionality is removed. '
                     'Connect to an existing service or explicitly configure an existing local executable. '
                     'Fresh configurations leave the executable path empty and autostart disabled. '
                     '\n\nCF packaging revision 1 removes the unused LavaPlayer YouTube source implementation '
                     'and its automatic source-registration helper. HTTP stream and local audio decoding are retained '
                     'and tested against the final deployment JAR. This corrects the blacklisted-class processing rejection. ')
    else:
        text += ('Requires matching MusicHud TuneWeave clients for music features. '
                 'Install Paper only on a standalone Paper server; on Velocity/BungeeCord networks install '
                 'only the matching proxy plugin, with no plugin on any backend. '
                 'Java 21 bytecode; the host may require a newer Java runtime. '
                 'The plugin does not download/start TuneWeave or store music-platform credentials. ')
    return text + '\n\n[Setup and dependencies](https://github.com/' + REPOSITORY + '/blob/v' + row['version'] + '/README.md)'


def modrinth_metadata(row, module, project, dependency_ids, manifest=None):
    dependencies = []
    if module in ('fabric', 'neoforge'):
        # Modrinth does not persist manually supplied file_name dependencies on
        # JAR uploads. The external ModernUI fork requirement is linked explicitly
        # in the changelog instead of inventing a project/version relation.
        if row['branch'] not in MODERNUI_FORKS:
            dep = 'modernui-mc-mvus' if row['branch'] in ('1.21.9-1.21.10', '1.21.11') else 'modern-ui'
            dependencies.append({'project_id': dependency_ids[dep], 'dependency_type': 'required'})
        if module == 'fabric':
            dependencies.append({'project_id': dependency_ids['fabric-api'], 'dependency_type': 'required'})
            if row['branch'] not in MODERNUI_FORKS:
                dependencies.append({'project_id': dependency_ids['forge-config-api-port'], 'dependency_type': 'required'})
    number = release.artifact_version(row) if row['range'] else row['version'] + '+' + module
    release.require(len(number) <= 32, 'Modrinth version number exceeds 32 characters')
    data = {'project_id': project, 'name': f"{row['version']} · {module} · {row['range'] or 'server/proxy'}",
            'version_number': number,
            'version_type': 'beta', 'loaders': [module],
            'game_versions': game_versions(row, manifest),
            'dependencies': dependencies, 'changelog': changelog(row, module),
            'featured': True, 'file_parts': ['file'], 'primary_file': 'file'}
    if row['range']:
        data['environment'] = 'client_only_server_optional'
    return data


def existing_modrinth_version(versions, path, row, module, config, manifest=None):
    accepted = config.get('existing_modrinth_files', {}).get(path.name)
    wanted = file_hash(path)
    matches = []
    for version in versions:
        for remote in version['files']:
            if remote['filename'] != path.name:
                continue
            seeded = (accepted and version['id'] == accepted['version_id'] and
                      remote['hashes'].get('sha512') == accepted['sha512'])
            release.require(remote['hashes'].get('sha512') == wanted or seeded,
                            'Existing Modrinth file differs from verified artifact: ' + path.name)
            release.require(version['loaders'] == [module] and set(version['game_versions']) ==
                            set(game_versions(row, manifest)),
                            'Existing Modrinth loader/game metadata conflict')
            release.require(len(version['files']) == 1 and remote.get('primary'), 'Unexpected additional Modrinth files')
            matches.append(version)
    release.require(len(matches) <= 1, 'Duplicate existing Modrinth versions')
    return matches[0] if matches else None


def dependency_signature(dependencies):
    return sorted(json.dumps({k: v for k, v in d.items() if v is not None}, sort_keys=True) for d in dependencies)


def modrinth_versions(client, project):
    versions = []
    for offset in range(0, 10000, 100):
        page = client.request('project/' + project + '/version?' + urlencode({'limit': 100, 'offset': offset}))
        release.require(isinstance(page, list), 'Invalid Modrinth version list')
        versions.extend(page)
        release.require(len({v['id'] for v in versions}) == len(versions), 'Duplicate Modrinth pagination results')
        if len(page) < 100:
            return versions
    raise ValueError('Too many Modrinth versions to reconcile safely')


def verify_modrinth_metadata(version, expected):
    release.require(version['loaders'] == expected['loaders'] and
                    set(version['game_versions']) == set(expected['game_versions']) and
                    version['version_type'] == expected['version_type'] and
                    ('environment' not in expected or version['environment'] == expected['environment']) and
                    dependency_signature(version['dependencies']) == dependency_signature(expected['dependencies']),
                    'Modrinth version metadata was not applied correctly')


def publish_modrinth(client, manifest, folder, config, report):
    writer = Client('modrinth_v3', client.token)
    project = client.request('project/' + config['modrinth'])
    release.require(project['id'] == config['modrinth'] and project['slug'] == 'musichud-tuneweave', 'Wrong Modrinth project')
    versions = modrinth_versions(client, project['id'])
    dependency_ids = {}
    for slug in ('modern-ui', 'modernui-mc-mvus', 'fabric-api', 'forge-config-api-port'):
        dep = client.request('project/' + slug)
        release.require(dep['slug'] == slug and dep['status'] in ('approved', 'unlisted'), 'Unavailable dependency: ' + slug)
        dependency_ids[slug] = dep['id']
    for row in manifest['entries']:
        if not row['publish']:
            continue
        for module in row['modules']:
            path = folder / release.filename(row, module)
            data = modrinth_metadata(row, module, project['id'], dependency_ids, manifest)
            try:
                existing = existing_modrinth_version(versions, path, row, module, config, manifest)
                if existing:
                    updates = {key: data[key] for key in ('version_type', 'dependencies', 'environment', 'changelog')
                               if key in data and existing.get(key) != data[key]}
                    if dependency_signature(existing['dependencies']) == dependency_signature(data['dependencies']):
                        updates.pop('dependencies', None)
                    if updates:
                        writer.request('version/' + existing['id'], method='PATCH', data=json.dumps(updates).encode(),
                                       content_type='application/json')
                    current = client.request('version/' + existing['id'])
                    verify_modrinth_metadata(current, data)
                    report.record(path.name, 'retained', version_id=current['id'],
                                  sha512=current['files'][0]['hashes']['sha512'], release_sha256=release.digest(path))
                    continue
                body, content_type = multipart('data', data, [('file', path)])
                report.record(path.name, 'pending', sha256=release.digest(path))
                created = writer.request('version', method='POST', data=body, content_type=content_type)
                result = client.request('version/' + created['id'])
                release.require(result['project_id'] == project['id'] and result['loaders'] == [module], 'Modrinth upload target mismatch')
                release.require(len(result['files']) == 1 and result['files'][0]['filename'] == path.name and
                                result['files'][0]['hashes']['sha512'] == file_hash(path), 'Modrinth uploaded file hash mismatch')
                verify_modrinth_metadata(result, data)
                versions.append(result)
                report.record(path.name, 'uploaded', version_id=result['id'], sha256=release.digest(path))
            except (ApiError, RuntimeError, ValueError) as error:
                report.failure(path.name, error)
    report.data['project_status'] = client.request('project/' + project['id'])['status']
    report.save()


def curseforge_metadata(row, module, available, manifest=None):
    names = list(game_versions(row, manifest))
    loader = {'fabric': 'Fabric', 'neoforge': 'NeoForge', 'paper': 'Paper',
              'velocity': 'Velocity', 'bungeecord': 'BungeeCord'}[module]
    if row['range']:
        release.require(loader in available, 'Missing CurseForge loader tag: ' + loader)
    if loader in available:
        names.append(loader)
    environment = 'Client' if row['range'] else 'Server'
    if environment in available:
        names.append(environment)
    release.require(all(name in available for name in names), 'Missing CurseForge game version tag')
    relations = []
    if row['range']:
        if row['branch'] not in MODERNUI_FORKS and row['branch'] not in ('1.21.9-1.21.10', '1.21.11'):
            relations.append({'slug': 'modern-ui', 'projectID': 352491, 'type': 'requiredDependency'})
        if module == 'fabric':
            relations.append({'slug': 'fabric-api', 'projectID': 306612, 'type': 'requiredDependency'})
            if row['branch'] not in MODERNUI_FORKS:
                relations.append({'slug': 'forge-config-api-port', 'projectID': 547434, 'type': 'requiredDependency'})
    data = {'displayName': f"MusicHud TuneWeave {release.artifact_version(row)} - {module}",
            'releaseType': 'beta', 'gameVersionNames': names,
            'changelog': changelog(row, module), 'changelogType': 'markdown',
            'isMarkedForManualRelease': False}
    if relations:
        data['relations'] = {'projects': relations}
    return data


def publish_curseforge(client, manifest, cf, folder, config, report):
    available = {v['name'] for v in client.request('game/versions')}
    items = [(a['row'], a['module'], folder / 'cf' / a['name'], config['curseforge_mods']) for a in cf['artifacts']]
    plugin = next(r for r in manifest['entries'] if r['branch'] == 'plugin')
    items += [(plugin, module, folder / 'standard' / release.filename(plugin, module), config['curseforge_plugins'])
              for module in plugin['modules']]
    blocked_projects = set()
    for row, module, path, project in items:
        key = str(project) + ':' + path.name
        if project in blocked_projects:
            report.record(key, 'blocked', reason='Project upload access failed earlier in this run')
            continue
        try:
            prior = report.data['files'].get(key)
            digest = release.digest(path)
            if prior and prior['status'] == 'uploaded':
                release.require(prior['sha256'] == digest, 'Previously uploaded CF artifact has different bytes; reuse its prepared run')
                continue
            release.require(not prior or prior['status'] not in ('pending', 'uncertain'),
                            'A previous CF upload needs remote confirmation before retrying')
            data = curseforge_metadata(row, module, available, manifest)
            body, content_type = multipart('metadata', data, [('file', path)])
            report.record(key, 'pending', sha256=digest)
            result = client.request(f'projects/{project}/upload-file', method='POST', data=body, content_type=content_type)
            release.require(isinstance(result.get('id'), int) and result['id'] > 0, 'Invalid CurseForge upload response')
            report.record(key, 'uploaded', sha256=digest, file_id=result['id'],
                          release_after_approval=True, project_id=project)
        except (ApiError, RuntimeError, ValueError) as error:
            report.failure(key, error)
            if isinstance(error, ApiError) and error.status in (401, 403, 404):
                blocked_projects.add(project)


def hangar_session(client):
    session = client.request('authenticate', method='POST', data=urlencode({'apiKey': client.token}).encode(),
                             content_type='application/x-www-form-urlencoded', authenticated=False)
    token = session['token']
    release.require(isinstance(token, str) and not any(ord(c) < 32 for c in token), 'Invalid Hangar session')
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print('::add-mask::' + token)
    client.token = 'HangarAuth ' + token


def ensure_hangar_channel(client, project, name):
    internal = Client('hangar_internal', client.token)
    try:
        channels = internal.request('channels/' + str(project['id']))
    except ApiError as error:
        if error.status not in (401, 403, 404):
            raise
        return  # Let the public upload API validate the channel.
    if any(channel['name'] == name for channel in channels):
        return
    used = {channel['color'].lower() for channel in channels}
    color = next((color for color in ('#eab308', '#a855f7', '#0ea5e9', '#f97316') if color not in used), None)
    release.require(color is not None, 'Choose an unused color for the Hangar Beta channel')
    data = {'name': name, 'description': 'Beta releases of MusicHud TuneWeave.', 'color': color, 'flags': ['UNSTABLE']}
    try:
        internal.request('channels/' + str(project['id']) + '/create', method='POST',
                         data=json.dumps(data).encode(), content_type='application/json')
    except ApiError as error:
        if error.status in (401, 403):
            raise RuntimeError('Hangar API key cannot create the Beta channel (HTTP ' + str(error.status) +
                               '); create Beta in project Channels, then retry') from None
        raise
    channels = internal.request('channels/' + str(project['id']))
    release.require(any(channel['name'] == name for channel in channels), 'Hangar Beta channel creation was not confirmed')


def publish_hangar(client, manifest, folder, config, report):
    hangar_session(client)
    project = client.request('projects/' + config['hangar'])
    release.require(project['namespace'] == {'owner': 'MOPELotus', 'slug': 'MusicHud-TuneWeave'}, 'Wrong Hangar project')
    row = next(r for r in manifest['entries'] if r['branch'] == 'plugin')
    paths = [folder / release.filename(row, module) for module in ('paper', 'velocity')]
    hashes = {platform: release.digest(path) for platform, path in zip(('PAPER', 'VELOCITY'), paths)}
    try:
        existing = client.request('projects/' + config['hangar'] + '/versions/' + row['version'])
    except ApiError as error:
        if error.status != 404:
            raise
        existing = None
    if existing:
        release.require(set(existing['downloads']) == set(hashes) and
                        all(existing['downloads'][p]['fileInfo']['sha256Hash'] == h for p, h in hashes.items()),
                        'Existing Hangar version differs from release artifacts')
        report.record(row['version'], 'retained', hashes=hashes)
    else:
        ensure_hangar_channel(client, project, config['hangar_channel'])
        data = {'version': row['version'], 'channel': config['hangar_channel'],
                'description': changelog(row, 'paper') + '\n\nAlso includes the dedicated Velocity proxy JAR.',
                'platformDependencies': {'PAPER': game_versions(row, manifest),
                                         'VELOCITY': ['3.4', '4.2.0'] if '26.3' in game_versions(row, manifest) else ['3.4']},
                'pluginDependencies': {'PAPER': [], 'VELOCITY': []},
                'files': [{'platforms': ['PAPER']}, {'platforms': ['VELOCITY']}]}
        body, content_type = multipart('versionUpload', data, [('files', p) for p in paths])
        report.record(row['version'], 'pending', hashes=hashes)
        result = client.request('projects/' + config['hangar'] + '/upload', method='POST', data=body, content_type=content_type)
        current = client.request('projects/' + config['hangar'] + '/versions/' + row['version'])
        release.require(all(current['downloads'][p]['fileInfo']['sha256Hash'] == h for p, h in hashes.items()),
                        'Hangar uploaded file hash mismatch')
        report.record(row['version'], 'uploaded', hashes=hashes, url=result.get('url'))
    report.data['project_visibility'] = client.request('projects/' + config['hangar'])['visibility']
    report.save()


class Receipt:
    def __init__(self, platform, tag, output, previous=None):
        self.output = output
        self.output.mkdir(parents=True, exist_ok=True)
        self.data = {'platform': platform, 'tag': tag, 'files': previous or {}}
        self.errors = []
        self.save()

    def save(self):
        release.dump(self.output / 'receipt.json', self.data)

    def record(self, key, status, **fields):
        self.data['files'][key] = {'status': status, **fields}
        self.save()
        print(f'{status}: {key}')

    def failure(self, key, error):
        prior = self.data['files'].get(key, {})
        uncertain = prior.get('status') in ('pending', 'uncertain') and not (isinstance(error, ApiError) and error.status < 500)
        status = prior['status'] if prior.get('status') in ('uploaded', 'retained') else 'uncertain' if uncertain else 'failed'
        self.record(key, status, error=str(error),
                    **{k: v for k, v in prior.items() if k not in ('status', 'error')})
        self.errors.append(key)


def previous_receipts(platform, tag):
    name = f'platform-receipt-{tag}-{platform}'
    metadata = release.api(REPOSITORY, 'actions/artifacts?' + urlencode({'name': name, 'per_page': 100}))
    release.require(metadata['total_count'] <= 100, 'Too many receipt histories; review before republishing')
    files = {}
    for artifact in sorted(metadata['artifacts'], key=lambda a: a['id']):
        release.require(not artifact['expired'], 'A previous upload receipt expired; confirm published files before retrying')
        run = release.api(REPOSITORY, 'actions/runs/' + str(artifact['workflow_run']['id']))
        release.require(run['event'] == 'workflow_dispatch' and run['head_branch'] in PREPARATION_BRANCHES and
                        run['path'] == '.github/workflows/platforms.yml', 'Untrusted upload receipt source')
        for attempt in range(4):
            result = subprocess.run(['gh', 'api', f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip"],
                                    capture_output=True)
            if result.returncode == 0:
                break
            if attempt == 3:
                raise RuntimeError('Cannot read previous upload receipts; no new uploads attempted')
            time.sleep(2 ** attempt)
        raw = result.stdout
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            release.require(archive.namelist() == ['receipt.json'] and archive.getinfo('receipt.json').file_size < 1_000_000,
                            'Invalid upload receipt archive')
            data = json.loads(archive.read('receipt.json'))
        release.require(data['platform'] == platform and data['tag'] == tag, 'Receipt tag/platform mismatch')
        files.update(data['files'])
    return files


def publish_platform(platform, folder, tag, config, output):
    release.require(platform in ('modrinth', 'curseforge', 'hangar'), 'Missing publishing platform')
    release.require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and
                    os.environ.get('GITHUB_REF') == 'refs/heads/' + release.DEFAULT_BRANCH and
                    os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Platform publishing requires default-branch dispatch')
    manifest, cf = verify_platform_bundle(folder, tag)
    previous = previous_receipts(platform, tag) if platform == 'curseforge' else {}
    report = Receipt(platform, tag, output, previous)
    secret = {'modrinth': 'MODRINTH_TOKEN', 'curseforge': 'CURSEFORGE_TOKEN', 'hangar': 'HANGAR_API_TOKEN'}[platform]
    client = Client(platform, os.environ.get(secret, ''))
    try:
        if platform == 'modrinth':
            publish_modrinth(client, manifest, folder / 'standard', config, report)
        elif platform == 'curseforge':
            publish_curseforge(client, manifest, cf, folder, config, report)
        else:
            publish_hangar(client, manifest, folder / 'standard', config, report)
    except (ApiError, RuntimeError, ValueError, KeyError) as error:
        report.failure('platform', error)
    release.require(not report.errors, 'Some uploads need attention; see the platform receipt')


def validate_dispatch(tag, target, prepared_run):
    validate_tag(tag)
    release.require(target in ('all', 'modrinth', 'curseforge', 'hangar'), 'Unsupported publishing target')
    if prepared_run:
        release.require(re.fullmatch(r'[1-9][0-9]*', prepared_run), 'Invalid prepared workflow run')
        run = release.api(REPOSITORY, 'actions/runs/' + prepared_run)
        release.require(run['event'] == 'workflow_dispatch' and run['head_branch'] in PREPARATION_BRANCHES and
                        run['path'] == '.github/workflows/platforms.yml' and run['conclusion'] == 'success',
                        'Prepared bundle must come from a successful default-branch platform workflow')
    platforms = ['modrinth', 'curseforge', 'hangar'] if target == 'all' else [target]
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('matrix=' + json.dumps({'platform': platforms}, separators=(',', ':')) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['inspect', 'prepare', 'assemble', 'publish', 'validate-dispatch'])
    parser.add_argument('--tag', default='')
    parser.add_argument('--input', type=Path, default=Path('cf-builds'))
    parser.add_argument('--platform', choices=['modrinth', 'curseforge', 'hangar'])
    parser.add_argument('--target', default='all')
    parser.add_argument('--prepared-run', default='')
    parser.add_argument('--output', type=Path, default=Path('platform-report'))
    args = parser.parse_args()
    if args.command == 'inspect':
        inspect_projects(release.load_json('.github/platform-projects.json'), args.output)
    elif args.command == 'prepare':
        prepare(args.tag, args.output)
    elif args.command == 'assemble':
        assemble(args.output, args.input, args.tag)
    elif args.command == 'publish':
        publish_platform(args.platform, args.input, args.tag,
                         release.load_json('.github/platform-projects.json'), args.output)
    else:
        validate_dispatch(args.tag, args.target, args.prepared_run)
