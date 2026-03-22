package com.benesquivelmusic.daw.core.spatial.ambisonics;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.spatial.AmbisonicOrder;
import com.benesquivelmusic.daw.sdk.spatial.DecoderType;
import com.benesquivelmusic.daw.sdk.spatial.SpatialPosition;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Decodes Ambisonic B-format signals to binaural stereo output using the
 * virtual speaker method.
 *
 * <p>The virtual speaker approach places a set of virtual loudspeakers
 * around the listener's head and first decodes to those speakers using
 * a standard Ambisonic decoder, then sums the contributions to left and
 * right ear channels based on the speaker positions.</p>
 *
 * <p>This provides a lightweight binaural rendering without requiring
 * external HRTF data. For high-fidelity binaural rendering with measured
 * HRTFs, use the {@link com.benesquivelmusic.daw.sdk.spatial.BinauralRenderer}
 * interface with SOFA-based implementations.</p>
 */
public final class AmbisonicBinauralDecoder implements AudioProcessor {

    private static final List<SpatialPosition> DEFAULT_VIRTUAL_SPEAKERS = List.of(
            new SpatialPosition(0, 0, 1.0),     // front
            new SpatialPosition(45, 0, 1.0),    // front-left
            new SpatialPosition(315, 0, 1.0),   // front-right
            new SpatialPosition(90, 0, 1.0),    // left
            new SpatialPosition(270, 0, 1.0),   // right
            new SpatialPosition(135, 0, 1.0),   // rear-left
            new SpatialPosition(225, 0, 1.0),   // rear-right
            new SpatialPosition(180, 0, 1.0)    // rear
    );

    private final AmbisonicDecoder internalDecoder;
    private final int numVirtualSpeakers;
    private final double[] leftGains;
    private final double[] rightGains;
    private float[][] virtualSpeakerBuffer;

    /**
     * Creates a binaural decoder using default virtual speakers and max-rE decoding.
     *
     * @param order the Ambisonic order
     */
    public AmbisonicBinauralDecoder(AmbisonicOrder order) {
        this(order, DEFAULT_VIRTUAL_SPEAKERS, DecoderType.MAX_RE);
    }

    /**
     * Creates a binaural decoder with custom virtual speaker positions.
     *
     * @param order                  the Ambisonic order
     * @param virtualSpeakerPositions the virtual speaker positions
     * @param decoderType            the decoder weighting type
     */
    public AmbisonicBinauralDecoder(AmbisonicOrder order,
                                     List<SpatialPosition> virtualSpeakerPositions,
                                     DecoderType decoderType) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(virtualSpeakerPositions, "virtualSpeakerPositions must not be null");
        Objects.requireNonNull(decoderType, "decoderType must not be null");

        this.internalDecoder = new AmbisonicDecoder(order, virtualSpeakerPositions, decoderType);
        this.numVirtualSpeakers = virtualSpeakerPositions.size();
        this.leftGains = new double[numVirtualSpeakers];
        this.rightGains = new double[numVirtualSpeakers];

        computeBinauralPanGains(virtualSpeakerPositions);
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        // Ensure virtual speaker buffer is allocated
        if (virtualSpeakerBuffer == null || virtualSpeakerBuffer[0].length < numFrames) {
            virtualSpeakerBuffer = new float[numVirtualSpeakers][numFrames];
        } else {
            for (float[] ch : virtualSpeakerBuffer) {
                Arrays.fill(ch, 0, numFrames, 0.0f);
            }
        }

        // Decode to virtual speakers
        internalDecoder.process(inputBuffer, virtualSpeakerBuffer, numFrames);

        // Sum to binaural left/right
        int leftCh = Math.min(0, outputBuffer.length - 1);
        int rightCh = Math.min(1, outputBuffer.length - 1);

        Arrays.fill(outputBuffer[leftCh], 0, numFrames, 0.0f);
        if (outputBuffer.length > 1) {
            Arrays.fill(outputBuffer[rightCh], 0, numFrames, 0.0f);
        }

        for (int spk = 0; spk < numVirtualSpeakers; spk++) {
            float leftGain = (float) leftGains[spk];
            float rightGain = (float) rightGains[spk];
            for (int i = 0; i < numFrames; i++) {
                outputBuffer[leftCh][i] += virtualSpeakerBuffer[spk][i] * leftGain;
                if (outputBuffer.length > 1) {
                    outputBuffer[rightCh][i] += virtualSpeakerBuffer[spk][i] * rightGain;
                }
            }
        }
    }

    @Override
    public void reset() {
        internalDecoder.reset();
        virtualSpeakerBuffer = null;
    }

    @Override
    public int getInputChannelCount() {
        return internalDecoder.getInputChannelCount();
    }

    @Override
    public int getOutputChannelCount() {
        return 2; // binaural stereo
    }

    /**
     * Computes simple amplitude panning gains from virtual speakers to left/right ears.
     * Speakers on the left side contribute more to the left ear and vice versa.
     */
    private void computeBinauralPanGains(List<SpatialPosition> speakers) {
        for (int i = 0; i < speakers.size(); i++) {
            double azDeg = speakers.get(i).azimuthDegrees() % 360.0;
            if (azDeg < 0) azDeg += 360.0;

            // Convert to [-180, 180] where negative = right, positive = left
            double angle = azDeg;
            if (angle > 180.0) angle -= 360.0;

            // Pan law: equal-power panning based on azimuth
            double panAngle = Math.toRadians(angle) / 2.0;
            leftGains[i] = Math.cos(Math.PI / 4.0 - panAngle);
            rightGains[i] = Math.cos(Math.PI / 4.0 + panAngle);
        }
    }
}
