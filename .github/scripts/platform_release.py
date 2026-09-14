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
import zipfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, HTTPRedirectHandler, build_opener

import build_release as release

BASES = {
    'modrinth': 'https://api.modrinth.com/v2/',
    'curseforge': 'https://minecraft.curseforge.com/api/',
    'hangar': 'https://hangar.papermc.io/api/v1/',
}
REPOSITORY = 'MOPELotus/MusicHud-TuneWeave'
GAME_VERSIONS = {
    '1.21.1': ['1.21.1'],
    '1.21.6-1.21.8': ['1.21.6', '1.21.7', '1.21.8'],
    '1.21.9-1.21.10': ['1.21.9', '1.21.10'],
    '1.21.11': ['1.21.11'],
    '26.1-26.1.2': ['26.1', '26.1.1', '26.1.2'],
    '26.2': ['26.2'],
}
PLUGIN_GAMES = ['1.21.1', '1.21.6', '1.21.7', '1.21.8', '1.21.9', '1.21.10',
                '1.21.11', '26.1', '26.1.1', '26.1.2', '26.2']
MAIN_CLASSES = {
    'fabric': 'indi/mopelotus/musichud/fabric/CommonInitializer.class',
    'neoforge': 'indi/mopelotus/musichud/neoforge/CommonInitializer.class',
    'paper': 'indi/mopelotus/musichud/paper/CommonInitializer.class',
    'velocity': 'indi/mopelotus/musichud/velocity/VelocityInitializer.class',
    'bungeecord': 'indi/mopelotus/musichud/bungeecord/BungeeInitializer.class',
}


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
    release.require(len(expected) == 15 and len({a['name'] for a in manifest['artifacts']}) == 15,
                    'Duplicate or missing release artifacts')
    for artifact in manifest['artifacts']:
        release.require(artifact['name'] in expected, 'Unexpected deployment artifact')
        row, module = expected[artifact['name']]
        release.require((artifact['module'], artifact['branch'], artifact['sha'], artifact['endpoint']) ==
                        (module, row['branch'], row['sha'], row['id']), 'Artifact provenance mismatch')
        verify_runtime_jar(folder / artifact['name'], row, module)
    return manifest


def cf_rows(manifest):
    return [row | {'distribution': 'cf'} for row in manifest['entries']
            if row['publish'] and row['branch'] != 'plugin']


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
    target = next(row['sha'] for row in manifest['entries'] if row['branch'] == '26.2')
    release.require(release.api(REPOSITORY, 'commits/' + tag)['sha'] == target, 'Release tag/source mismatch')
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('matrix=' + json.dumps({'include': cf_rows(manifest)}, separators=(',', ':')) + '\n')
    print('Verified 15 standard deployment JARs; CF builds use the same frozen source commits.')


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
    print('Verified platform bundle: 15 standard JARs and 12 CF client JARs.')


def verify_platform_bundle(folder, tag):
    manifest = verify_standard(folder / 'standard', tag)
    cf = release.load_json(folder / 'cf/cf-manifest.json')
    release.require(cf['tag'] == tag and len(cf['artifacts']) == 12, 'Incomplete CF manifest')
    expected = {release.filename(row, m): (row, m) for row in cf_rows(manifest) for m in row['modules']}
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
    def __init__(self, platform, status):
        self.platform, self.status = platform, status
        super().__init__(f'{platform} API returned HTTP {status}')


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
                # Never log request headers, response bodies, or authentication data.
                raise ApiError(self.platform, error.code) from None
            except (URLError, TimeoutError):
                if method == 'GET' and attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                raise RuntimeError(f'{self.platform} API network error; check remote state before retrying a write') from None


def clients():
    result = {name: Client(name, os.environ.get(secret, '')) for name, secret in (
        ('modrinth', 'MODRINTH_TOKEN'), ('curseforge', 'CURSEFORGE_TOKEN'), ('hangar', 'HANGAR_API_TOKEN'))}
    hangar = result['hangar']
    session = hangar.request('authenticate', method='POST',
                             data=urlencode({'apiKey': hangar.token}).encode(),
                             content_type='application/x-www-form-urlencoded', authenticated=False)
    token = session['token']
    release.require(isinstance(token, str) and '\n' not in token and '\r' not in token, 'Invalid Hangar session')
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print('::add-mask::' + token)
    hangar.token = 'HangarAuth ' + token
    return result


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


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['inspect', 'prepare', 'assemble'])
    parser.add_argument('--tag', default='v1.3.0-beta-3')
    parser.add_argument('--input', type=Path, default=Path('cf-builds'))
    parser.add_argument('--output', type=Path, default=Path('platform-report'))
    args = parser.parse_args()
    if args.command == 'inspect':
        inspect_projects(release.load_json('.github/platform-projects.json'), args.output)
    elif args.command == 'prepare':
        prepare(args.tag, args.output)
    else:
        assemble(args.output, args.input, args.tag)
