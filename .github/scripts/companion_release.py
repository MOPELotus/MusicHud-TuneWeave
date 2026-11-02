"""Bounded identity and provenance checks for the separate Companion distribution."""
import json
from pathlib import PurePosixPath
import re
import stat
import zipfile
import build_release as release

REPOSITORY = 'MOPELotus/MusicHud-TuneWeave'
PLATFORM = 'windows-x64'
ENTRY = 'Companion/Companion.exe'
MAX_ARCHIVE = 512 * 1024 * 1024


def allowed(manifest):
    return (manifest.get('distribution', 'standard') == 'standard'
            and all(row.get('distribution', 'standard') == 'standard' for row in manifest.get('entries', []))
            and manifest.get('release_scope', 'full') == 'full')


def validate_source(source):
    release.require(isinstance(source, dict) and source.get('branch') == 'companion'
                    and isinstance(source.get('sha'), str) and release.SHA.fullmatch(source['sha'])
                    and isinstance(source.get('version'), str) and release.VERSION.fullmatch(source['version']),
                    'Invalid frozen Companion source')


def verify(folder, source):
    validate_source(source)
    metadata = release.load_json(folder / 'companion-manifest.json')
    name = f'Companion-{source["version"]}-windows-x64.zip'
    archive = folder / name
    release.require(metadata.get('schemaVersion') == 1 and type(metadata.get('schemaVersion')) is int
                    and metadata.get('platform') == PLATFORM and metadata.get('version') == source['version']
                    and metadata.get('archive') == name and metadata.get('entryPoint') == ENTRY
                    and type(metadata.get('protocolVersion')) is int and metadata['protocolVersion'] == 1
                    and metadata.get('sourceCommit') == source['sha'], 'Companion identity differs from frozen source')
    release.require(archive.is_file() and type(metadata.get('size')) is int
                    and 0 < metadata['size'] <= MAX_ARCHIVE and metadata['size'] == archive.stat().st_size
                    and isinstance(metadata.get('sha256'), str)
                    and re.fullmatch(r'[0-9a-f]{64}', metadata['sha256'])
                    and release.digest(archive) == metadata['sha256'], 'Companion size or checksum mismatch')
    with zipfile.ZipFile(archive) as bundle:
        seen = set()
        expanded = 0
        for item in bundle.infolist():
            path = PurePosixPath(item.filename)
            release.require(item.filename and '\\' not in item.filename and ':' not in item.filename
                            and not path.is_absolute() and '..' not in path.parts
                            and path.parts[0] == 'Companion' and item.filename.casefold() not in seen
                            and not stat.S_ISLNK(item.external_attr >> 16)
                            and not item.flag_bits & 1, 'Unsafe Companion ZIP entry')
            seen.add(item.filename.casefold())
            expanded += item.file_size
            release.require(expanded <= 1536 * 1024 * 1024 and len(seen) <= 20000,
                            'Oversized Companion ZIP')
        names = set(bundle.namelist())
        release.require(ENTRY in names and any(n.startswith('Companion/app/') for n in names)
                        and any(n.startswith('Companion/runtime/') for n in names)
                        and any(n.startswith('Companion/licenses/') for n in names),
                        'Incomplete Companion app image')
        identity_info = bundle.getinfo('Companion/companion.json')
        release.require(identity_info.file_size <= 4096, 'Oversized Companion identity')
        identity = json.loads(bundle.read(identity_info))
        release.require(identity == dict(schemaVersion=1, version=source['version'], protocolVersion=1,
                                         sourceCommit=source['sha']), 'Companion embedded identity mismatch')
        release.require(bundle.open(ENTRY).read(2) == b'MZ', 'Companion launcher is not a Windows executable')
    return metadata


def catalog(metadata, tag):
    release.require(isinstance(tag, str) and tag.startswith('v') and release.VERSION.fullmatch(tag[1:]),
                    'Invalid Companion release tag')
    return dict(schemaVersion=1, version=metadata['version'], protocolVersion=metadata['protocolVersion'],
                sourceCommit=metadata['sourceCommit'], platforms={PLATFORM: dict(
                    url=f'https://github.com/{REPOSITORY}/releases/download/{tag}/{metadata["archive"]}',
                    sha256=metadata['sha256'], size=metadata['size'], entryPoint=ENTRY)})


def verify_release(folder, manifest):
    source = manifest.get('companion')
    names = {p.name for p in folder.iterdir()}
    has_files = any(n.startswith('Companion-') or n in ('companion-manifest.json', 'companion-latest.json') for n in names)
    if source is None:
        release.require(not has_files, 'Unplanned Companion release files')
        return
    release.require(allowed(manifest), 'Companion management artifacts forbidden in CF or scoped release')
    metadata = verify(folder, source)
    release.require(release.load_json(folder / 'companion-latest.json') == catalog(metadata, manifest['tag']),
                    'Companion download catalog mismatch')
