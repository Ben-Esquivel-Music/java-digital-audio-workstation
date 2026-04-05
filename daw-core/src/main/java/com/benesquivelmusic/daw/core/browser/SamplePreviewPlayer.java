package com.benesquivelmusic.daw.core.browser;

import com.benesquivelmusic.daw.core.audioimport.WavFileReader;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Plays audio file previews through the system's default audio output.
 *
 * <p>Supports play and stop operations for auditioning samples in the
 * browser panel. Only one preview can play at a time — starting a new
 * preview automatically stops any currently playing one.</p>
 *
 * <p>Preview volume is controllable via {@link #setVolume(double)} with
 * a range of 0.0 (silent) to 1.0 (full volume).</p>
 *
 * <p>Currently supports WAV files only.</p>
 */
public final class SamplePreviewPlayer {

    private static final Logger LOG = Logger.getLogger(SamplePreviewPlayer.class.getName());

    private static final int OUTPUT_SAMPLE_RATE = 44100;
    private static final int OUTPUT_BIT_DEPTH = 16;
    private static final int BUFFER_FRAMES = 4096;

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicReference<Thread> playbackThread = new AtomicReference<>(null);
    private volatile double volume = 1.0;
    private volatile Runnable onPlaybackFinished;

    /**
     * Returns whether a preview is currently playing.
     *
     * @return {@code true} if audio is playing
     */
    public boolean isPlaying() {
        return playing.get();
    }

    /**
     * Returns the current preview volume.
     *
     * @return the volume between 0.0 and 1.0
     */
    public double getVolume() {
        return volume;
    }

    /**
     * Sets the preview volume.
     *
     * @param volume the volume between 0.0 and 1.0
     */
    public void setVolume(double volume) {
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        this.volume = volume;
    }

    /**
     * Sets a callback invoked when playback finishes (naturally or stopped).
     *
     * @param callback the callback to invoke, or {@code null} to clear
     */
    public void setOnPlaybackFinished(Runnable callback) {
        this.onPlaybackFinished = callback;
    }

    /**
     * Starts playing a preview of the given audio file.
     *
     * <p>If a preview is already playing, it is stopped first. Playback
     * runs on a dedicated daemon thread and does not block the caller.</p>
     *
     * @param filePath the path to the audio file to preview
     */
    public void play(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");
        stop();

        stopRequested.set(false);

        Thread thread = Thread.ofPlatform()
                .daemon(true)
                .name("sample-preview-player")
                .start(() -> playInternal(filePath));
        playbackThread.set(thread);
    }

    /**
     * Stops any currently playing preview.
     */
    public void stop() {
        stopRequested.set(true);
        Thread thread = playbackThread.getAndSet(null);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        playing.set(false);
    }

    private void playInternal(Path filePath) {
        playing.set(true);
        try {
            WavFileReader.WavReadResult readResult = WavFileReader.read(filePath);
            float[][] audioData = readResult.audioData();
            int channels = readResult.channels();
            int outputChannels = Math.min(channels, 2);

            AudioFormat format = new AudioFormat(
                    OUTPUT_SAMPLE_RATE,
                    OUTPUT_BIT_DEPTH,
                    outputChannels,
                    true,
                    false);

            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                line.open(format, BUFFER_FRAMES * outputChannels * 2);
                line.start();

                int totalFrames = audioData[0].length;
                int bytesPerFrame = outputChannels * 2;
                byte[] buffer = new byte[BUFFER_FRAMES * bytesPerFrame];
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

                int frameIndex = 0;
                while (frameIndex < totalFrames && !stopRequested.get()) {
                    int framesThisBatch = Math.min(BUFFER_FRAMES, totalFrames - frameIndex);
                    byteBuffer.clear();

                    double currentVolume = volume;
                    for (int i = 0; i < framesThisBatch; i++) {
                        for (int ch = 0; ch < outputChannels; ch++) {
                            float sample = audioData[ch][frameIndex + i] * (float) currentVolume;
                            sample = Math.max(-1.0f, Math.min(1.0f, sample));
                            short shortSample = (short) (sample * 32767);
                            byteBuffer.putShort(shortSample);
                        }
                    }

                    int bytesToWrite = framesThisBatch * bytesPerFrame;
                    line.write(buffer, 0, bytesToWrite);
                    frameIndex += framesThisBatch;
                }

                if (!stopRequested.get()) {
                    line.drain();
                }
                line.stop();
            }
        } catch (IOException | IllegalArgumentException | LineUnavailableException e) {
            LOG.log(Level.WARNING, "Preview playback failed for: " + filePath, e);
        } finally {
            playing.set(false);
            playbackThread.set(null);
            Runnable callback = onPlaybackFinished;
            if (callback != null) {
                callback.run();
            }
        }
    }
}
