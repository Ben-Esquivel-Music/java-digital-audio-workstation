package com.benesquivelmusic.daw.core.audio.javasound;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamCallback;
import com.benesquivelmusic.daw.sdk.audio.AudioStreamConfig;
import com.benesquivelmusic.daw.sdk.audio.LatencyInfo;
import com.benesquivelmusic.daw.sdk.audio.NativeAudioBackend;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fallback audio backend using the Java Sound API ({@code javax.sound.sampled}).
 *
 * <p>This backend is always available on any JVM but typically has higher
 * latency (20–50 ms+) compared to native backends like PortAudio. It serves
 * as a fallback when no native audio library is installed.</p>
 *
 * <p>The Java Sound API does not support callback-driven I/O natively. This
 * backend simulates it by running a dedicated virtual thread that reads/writes
 * audio in a loop, invoking the {@link AudioStreamCallback} on each iteration.</p>
 */
public final class JavaSoundBackend implements NativeAudioBackend {

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean streamActive = new AtomicBoolean(false);

    private List<AudioDeviceInfo> cachedDevices;
    private AudioStreamConfig currentConfig;
    private AudioStreamCallback currentCallback;
    private Thread audioThread;
    private SourceDataLine outputLine;
    private TargetDataLine inputLine;

    @Override
    public void initialize() {
        initialized.set(true);
    }

    @Override
    public List<AudioDeviceInfo> getAvailableDevices() {
        ensureInitialized();
        if (cachedDevices != null) {
            return cachedDevices;
        }

        ArrayList<AudioDeviceInfo> devices = new ArrayList<AudioDeviceInfo>();
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();

        for (int i = 0; i < mixerInfos.length; i++) {
            Mixer mixer = AudioSystem.getMixer(mixerInfos[i]);
            int maxInputChannels = countMaxChannels(mixer, true);
            int maxOutputChannels = countMaxChannels(mixer, false);

            if (maxInputChannels > 0 || maxOutputChannels > 0) {
                devices.add(new AudioDeviceInfo(
                        i,
                        mixerInfos[i].getName(),
                        "Java Sound",
                        maxInputChannels,
                        maxOutputChannels,
                        44_100.0,
                        List.of(SampleRate.HZ_44100, SampleRate.HZ_48000),
                        // Java Sound doesn't report native latency; use typical values
                        23.0,
                        23.0
                ));
            }
        }

        cachedDevices = Collections.unmodifiableList(devices);
        return cachedDevices;
    }

    @Override
    public AudioDeviceInfo getDefaultInputDevice() {
        return getAvailableDevices().stream()
                .filter(AudioDeviceInfo::supportsInput)
                .findFirst()
                .orElse(null);
    }

    @Override
    public AudioDeviceInfo getDefaultOutputDevice() {
        return getAvailableDevices().stream()
                .filter(AudioDeviceInfo::supportsOutput)
                .findFirst()
                .orElse(null);
    }

    @Override
    public void openStream(AudioStreamConfig config, AudioStreamCallback callback) {
        ensureInitialized();
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (currentConfig != null) {
            throw new IllegalStateException("A stream is already open; close it first");
        }

        currentConfig = config;
        currentCallback = callback;

        javax.sound.sampled.AudioFormat javaFormat = new javax.sound.sampled.AudioFormat(
                config.sampleRate().getHz(),
                32,   // 32-bit float
                Math.max(config.inputChannels(), config.outputChannels()),
                true,  // signed
                false  // little-endian
        );

        try {
            if (config.hasOutput()) {
                DataLine.Info outputInfo = new DataLine.Info(SourceDataLine.class, javaFormat);
                outputLine = (SourceDataLine) AudioSystem.getLine(outputInfo);
                int bufferBytes = config.bufferSize().getFrames() * config.outputChannels() * Float.BYTES;
                outputLine.open(javaFormat, bufferBytes * 2);
            }
            if (config.hasInput()) {
                DataLine.Info inputInfo = new DataLine.Info(TargetDataLine.class, javaFormat);
                inputLine = (TargetDataLine) AudioSystem.getLine(inputInfo);
                int bufferBytes = config.bufferSize().getFrames() * config.inputChannels() * Float.BYTES;
                inputLine.open(javaFormat, bufferBytes * 2);
            }
        } catch (Exception e) {
            // Clean up any partially opened lines before propagating
            if (outputLine != null) {
                outputLine.close();
                outputLine = null;
            }
            if (inputLine != null) {
                inputLine.close();
                inputLine = null;
            }
            currentConfig = null;
            currentCallback = null;
            throw new AudioBackendException("Failed to open Java Sound stream: " + e.getMessage(), e);
        }
    }

    @Override
    public void startStream() {
        ensureStreamOpen();

        if (outputLine != null) {
            outputLine.start();
        }
        if (inputLine != null) {
            inputLine.start();
        }

        streamActive.set(true);

        // Use a virtual thread for the audio processing loop (JEP 444, final in JDK 21)
        audioThread = Thread.ofVirtual().name("java-sound-audio-io").start(this::audioLoop);
    }

    @Override
    public void stopStream() {
        if (!streamActive.compareAndSet(true, false)) {
            return;
        }

        if (audioThread != null) {
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioThread = null;
        }

        if (outputLine != null) {
            outputLine.stop();
        }
        if (inputLine != null) {
            inputLine.stop();
        }
    }

    @Override
    public void closeStream() {
        if (currentConfig == null) {
            return;
        }
        if (streamActive.get()) {
            stopStream();
        }

        if (outputLine != null) {
            outputLine.close();
            outputLine = null;
        }
        if (inputLine != null) {
            inputLine.close();
            inputLine = null;
        }

        currentConfig = null;
        currentCallback = null;
    }

    @Override
    public LatencyInfo getLatencyInfo() {
        ensureStreamOpen();

        int bufferFrames = currentConfig.bufferSize().getFrames();
        double sampleRate = currentConfig.sampleRate().getHz();

        // Java Sound latency is typically the buffer size * 2 (double-buffering)
        double bufferLatencyMs = (bufferFrames * 2.0 / sampleRate) * 1000.0;
        double inputLatencyMs = currentConfig.hasInput() ? bufferLatencyMs : 0.0;
        double outputLatencyMs = currentConfig.hasOutput() ? bufferLatencyMs : 0.0;

        return LatencyInfo.of(inputLatencyMs, outputLatencyMs, bufferFrames, sampleRate);
    }

    @Override
    public boolean isStreamActive() {
        return streamActive.get();
    }

    @Override
    public String getBackendName() {
        return "Java Sound";
    }

    @Override
    public boolean isAvailable() {
        return true; // Java Sound is always available
    }

    @Override
    public void close() {
        closeStream();
        initialized.set(false);
        cachedDevices = null;
    }

    // --- Internal helpers ---

    private void ensureInitialized() {
        if (!initialized.get()) {
            throw new IllegalStateException("Java Sound backend is not initialized; call initialize() first");
        }
    }

    private void ensureStreamOpen() {
        if (currentConfig == null) {
            throw new IllegalStateException("No stream is open");
        }
    }

    private void audioLoop() {
        int frames = currentConfig.bufferSize().getFrames();
        int inCh = currentConfig.inputChannels();
        int outCh = currentConfig.outputChannels();

        float[][] inputBuffer = inCh > 0 ? new float[inCh][frames] : new float[0][];
        float[][] outputBuffer = outCh > 0 ? new float[outCh][frames] : new float[0][];

        byte[] inputBytes = inCh > 0 ? new byte[frames * inCh * Float.BYTES] : null;
        byte[] outputBytes = outCh > 0 ? new byte[frames * outCh * Float.BYTES] : null;

        while (streamActive.get()) {
            // Read input (if configured)
            if (inputLine != null && inputBytes != null) {
                int bytesRead = inputLine.read(inputBytes, 0, inputBytes.length);
                if (bytesRead > 0) {
                    deinterleaveFloat(inputBytes, inputBuffer, inCh, frames);
                }
            }

            // Invoke the callback
            currentCallback.process(inputBuffer, outputBuffer, frames);

            // Write output (if configured)
            if (outputLine != null && outputBytes != null) {
                interleaveFloat(outputBuffer, outputBytes, outCh, frames);
                outputLine.write(outputBytes, 0, outputBytes.length);
            }
        }
    }

    /**
     * Converts interleaved byte data (little-endian float32) to de-interleaved float[][].
     */
    private static void deinterleaveFloat(byte[] src, float[][] dst, int channels, int frames) {
        for (int f = 0; f < frames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                int byteIndex = (f * channels + ch) * Float.BYTES;
                int bits = (src[byteIndex] & 0xFF)
                        | ((src[byteIndex + 1] & 0xFF) << 8)
                        | ((src[byteIndex + 2] & 0xFF) << 16)
                        | ((src[byteIndex + 3] & 0xFF) << 24);
                dst[ch][f] = Float.intBitsToFloat(bits);
            }
        }
    }

    /**
     * Converts de-interleaved float[][] to interleaved byte data (little-endian float32).
     */
    private static void interleaveFloat(float[][] src, byte[] dst, int channels, int frames) {
        for (int f = 0; f < frames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                int bits = Float.floatToIntBits(src[ch][f]);
                int byteIndex = (f * channels + ch) * Float.BYTES;
                dst[byteIndex] = (byte) (bits & 0xFF);
                dst[byteIndex + 1] = (byte) ((bits >> 8) & 0xFF);
                dst[byteIndex + 2] = (byte) ((bits >> 16) & 0xFF);
                dst[byteIndex + 3] = (byte) ((bits >> 24) & 0xFF);
            }
        }
    }

    private static int countMaxChannels(Mixer mixer, boolean input) {
        try {
            javax.sound.sampled.Line.Info[] lineInfos = input ? mixer.getTargetLineInfo() : mixer.getSourceLineInfo();
            int maxChannels = 0;
            for (javax.sound.sampled.Line.Info lineInfo : lineInfos) {
                if (lineInfo instanceof DataLine.Info dataLineInfo) {
                    for (javax.sound.sampled.AudioFormat format : dataLineInfo.getFormats()) {
                        maxChannels = Math.max(maxChannels, format.getChannels());
                    }
                }
            }
            return maxChannels;
        } catch (Exception _) {
            return 0;
        }
    }
}
