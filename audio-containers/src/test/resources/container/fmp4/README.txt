Synthetic stereo AAC fragments copied from the project's hls-timeline fixture.
They contain generated test signals, not recordings or user media. The init
segment has a selected-track trex and one zero-duration, media-start-zero edit.
Concatenate init.mp4, media0.m4s and media1.m4s in that order for standalone use.

The raw packet decoder yields 62 * 1024 = 63488 stereo frames. Container trun
sample durations declare an exact end at 63424/48000 (last duration 960).
The presentation window therefore excludes 64 final codec padding frames;
changing this endpoint to the untrimmed decoder output would be incorrect.

ffmpeg.s16le.gz is a gzip-compressed independent FFmpeg 6.1.1 PCM oracle:
  ffmpeg -v error -ignore_editlist 1 -i joined.mp4 -f s16le -c:a pcm_s16le output.pcm
Raw size: 253952 bytes, stereo signed little-endian 16-bit, 48000 Hz.
Raw SHA-256: 8361b8cbc708d8fda6c42441b59e39b37f8349ae1de26b82bf743a5a3232c2fe
Tests compare actual shaded container + shaded codec outputs in both assertion
modes, then compare zero-start and primed presentation windows to this oracle.
The common presentation mapper separately owns applying edits exactly once.
