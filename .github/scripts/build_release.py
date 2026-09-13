#!/usr/bin/env python3
"""Freeze branch revisions, build a bounded matrix and publish a complete GitHub release."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import time
from urllib.parse import quote
import zipfile

BRANCHES = {'26.2', '26.1', '1.21.1', '1.21.6-1.21.8', '1.21.9-1.21.10', '1.21.11', 'plugin'}
MODULES = {'fabric', 'neoforge', 'paper', 'velocity', 'bungeecord'}
VERSION = re.compile(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?\Z')
SHA = re.compile(r'[0-9a-f]{40}\Z')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def api(repo, path):
    for attempt in range(4):
        try:
            return json.loads(run('gh', 'api', f'repos/{repo}/{path}'))
        except subprocess.CalledProcessError:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)



def properties(text):
    return {k.strip(): v.strip() for line in text.splitlines()
            if line.strip() and not line.lstrip().startswith('#') and '=' in line
            for k, v in [line.split('=', 1)]}


def load_json(path):
    return json.loads(Path(path).read_text())


def dump(path, value):
    Path(path).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate_row(row):
    require(row['branch'] in BRANCHES, 'Unsupported source branch')
    require(re.fullmatch(r'[0-9.]+|plugin', row['id']), 'Invalid build ID')
    require(set(row['modules']) <= MODULES and len(set(row['modules'])) == len(row['modules']), 'Invalid modules')
    expected = {'paper', 'velocity', 'bungeecord'} if row['branch'] == 'plugin' else {'fabric', 'neoforge'}
    require(set(row['modules']) == expected, 'Incomplete loader/plugin set')
    require(isinstance(row['publish'], bool), 'publish must be boolean')
    allowed = {'minecraft_version', 'fabric_api_version', 'neoforge_version', 'fancymodloader_version',
               'modernui_mc_version', 'modernui_fabric_version', 'modernui_neoforge_version'}
    require(set(row['properties']) <= allowed, 'Unrecognized build property')
    for value in row['properties'].values():
        require(isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9.+_-]+', value), 'Invalid property value')
    if 'sha' in row:
        require(SHA.fullmatch(row['sha']) and VERSION.fullmatch(row['version']), 'Invalid revision/version')
        require(re.fullmatch(r'[0-9.-]*', row['range']), 'Invalid Minecraft version range')


def validate_matrix(rows):
    require(len(rows) == 12, 'Expected eleven client endpoints and one plugin build')
    require(len({row['id'] for row in rows}) == len(rows), 'Duplicate build ID')
    require({row['branch'] for row in rows} == BRANCHES, 'Incomplete branch matrix')
    for row in rows:
        validate_row(row)
    published = [row for row in rows if row['publish']]
    require(len(published) == 7 and {row['branch'] for row in published} == BRANCHES,
            'Each branch needs exactly one distribution build')


def release_requested(event, ref, requested):
    if event == 'push' and ref.startswith('refs/tags/v'):
        return True
    if requested:
        require(event == 'workflow_dispatch' and ref == 'refs/heads/26.2', 'Manual publishing requires the default branch')
        return True
    return False


def plan():
    repo = os.environ['GITHUB_REPOSITORY']
    event, ref = os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_REF']
    publish = release_requested(event, ref, os.environ.get('PUBLISH_REQUESTED') == 'true')
    rows = load_json('.github/build-matrix.json')
    validate_matrix(rows)
    source_branch = os.environ.get('GITHUB_BASE_REF') if event == 'pull_request' else ref.removeprefix('refs/heads/')
    if ref.startswith('refs/tags/'):
        source_branch = '26.2'
    revisions = {}
    for branch in sorted(BRANCHES):
        sha = (os.environ['GITHUB_SHA'] if branch == source_branch else
               api(repo, f'git/ref/heads/{quote(branch, safe="")}')['object']['sha'])
        require(SHA.fullmatch(sha), 'Invalid source commit')
        encoded = api(repo, f'contents/gradle.properties?ref={sha}')['content']
        props = properties(base64.b64decode(encoded).decode())
        revisions[branch] = {'sha': sha, 'version': props['mod_version'],
                             'range': '' if branch == 'plugin' else props['minecraft_version_range']}
    for row in rows:
        row.update(revisions[row['branch']])
    validate_matrix(rows)
    version = revisions['26.2']['version']
    tag = 'v' + version
    if publish:
        require(all(row['version'] == version for row in rows), 'Release versions must match on all seven branches')
        require(not ref.startswith('refs/tags/') or ref == 'refs/tags/' + tag, 'Tag does not match mod_version')
    controller = run('git', 'rev-parse', 'HEAD')
    manifest = {'repository': repo, 'controller': controller, 'tag': tag, 'publish': publish,
                'run_id': os.environ['GITHUB_RUN_ID'], 'entries': rows}
    dump('build-plan.json', manifest)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as out:
        out.write('matrix=' + json.dumps({'include': rows}, separators=(',', ':')) + '\n')
        out.write(f'controller={controller}\npublish={str(publish).lower()}\ntag={tag}\n')


def filename(row, module):
    validate_row(row)
    suffix = '+' + row['range'] if row['range'] else ''
    return f'musichud-tuneweave-{module}-{row["version"]}{suffix}.jar'


def verify_jar(path, row, module):
    expected_version = row['version'] + ('+' + row['range'] if row['range'] else '')
    with zipfile.ZipFile(path) as jar:
        require(jar.testzip() is None, 'Corrupt JAR')
        names = jar.namelist()
        require(any(n.startswith('indi/mopelotus/musichud/') and n.endswith('.class') for n in names), 'Missing project classes')
        require(not any(n.startswith(('.codex-local/', 'docs/verification/')) or n.endswith('AGENTS.md') for n in names), 'Internal files in JAR')
        if module == 'fabric':
            meta = json.loads(jar.read('fabric.mod.json'))
            require(meta['id'] == 'musichud_tuneweave' and meta['version'] == expected_version, 'Fabric metadata mismatch')
        elif module == 'neoforge':
            meta = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
            require(any(m['modId'] == 'musichud_tuneweave' and m['version'] == expected_version for m in meta['mods']), 'NeoForge metadata mismatch')
        elif module == 'velocity':
            meta = json.loads(jar.read('velocity-plugin.json'))
            require(meta['id'] == 'musichud_tuneweave' and meta['version'] == expected_version, 'Velocity metadata mismatch')
        else:
            text = jar.read('plugin.yml' if module == 'paper' else 'bungee.yml').decode()
            match = re.search(r'^version:\s*[\'\"]?([^\'\"\r\n]+)', text, re.M)
            require(match and match[1].strip() == expected_version, 'Plugin metadata mismatch')


def build(source, output):
    row = json.loads(os.environ['BUILD_ENTRY'])
    validate_row(row)
    require(run('git', 'rev-parse', 'HEAD', cwd=source) == row['sha'], 'Checkout differs from frozen plan')
    require(properties((source / 'gradle.properties').read_text())['mod_version'] == row['version'], 'Version changed after planning')
    tasks = ['core:test'] if row['branch'] == 'plugin' else ['common:test']
    tasks += [module + ':build' for module in row['modules']]
    command = ['./gradlew', *tasks, '--rerun-tasks', '--no-daemon', '--no-parallel', '--max-workers=2',
               '-Dorg.gradle.jvmargs=-Xmx4G', '--console=plain']
    command += [f'-P{k}={v}' for k, v in sorted(row['properties'].items())]
    subprocess.run(command, cwd=source, check=True)
    output.mkdir(parents=True, exist_ok=False)
    artifacts = []
    for module in row['modules']:
        name = filename(row, module)
        source_jar = source / module / 'build/libs' / name
        verify_jar(source_jar, row, module)
        shutil.copy2(source_jar, output / name)
        artifacts.append({'name': name, 'sha256': digest(output / name), 'module': module})
    dump(output / 'build.json', {'entry': row, 'artifacts': artifacts})


def bundle(downloads, output):
    manifest = load_json(downloads / 'build-plan/build-plan.json')
    rows = manifest['entries']
    validate_matrix(rows)
    verified = []
    for row in rows:
        folder = downloads / ('build-' + row['id'])
        actual = load_json(folder / 'build.json')
        require(actual['entry'] == row, 'Artifact provenance differs from frozen plan')
        expected = {filename(row, module): module for module in row['modules']}
        require({a['name'] for a in actual['artifacts']} == set(expected)
                and len(actual['artifacts']) == len(expected), 'Incomplete or duplicate artifacts')
        require({p.name for p in folder.iterdir()} == set(expected) | {'build.json'}, 'Unexpected build artifact')
        for artifact in actual['artifacts']:
            name = artifact['name']
            require(artifact['module'] == expected[name], 'Artifact loader mismatch')
            require(digest(folder / name) == artifact['sha256'], 'Artifact checksum mismatch')
            verify_jar(folder / name, row, expected[name])
            if row['publish']:
                verified.append((folder / name, artifact | {'branch': row['branch'], 'sha': row['sha'], 'endpoint': row['id']}))
    require(len(verified) == 15 and len({a['name'] for _, a in verified}) == 15, 'Expected exactly fifteen unique distribution JARs')
    output.mkdir(parents=True, exist_ok=False)
    for path, artifact in verified:
        shutil.copy2(path, output / artifact['name'])
    manifest['artifacts'] = [artifact for _, artifact in verified]
    dump(output / 'build-manifest.json', manifest)
    notes = ['Fabric and NeoForge client builds for every supported Minecraft version group, plus Paper, Velocity and BungeeCord plugins.',
             '', 'Choose the matching Minecraft version and loader. Install the ModernUI dependency described in the README.',
             'Proxy networks install the plugin only on the proxy; standalone servers use Paper.',
             '', 'All branches were built from the commit IDs in build-manifest.json. SHA256SUMS covers every attached distribution file.',
             'Modrinth and CurseForge publishing is currently disabled.', '', '| Branch | Commit |', '| --- | --- |']
    notes += [f'| {b} | `{next(r["sha"] for r in rows if r["branch"] == b)}` |' for b in sorted(BRANCHES)]
    (output / 'RELEASE_NOTES.md').write_text('\n'.join(notes) + '\n')
    for name in ('license', 'LICENSE', 'COPYING', 'COPYING.LESSER'):
        if Path(name).is_file():
            shutil.copy2(name, output / name)
    sums = ''.join(f'{digest(p)}  {p.name}\n' for p in sorted(output.iterdir()))
    (output / 'SHA256SUMS').write_text(sums)


def verify_bundle(folder):
    lines = (folder / 'SHA256SUMS').read_text().splitlines()
    listed = set()
    for line in lines:
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9_.+-]+)', line)
        require(match and match[2] not in {'.', '..', 'SHA256SUMS'}, 'Invalid checksum entry')
        sha, name = match.groups()
        require(name not in listed, 'Duplicate checksum entry')
        listed.add(name)
        require((folder / name).is_file() and digest(folder / name) == sha, 'Release checksum mismatch')
    require(listed == {p.name for p in folder.iterdir()} - {'SHA256SUMS'}, 'Unlisted release file')
    manifest = load_json(folder / 'build-manifest.json')
    validate_matrix(manifest['entries'])
    require(len(manifest['artifacts']) == 15, 'Incomplete release')
    require({a['name'] for a in manifest['artifacts']} == {n for n in listed if n.endswith('.jar')}, 'Release manifest differs from files')
    for a in manifest['artifacts']:
        require(digest(folder / a['name']) == a['sha256'], 'Manifest checksum mismatch')
    return manifest


def publish(folder):
    manifest = verify_bundle(folder)
    repo, tag = os.environ['GITHUB_REPOSITORY'], os.environ['RELEASE_TAG']
    require(manifest['repository'] == repo and manifest['publish'] is True and tag == manifest['tag'], 'Release authorization mismatch')
    require(tag.startswith('v') and VERSION.fullmatch(tag[1:]), 'Invalid release tag')
    require(all(row['version'] == tag[1:] for row in manifest['entries']), 'Mixed release versions')
    target = next(row['sha'] for row in manifest['entries'] if row['branch'] == '26.2')
    require(str(manifest['run_id']) == os.environ['GITHUB_RUN_ID'], 'Release belongs to another workflow run')
    matching_tags = api(repo, 'git/matching-refs/tags/' + quote(tag, safe=''))
    if any(item['ref'] == 'refs/tags/' + tag for item in matching_tags):
        require(api(repo, 'commits/' + quote(tag, safe=''))['sha'] == target, 'Existing tag points to a different commit')
    else:
        # Draft releases do not guarantee that a missing tag is materialized yet.
        # Create the exact frozen tag first; a racing existing ref makes this fail closed.
        subprocess.run(['gh', 'api', '--method', 'POST', f'repos/{repo}/git/refs',
                        '-f', 'ref=refs/tags/' + tag, '-f', 'sha=' + target], check=True)
    # gh refuses existing releases; never overwrite an earlier release or its assets.
    command = ['gh', 'release', 'create', tag, '--repo', repo, '--target', target, '--draft',
               '--verify-tag', '--title', f'MusicHud TuneWeave {tag[1:]}', '--notes-file', str(folder / 'RELEASE_NOTES.md')]
    prerelease = '-' in tag
    if prerelease:
        command += ['--prerelease']
    subprocess.run(command, check=True)
    # An existing tag must resolve to the same default-branch source commit.
    require(api(repo, 'commits/' + quote(tag, safe=''))['sha'] == target, 'Existing tag points to a different commit')
    subprocess.run(['gh', 'release', 'upload', tag, '--repo', repo,
                    *[str(p) for p in sorted(folder.iterdir())]], check=True)
    subprocess.run(['gh', 'release', 'edit', tag, '--repo', repo, '--draft=false',
                    '--latest=false' if prerelease else '--latest'], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['plan', 'build', 'bundle', 'publish'])
    parser.add_argument('--source', type=Path)
    parser.add_argument('--input', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.command == 'plan': plan()
    elif args.command == 'build': build(args.source, args.output)
    elif args.command == 'bundle': bundle(args.input, args.output)
    else: publish(args.input)
