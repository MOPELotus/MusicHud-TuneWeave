package net.sourceforge.jaad.aac.sbr;

import net.sourceforge.jaad.aac.AACException;

class HFGeneration {

    private static final int[] goalSbTab = {21, 23, 32, 43, 46, 64, 85, 93, 128, 0, 0, 0};

    private static class acorr_coef {

        final double[] r01 = new double[2];
        final double[] r02 = new double[2];
        final double[] r11 = new double[2];
        final double[] r12 = new double[2];
        final double[] r22 = new double[2];
        double det;
    }

    public static void hf_generation(float[][][] Xlow, float[][][] Xhigh, Channel ch, boolean reset) {

        float[][] alpha_0 = new float[64][2], alpha_1 = new float[64][2];

        int offset = ch.sbr.tHFAdj;
        int first = ch.t_E[0];
        int last = ch.t_E[ch.L_E];

        calc_chirp_factors(ch);

        if (reset)
            patch_construction(ch.sbr);

        // calculate the prediction coefficients

        // actual HF generation
        for (int i = 0; i < ch.sbr.noPatches; i++) {
            for (int x = 0; x < ch.sbr.patchNoSubbands[i]; x++) {
                float a0_r, a0_i, a1_r, a1_i;
                float bw, bw2;
                int q, p, k, g;

                // find the low and high band for patching
                k = ch.sbr.kx + x;
                for (q = 0; q < i; q++) {
                    k += ch.sbr.patchNoSubbands[q];
                }
                p = ch.sbr.patchStartSubband[i] + x;

                g = ch.sbr.table_map_k_to_g[k];

                bw = ch.bwArray[g];
                bw2 = bw * bw;

                // do the patching
                // with or without filtering
                if (bw2 > 0) {
                    float temp1_r, temp2_r, temp3_r;
                    float temp1_i, temp2_i, temp3_i;
                    calc_prediction_coef(ch.sbr, Xlow, alpha_0, alpha_1, p);

                    a0_r = (alpha_0[p][0] * bw);
                    a1_r = (alpha_1[p][0] * bw2);
                    a0_i = (alpha_0[p][1] * bw);
                    a1_i = (alpha_1[p][1] * bw2);

                    temp2_r = (Xlow[first - 2 + offset][p][0]);
                    temp3_r = (Xlow[first - 1 + offset][p][0]);
                    temp2_i = (Xlow[first - 2 + offset][p][1]);
                    temp3_i = (Xlow[first - 1 + offset][p][1]);
                    for (int l = first; l < last; l++) {
                        temp1_r = temp2_r;
                        temp2_r = temp3_r;
                        temp3_r = (Xlow[l + offset][p][0]);
                        temp1_i = temp2_i;
                        temp2_i = temp3_i;
                        temp3_i = (Xlow[l + offset][p][1]);

                        Xhigh[l + offset][k][0]
                                = temp3_r
                                + ((a0_r * temp2_r)
                                - (a0_i * temp2_i)
                                + (a1_r * temp1_r)
                                - (a1_i * temp1_i));
                        Xhigh[l + offset][k][1]
                                = temp3_i
                                + ((a0_i * temp2_r)
                                + (a0_r * temp2_i)
                                + (a1_i * temp1_r)
                                + (a1_r * temp1_i));
                    }
                } else {
                    for (int l = first; l < last; l++) {
                        Xhigh[l + offset][k][0] = Xlow[l + offset][p][0];
                        Xhigh[l + offset][k][1] = Xlow[l + offset][p][1];
                    }
                }
            }
        }

        if (ch.sbr.reset) {
            FBT.limiter_frequency_table(ch.sbr);
        }
    }

    private static void auto_correlation(SBR sbr, acorr_coef ac, float[][][] buffer,
                                         int bd, int len) {
        double r01r = 0, r01i = 0, r02r = 0, r02i = 0, r11r = 0;
        double temp1_r, temp1_i, temp2_r, temp2_i, temp3_r, temp3_i, temp4_r, temp4_i, temp5_r, temp5_i;
        // Near-singular covariance needs double precision; preserve the original float regularizer.
        double rel = 1.0f / (1.0f + 1e-6f);
        int j;
        int offset = sbr.tHFAdj;

        temp2_r = buffer[offset - 2][bd][0];
        temp2_i = buffer[offset - 2][bd][1];
        temp3_r = buffer[offset - 1][bd][0];
        temp3_i = buffer[offset - 1][bd][1];
        // Save these because they are needed after loop
        temp4_r = temp2_r;
        temp4_i = temp2_i;
        temp5_r = temp3_r;
        temp5_i = temp3_i;

        for (j = offset; j < len + offset; j++) {
            temp1_r = temp2_r; // temp1_r = QMF_RE(buffer[j-2][bd];
            temp1_i = temp2_i; // temp1_i = QMF_IM(buffer[j-2][bd];
            temp2_r = temp3_r; // temp2_r = QMF_RE(buffer[j-1][bd];
            temp2_i = temp3_i; // temp2_i = QMF_IM(buffer[j-1][bd];
            temp3_r = buffer[j][bd][0];
            temp3_i = buffer[j][bd][1];
            r01r += temp3_r * temp2_r + temp3_i * temp2_i;
            r01i += temp3_i * temp2_r - temp3_r * temp2_i;
            r02r += temp3_r * temp1_r + temp3_i * temp1_i;
            r02i += temp3_i * temp1_r - temp3_r * temp1_i;
            r11r += temp2_r * temp2_r + temp2_i * temp2_i;
        }

        // These are actual values in temporary variable at this point
//        temp1_r = QMF_RE(buffer[len + offset - 1 - 2][bd];
//        temp1_i = QMF_IM(buffer[len + offset - 1 - 2][bd];
//        temp2_r = QMF_RE(buffer[len + offset - 1 - 1][bd];
//        temp2_i = QMF_IM(buffer[len + offset - 1 - 1][bd];
//        temp3_r = QMF_RE(buffer[len + offset - 1][bd]);
//        temp3_i = QMF_IM(buffer[len + offset - 1][bd]);
//        temp4_r = QMF_RE(buffer[offset - 2][bd]);
//        temp4_i = QMF_IM(buffer[offset - 2][bd]);
//        temp5_r = QMF_RE(buffer[offset - 1][bd]);
//        temp5_i = QMF_IM(buffer[offset - 1][bd]);
        ac.r12[0] = r01r
                - (temp3_r * temp2_r + temp3_i * temp2_i)
                + (temp5_r * temp4_r + temp5_i * temp4_i);
        ac.r12[1] = r01i
                - (temp3_i * temp2_r - temp3_r * temp2_i)
                + (temp5_i * temp4_r - temp5_r * temp4_i);
        ac.r22[0] = r11r
                - (temp2_r * temp2_r + temp2_i * temp2_i)
                + (temp4_r * temp4_r + temp4_i * temp4_i);

        ac.r01[0] = r01r;
        ac.r01[1] = r01i;
        ac.r02[0] = r02r;
        ac.r02[1] = r02i;
        ac.r11[0] = r11r;

        ac.det = (ac.r11[0] * ac.r22[0]) - (rel * ((ac.r12[0] * ac.r12[0]) + (ac.r12[1] * ac.r12[1])));
    }

    /** calculate linear prediction coefficients using the covariance method */
    private static void calc_prediction_coef(SBR sbr, float[][][] Xlow,
                                             float[][] alpha_0, float[][] alpha_1, int k) {
        acorr_coef ac = new acorr_coef();
        auto_correlation(sbr, ac, Xlow, k, sbr.numTimeSlotsRate + 6);
        double a1r = 0, a1i = 0, a0r = 0, a0i = 0;
        if (ac.det != 0) {
            a1r = (ac.r01[0] * ac.r12[0] - ac.r01[1] * ac.r12[1] - ac.r02[0] * ac.r11[0]) / ac.det;
            a1i = (ac.r01[1] * ac.r12[0] + ac.r01[0] * ac.r12[1] - ac.r02[1] * ac.r11[0]) / ac.det;
        }
        if (ac.r11[0] != 0) {
            a0r = -(ac.r01[0] + a1r * ac.r12[0] + a1i * ac.r12[1]) / ac.r11[0];
            a0i = -(ac.r01[1] + a1i * ac.r12[0] - a1r * ac.r12[1]) / ac.r11[0];
        }

        alpha_0[k][0] = (float) a0r;
        alpha_0[k][1] = (float) a0i;
        alpha_1[k][0] = (float) a1r;
        alpha_1[k][1] = (float) a1i;

        if (((alpha_0[k][0] * alpha_0[k][0]) + (alpha_0[k][1] * alpha_0[k][1]) >= 16.0f)
                || ((alpha_1[k][0] * alpha_1[k][0]) + (alpha_1[k][1] * alpha_1[k][1]) >= 16.0f)) {
            alpha_0[k][0] = 0;
            alpha_0[k][1] = 0;
            alpha_1[k][0] = 0;
            alpha_1[k][1] = 0;
        }
    }

    /** FIXED POINT: bwArray = COEF */
    private static float mapNewBw(int invf_mode, int invf_mode_prev) {
        switch (invf_mode) {
        case 1: // LOW

            if (invf_mode_prev == 0) // NONE
                return 0.6f;
            else
                return 0.75f;

        case 2: // MID

            return 0.9f;

        case 3: // HIGH

            return 0.98f;

        default: // NONE

            if (invf_mode_prev == 1) // LOW
                return 0.6f;
            else
                return 0.0f;
        }
    }

    /** FIXED POINT: bwArray = COEF */
    private static void calc_chirp_factors(Channel ch) {
        for (int i = 0; i < ch.sbr.N_Q; i++) {
            ch.bwArray[i] = mapNewBw(ch.bs_invf_mode[i], ch.bs_invf_mode_prev[i]);

            if (ch.bwArray[i] < ch.bwArray_prev[i])
                ch.bwArray[i] = (ch.bwArray[i] * 0.75f) + (ch.bwArray_prev[i] * 0.25f);
            else
                ch.bwArray[i] = (ch.bwArray[i] * 0.90625f) + (ch.bwArray_prev[i] * 0.09375f);

            if (ch.bwArray[i] < 0.015625f)
                ch.bwArray[i] = 0.0f;

            if (ch.bwArray[i] >= 0.99609375f)
                ch.bwArray[i] = 0.99609375f;

            ch.bwArray_prev[i] = ch.bwArray[i];
            ch.bs_invf_mode_prev[i] = ch.bs_invf_mode[i];
        }
    }

    private static void patch_construction(SBR sbr) {
        // ISO normally limits this to five. The published Coding Technologies
        // decoder-check stream needs six; never silently drop its final patch.
        final int maxPatches = 6;
        final int target = sbr.kx + sbr.M;
        if (sbr.k0 < 1 || sbr.k0 > sbr.kx || sbr.kx > 32
                || sbr.M < 1 || sbr.M > SBR.MAX_M || target > 64
                || sbr.N_master < 1 || sbr.N_master >= sbr.f_master.length
                || sbr.f_master[0] != sbr.k0
                || sbr.f_master[sbr.N_master] != target)
            throw new AACException("Invalid SBR patch frequency range");
        for (int i = 1; i <= sbr.N_master; i++) {
            if (sbr.f_master[i] <= sbr.f_master[i - 1])
                throw new AACException("Invalid SBR master frequency table");
        }
        int msb = sbr.k0;
        int usb = sbr.kx;
        //(uint8_t)(2.048e6/sbr.sample_rate + 0.5);
        int rateIndex = sbr.sample_rate.getIndex();
        if (rateIndex < 0 || rateIndex >= goalSbTab.length)
            throw new AACException("Unsupported SBR patch sample rate");
        int goalSb = goalSbTab[rateIndex];

        sbr.noPatches = 0;

        int k = 0;
        if (goalSb < (sbr.kx + sbr.M)) {
            for (int i = 0; i < sbr.N_master && sbr.f_master[i] < goalSb; i++) {
                k = i + 1;
            }
        } else {
            k = sbr.N_master;
        }

        int sb;
        int lastK = -1, lastMsb = -1;
        int attempts = 0;
        do {
            // k changes to N_master at most once. Productive steps advance usb;
            // empty steps reset msb. Reject a repeated state and bound all work.
            if ((k == lastK && msb == lastMsb) || ++attempts > 2 * (maxPatches + 1))
                throw new AACException("SBR patch construction did not converge");
            lastK = k;
            lastMsb = msb;
            if (sbr.noPatches >= maxPatches)
                throw new AACException("Too many SBR patches");
            int j = k + 1;
            int odd;

            do {
                if (--j < 0)
                    throw new AACException("Invalid SBR patch source range");

                sb = sbr.f_master[j];
                odd = (sb - 2 + sbr.k0) % 2;
            }
            while (sb > (sbr.k0 - 1 + msb - odd));

            sbr.patchNoSubbands[sbr.noPatches] = Math.max(sb - usb, 0);
            sbr.patchStartSubband[sbr.noPatches] = sbr.k0 - odd
                    - sbr.patchNoSubbands[sbr.noPatches];

            if (sbr.patchStartSubband[sbr.noPatches] < 0
                    || sbr.patchStartSubband[sbr.noPatches]
                    + sbr.patchNoSubbands[sbr.noPatches] > sbr.kx)
                throw new AACException("Invalid SBR patch source range");

            if (sbr.patchNoSubbands[sbr.noPatches] > 0) {
                usb = sb;
                msb = sb;
                sbr.noPatches++;
            } else {
                msb = sbr.kx;
            }

            if (sbr.f_master[k] - sb < 3)
                k = sbr.N_master;
        }
        while (sb != (sbr.kx + sbr.M));

        if ((sbr.patchNoSubbands[sbr.noPatches - 1] < 3) && (sbr.noPatches > 1)) {
            sbr.noPatches--;
        }

        // Keep every constructed patch; over-limit input was rejected above.
    }
}
