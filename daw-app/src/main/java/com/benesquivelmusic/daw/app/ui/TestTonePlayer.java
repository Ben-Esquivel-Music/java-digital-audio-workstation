package com.benesquivelmusic.daw.app.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays a short 440 Hz sine tone through {@code javax.sound.sampled} so
 * users can verify their audio output configuration without disturbing
 * the running DAW engine.
 *
 * <p>Deliberately uses the Java Sound API directly rather than the active
 * {@link com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend} so that
 * the test tone never clashes with an already-open PortAudio stream.</p>
 */
final class TestTonePlayer {

    private static final Logger LOG = Logger.getLogger(TestTonePlayer.class.getName());

    private static final float TONE_FREQUENCY_HZ = 440.0f;
    private static final float TONE_AMPLITUDE = 0.2f;
    private static final int TONE_DURATION_MS = 700;
    private static final int SAMPLE_RATE = 44_100;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 2;

    private final ScheduledExecutorService executor;

    TestTonePlayer() {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "daw-test-tone");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Plays a test tone on a background thread. Returns immediately; the
     * tone fades in and out to avoid clicks.
     *
     * @param preferredOutputDeviceName the preferred {@code Mixer.Info#getName()}
     *                                  value, or empty to use the JVM default
     * @throws RuntimeException if no suitable output line can be opened
     */
    void play(String preferredOutputDeviceName) {
        byte[] samples = generateSineBytes();
        AudioFormat format = new AudioFormat(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS, true, false);
        Mixer.Info mixerInfo = resolveMixerInfo(preferredOutputDeviceName);
        executor.submit(() -> writeToLine(samples, format, mixerInfo));
    }

    /** Shuts down the background executor. */
    void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static Mixer.Info resolveMixerInfo(String preferredName) {
        if (preferredName == null || preferredName.isBlank()) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (preferredName.equals(info.getName())) {
                return info;
            }
        }
        return null;
    }

    private static void writeToLine(byte[] samples, AudioFormat format, Mixer.Info mixerInfo) {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try (SourceDataLine line = mixerInfo != null
                ? (SourceDataLine) AudioSystem.getMixer(mixerInfo).getLine(info)
                : AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            line.write(samples, 0, samples.length);
            line.drain();
            line.stop();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Failed to play test tone", e);
            throw new RuntimeException("Failed to play test tone: " + e.getMessage(), e);
        }
    }

    private static byte[] generateSineBytes() {
        int totalFrames = SAMPLE_RATE * TONE_DURATION_MS / 1000;
        int fadeFrames = SAMPLE_RATE / 100; // 10 ms fade in/out
        byte[] out = new byte[totalFrames * CHANNELS * (BITS_PER_SAMPLE / 8)];
        double phaseStep = 2.0 * Math.PI * TONE_FREQUENCY_HZ / SAMPLE_RATE;
        double phase = 0.0;
        for (int frame = 0; frame < totalFrames; frame++) {
            double envelope = TONE_AMPLITUDE;
            if (frame < fadeFrames) {
                envelope *= (double) frame / fadeFrames;
            } else if (frame > totalFrames - fadeFrames) {
                envelope *= (double) (totalFrames - frame) / fadeFrames;
            }
            short sample = (short) Math.round(Math.sin(phase) * envelope * Short.MAX_VALUE);
            int base = frame * CHANNELS * (BITS_PER_SAMPLE / 8);
            for (int ch = 0; ch < CHANNELS; ch++) {
                int pos = base + ch * (BITS_PER_SAMPLE / 8);
                out[pos] = (byte) (sample & 0xFF);
                out[pos + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            phase += phaseStep;
            if (phase > 2.0 * Math.PI) {
                phase -= 2.0 * Math.PI;
            }
        }
        return out;
    }
}
