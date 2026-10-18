package com.beatofthedrum.wv;

class FloatUtils
{


    static int read_float_info (WavPackStream wps, WavPackMetadata wpmd)
    {
        final int bytecnt = wpmd.byte_length;
        byte byteptr[] = wpmd.data;
        int counter = 0;


        if (bytecnt != 4)
            return Defines.FALSE;

        wps.float_flags = byteptr[counter];
        counter++;
        wps.float_shift = byteptr[counter];
        counter++;
        wps.float_max_exp = byteptr[counter];
        counter++;
        wps.float_norm_exp = byteptr[counter];
  
        return Defines.TRUE;
    }


    // MusicHud-TuneWeave 2026-10-18: keep intermediate integers for exact float WVX reconstruction in bounded wrapper.
    // Original Tiny function irreversibly clamps to 24-bit signed PCM; integer paths are unaffected.
    static int[] float_values(WavPackStream wps, int[] values, long num_values) { return values; }
}
