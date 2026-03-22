package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

/**
 * Folds down a multi-channel audio mix to fewer channels for compatibility monitoring.
 *
 * <p>Supports the standard fold-down chain:
 * 7.1.4 → 7.1 → 5.1 → stereo → mono, following ITU-R BS.775 and
 * Dolby recommended downmix coefficients.</p>
 *
 * <p>Each method accepts an input buffer indexed by the source layout's
 * channel order and returns a new buffer indexed by the target layout's
 * channel order.</p>
 */
public final class FoldDownRenderer {

    /** −3 dB coefficient for equal-power downmix. */
    private static final double MINUS_3_DB = Math.sqrt(0.5);

    /** −6 dB coefficient for surround fold-down. */
    private static final double MINUS_6_DB = 0.5;

    private FoldDownRenderer() {
        // utility class
    }

    /**
     * Folds a 7.1.4 mix down to 7.1 by summing height channels into the
     * corresponding ear-level channels with −3 dB attenuation.
     *
     * <p>Channel mapping: LTF → L, RTF → R, LTR → LRS, RTR → RRS.</p>
     *
     * @param input      12-channel buffer (7.1.4 order: L,R,C,LFE,LS,RS,LRS,RRS,LTF,RTF,LTR,RTR)
     * @param numSamples number of samples per channel
     * @return 8-channel buffer (7.1 order: L,R,C,LFE,LS,RS,LRS,RRS)
     */
    public static float[][] foldTo71(float[][] input, int numSamples) {
        validateChannels(input, SpeakerLayout.LAYOUT_7_1_4.channelCount(), "7.1.4");

        int lIdx = 0, rIdx = 1, cIdx = 2, lfeIdx = 3;
        int lsIdx = 4, rsIdx = 5, lrsIdx = 6, rrsIdx = 7;
        int ltfIdx = 8, rtfIdx = 9, ltrIdx = 10, rtrIdx = 11;

        float[][] output = new float[8][numSamples];
        for (int i = 0; i < numSamples; i++) {
            output[0][i] = (float) (input[lIdx][i] + MINUS_3_DB * input[ltfIdx][i]);
            output[1][i] = (float) (input[rIdx][i] + MINUS_3_DB * input[rtfIdx][i]);
            output[2][i] = input[cIdx][i];
            output[3][i] = input[lfeIdx][i];
            output[4][i] = input[lsIdx][i];
            output[5][i] = input[rsIdx][i];
            output[6][i] = (float) (input[lrsIdx][i] + MINUS_3_DB * input[ltrIdx][i]);
            output[7][i] = (float) (input[rrsIdx][i] + MINUS_3_DB * input[rtrIdx][i]);
        }
        return output;
    }

    /**
     * Folds a 7.1 mix down to 5.1 by summing rear surround channels
     * into side surrounds with −3 dB attenuation.
     *
     * @param input      8-channel buffer (7.1 order: L,R,C,LFE,LS,RS,LRS,RRS)
     * @param numSamples number of samples per channel
     * @return 6-channel buffer (5.1 order: L,R,C,LFE,LS,RS)
     */
    public static float[][] foldTo51(float[][] input, int numSamples) {
        validateChannels(input, 8, "7.1");

        float[][] output = new float[6][numSamples];
        for (int i = 0; i < numSamples; i++) {
            output[0][i] = input[0][i]; // L
            output[1][i] = input[1][i]; // R
            output[2][i] = input[2][i]; // C
            output[3][i] = input[3][i]; // LFE
            output[4][i] = (float) (input[4][i] + MINUS_3_DB * input[6][i]); // LS + LRS
            output[5][i] = (float) (input[5][i] + MINUS_3_DB * input[7][i]); // RS + RRS
        }
        return output;
    }

    /**
     * Folds a 5.1 mix down to stereo following ITU-R BS.775 coefficients.
     *
     * <p>Stereo = L + 0.707×C + 0.707×LS, R + 0.707×C + 0.707×RS (LFE discarded).</p>
     *
     * @param input      6-channel buffer (5.1 order: L,R,C,LFE,LS,RS)
     * @param numSamples number of samples per channel
     * @return 2-channel buffer (stereo: L,R)
     */
    public static float[][] foldToStereo(float[][] input, int numSamples) {
        validateChannels(input, SpeakerLayout.LAYOUT_5_1.channelCount(), "5.1");

        float[][] output = new float[2][numSamples];
        for (int i = 0; i < numSamples; i++) {
            output[0][i] = (float) (input[0][i] + MINUS_3_DB * input[2][i] + MINUS_3_DB * input[4][i]);
            output[1][i] = (float) (input[1][i] + MINUS_3_DB * input[2][i] + MINUS_3_DB * input[5][i]);
        }
        return output;
    }

    /**
     * Folds a stereo mix down to mono by summing both channels with −3 dB attenuation.
     *
     * @param input      2-channel buffer (stereo: L,R)
     * @param numSamples number of samples per channel
     * @return 1-channel buffer (mono)
     */
    public static float[][] foldToMono(float[][] input, int numSamples) {
        validateChannels(input, SpeakerLayout.LAYOUT_STEREO.channelCount(), "Stereo");

        float[][] output = new float[1][numSamples];
        for (int i = 0; i < numSamples; i++) {
            output[0][i] = (float) (MINUS_3_DB * (input[0][i] + input[1][i]));
        }
        return output;
    }

    /**
     * Performs a full fold-down chain from 7.1.4 to the target layout.
     *
     * @param input      12-channel buffer (7.1.4 layout)
     * @param target     the target speaker layout
     * @param numSamples number of samples per channel
     * @return the folded-down buffer matching the target channel count
     * @throws IllegalArgumentException if the target layout is unsupported
     */
    public static float[][] foldDown(float[][] input, SpeakerLayout target, int numSamples) {
        float[][] current = input;
        int targetChannels = target.channelCount();

        if (current.length > 8 && targetChannels <= 8) {
            current = foldTo71(current, numSamples);
        }
        if (current.length > 6 && targetChannels <= 6) {
            current = foldTo51(current, numSamples);
        }
        if (current.length > 2 && targetChannels <= 2) {
            current = foldToStereo(current, numSamples);
        }
        if (current.length > 1 && targetChannels <= 1) {
            current = foldToMono(current, numSamples);
        }

        return current;
    }

    private static void validateChannels(float[][] input, int expected, String layoutName) {
        if (input.length != expected) {
            throw new IllegalArgumentException(
                    "%s layout requires %d channels but got %d"
                            .formatted(layoutName, expected, input.length));
        }
    }
}
