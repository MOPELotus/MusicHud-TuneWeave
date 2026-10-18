Synthetic WMA v1/v2 regression vectors, generated for this project; no third-party music.
Source: deterministic chirp + random.Random(289145) uniform noise, silence segment and impulse.
noise-v1 is a 16 kHz mono 24 kbit WMA1 stream exercising perceptual noise coding;
stereo-v2 is 32 kHz stereo 64 kbit WMA2 with distinct channels.
Reference: complete independent FFmpeg 6.1.1 F32LE output, no shifts, trimming, resampling or normalization.
These cover decoder priming and final overlap drain. Codec tests compare every decoded float.
Only generated PCM/encoded fixtures are required at test runtime; ffmpeg is not a dependency.
