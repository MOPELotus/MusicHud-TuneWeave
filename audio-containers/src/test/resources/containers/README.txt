Synthetic, generated test signals; no music recordings or user data.
Generated with FFmpeg 6.1.1 from its sine lavfi source:
  ffmpeg -f lavfi -i sine=frequency=997:sample_rate=48000:duration=0.13 -ac 2 -c:a aac -b:a 128k -map_metadata -1 aac.m4a
  ffmpeg -f lavfi -i sine=frequency=997:sample_rate=48000:duration=0.13 -ac 2 -c:a alac -map_metadata -1 alac.m4a
  ffmpeg -f lavfi -i sine=frequency=997:sample_rate=48000:duration=0.13 -ac 2 -c:a libopus -b:a 96k -map_metadata -1 opus.webm
reference.json records the independently extracted ffprobe compressed-packet SHA256,
configuration bytes and timing, using -show_packets -show_streams -show_data.
The test suite does not need FFmpeg installed.

alac-description-switch.m4a concatenates the two compressed packets of alac.m4a
and an otherwise identical sine generated with -sample_fmt s32p (24-bit ALAC).
Its stsd contains both original sample descriptions; stsc switches to the second
at chunk 2. stsz/stts/stco are rebuilt for four packets, and the edit list is
removed. alac-description-switch.json records each independently ffprobe-extracted
source packet hash/config; the test checks configuration changes and exactly one
decoder reset. No source recording is used.

mp3.mp4 uses the same sine with -c:a libmp3lame -b:a 128k -f mp4.
The QuickTime regression mutates the actual sample entry type mp4a to .mp3,
then compares every compressed packet with the original while asserting the
unified codec identifier is mp3.
