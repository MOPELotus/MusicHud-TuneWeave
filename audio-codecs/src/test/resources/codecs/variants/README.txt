Additional synthetic codec regression vectors

WavPack5.9.0 command line encodes97-frame source formulas (seed79 integers,
per-channel finite float ramps), with --raw-pcm=48000,<depth/type>,<channels>,le
and --blocksize128. Hybrid adds-b3; no sidecar correction is discovered or used.
Reference PCM is independently decoded by system FFmpeg6.1.1. Eight channels
explicitly declare WAVE mask255, not an assumed7.1 layout.

ALAC20 is independently encoded by the official Apple ALAC source using generated
integer signals. The selected first4096-frame packet and final17-frame raw packet
are retained from a12305-frame stream; a length-prefixed config/count/packets bundle
uses the same test-only format as the base AAC/ALAC vectors. Independent FFmpeg
S32 reference is shifted to signed24-bit storage. Its lowest4 bits must remain0;
this is native20-bit precision left-aligned in three-byte samples.

AIFF/AIFC/AU vectors contain97 frames of generated formulas from the independent
PCM-container study. Expected samples are independently decoded by FFmpeg at the
same integer/float precision. No native decoder, encoder or network is required
to run the checked-in regression suite.
