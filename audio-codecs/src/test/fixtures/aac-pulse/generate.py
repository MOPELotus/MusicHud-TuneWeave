"""Original CC0 AAC-LC pulse-tool regression vectors; no copied audio/bitstreams.
Run from an empty output directory. FFmpeg6.1.1 is only the generation-time oracle.
"""
from pathlib import Path
import struct, json, subprocess, hashlib
p = Path.cwd()
class Bits:
    def __init__(self): self.value = ''
    def put(self, value, width): self.value += format(value, f'0{width}b')
    def data(self): return int(self.value + '0'*((-len(self.value)) % 8), 2).to_bytes((len(self.value)+7)//8, 'big')
def frame(initial, pulses):
    maximum = abs(initial) == 8191
    b = Bits()
    # SCE tag0, long sine window, max_sfb1, no prediction; one coded band.
    for v,n in [(0,3),(0,4),(120 if maximum else 160,8),(0,1),(0,2),(0,1),(1,6),(0,1),(11 if maximum else 1,4),(1,5),(0,1),(bool(pulses),1)]: b.put(int(v),n)
    if pulses:
        b.put(len(pulses)-1,2); b.put(0,6)
        for offset, amplitude in pulses: b.put(offset,5); b.put(amplitude,4)
    b.put(0,1); b.put(0,1) # no TNS/gain control
    if maximum:
        b.put(450,9); b.put(initial < 0,1) # HCB11(16,0), sign
        for _ in range(8): b.put(1,1)
        b.put(0,1); b.put(4095,12); b.put(0,4) # escape8191, zero pair
    else:
        b.put(0 if initial == 0 else 16 if initial > 0 else 17, 1 if initial == 0 else 5)
    b.put(7,3)
    return b.data()
def adts(raw):
    b = Bits()
    for v,n in [(0xfff,12),(0,1),(0,2),(1,1),(1,2),(4,4),(0,1),(1,3),(0,1),(0,1),(0,1),(0,1),(len(raw)+7,13),(0x7ff,11),(0,2)]: b.put(v,n)
    return b.data() + raw
cases = [('zero-positive',0,[(0,15)]),('positive-positive',1,[(0,15)]),
         ('negative-negative',-1,[(0,15)]),('repeat-same-bin',0,[(1,3),(0,5)]),
         ('cumulative-offsets',0,[(0,2),(1,4),(2,6)]),('zero-amplitude',1,[(0,0)]),
         ('max-positive',8191,[(0,15)]*4),('max-negative',-8191,[(0,15)]*4),
         ('then-absent',0,[(0,15)])]
rows = []
for name, initial, pulses in cases:
    raw = frame(initial,pulses)
    frames = [raw]*8 if name != 'then-absent' else [raw]*3+[frame(0,[])]*5
    base = 'pulse-' + name
    (p/(base+'.aac')).write_bytes(b''.join(adts(f) for f in frames))
    with open(p/(base+'.packets'),'wb') as out:
        out.write(struct.pack('>I',2)+bytes.fromhex('1208')+struct.pack('>I',len(frames)))
        for raw in frames: out.write(struct.pack('>I',len(raw))+raw)
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(p/(base+'.aac')),'-f','s16le','-acodec','pcm_s16le',str(p/(base+'.reference.s16le'))],check=True)
    rows.append({'id':base,'quantized_initial':initial,'pulses_offset_amplitude':pulses,
        'raw_au_hex': [f.hex() for f in frames],
        'packets_sha256':hashlib.sha256((p/(base+'.packets')).read_bytes()).hexdigest(),
        'reference_sha256':hashlib.sha256((p/(base+'.reference.s16le')).read_bytes()).hexdigest()})
(p/'manifest.json').write_text(json.dumps({'license':'CC0-1.0','reference':'FFmpeg6.1.1-3ubuntu5+esm15, mono PCM16, all8 raw AUs including overlap/tail; <=1LSB expected','cases':rows},indent=2)+'\n')
