"""Generate updater catalogs from verified runtime JARs; publish CF assets without replacing releases."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
import build_release as release

REPOSITORY = 'MOPELotus/MusicHud-TuneWeave'
METADATA = 'META-INF/musichud-update.properties'


def catalog(folder, tag, distribution):
    release.require(re.fullmatch(r'v\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', tag), 'Invalid catalog tag')
    release.require(distribution in ('standard', 'cf'), 'Invalid catalog edition')
    artifacts = []
    matches = set()
    for path in sorted(folder.glob('*.jar')):
        with zipfile.ZipFile(path) as archive:
            if METADATA not in archive.namelist():
                continue  # Historical branches do not have the client updater yet.
            release.require(archive.getinfo(METADATA).file_size <= 4096, 'Oversized identity')
            data = release.properties(archive.read(METADATA).decode('utf-8'))
        loader, version = data.get('loader'), data.get('version', '')
        release.require(data.get('id') == 'musichud_tuneweave' and data.get('distribution') == distribution
                        and loader in ('fabric', 'neoforge'), 'Updater identity mismatch')
        base = version.split('+')[0]
        if distribution == 'cf':
            release.require(re.search(r'-cf\.[1-9][0-9]*$', base), 'Missing CF revision')
            base = re.sub(r'-cf\.[1-9][0-9]*$', '', base)
        release.require('v' + base == tag and path.name == f'musichud-tuneweave-{loader}-{version}.jar', 'Updater version mismatch')
        games = data.get('minecraft', '').split(',')
        release.require(0 < len(games) <= 32 and len(set(games)) == len(games)
                        and all(re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', game) for game in games), 'Invalid game versions')
        for game in games:
            key = (loader, game)
            release.require(key not in matches, 'Ambiguous update target')
            matches.add(key)
        artifacts.append(dict(version=version, distribution=distribution, loader=loader, minecraft=games,
                              file=path.name, sha256=release.digest(path), size=path.stat().st_size))
    return dict(schema=1, repository=REPOSITORY, tag=tag, distribution=distribution, artifacts=artifacts)


def mirror_cf(folder, tag, output):
    import os
    import platform_release as platform
    release.require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
                    and os.environ.get('GITHUB_REF') == 'refs/heads/' + release.DEFAULT_BRANCH
                    and os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'CF mirroring requires default-branch publish dispatch')
    manifest, _ = platform.verify_platform_bundle(folder, tag)
    info = release.api(REPOSITORY, 'releases/tags/' + tag)
    release.require(not info['draft'] and info['tag_name'] == tag, 'Release not published')
    release.require(release.api(REPOSITORY, 'commits/' + tag)['sha'] == release.release_target(manifest), 'Release source mismatch')
    data = catalog(folder / 'cf', tag, 'cf')
    if not data['artifacts']:
        print('Historical CF bundle has no updater-enabled clients; nothing to mirror.')
        return
    output.mkdir(parents=True, exist_ok=True)
    metadata = output / 'client-updates-cf.json'
    release.dump(metadata, data)
    paths = [folder / 'cf' / a['file'] for a in data['artifacts']] + [metadata]
    existing = {}
    for page in range(1, 11):
        assets = release.api(REPOSITORY, f"releases/{info['id']}/assets?per_page=100&page={page}")
        for asset in assets:
            release.require(asset['name'] not in existing, 'Duplicate release asset')
            existing[asset['name']] = asset
        if len(assets) < 100:
            break
    else:
        raise ValueError('Too many release assets')
    # Verify every collision before any upload. Catalog is uploaded last, after all JARs exist.
    with tempfile.TemporaryDirectory() as directory:
        for path in paths:
            if path.name not in existing:
                continue
            subprocess.run(['gh', 'release', 'download', tag, '--repo', REPOSITORY, '--dir', directory,
                            '--pattern', path.name], check=True)
            release.require(release.digest(Path(directory) / path.name) == release.digest(path), 'Existing update asset differs; refusing overwrite')
    for path in paths:
        if path.name not in existing:
            subprocess.run(['gh', 'release', 'upload', tag, str(path), '--repo', REPOSITORY], check=True)
    print('Verified CF update assets and published the matching catalog.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    mirror_cf(args.input, args.tag, args.output)
