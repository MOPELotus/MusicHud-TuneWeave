Synthetic codec regression vectors

These vectors contain project-generated signals, not third-party music. The
stereo source is 4,800 frames at 48kHz: independent 735/1470Hz sine channels,
11,000 amplitude, with uniform +/-1,400 integer noise, Python Random seed42.
The WavPack vector contains 800 stereo frames of full-range signed32 integers
from the same deterministic generator after the sine source.

FFmpeg6.1.1 encoded MP3(libmp3lame), Vorbis(libvorbis), Opus(libopus), AAC-LC,
ALAC and integer WavPack. MP3/AAC/ALAC references are independent FFmpeg PCM decodes. Opus
reference is independently compiled official Xiph libopus1.1.2 FIXED_POINT C;
it matches Concentus exactly. System libopus float and FFmpeg native produce
small different rounding errors and are not the fixed-point bit-exact oracle. Vorbis reference uses libvorbisfile ov_read directly: some short
files have a different PCM tail in FFmpeg; the original 4,800-frame duration
is preserved by libvorbisfile. WavPack reference is the original integer input.

*.packets are test-only big-endian bundles: configuration length(int32), raw
configuration, packet count(int32), repeated packet length(int32)+encoded bytes.
AAC packets are stripped from ADTS with the original ASC retained. ALAC packets
and original child config are extracted from the generated MP4 container.
Production contains no test-bundle parser or dependency on fixture preparation.
All *.s16 and *.s32 PCM are interleaved little-endian stereo. The test suite
requires neither FFmpeg, native decoder libraries nor network access.

The noisy AAC sample uses143 perceptual-noise-substitution bands. Independent
decoders synthesize different random waveforms, so it is checked for matched
frame/timing, per-channel RMS and high-frequency noise energy instead of sample
SNR. The same signal encoded with only -aac_pns0 forms aac-no-pns: its independent
FFmpeg reference differs from the Java decoder by at most one S16 unit.
