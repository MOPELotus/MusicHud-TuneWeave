#!/usr/bin/env python3
"""CC0-1.0 original synthetic Main-profile vectors; FFmpeg is generation-only."""
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent

def run(args):
    return subprocess.check_output(args)

def sha(data):
    return hashlib.sha256(data).hexdigest()

rows = []
with tempfile.TemporaryDirectory() as temp:
    temp = Path(temp)
    for name, rate, channels, seconds in [('stereo', 48000, 2, 1.4), ('mono', 22050, 1, 1.1)]:
        raw = bytearray()
        for frame in range(int(rate * seconds)):
            t = frame / rate
            fade = min(1, t / .01) * min(1, (seconds - t) / .01)
            for channel in range(channels):
                value = (.18 * math.sin(2 * math.pi * (211*t + 230*t*t))
                         + .03 * math.sin(2 * math.pi * 1901*t)) if channel == 0 else (
                             .17 * math.sin(2 * math.pi * (397*t + 91*t*t))
                             + .02 * math.cos(2 * math.pi * 2711*t))
                raw.extend(struct.pack('<f', fade * value))
        input_path = temp / 'source.f32'
        input_path.write_bytes(raw)
        encoded = temp / (name + '.m4a')
        encode = ['ffmpeg', '-v', 'error', '-y', '-f', 'f32le', '-ar', str(rate),
                  '-ac', str(channels), '-i', str(input_path), '-c:a', 'aac',
                  '-profile:a', 'aac_main', '-b:a', '128k', '-aac_pns', '0',
                  '-aac_tns', '0', str(encoded)]
        run(encode)
        stream = json.loads(run(['ffprobe', '-v', 'error', '-show_streams',
                                 '-show_data', '-of', 'json', str(encoded)]))['streams'][0]
        config = bytes.fromhex(''.join(line.split(':', 1)[1].split('  ')[0].strip()
                                      for line in stream['extradata'].splitlines() if ':' in line))
        packets = json.loads(run(['ffprobe', '-v', 'error', '-show_packets',
                                  '-of', 'json', str(encoded)]))['packets']
        data = encoded.read_bytes()
        packed = bytearray(struct.pack('>I', len(config)) + config + struct.pack('>I', len(packets)))
        for packet in packets:
            pos, size = int(packet['pos']), int(packet['size'])
            packed.extend(struct.pack('>I', size))
            packed.extend(data[pos:pos+size])
        oracle = ['ffmpeg', '-v', 'error', '-ignore_editlist', '1', '-i', str(encoded),
                  '-f', 's16le', '-c:a', 'pcm_s16le', '-']
        reference = run(oracle)
        (ROOT / (name + '.packets')).write_bytes(packed)
        (ROOT / (name + '.reference.s16le')).write_bytes(reference)
        rows.append(dict(name=name, rate=rate, channels=channels, asc=config.hex(),
                         access_units=len(packets), reference_frames=len(reference)//(channels*2),
                         packets_sha256=sha(packed), reference_sha256=sha(reference),
                         source_sha256=sha(raw),
                         encode_argv=[arg.replace(str(temp), '<temporary>') for arg in encode],
                         oracle_argv=[arg.replace(str(temp), '<temporary>') for arg in oracle]))
(ROOT / 'manifest.json').write_text(json.dumps(dict(
    license='CC0-1.0 original waveform, generator, encoded inputs and reference PCM',
    format='BE32 ASC length, ASC, BE32 AU count, repeated BE32 AU length and AU',
    reference='FFmpeg full raw AUs including priming/tail; no edit-list trimming',
    ffmpeg=run(['ffmpeg', '-version']).decode().splitlines()[0], vectors=rows), indent=2)+'\n')
