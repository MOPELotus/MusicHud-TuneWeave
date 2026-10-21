Original CC0 chirp/multitone AAC Main vectors with independent FFmpeg PCM.
No downloaded or third-party recordings. FFmpeg is generation-time only.

generate.py writes raw access-unit fixtures and full PCM including encoder
priming/tail. PNS/TNS are disabled to isolate Main prediction from those tools.
The stereo vector exercises independent common-window channel histories;
mono covers Main without a channel pair. The tests also compare the R8 JAR.
See manifest.json for exact hashes, configuration, lengths and tool versions.
Temporary paths in generation argv are placeholders created by the generator.
