AAC TNS regression vectors

Original 0.8s 44.1kHz mono chirp, tones and decaying pulses; CC0.
The TNS-enabled stream has eight active access units: short-window order7
filters and long-window order6 filters in both directions. PNS is disabled.
The TNS-disabled stream checks exact preservation of the previous PCM.

Packets use BE32 ASC length, ASC, BE32 count, repeated BE32 length and raw AU.
The independent FFmpeg reference decodes every AU, including encoder priming
and tail padding. The player currently duplicates mono; compare channel0 and
check channel1 matches. The original TNS no-op failed by up to2354 PCM16 units;
correct filtering agrees within1 unit. The manifest pins the input/reference
hashes and filter coverage. Regeneration is in src/test/fixtures/aac-tns; unit
tests use checked-in files and require no encoder, native code or network.
