package com.benesquivelmusic.daw.core.midi;

import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontInfo;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Device-free built-in keyboard synthesis. MIDI program families select distinct
 * harmonic spectra and envelopes; these are synthesized timbres, not sampled
 * General MIDI instruments. The host renders directly into its active backend.
 * Note/control state is atomically published; voice phases/envelopes belong only
 * to the render thread. No queue can drop a note-off and leave a stuck voice.
 */
public final class GraphKeyboardRenderer implements SoundFontRenderer {
    private static final int CHANNELS = 16;
    private static final int NOTES = 128;
    private final AtomicIntegerArray velocities = new AtomicIntegerArray(CHANNELS * NOTES);
    private final AtomicIntegerArray programs = new AtomicIntegerArray(CHANNELS);
    private final AtomicIntegerArray bends = new AtomicIntegerArray(CHANNELS);
    private final AtomicIntegerArray sustain = new AtomicIntegerArray(CHANNELS);
    private final AtomicLong resetGeneration = new AtomicLong();
    private final double[] phases = new double[CHANNELS * NOTES];
    private final double[] envelopes = new double[CHANNELS * NOTES];
    private final int[] heldVelocities = new int[CHANNELS * NOTES];
    private final double[] frequencies = new double[NOTES];
    private long renderedReset;
    private double sampleRate;
    private volatile float gain = 0.22f;
    private volatile boolean initialized;

    @Override
    public void initialize(double sampleRate, int bufferSize) {
        if (!Double.isFinite(sampleRate) || sampleRate <= 0 || bufferSize <= 0) {
            throw new IllegalArgumentException("Invalid synthesis format");
        }
        this.sampleRate = sampleRate;
        for (int note = 0; note < NOTES; note++) {
            frequencies[note] = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        }
        for (int channel = 0; channel < CHANNELS; channel++) {
            bends.set(channel, MidiEvent.PITCH_BEND_CENTER);
        }
        initialized = true;
    }

    @Override
    public void selectPreset(int channel, int bank, int program) {
        if (channel < 0 || channel >= CHANNELS || bank < 0 || program < 0 || program > 127) {
            throw new IllegalArgumentException("Invalid keyboard preset");
        }
        programs.set(channel, program);
    }

    @Override
    public void sendEvent(MidiEvent event) {
        int channel = event.channel();
        switch (event.type()) {
            case NOTE_ON -> velocities.set(channel * NOTES + event.data1(), event.data2());
            case NOTE_OFF -> velocities.set(channel * NOTES + event.data1(), 0);
            case PROGRAM_CHANGE -> programs.set(channel, event.data1());
            case PITCH_BEND -> bends.set(channel, event.data1());
            case CONTROL_CHANGE -> {
                if (event.data1() == 64) {
                    sustain.set(channel, event.data2() >= 64 ? 1 : 0);
                } else if (event.data1() == 120 || event.data1() == 123) {
                    for (int note = 0; note < NOTES; note++) {
                        velocities.set(channel * NOTES + note, 0);
                    }
                    sustain.set(channel, 0);
                }
            }
        }
    }

    @Override @RealTimeSafe
    public void render(float[][] output, int frames) {
        if (!initialized) {
            return;
        }
        long reset = resetGeneration.get();
        if (reset != renderedReset) {
            Arrays.fill(phases, 0);
            Arrays.fill(envelopes, 0);
            Arrays.fill(heldVelocities, 0);
            renderedReset = reset;
        }
        double attackStep = 1.0 / (0.004 * sampleRate);
        double release = Math.exp(-1.0 / (0.06 * sampleRate));
        for (int channel = 0; channel < CHANNELS; channel++) {
            int program = programs.get(channel);
            double pitch = Math.pow(2, (bends.get(channel) - 8192) / 8192.0 / 6.0);
            boolean pedal = sustain.get(channel) != 0;
            for (int note = 0; note < NOTES; note++) {
                int index = channel * NOTES + note;
                int velocity = velocities.get(index);
                boolean held = velocity > 0 || pedal && heldVelocities[index] > 0;
                if (velocity > 0) {
                    heldVelocities[index] = velocity;
                }
                double envelope = envelopes[index];
                if (!held && envelope < 0.00001) {
                    heldVelocities[index] = 0;
                    continue;
                }
                double phase = phases[index];
                double frequency = frequencies[note] * pitch;
                double increment = frequency / sampleRate;
                double amplitude = gain * heldVelocities[index] / 127.0;
                for (int frame = 0; frame < frames; frame++) {
                    envelope = held ? Math.min(1, envelope + attackStep) : envelope * release;
                    double sample = waveform(program, phase, frequency) * envelope * amplitude;
                    for (int lane = 0; lane < output.length; lane++) {
                        output[lane][frame] += (float) sample;
                    }
                    phase += increment;
                    phase -= Math.floor(phase);
                }
                phases[index] = phase;
                envelopes[index] = envelope;
            }
        }
    }

    @RealTimeSafe
    private double waveform(int program, double phase, double frequency) {
        double sample = 0;
        double weightSum = 0;
        for (int harmonic = 1; harmonic <= 8; harmonic++) {
            if (frequency * harmonic >= sampleRate * 0.48) {
                break;
            }
            double weight = switch (program) {
                case 4 -> harmonic == 1 ? 1 : harmonic == 3 ? 0.3 : 0.03 / harmonic;
                case 19 -> harmonic == 1 || harmonic == 2 || harmonic == 4 ? 1.0 / harmonic : 0;
                case 32 -> 1.0 / (harmonic * harmonic * harmonic);
                case 48 -> 1.0 / harmonic;
                case 52 -> harmonic == 1 ? 1 : harmonic == 3 || harmonic == 5 ? 0.4 : 0;
                case 80 -> harmonic % 2 == 1 ? 1.0 / harmonic : 0;
                case 88 -> harmonic == 1 ? 1 : 0.1 / harmonic;
                default -> 1.0 / (harmonic * harmonic);
            };
            sample += weight * Math.sin(2 * Math.PI * harmonic * phase);
            weightSum += weight;
        }
        return weightSum == 0 ? 0 : sample / weightSum;
    }

    @Override public void allNotesOff() {
        for (int i = 0; i < velocities.length(); i++) {
            velocities.set(i, 0);
        }
        for (int i = 0; i < CHANNELS; i++) {
            sustain.set(i, 0);
        }
        resetGeneration.incrementAndGet();
    }
    @Override public void close() { allNotesOff(); initialized = false; }
    @Override public boolean isAvailable() { return true; }
    @Override public String getRendererName() { return "DAW Built-in Synthesis"; }
    @Override public void setGain(float gain) {
        if (!Float.isFinite(gain) || gain < 0) { throw new IllegalArgumentException("Invalid gain"); }
        this.gain = gain;
    }
    @Override public SoundFontInfo loadSoundFont(Path path) {
        throw new UnsupportedOperationException("Built-in synthesis does not load SoundFonts");
    }
    @Override public void unloadSoundFont(int id) {
        throw new UnsupportedOperationException("Built-in synthesis does not load SoundFonts");
    }
    @Override public List<SoundFontInfo> getLoadedSoundFonts() { return List.of(); }
    @Override public void setReverbEnabled(boolean enabled) {
        if (enabled) { throw new UnsupportedOperationException("Use a reverb insert on the keyboard channel"); }
    }
    @Override public void setChorusEnabled(boolean enabled) {
        if (enabled) { throw new UnsupportedOperationException("Use a chorus insert on the keyboard channel"); }
    }
    @Override public float[][] bounce(List<MidiEvent> events, int frames) {
        allNotesOff();
        for (MidiEvent event : events) { sendEvent(event); }
        float[][] output = new float[2][frames];
        render(output, frames);
        return output;
    }
}
