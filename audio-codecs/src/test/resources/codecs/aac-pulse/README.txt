AAC pulse-tool regression vectors

Original CC0 hand-written AAC-LC syntax, mono 44.1 kHz, one long-window band.
Cases cover zero/positive/negative coefficients, repeated same-bin pulses,
cumulative offsets, zero amplitude, the signed 8191 + 4*15 = 8251 magnitude
limits, and pulse-present followed by pulse-absent frames.

Each bundle uses BE32 ASC length, ASC, BE32 count and BE32 length/raw AU.
The independent FFmpeg 6.1.1-3ubuntu5+esm15 reference includes all eight AUs.
Correct pulse processing agrees within 1 PCM16 unit; the previous no-op
decoded the nonzero zero-coefficient pulse case to silence. The final empty
frames check that old pulse metadata is not reused after overlap decays.

The generator is in src/test/fixtures/aac-pulse; run from an empty output
directory. Tests read checked-in bytes and require no native codec or network.
