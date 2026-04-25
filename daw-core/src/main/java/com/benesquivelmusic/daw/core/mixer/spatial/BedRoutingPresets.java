package com.benesquivelmusic.daw.core.mixer.spatial;

import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;

import java.util.Arrays;
import java.util.UUID;

/**
 * Preset routings that fill in sensible bed-channel gains for common
 * source types.
 *
 * <p>The presets cover the three workflow shortcuts called out in the
 * issue:
 * <ul>
 *   <li>{@link #lcr(UUID, ImmersiveFormat)} — "LCR": route a mono source
 *       equally to L, C and R at 0 dB (other channels muted).</li>
 *   <li>{@link #stereoToLR(UUID, ImmersiveFormat)} — "Stereo to LR":
 *       send to L and R at 0 dB (other channels muted).</li>
 *   <li>{@link #surroundDropThrough(UUID, ImmersiveFormat)} —
 *       "Surround Drop-Through": pass through every ear-level surround
 *       channel at 0 dB and leave LFE / height channels muted.</li>
 * </ul>
 */
public final class BedRoutingPresets {

    private BedRoutingPresets() {
        // utility class
    }

    /**
     * "LCR": mono into L + C + R at 0 dB.
     *
     * @param trackId the source track id
     * @param format  the bed format
     * @return a routing that is unity on L, C, R and silent elsewhere
     */
    public static BedChannelRouting lcr(UUID trackId, ImmersiveFormat format) {
        double[] gains = silentGains(format);
        setIfPresent(gains, format, SpeakerLabel.L, 0.0);
        setIfPresent(gains, format, SpeakerLabel.C, 0.0);
        setIfPresent(gains, format, SpeakerLabel.R, 0.0);
        return new BedChannelRouting(trackId, format, gains);
    }

    /**
     * "Stereo to LR": stereo into L + R at 0 dB (mute everything else).
     *
     * @param trackId the source track id
     * @param format  the bed format
     * @return a routing that is unity on L + R and silent elsewhere
     */
    public static BedChannelRouting stereoToLR(UUID trackId, ImmersiveFormat format) {
        double[] gains = silentGains(format);
        setIfPresent(gains, format, SpeakerLabel.L, 0.0);
        setIfPresent(gains, format, SpeakerLabel.R, 0.0);
        return new BedChannelRouting(trackId, format, gains);
    }

    /**
     * "Surround Drop-Through": route every ear-level surround channel
     * (L, R, C, LS, RS, LRS, RRS, LW, RW) at 0 dB; mute LFE and all
     * height channels (LTF, RTF, LTR, RTR, LTS, RTS).
     *
     * <p>This is the typical preset for an existing surround stem that
     * should pass through the bed bus untouched while the height
     * channels remain free for object panning or dedicated content.</p>
     *
     * @param trackId the source track id
     * @param format  the bed format
     * @return the surround drop-through routing
     */
    public static BedChannelRouting surroundDropThrough(UUID trackId, ImmersiveFormat format) {
        double[] gains = silentGains(format);
        SpeakerLabel[] earLevel = {
                SpeakerLabel.L, SpeakerLabel.R, SpeakerLabel.C,
                SpeakerLabel.LS, SpeakerLabel.RS, SpeakerLabel.LRS, SpeakerLabel.RRS,
                SpeakerLabel.LW, SpeakerLabel.RW
        };
        for (SpeakerLabel label : earLevel) {
            setIfPresent(gains, format, label, 0.0);
        }
        return new BedChannelRouting(trackId, format, gains);
    }

    private static double[] silentGains(ImmersiveFormat format) {
        double[] gains = new double[format.channelCount()];
        Arrays.fill(gains, BedChannelRouting.SILENT_DB);
        return gains;
    }

    private static void setIfPresent(double[] gains, ImmersiveFormat format,
                                     SpeakerLabel label, double gainDb) {
        int idx = format.layout().indexOf(label);
        if (idx >= 0) {
            gains[idx] = gainDb;
        }
    }
}
