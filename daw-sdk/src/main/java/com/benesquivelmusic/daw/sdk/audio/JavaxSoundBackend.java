package com.benesquivelmusic.daw.sdk.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Cross-platform {@link AudioBackend} built on the JDK's
 * {@code javax.sound.sampled} API.
 *
 * <p>Always available. Latency is higher than a native driver
 * (typically 30–50&nbsp;ms on Windows) which is why this backend is the
 * graceful-fallback target chosen by {@link AudioBackendSelector} when
 * {@link AsioBackend}, {@link CoreAudioBackend}, {@link WasapiBackend},
 * or {@link JackBackend} cannot open a stream on the current host.</p>
 */
public final class JavaxSoundBackend implements AudioBackend {

    /** Backend name. */
    public static final String NAME = "Java Sound";

    private final AudioBackendSupport support = new AudioBackendSupport();
    private SourceDataLine outputLine;
    private TargetDataLine inputLine;
    private Thread captureThread;

    /** Creates a new Java Sound backend. */
    public JavaxSoundBackend() {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        for (int i = 0; i < infos.length; i++) {
            Mixer mixer = AudioSystem.getMixer(infos[i]);
            int maxIn = 0;
            int maxOut = 0;
            for (Line.Info li : mixer.getTargetLineInfo()) {
                if (li instanceof DataLine.Info dli) {
                    maxIn = Math.max(maxIn, maxChannels(dli));
                }
            }
            for (Line.Info li : mixer.getSourceLineInfo()) {
                if (li instanceof DataLine.Info dli) {
                    maxOut = Math.max(maxOut, maxChannels(dli));
                }
            }
            devices.add(new AudioDeviceInfo(
                    i,
                    infos[i].getName(),
                    NAME,
                    maxIn,
                    maxOut,
                    44_100.0,
                    List.of(SampleRate.HZ_44100, SampleRate.HZ_48000),
                    20.0,
                    20.0));
        }
        return Collections.unmodifiableList(devices);
    }

    private static int maxChannels(DataLine.Info info) {
        int max = 0;
        for (javax.sound.sampled.AudioFormat f : info.getFormats()) {
            if (f.getChannels() > max) {
                max = f.getChannels();
            }
        }
        return Math.max(max, 2);
    }

    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        support.markOpen(format, bufferFrames);
        javax.sound.sampled.AudioFormat jFormat = new javax.sound.sampled.AudioFormat(
                (float) format.sampleRate(),
                format.bitDepth(),
                format.channels(),
                true,
                false);
        try {
            this.outputLine = AudioSystem.getSourceDataLine(jFormat);
            this.outputLine.open(jFormat, bufferFrames * format.bytesPerFrame());
            this.outputLine.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            // Output-only not available — proceed; output will be a no-op.
            this.outputLine = null;
        }
        try {
            this.inputLine = AudioSystem.getTargetDataLine(jFormat);
            this.inputLine.open(jFormat, bufferFrames * format.bytesPerFrame());
            this.inputLine.start();
            startCapture(format, bufferFrames);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            this.inputLine = null;
        }
    }

    private void startCapture(AudioFormat format, int bufferFrames) {
        final TargetDataLine line = this.inputLine;
        final int bytes = bufferFrames * format.bytesPerFrame();
        Thread t = new Thread(() -> {
            byte[] buf = new byte[bytes];
            while (support.isOpen() && !Thread.currentThread().isInterrupted()) {
                int read = line.read(buf, 0, buf.length);
                if (read <= 0) {
                    break;
                }
                AudioBlock block = decodePcm16(buf, read, format);
                support.publishInput(block);
            }
        }, "javax-sound-capture");
        t.setDaemon(true);
        this.captureThread = t;
        t.start();
    }

    static AudioBlock decodePcm16(byte[] pcm, int bytes, AudioFormat format) {
        ShortBuffer sb = ByteBuffer.wrap(pcm, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int shorts = sb.remaining();
        float[] samples = new float[shorts];
        for (int i = 0; i < shorts; i++) {
            samples[i] = sb.get(i) / 32768.0f;
        }
        int frames = shorts / format.channels();
        return new AudioBlock(format.sampleRate(), format.channels(), frames, samples);
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    @Override
    public void sink(AudioBlock block) {
        support.validateOutgoing(block);
        SourceDataLine line = this.outputLine;
        if (!support.isOpen() || line == null) {
            return;
        }
        AudioFormat fmt = support.format();
        if (fmt == null) {
            return;
        }
        byte[] pcm = encodePcm16(block, fmt.bitDepth());
        line.write(pcm, 0, pcm.length);
    }

    static byte[] encodePcm16(AudioBlock block, int bitDepth) {
        if (bitDepth != 16) {
            // Down-convert to 16-bit LE for simplicity; the SDK allows any bit
            // depth but javax.sound.sampled output is configured for 16-bit here.
            // Higher bit depths are the domain of native backends.
            throw new IllegalArgumentException(
                    "JavaxSoundBackend only supports 16-bit output, got " + bitDepth);
        }
        float[] s = block.samples();
        byte[] out = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            int v = Math.round(Math.max(-1.0f, Math.min(1.0f, s[i])) * 32767.0f);
            out[2 * i]     = (byte) (v & 0xFF);
            out[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    @Override
    public boolean isOpen() {
        return support.isOpen();
    }

    @Override
    public void close() {
        support.close();
        Thread t = this.captureThread;
        if (t != null) {
            t.interrupt();
            this.captureThread = null;
        }
        if (inputLine != null) {
            try { inputLine.stop(); inputLine.close(); } catch (RuntimeException ignored) { /* swallow */ }
            inputLine = null;
        }
        if (outputLine != null) {
            try { outputLine.drain(); outputLine.stop(); outputLine.close(); } catch (RuntimeException ignored) { /* swallow */ }
            outputLine = null;
        }
    }
}
