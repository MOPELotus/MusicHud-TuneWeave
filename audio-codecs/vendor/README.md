# Vendored Java decoder sources

This directory retains third-party decoder sources and their original license texts. Author and copyright headers are preserved. `manifest.json` pins the source revisions, extracted source artifacts, file SHA-256 values, and local patch SHA-256 values. No download or source rewriting is needed to build.

| Core | Source version | License retained in `upstream/<core>/LICENSE` |
| --- | --- | --- |
| JLayer | 1.0.3, `eff3e6055b70af4532583127fe39ae931af3f492` | LGPL 2.1 |
| JOrbis/Jogg | 0.0.17, `28592f3dde5134871165470e7390d66ed5f1dce5` | GNU Library GPL 2 |
| Concentus | `3885c4e46513ef0fc81fca100189e54f1714c6ca` | BSD terms |
| Java ALAC | BSD core in `com.tianscar.javasound:javasound-alac:0.2.3:sources` | BSD terms |
| Java WavPack | BSD core in `io.github.jseproject:jse-spi-wavpack:1.1.0:sources` | BSD terms |
| JAAD/Vavi AAC | `e4dcdaf272a8ec8ce4c598d2df105f85e437b9cb` | Upstream public-domain dedication |

For ALAC and WavPack, the manifest also pins the original author's repository revision used for the retained license. The Java files are pinned to the published source artifact; that is not a claim that every file is byte-identical to the original repository revision. Only the BSD codec packages are extracted; no Java Sound SPI implementation or registration is included.

## Source layout

`upstream/<core>/src` contains unchanged source files. JLayer includes its original four serialized lookup-table resources in `upstream/jlayer/resources`. These resources must accompany the compiled decoder. Unused upstream command-line examples, SPI registrations, players, and tests are omitted. AAC includes its decoder, ADTS support, sample buffer, and the small exception type required by ADTS; its MP4 implementation is omitted.

Only the files listed in each manifest entry's `patches` array are replaced during compilation. Their unchanged originals remain under `upstream`, their complete unified diffs are under `patches`, and their compiled versions are under `../src/thirdParty/java` with the same Java packages. The build must exclude each replaced original to avoid duplicate classes. The manifest records hashes for both versions, so an upstream refresh cannot silently apply an old patch.

## Local changes

The 2026-10-18 JOrbis patches correct multichannel coupling index widths and bound codebook/setup allocations for malformed input. All three retain the original copyright/license headers.

The 2026-10-18 AAC patch changes eight files: per-decoder noise state and its recurrence, implicit SBR rate initialization, ADTS first-frame handling and construction validation, decoder construction validation, PS implementation selection and filter corrections, stereo SBR coupling, and high-frequency generation. The existing upstream `PSOrig` implementation is reused without copying another project's algorithm source. The full diff, rather than a rewritten algorithm snapshot, is retained for review and future upstream reconciliation.

The Concentus files, including `VorbisLayout.java`, match the pinned upstream Git blobs exactly. JLayer remains unchanged. ALAC has a finite native20-bit packing/sign-extension patch; WavPack preserves intermediate integer values before exact bounded IEEE float reconstruction. Their prediction and entropy algorithms are unchanged. Vendoring a core does not by itself claim that every optional format profile or malformed input has been qualified by the player.

When distributing binaries, include these license texts and preserve the required attribution. Source distribution must retain the original sources, the corresponding patches, and the project's existing LGPL license and upstream attribution.

The isolated WMA1/2 core retains its pinned original Java source and finite sign,
bit-reader and overlap patches; ASF transport-free parsing is a separate adapter.
APE is a controlled LGPL FFmpeg-to-Java translation, not upstream Java: its C
references, original translation, namespace-only integration diff and SHA guards
are recorded separately under the manifest's ports array. No GPL JMAC is used.
