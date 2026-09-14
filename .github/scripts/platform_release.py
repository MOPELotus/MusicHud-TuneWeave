#!/usr/bin/env python3
"""Inspect platform projects and publish verified deployment artifacts."""
import argparse
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, HTTPRedirectHandler, build_opener

import build_release as release

BASES = {
    'modrinth': 'https://api.modrinth.com/v2/',
    'curseforge': 'https://minecraft.curseforge.com/api/',
    'hangar': 'https://hangar.papermc.io/api/v1/',
}


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
    parser.add_argument('command', choices=['inspect'])
    parser.add_argument('--output', type=Path, default=Path('platform-report'))
    args = parser.parse_args()
    inspect_projects(release.load_json('.github/platform-projects.json'), args.output)
