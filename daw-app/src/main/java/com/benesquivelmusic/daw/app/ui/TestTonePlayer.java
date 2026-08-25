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
     * Plays a test tone on a background thread. Returns as soon as the
     * selection has resolved; the tone fades in and out to avoid clicks.
     *
     * <p>The selection is resolved HERE, on the caller's thread, before the
     * write is handed to the executor. That ordering is load-bearing: it is
     * what lets the stale-selection refusal below propagate to
     * {@code SettingsDialog}, which runs {@code controller.playTestTone}
     * inside a {@code catch (RuntimeException)} and shows the message. Opening
     * the line happens on the executor; a {@link LineUnavailableException} or
     * {@link IllegalArgumentException} there is logged at WARNING from that
     * thread and is otherwise unobservable &mdash; it is thrown into the
     * {@code Future} returned by {@code executor.submit}, which nothing
     * reads.</p>
     *
     * @param preferredOutputDeviceName the persisted selection &mdash; a bare
     *                                  {@code Mixer.Info#getName()} or its
     *                                  host-API-qualified form &mdash; or empty
     *                                  to use the JVM default
     * @throws IllegalArgumentException synchronously, when the selection is
     *                                  non-blank and names no Java Sound mixer
     *                                  (story 316 review follow-up); the tone
     *                                  is NOT played on the JVM default instead
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
     * Resolves the persisted output-device selection to a Java Sound mixer.
     * The rule as it stands: blank or {@code null} means "JVM default" and
     * yields {@code null}; a non-blank selection must name an enumerated mixer,
     * and one that names none is REFUSED with an
     * {@link IllegalArgumentException} rather than being played on the JVM
     * default (story 316 review follow-up, last paragraph).
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
     * <p>Refusing a non-blank selection that matches nothing is the story 316
     * review follow-up. The qualified-name fix above closed one road to the
     * JVM default; a STALE selection &mdash; a device unplugged since it was
     * chosen, or a name persisted from another backend's enumeration that Java
     * Sound never lists &mdash; walked the same road: this method returned
     * {@code null} for it, and {@code null} is precisely what
     * {@code writeToLine} reads as "use
     * {@code AudioSystem.getSourceDataLine(format)}". The two meanings of
     * {@code null} had to be split, and only blank may carry the "default"
     * one. The refusal is thrown here rather than from {@code writeToLine}
     * because {@link #play(String)} resolves on the caller's thread before it
     * submits to the executor, and only a throw on that thread reaches the
     * user; see {@code play}. The honest consequence: the refusal also fires
     * for EVERY selection made under a backend other than Java Sound &mdash; an
     * ASIO driver name ({@code "Focusrite USB ASIO"}, from {@code AsioBackend}'s
     * enumeration) never coincides with a {@code Mixer.Info} name (on Windows
     * those are DirectSound endpoint names), and PortAudio's MME entries are
     * truncated to 31 characters &mdash; so with ASIO selected the test tone is
     * refused with a notice saying so rather than played on the JVM default.
     * That is the story 098 contract ("plays a short tone through the selected
     * output") kept honestly, and it is why the message states the fact and
     * offers no remedy: re-choosing the same driver reproduces the refusal.</p>
     *
     * @param preferredName the persisted selection; blank or null means "JVM
     *                      default"
     * @return the matching mixer, or {@code null} for the JVM default
     * @throws IllegalArgumentException if {@code preferredName} is non-blank
     *                                  and names no enumerated mixer
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
     * @return the matching mixer, or {@code null} for the JVM default (blank
     *         or null selection only)
     * @throws IllegalArgumentException if {@code preferredName} is non-blank
     *                                  and names none of {@code mixers}
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
        // IllegalArgumentException, not IllegalStateException: the fault is in
        // the argument (a selection naming nothing Java Sound enumerates), not
        // in this player's state.
        throw new IllegalArgumentException("no Java Sound output is named \""
                + preferredName + "\"; the test tone plays through Java Sound only");
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
