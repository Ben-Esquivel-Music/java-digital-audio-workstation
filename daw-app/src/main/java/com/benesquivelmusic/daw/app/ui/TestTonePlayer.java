package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

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
 * <p>Deliberately uses the Java Sound API directly rather than the engine's
 * streaming {@link com.benesquivelmusic.daw.sdk.audio.AudioBackend} seam so
 * that the test tone never clashes with the stream the engine may already
 * hold open (ASIO, PortAudio, or Java Sound).</p>
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

    /**
     * Resolves the persisted output-device selection to a Java Sound mixer, or
     * {@code null} to let the JVM pick its default.
     *
     * <p>The comparison is
     * {@link AudioDeviceInfo#isSelectionFor(String, String)} rather than a
     * plain {@code equals} (story 316 review). {@code preferredName} is the
     * value of the {@code audio.outputDevice} setting, which
     * {@code SettingsDialog.playTestTone()} reads straight out of the row and
     * hands down here; and {@code DeviceEnumerationTask} now offers &mdash; and
     * therefore persists &mdash; the host-API-qualified
     * {@link AudioDeviceInfo#qualifiedName()} form,
     * {@code "Speakers [Windows WASAPI]"}, for any device name that collides
     * across host APIs. Against {@code Mixer.Info#getName()}, which is always
     * the BARE name, {@code equals} silently failed on exactly those
     * selections, this method returned {@code null}, and {@code writeToLine}
     * fell through to {@code AudioSystem.getSourceDataLine(format)} &mdash; so
     * the tone the user pressed to verify one endpoint played out of a
     * different one. A test tone that lies about which device it used is worse
     * than no test tone.</p>
     *
     * <p>{@code isSelectionFor} is the single definition of "this persisted
     * selection names that device" and accepts both the bare and the qualified
     * form, so pre-existing settings keep resolving unchanged. Accepting a
     * qualified selection against a bare mixer name is right here even though
     * the qualification was minted from another backend's enumeration: Java
     * Sound stamps one constant host API ({@code JavaxSoundBackend.NAME}) on
     * everything it enumerates and lists each mixer once, so a suffix can
     * never be the thing that picks between two Java Sound mixers. Where two
     * mixers genuinely share a bare name, this returns the first &mdash; but
     * nothing in {@code Mixer.Info} distinguishes them, so there is no better
     * answer to give. That is the opposite trade from
     * {@code CallbackBackendAdapter}, which searches a snapshot that really
     * does hold one entry per host API and so must try the exact qualified
     * match first and refuse a bare name that hits more than one.</p>
     *
     * @param preferredName the persisted selection; blank or null means "JVM
     *                      default"
     * @return the matching mixer, or {@code null} for the JVM default
     */
    private static Mixer.Info resolveMixerInfo(String preferredName) {
        return resolveMixerInfo(preferredName, AudioSystem.getMixerInfo());
    }

    /**
     * The matching rule of {@link #resolveMixerInfo(String)} over an explicit
     * mixer list &mdash; the test seam (story 316 review).
     *
     * <p>The list is a parameter rather than being read from
     * {@link AudioSystem} inside, so the rule can be pinned without audio
     * hardware. It has to be: the resolution this method owns is
     * bare-vs-qualified string matching, and a test that could only run where
     * {@code AudioSystem.getMixerInfo()} returns something would be skipped on
     * the CI host (ubuntu-latest, no sound card) &mdash; i.e. skipped exactly
     * where regressions get caught.</p>
     *
     * @param preferredName the persisted selection; blank or null means "JVM
     *                      default"
     * @param mixers        the mixers to search
     * @return the matching mixer, or {@code null} for the JVM default
     */
    static Mixer.Info resolveMixerInfo(String preferredName, Mixer.Info[] mixers) {
        if (preferredName == null || preferredName.isBlank()) {
            return null;
        }
        for (Mixer.Info info : mixers) {
            if (AudioDeviceInfo.isSelectionFor(preferredName, info.getName())) {
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
