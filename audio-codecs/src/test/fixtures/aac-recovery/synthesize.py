# SPDX-License-Identifier: CC0-1.0
# All samples are generated here, with no external media.
import math,random,struct,wave
from pathlib import Path
root=Path(__file__).resolve().parent
rng=random.Random(20261018);pcm=bytearray();rate=48000;seconds=5
for i in range(rate*seconds):
 t=i/rate;burst=(i%(rate//2))<rate//25
 noise=rng.uniform(-1,1)*0.06 if burst else 0
 l=.24*math.sin(2*math.pi*347*t)+.14*math.sin(2*math.pi*1913*t)+.07*math.sin(2*math.pi*6311*t)+noise
 r=.22*math.sin(2*math.pi*523*t+.3)+.12*math.sin(2*math.pi*2927*t)+.07*math.sin(2*math.pi*7993*t)-noise
 fade=min(1,i/480,(rate*seconds-1-i)/480)
 pcm.extend(struct.pack('<hh',round(l*fade*32767),round(r*fade*32767)))
(root/'synthetic-stereo-48k.s16le').write_bytes(pcm)
with wave.open(str(root/'synthetic-stereo-48k.wav'),'wb')as w:w.setnchannels(2);w.setsampwidth(2);w.setframerate(rate);w.writeframes(pcm)
