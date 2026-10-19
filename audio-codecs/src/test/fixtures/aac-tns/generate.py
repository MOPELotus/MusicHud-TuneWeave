"""Regenerate original TNS vectors (CC0-1.0); requires NumPy and FFmpeg/ffprobe.
Reference tool: FFmpeg 6.1.1-3ubuntu5+esm15. Tests never invoke these tools.
Run in an empty output directory; no external recordings are used.
"""
from pathlib import Path
import json
import struct
import subprocess
import wave
import numpy as np

p = Path.cwd()
rate = 44100
t = np.arange(int(rate * .8)) / rate
x = np.zeros(len(t))
for start in [.05, .19, .31, .49, .63]:
    dt = t - start
    env = np.where(dt >= 0, np.exp(-np.maximum(dt, 0) * 45), 0)
    x += (np.sin(2*np.pi*(350*dt + 12000*dt*dt)) + .7*np.sin(2*np.pi*4000*dt)) * env
x += .05*np.sin(2*np.pi*997*t) + .02*np.sin(2*np.pi*3791*t)
x = x / max(1., np.max(np.abs(x))) * .85
wav = p / 'chirp-44100.wav'
with wave.open(str(wav), 'wb') as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(rate)
    out.writeframes(np.rint(x * 32767).astype('<i2').tobytes())
for enabled in [0, 1]:
    name = f'chirp-44100-tns{enabled}'
    m4a = p / (name + '.m4a')
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(wav), '-c:a', 'aac',
                    '-profile:a', 'aac_low', '-b:a', '64k', '-aac_tns', str(enabled),
                    '-aac_pns', '0', str(m4a)], check=True)
    info = json.loads(subprocess.check_output(['ffprobe', '-v', 'error',
        '-select_streams', 'a:0', '-show_streams', '-show_data', '-show_packets',
        '-show_entries', 'packet=pos,size:stream=extradata', '-of', 'json', str(m4a)]))
    asc = bytes.fromhex(info['streams'][0]['extradata'].split(':', 1)[1].split('  ')[0].strip().replace(' ', ''))
    source = m4a.read_bytes()
    packets = [source[int(row['pos']):int(row['pos']) + int(row['size'])] for row in info['packets']]
    with open(p / (name + '.packets'), 'wb') as out:
        out.write(struct.pack('>I', len(asc)) + asc + struct.pack('>I', len(packets)))
        for packet in packets:
            out.write(struct.pack('>I', len(packet)) + packet)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-ignore_editlist', '1', '-i', str(m4a),
                    '-f', 's16le', '-acodec', 'pcm_s16le', str(p / (name + '.reference.s16le'))], check=True)
