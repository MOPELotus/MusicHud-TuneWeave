Synthetic decoder regression tones; test resources only, not client distribution data.
The basic smoke tones and second Ogg links were generated with FFmpeg. APE/WMA/WavPack/AIFF/AU and raw Vorbis packet vectors reuse the matching audio-codecs test fixtures and their provenance.

seek-lc.m4a: CC0 synthetic 6-second 523 Hz stereo tone generated for random AAC recovery; ffmpeg lavfi sine=frequency=523:sample_rate=48000:duration=6, -ac 2 -c:a aac -b:a 64k -movflags +faststart. No copyrighted music.
