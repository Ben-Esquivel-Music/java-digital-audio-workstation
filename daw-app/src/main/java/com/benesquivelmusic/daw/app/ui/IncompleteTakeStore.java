package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe in-memory store for the float samples being captured by the
 * recording thread, with the ability to flush whatever has accumulated to a
 * timestamped {@code .wav} file when the audio device disappears.
 *
 * <p>Used by {@link DefaultAudioEngineController} to salvage the in-flight
 * recording take when an {@code AudioDeviceEvent.DeviceRemoved} is received:
 * if any samples have been captured but not yet committed to the project,
 * the controller calls {@link #flushIfNotEmpty(DeviceId, AudioFormat)} which
 * writes them under {@code <projectRoot>/.daw/incomplete-takes/} so the user
 * can review the recovered take after the device returns.</p>
 *
 * <p>{@link #appendCapturedFrames(float[][], int)} appends data to an in-memory
 * buffer and does not perform disk I/O, but this class is not lock-free or
 * allocation-free: synchronization and buffer growth may occur. Disk I/O
 * happens only inside {@link #flushIfNotEmpty(DeviceId, AudioFormat)}.</p>
 */
public final class IncompleteTakeStore {

    private static final Logger LOG = Logger.getLogger(IncompleteTakeStore.class.getName());

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path projectRoot;
    private final ReentrantLock lock = new ReentrantLock();
    private final ByteArrayOutputStream pcmBytes = new ByteArrayOutputStream();
    private int channels;

    /**
     * Creates a store rooted at {@code projectRoot}. The
     * {@code .daw/incomplete-takes/} subdirectory is created on demand on
     * the first successful flush.
     *
     * @param projectRoot directory representing the open project; must not be null
     */
    public IncompleteTakeStore(Path projectRoot) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot must not be null");
    }

    /**
     * Appends one block of captured audio to the in-memory take buffer.
     * Safe to call from the audio callback thread.
     *
     * @param inputBuffer captured samples laid out as {@code [channel][frame]};
     *                    must not be null
     * @param numFrames   number of frames in {@code inputBuffer}; must be
     *                    non-negative
     */
    public void appendCapturedFrames(float[][] inputBuffer, int numFrames) {
        Objects.requireNonNull(inputBuffer, "inputBuffer must not be null");
        if (numFrames < 0) {
            throw new IllegalArgumentException("numFrames must not be negative: " + numFrames);
        }
        if (numFrames == 0 || inputBuffer.length == 0) {
            return;
        }
        int ch = inputBuffer.length;
        lock.lock();
        try {
            this.channels = ch;
            // Encode interleaved little-endian 16-bit PCM (the canonical
            // intermediate format for the WAV writer below).
            byte[] scratch = new byte[numFrames * ch * 2];
            int idx = 0;
            for (int f = 0; f < numFrames; f++) {
                for (int c = 0; c < ch; c++) {
                    float sample = inputBuffer[c][f];
                    if (sample > 1.0f) sample = 1.0f;
                    else if (sample < -1.0f) sample = -1.0f;
                    short pcm = (short) Math.round(sample * 32767.0f);
                    scratch[idx++] = (byte) (pcm & 0xFF);
                    scratch[idx++] = (byte) ((pcm >>> 8) & 0xFF);
                }
            }
            pcmBytes.write(scratch, 0, scratch.length);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of bytes currently buffered (encoded as
     * little-endian 16-bit PCM). Useful for tests.
     *
     * @return buffered byte count (never negative)
     */
    public int bufferedByteCount() {
        lock.lock();
        try {
            return pcmBytes.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes the in-memory take to a timestamped {@code .wav} file under
     * {@code <projectRoot>/.daw/incomplete-takes/} and returns the file
     * path, or {@link java.util.Optional#empty()} when nothing has been
     * captured yet. The buffer is reset on success so a subsequent take
     * starts from zero.
     *
     * @param device device that was just lost (used to tag the filename so
     *               multiple parallel takes from different devices don't
     *               collide); must not be null
     * @param format audio format the buffer was captured in; must not be null
     * @return absolute path of the written file, or empty when nothing was
     *         buffered; never null
     */
    public java.util.Optional<Path> flushIfNotEmpty(DeviceId device, AudioFormat format) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        byte[] data;
        int channelCount;
        lock.lock();
        try {
            if (pcmBytes.size() == 0) {
                return java.util.Optional.empty();
            }
            data = pcmBytes.toByteArray();
            channelCount = Math.max(1, this.channels);
            pcmBytes.reset();
        } finally {
            lock.unlock();
        }

        try {
            Path dir = projectRoot.resolve(".daw").resolve("incomplete-takes");
            Files.createDirectories(dir);
            String safeName = device.name().replaceAll("[^A-Za-z0-9._-]", "_");
            String filename = "take-" + LocalDateTime.now().format(STAMP)
                    + "-" + safeName + ".wav";
            Path target = dir.resolve(filename);
            byte[] wav = wrapInWav(data, (int) format.sampleRate(), channelCount, 16);
            Files.write(target, wav);
            LOG.info("Persisted incomplete take to " + target + " (" + data.length + " bytes)");
            return java.util.Optional.of(target);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to persist incomplete take", e);
            return java.util.Optional.empty();
        }
    }

    private static byte[] wrapInWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int riffSize = 36 + pcm.length;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(riffSize);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);             // fmt chunk size
        header.putShort((short) 1);    // PCM
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
