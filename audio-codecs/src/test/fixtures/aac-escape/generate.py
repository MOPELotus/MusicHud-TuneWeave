"""Original minimal AAC-LC escape boundary vectors, CC0-1.0.
No recordings or copied bitstreams. HCB11 pair (16,0) followed by (0,0).
"""
from pathlib import Path
import struct,subprocess,json,hashlib
p=Path.cwd()
class Bits:
 def __init__(self):self.bits=''
 def put(self,value,width):self.bits+=format(value,f'0{width}b')
 def data(self):return int(self.bits+'0'*((-len(self.bits))%8),2).to_bytes((len(self.bits)+7)//8,'big')
def frame(value,negative):
 b=Bits()
 for v,n in [(0,3),(0,4),(120,8),(0,1),(0,2),(0,1),(1,6),(0,1),(11,4),(1,5),(0,1),(0,1),(0,1),(0,1)]:b.put(v,n)
 b.put(450,9);b.put(negative,1) # HCB11(16,0), sign
 width=value.bit_length()-1
 for _ in range(width-4):b.put(1,1)
 b.put(0,1);b.put(value-(1<<width),width)
 b.put(0,4);b.put(7,3) # HCB11(0,0), END
 return b.data()
def adts(raw):
 b=Bits()
 for v,n in [(0xfff,12),(0,1),(0,2),(1,1),(1,2),(4,4),(0,1),(1,3),(0,1),(0,1),(0,1),(0,1),(len(raw)+7,13),(0x7ff,11),(0,2)]:b.put(v,n)
 return b.data()+raw
rows=[]
for value in [8190,8191]:
 for sign in [0,1]:
  name=f'escape-{value}-'+('negative' if sign else 'positive');raw=frame(value,sign);asc=bytes.fromhex('1208')
  (p/(name+'.aac')).write_bytes(adts(raw)*8)
  with open(p/(name+'.packets'),'wb') as f:
   f.write(struct.pack('>I',2)+asc+struct.pack('>I',8))
   for _ in range(8):f.write(struct.pack('>I',len(raw))+raw)
  with open(p/(name+'.rawpackets'),'wb') as f:
   for _ in range(8):f.write(struct.pack('>I',len(raw))+raw)
  result=subprocess.run(['ffmpeg','-v','error','-y','-i',str(p/(name+'.aac')),'-f','s16le','-acodec','pcm_s16le',str(p/(name+'.reference.s16le'))],capture_output=True,text=True);assert result.returncode==0 and not result.stderr,result.stderr
  rows.append({'id':name,'asc':'1208','raw_au':raw.hex(),'packets_sha256':hashlib.sha256((p/(name+'.packets')).read_bytes()).hexdigest(),'reference_sha256':hashlib.sha256((p/(name+'.reference.s16le')).read_bytes()).hexdigest()})
(p/'manifest.json').write_text(json.dumps(rows,indent=2)+'\n')
