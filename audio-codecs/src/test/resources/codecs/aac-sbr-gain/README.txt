Original HE-AAC v2 transient regression (CC0-1.0).

The continuous 3-second signal combines two chirps and deterministic exponentially
decaying noise transients. Only the first 54 access units are retained, including
the inherited-short-envelope regression and its decoding history. It is not a
loop or a third-party recording. The packet file uses BE32 ASC length, ASC, BE32
packet count and BE32 length plus bytes per packet. Reference PCM is complete raw
ADTS decoding by the recorded independent FFmpeg version, including encoder
priming, with no edit-list, lag adjustment, gain fitting or dropped samples.

Regenerate using Python 3, FFmpeg and the separate FDK encode_fixture generation
tool: python3 generate.py --encoder /path/to/encode_fixture
FDK is used only to produce the test stream, never packaged with the decoder.
The unit test checks native JAAD and relocated/R8 PCM byte equality, and the full
reference within one signed-16-bit LSB. This fixture is not a universal HE-AAC
conformance or physical audio-output claim.
