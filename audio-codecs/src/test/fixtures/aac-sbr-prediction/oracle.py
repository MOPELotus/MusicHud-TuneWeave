#!/usr/bin/env python3
"""Independent Decimal70 oracle: direct inner products of exact captured floats."""
from decimal import Decimal as D, getcontext
from pathlib import Path
import struct
getcontext().prec = 70
root = Path(__file__).resolve().parents[2] / "resources/codecs/aac-sbr-prediction"
f32 = lambda x: struct.unpack("f", struct.pack("f", x))[0]
rel = D.from_float(f32(1.0 / f32(1.0 + f32(1e-6))))
def mul(a,b): return a[0]*b[0]-a[1]*b[1], a[0]*b[1]+a[1]*b[0]
def conj(a): return a[0], -a[1]
def dot(a,b):
    terms = [mul(x,conj(y)) for x,y in zip(a,b)]
    return sum(t[0] for t in terms), sum(t[1] for t in terms)
for path in sorted(root.glob("*.hex")):
    vals = [D.from_float(struct.unpack(">f", bytes.fromhex(s))[0]) for s in path.read_text().split()]
    x = list(zip(vals[::2], vals[1::2]))
    r01, r02, r12 = dot(x[2:],x[1:-1]), dot(x[2:],x[:-2]), dot(x[1:-1],x[:-2])
    r11, r22 = dot(x[1:-1],x[1:-1])[0], dot(x[:-2],x[:-2])[0]
    det = r11*r22-rel*(r12[0]**2+r12[1]**2)
    p = mul(r01,r12)
    a1 = ((p[0]-r02[0]*r11)/det,(p[1]-r02[1]*r11)/det)
    p = mul(a1,conj(r12))
    a0 = (-(r01[0]+p[0])/r11,-(r01[1]+p[1])/r11)
    print(path.name, *a0, *a1)
