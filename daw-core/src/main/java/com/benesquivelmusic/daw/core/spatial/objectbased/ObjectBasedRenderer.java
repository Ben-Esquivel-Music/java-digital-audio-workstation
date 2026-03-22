package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.List;
import java.util.Objects;

/**
 * Sums bed channels and audio objects into a multi-channel output buffer
 * for monitoring.
 *
 * <p>Bed channels are routed directly to their assigned speaker index in
 * the output layout. Audio objects are rendered to the layout using
 * nearest-neighbor panning based on normalized Cartesian position.</p>
 */
public final class ObjectBasedRenderer {

    private final SpeakerLayout layout;

    /**
     * Creates a renderer targeting the given speaker layout.
     *
     * @param layout the output speaker layout
     */
    public ObjectBasedRenderer(SpeakerLayout layout) {
        this.layout = Objects.requireNonNull(layout, "layout must not be null");
    }

    /** Returns the output speaker layout. */
    public SpeakerLayout getLayout() {
        return layout;
    }

    /**
     * Renders bed channels and audio objects into a multi-channel output buffer.
     *
     * @param bedChannels    the bed channel assignments with per-channel audio
     * @param bedAudio       audio buffers for each bed channel (same order as bedChannels)
     * @param audioObjects   the audio objects
     * @param objectAudio    audio buffers for each object (same order as audioObjects)
     * @param numSamples     number of samples per channel
     * @return multi-channel output buffer indexed by layout channel order
     */
    public float[][] render(List<BedChannel> bedChannels, List<float[]> bedAudio,
                            List<AudioObject> audioObjects, List<float[]> objectAudio,
                            int numSamples) {
        int channels = layout.channelCount();
        float[][] output = new float[channels][numSamples];

        // Route bed channels to their assigned speaker positions
        for (int b = 0; b < bedChannels.size(); b++) {
            var bed = bedChannels.get(b);
            int speakerIdx = layout.indexOf(bed.speakerLabel());
            if (speakerIdx < 0) {
                continue; // speaker not in layout — skip
            }
            float[] audio = bedAudio.get(b);
            float gain = (float) bed.gain();
            for (int i = 0; i < numSamples; i++) {
                output[speakerIdx][i] += audio[i] * gain;
            }
        }

        // Render audio objects using nearest-speaker panning
        for (int o = 0; o < audioObjects.size(); o++) {
            var obj = audioObjects.get(o);
            float[] audio = objectAudio.get(o);
            double[] gains = computeObjectGains(obj.getMetadata());
            for (int ch = 0; ch < channels; ch++) {
                float g = (float) gains[ch];
                if (g > 0.0f) {
                    for (int i = 0; i < numSamples; i++) {
                        output[ch][i] += audio[i] * g;
                    }
                }
            }
        }

        return output;
    }

    /**
     * Computes per-speaker gain coefficients for an audio object based on
     * its 3D metadata position using inverse-distance weighting.
     *
     * @param metadata the object spatial metadata
     * @return per-speaker gain array (same length as layout channels)
     */
    double[] computeObjectGains(ObjectMetadata metadata) {
        List<SpeakerLabel> speakers = layout.speakers();
        int numSpeakers = speakers.size();
        double[] gains = new double[numSpeakers];

        // Convert object position to spherical for speaker matching
        double objAz = objectAzimuth(metadata.x(), metadata.y());
        double objEl = objectElevation(metadata.z());

        double totalWeight = 0.0;
        for (int i = 0; i < numSpeakers; i++) {
            var speaker = speakers.get(i);
            // Skip LFE for object panning
            if (speaker == SpeakerLabel.LFE) {
                continue;
            }
            double spkAz = Math.toRadians(speaker.azimuthDegrees());
            double spkEl = Math.toRadians(speaker.elevationDegrees());
            double oAz = Math.toRadians(objAz);
            double oEl = Math.toRadians(objEl);

            double cosAngle = Math.sin(oEl) * Math.sin(spkEl)
                    + Math.cos(oEl) * Math.cos(spkEl) * Math.cos(oAz - spkAz);
            cosAngle = Math.max(-1.0, Math.min(1.0, cosAngle));
            double angleDeg = Math.toDegrees(Math.acos(cosAngle));

            // Inverse-distance weight with softening to avoid division by zero
            double weight = 1.0 / (angleDeg + 1.0);
            weight *= weight; // sharpen
            gains[i] = weight;
            totalWeight += weight;
        }

        // Normalize and apply object gain
        if (totalWeight > 0.0) {
            double gain = metadata.gain();
            for (int i = 0; i < numSpeakers; i++) {
                gains[i] = (gains[i] / totalWeight) * gain;
            }
        }

        // Apply size/spread: blend between panned and equal distribution
        double size = metadata.size();
        if (size > 0.0) {
            long nonLfeCount = speakers.stream().filter(s -> s != SpeakerLabel.LFE).count();
            double equalGain = metadata.gain() / Math.max(1, nonLfeCount);
            for (int i = 0; i < numSpeakers; i++) {
                if (speakers.get(i) == SpeakerLabel.LFE) {
                    continue;
                }
                gains[i] = gains[i] * (1.0 - size) + equalGain * size;
            }
        }

        return gains;
    }

    /** Converts normalized x/y to azimuth degrees (SOFA convention). */
    private static double objectAzimuth(double x, double y) {
        double az = Math.toDegrees(Math.atan2(-x, y));
        if (az < 0) {
            az += 360.0;
        }
        return az;
    }

    /** Converts normalized z to elevation degrees. */
    private static double objectElevation(double z) {
        return z * 90.0; // z in [-1,1] maps to [-90,90]
    }
}
