AAC escape magnitude boundaries

Original CC0 hand-authored AAC-LC syntax: mono 44.1 kHz, long window,
one HCB11 band, global gain 120, a pair containing signed 8190 or 8191
followed by the zero pair. Each bundle has 8 identical raw access units.
The reference is FFmpeg 6.1.1-3ubuntu5+esm15 mono PCM16 for every AU.
The existing 8190 output is preserved exactly. The valid maximum 8191
previously addressed a missing table entry and failed decoding.

Packet format: BE32 ASC length, ASC, BE32 count, then BE32 length/raw AU.
The manifest pins exact input/reference bytes. Regenerate with the Python
script in src/test/fixtures/aac-escape from an empty output directory.
Tests require no native codec, encoder, dependency download or network.
