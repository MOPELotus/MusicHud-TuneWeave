AAC recovery regression vectors

The project generated all signals and encoded them with official FDK AAC 2.0.3
(commit716f4394641d53f0d79c9ddac3fa93b03a49f278). No downloaded music or
conformance clips are included. The fixture manifest pins encoded input and the
historical pre-observer JAAD PCM hashes and the corrected hashes after enabling
TNS filtering, followed by inherited-short-envelope SBR noise compensation.
Historical hashes remain in the manifest alongside the latest corrected hashes.
The tests pin deterministic recovery output; these hashes do not
claim independent waveform equivalence of different AAC implementations.

Each .packets file: BE32 ASC length, ASC, BE32 AU count, then repeated
BE32 AU length and raw compressed AU. Output is48k stereo,2048frames per AU.
FDK's ordinary header period10 and a generation-only period72 variant are used.
Period72 changes only the encoder's header scheduling limit, not syntax or DSP;
it demonstrates legal initialization gaps longer than two seconds.

Generation uses five seconds of independent multitone channels, periodic short
seeded noise bursts and fades, PCM16, seed20261018. Regeneration sources and the
encoder-only patch are in src/test/fixtures/aac-recovery. Tests need no encoder,
native library or network. The FDK source is a test-generation tool and is not a
module dependency or bundled runtime.

The mono vectors use the same generated signal's left channel, original FDK
MODE_1 and three seconds. Both declared mono LC and HE-v1 have no PS. JAAD's
existing output duplicates to stereo; that is not evidence of PS and must not
force PS readiness.

The single-rate-SBR vector uses official AACENC_SBR_RATIO=1,24kHz stereo,
1024frames/AU. Official FDK accepts the complete stream; this Java core does not
implement that mode, so it is explicitly rejected. This is a known compatibility
limit of the Java backend, not a claim that every HE combination is supported.
