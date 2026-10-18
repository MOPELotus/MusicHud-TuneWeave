The information-frame-only fixture is the original generated LAME Info frame,
with no audio frames. A normal gapless decoder must report that it produced no
PCM, rather than treating it as a successful silent/empty song. It is not music.
The free-format rejection test constructs an explicit valid free-format header
before other apparent sync patterns; no arbitrary third-party recording is used.
