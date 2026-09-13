# Native PCM fixtures

Files contain four stereo or eight mono 48 kHz sample frames with sample values:
`0, 0, 1, -1, signed_max, signed_min, half_scale, -half_scale`.

- `pcm24-*.flac`: encoded from signed 24-bit little-endian PCM using FFmpeg, compression level 0.
- `pcm32-*.flac`: independent-channel verbatim FLAC frames with 32-bit STREAMINFO, header CRC-8 and frame CRC-16. The stereo fixture was independently decoded with FFmpeg and compared byte-for-byte to the original signed 32-bit PCM. This avoids encoders that silently reduce their output to 24 bits.

Fixtures are generated test data, contain no third-party music, and require no encoder at test time.

`pcm32-wide-{8,9,10}-{verbatim,fixed,lpc}.flac` exercise signed 33-bit side channels in left-side, side-right and mid-side stereo. Each has eight frames at 48 kHz, explicit 32-bit depth code and kHz rate code; verbatim uses extreme values, fixed/LPC use opposing full-scale ramps. The accompanying `.s32le` files are expected interleaved PCM, independently verified using FFmpeg. These test decoder precision, CRC rejection, frame order and chunk alignment.

`pcm24-surround-6.flac` and `pcm24-surround-8.flac` contain one half-scale impulse per input channel, then 480 silent frames at 48 kHz. Layouts are standard 5.1 and 7.1, encoded using FFmpeg from signed 24-bit PCM and independently decoded back to the exact original PCM bytes. Tests check speaker routing, stereo fold-down levels and frame timing without invoking FFmpeg.
