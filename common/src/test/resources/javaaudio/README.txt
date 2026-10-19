Synthetic decoder regression tones; test resources only, not client distribution data.
The basic smoke tones and second Ogg links were generated with FFmpeg. APE/WMA/WavPack/AIFF/AU and raw Vorbis packet vectors reuse the matching audio-codecs test fixtures and their provenance.

seek-lc.m4a: CC0 synthetic 6-second 523 Hz stereo tone generated for random AAC recovery; ffmpeg lavfi sine=frequency=523:sample_rate=48000:duration=6, -ac 2 -c:a aac -b:a 64k -movflags +faststart. No copyrighted music.

seek-hev2-period72.m4a: CC0 self-generated stereo HE-AACv2; encoder FDK 2.0.3 with header repetition set to 72 access units, losslessly remuxed ADTS to M4A with ffmpeg -c:a copy -movflags +faststart. Exercises a legal >3-second recovery header interval. Source synthetic PCM/generator dedicated CC0; FDK is a generation-time tool, not a runtime dependency.

seek-ignored-range.opus: CC0 original synthetic 10-second 431 Hz stereo tone. Encoded with ffmpeg -f lavfi -i sine=frequency=431:sample_rate=48000:duration=10 -map_metadata -1 -ac 2 -c:a libopus -b:a 96k -vbr off. Its compressed tail is beyond the first streaming read, so the HTTP seek test must issue a real Range before exercising same-entity 200 fallback. FFmpeg is fixture-generation only.
