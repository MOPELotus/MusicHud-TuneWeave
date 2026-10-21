#!/usr/bin/env python3
# SPDX-License-Identifier: CC0-1.0
# Original synthetic transient signal; the encoder is a generation-only tool.
import argparse, gzip, hashlib, json, math, struct, subprocess, tempfile
from pathlib import Path
ap = argparse.ArgumentParser()
ap.add_argument('--encoder', required=True, help='FDK encode_fixture CLI (AOT, period, signaling, bitrate)')
a = ap.parse_args()
out = Path(__file__).parent
with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    source = bytearray(); seed = 1729
    for i in range(48000 * 3):
        t = i / 48000
        seed = (1664525 * seed + 1013904223) & 0xffffffff
        noise = (seed >> 8) / 16777216 - .5
        envelope = math.exp(-((t % .173) / .173) * 11)
        left = .16 * math.sin(2 * math.pi * (211*t + 160*t*t)) + .20 * noise * envelope
        right = .16 * math.sin(2 * math.pi * (397*t + 90*t*t)) - .18 * noise * envelope
        source += struct.pack('<hh', round(left * 32767), round(right * 32767))
    raw = tmp / 'source.s16le'; raw.write_bytes(source)
    bundle = tmp / 'all.packets'
    info = json.loads(subprocess.check_output([a.encoder, str(raw), str(bundle), '29', '10', '1', '32000']))
    full = bundle.read_bytes(); pos = 0
    def u32():
        global pos
        n = struct.unpack_from('>I', full, pos)[0]; pos += 4
        return n
    asc = full[4:4+struct.unpack_from('>I', full)[0]]
    pos = 4 + len(asc); count = u32(); packets = []
    for i in range(count):
        n = u32(); packets.append(full[pos:pos+n]); pos += n
    assert pos == len(full)
    # Retain the first inherited-short-envelope regression, with preceding history.
    packets = packets[:54]
    packed = struct.pack('>I', len(asc)) + asc + struct.pack('>I', len(packets))
    adts = bytearray(); bits = int.from_bytes(asc[:2], 'big')
    aot, sf, channels = (bits >> 11) & 31, (bits >> 7) & 15, (bits >> 3) & 15
    assert aot == 2  # Backwards-compatible LC core with in-band SBR/PS signaling.
    for packet in packets:
        packed += struct.pack('>I', len(packet)) + packet
        n = len(packet) + 7
        adts += bytes([255, 241, ((aot-1)<<6)|(sf<<2)|(channels>>2),
                       ((channels&3)<<6)|(n>>11), (n>>3)&255, ((n&7)<<5)|31, 252]) + packet
    adts_path = tmp / 'reference.aac'; adts_path.write_bytes(adts)
    reference = subprocess.check_output(['ffmpeg', '-v', 'error', '-i', str(adts_path), '-f', 's16le', '-'])
    (out / 'transient.packets').write_bytes(packed)
    (out / 'transient.reference.s16le.gz').write_bytes(gzip.compress(reference, mtime=0))
    digest = lambda b: hashlib.sha256(b).hexdigest()
    manifest = dict(license='CC0-1.0', source_sha256=digest(source), source_rate=48000,
        source_channels=2, source_frames=144000, seed=1729, asc_hex=asc.hex(),
        access_units=len(packets), reference_frames=len(reference)//4,
        reference_encoding='PCM_S16_LE', reference_channels=2, reference_rate=48000,
        packet_sha256=digest(packed), reference_pcm_sha256=digest(reference),
        reference_gzip_sha256=digest((out/'transient.reference.s16le.gz').read_bytes()),
        encoder_sha256=digest(Path(a.encoder).read_bytes()), encoder_info=info,
        ffmpeg_version=subprocess.check_output(['ffmpeg','-version'],text=True).splitlines()[0])
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
