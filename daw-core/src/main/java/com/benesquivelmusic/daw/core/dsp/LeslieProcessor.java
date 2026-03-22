package com.benesquivelmusic.daw.core.dsp;

import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.Arrays;

/**
 * Physically modeled Leslie (rotary speaker) effect processor.
 *
 * <p>Models the physical behavior of a Leslie speaker cabinet, incorporating:
 * <ul>
 *   <li><b>Horn and drum crossover split</b> — a Linkwitz-Riley crossover filter
 *       splits the signal into high-frequency (horn) and low-frequency (drum)
 *       bands at 800 Hz, matching real Leslie speaker behavior</li>
 *   <li><b>Amplitude modulation</b> — the rotating horn and drum produce
 *       periodic volume changes as they face toward and away from the listener</li>
 *   <li><b>Frequency modulation (Doppler effect)</b> — the rotational movement
 *       causes pitch shifting proportional to the component of velocity toward
 *       the listener, modeled with modulated delay lines</li>
 *   <li><b>Speed control with acceleration</b> — smooth transitions between
 *       slow (chorale, ~0.8 Hz) and fast (tremolo, ~6.7 Hz) speeds with
 *       realistic ramp-up/ramp-down inertia modeling</li>
 * </ul>
 *
 * <p>Based on AES research: "Analog Pseudo Leslie Effect with High Grade
 * of Repeatability" — physical modeling of rotary speaker with AM, FM,
 * and Doppler characteristics.</p>
 *
 * <p>This is a pure-Java implementation — no JNI required.</p>
 */
public final class LeslieProcessor implements AudioProcessor {

    // Leslie speaker physical constants
    private static final double SLOW_SPEED_HZ = 0.8;   // Chorale
    private static final double FAST_SPEED_HZ = 6.7;   // Tremolo
    private static final double CROSSOVER_FREQ = 800.0; // Horn/drum split frequency
    private static final double MAX_DOPPLER_DELAY_MS = 5.0;

    private final int channels;
    private final double sampleRate;

    // Parameters
    private double speed;           // [0, 1]: 0 = slow (chorale), 1 = fast (tremolo)
    private double acceleration;    // [0, 1]: rate of speed change
    private double hornDrumBalance; // [0, 1]: 0 = all drum, 1 = all horn
    private double distance;        // [0, 1]: mic distance (affects modulation depth)
    private double mix;             // [0, 1]: dry/wet

    // Current rotation state
    private double hornPhase;
    private double drumPhase;
    private double currentHornSpeedHz;
    private double currentDrumSpeedHz;

    // Per-channel crossover filters
    private final CrossoverFilter[] crossovers;

    // Per-channel Doppler delay lines for horn
    private final float[][] hornDelayLines;
    private final int[] hornWritePos;
    private final int maxDopplerSamples;

    // Per-channel Doppler delay lines for drum
    private final float[][] drumDelayLines;
    private final int[] drumWritePos;

    /**
     * Creates a Leslie processor with default settings.
     *
     * @param channels   number of audio channels
     * @param sampleRate the sample rate in Hz
     */
    public LeslieProcessor(int channels, double sampleRate) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.speed = 0.0;
        this.acceleration = 0.5;
        this.hornDrumBalance = 0.5;
        this.distance = 0.5;
        this.mix = 0.5;

        this.hornPhase = 0.0;
        this.drumPhase = 0.0;
        this.currentHornSpeedHz = SLOW_SPEED_HZ;
        this.currentDrumSpeedHz = SLOW_SPEED_HZ * 0.6; // Drum rotates slower

        // Crossover filters
        double safeFreq = Math.min(CROSSOVER_FREQ, sampleRate / 2.0 - 1.0);
        crossovers = new CrossoverFilter[channels];
        for (int ch = 0; ch < channels; ch++) {
            crossovers[ch] = new CrossoverFilter(sampleRate, safeFreq);
        }

        // Doppler delay lines
        maxDopplerSamples = (int) (MAX_DOPPLER_DELAY_MS * 0.001 * sampleRate) + 2;
        hornDelayLines = new float[channels][maxDopplerSamples];
        hornWritePos = new int[channels];
        drumDelayLines = new float[channels][maxDopplerSamples];
        drumWritePos = new int[channels];
    }

    @Override
    public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
        int activeCh = Math.min(channels, inputBuffer.length);

        // Compute target speeds based on speed parameter
        double targetHornHz = SLOW_SPEED_HZ + speed * (FAST_SPEED_HZ - SLOW_SPEED_HZ);
        double targetDrumHz = targetHornHz * 0.6; // Drum always rotates slower than horn

        // Acceleration smoothing coefficient (per-sample)
        double accelCoeff = 1.0 - Math.exp(-acceleration * 10.0 / sampleRate);

        float[] crossoverOut = new float[2]; // [low, high]

        for (int frame = 0; frame < numFrames; frame++) {
            // Smoothly approach target speed (models motor inertia)
            currentHornSpeedHz += (targetHornHz - currentHornSpeedHz) * accelCoeff;
            currentDrumSpeedHz += (targetDrumHz - currentDrumSpeedHz) * accelCoeff;

            // Advance rotation phases
            double hornPhaseInc = 2.0 * Math.PI * currentHornSpeedHz / sampleRate;
            double drumPhaseInc = 2.0 * Math.PI * currentDrumSpeedHz / sampleRate;
            hornPhase += hornPhaseInc;
            drumPhase += drumPhaseInc;
            if (hornPhase >= 2.0 * Math.PI) hornPhase -= 2.0 * Math.PI;
            if (drumPhase >= 2.0 * Math.PI) drumPhase -= 2.0 * Math.PI;

            // Compute modulation values
            double hornSin = Math.sin(hornPhase);
            double drumSin = Math.sin(drumPhase);

            // AM depth scales with distance (closer = more variation)
            double amDepth = 0.3 * (0.3 + 0.7 * distance);
            double hornAm = 1.0 - amDepth + amDepth * (0.5 + 0.5 * hornSin);
            double drumAm = 1.0 - amDepth + amDepth * (0.5 + 0.5 * drumSin);

            // FM (Doppler) delay modulation in samples
            double dopplerDepth = distance * 0.5; // [0, 0.5]
            double baseDopplerSamples = MAX_DOPPLER_DELAY_MS * 0.5 * 0.001 * sampleRate;
            double hornDelaySamples = baseDopplerSamples
                    * (1.0 + dopplerDepth * hornSin);
            double drumDelaySamples = baseDopplerSamples
                    * (1.0 + dopplerDepth * drumSin);

            // Clamp delays
            hornDelaySamples = Math.max(0.0, Math.min(hornDelaySamples, maxDopplerSamples - 2));
            drumDelaySamples = Math.max(0.0, Math.min(drumDelaySamples, maxDopplerSamples - 2));

            for (int ch = 0; ch < activeCh; ch++) {
                float input = inputBuffer[ch][frame];

                // Split into low (drum) and high (horn) bands
                crossovers[ch].processSample(input, crossoverOut);
                float lowBand = crossoverOut[0];
                float highBand = crossoverOut[1];

                // --- Horn path (high frequencies): Doppler + AM ---
                hornDelayLines[ch][hornWritePos[ch]] = highBand;
                float hornWet = DspUtils.readInterpolated(hornDelayLines[ch],
                        hornWritePos[ch], hornDelaySamples, maxDopplerSamples);
                hornWritePos[ch] = (hornWritePos[ch] + 1) % maxDopplerSamples;
                hornWet *= (float) hornAm;

                // --- Drum path (low frequencies): Doppler + AM ---
                drumDelayLines[ch][drumWritePos[ch]] = lowBand;
                float drumWet = DspUtils.readInterpolated(drumDelayLines[ch],
                        drumWritePos[ch], drumDelaySamples, maxDopplerSamples);
                drumWritePos[ch] = (drumWritePos[ch] + 1) % maxDopplerSamples;
                drumWet *= (float) drumAm;

                // Combine horn and drum with balance
                float wet = (float) (hornWet * hornDrumBalance
                        + drumWet * (1.0 - hornDrumBalance));

                // Mix dry and wet
                outputBuffer[ch][frame] = (float) (input * (1.0 - mix) + wet * mix);
            }
        }
    }

    // --- Parameter accessors ---

    public double getSpeed() { return speed; }

    public void setSpeed(double speed) {
        if (speed < 0 || speed > 1.0) {
            throw new IllegalArgumentException("speed must be in [0, 1]: " + speed);
        }
        this.speed = speed;
    }

    public double getAcceleration() { return acceleration; }

    public void setAcceleration(double acceleration) {
        if (acceleration < 0 || acceleration > 1.0) {
            throw new IllegalArgumentException(
                    "acceleration must be in [0, 1]: " + acceleration);
        }
        this.acceleration = acceleration;
    }

    public double getHornDrumBalance() { return hornDrumBalance; }

    public void setHornDrumBalance(double hornDrumBalance) {
        if (hornDrumBalance < 0 || hornDrumBalance > 1.0) {
            throw new IllegalArgumentException(
                    "hornDrumBalance must be in [0, 1]: " + hornDrumBalance);
        }
        this.hornDrumBalance = hornDrumBalance;
    }

    public double getDistance() { return distance; }

    public void setDistance(double distance) {
        if (distance < 0 || distance > 1.0) {
            throw new IllegalArgumentException("distance must be in [0, 1]: " + distance);
        }
        this.distance = distance;
    }

    public double getMix() { return mix; }

    public void setMix(double mix) {
        if (mix < 0 || mix > 1.0) {
            throw new IllegalArgumentException("mix must be in [0, 1]: " + mix);
        }
        this.mix = mix;
    }

    @Override
    public void reset() {
        hornPhase = 0.0;
        drumPhase = 0.0;
        currentHornSpeedHz = SLOW_SPEED_HZ;
        currentDrumSpeedHz = SLOW_SPEED_HZ * 0.6;

        for (int ch = 0; ch < channels; ch++) {
            crossovers[ch].reset();
            Arrays.fill(hornDelayLines[ch], 0.0f);
            hornWritePos[ch] = 0;
            Arrays.fill(drumDelayLines[ch], 0.0f);
            drumWritePos[ch] = 0;
        }
    }

    @Override
    public int getInputChannelCount() { return channels; }

    @Override
    public int getOutputChannelCount() { return channels; }
}
