package com.benesquivelmusic.daw.sdk.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic {@link AudioBackend} implementation for offline tests.
 *
 * <p>A {@code MockAudioBackend} never touches real hardware: it plays
 * {@link #inputBlocks()} from a caller-supplied {@code byte[]} of
 * little-endian 16-bit PCM and captures every block written to
 * {@link #sink(AudioBlock)} into an internal {@code byte[]} that tests
 * can assert on. That is what makes it safe on headless CI runners
 * where no audio device is available.</p>
 *
 * <p>Integration tests use this backend instead of {@link JavaxSoundBackend}
 * so they produce bit-exact reproducible output without requiring
 * {@code xvfb} or a sound card on the runner.</p>
 */
public final class MockAudioBackend implements AudioBackend {

    /** Backend name. */
    public static final String NAME = "Mock";

    private final AudioBackendSupport support = new AudioBackendSupport();
    private final byte[] inputPcm;
    private final java.io.ByteArrayOutputStream outputPcm = new java.io.ByteArrayOutputStream();
    private final Map<Integer, java.io.ByteArrayOutputStream> directChannelOutput =
            new ConcurrentHashMap<>();
    private final AtomicInteger controlPanelInvocations = new AtomicInteger();
    private int inputCursor;
    private volatile BufferSizeRange bufferSizeRange = BufferSizeRange.DEFAULT_RANGE;
    private volatile Set<Integer> supportedSampleRates =
            Set.of(44_100, 48_000, 88_200, 96_000, 176_400, 192_000);

    /**
     * Creates a new mock backend with no pre-canned input audio. Useful when
     * the test only needs to assert on {@link #sink(AudioBlock)} output.
     */
    public MockAudioBackend() {
        this(new byte[0]);
    }

    /**
     * Creates a new mock backend seeded with the given PCM data that will
     * be streamed out through {@link #inputBlocks()} on each call to
     * {@link #pumpInput(int)}.
     *
     * @param inputPcm little-endian 16-bit PCM to replay as captured input;
     *                 must not be null (may be empty)
     */
    public MockAudioBackend(byte[] inputPcm) {
        this.inputPcm = Objects.requireNonNull(inputPcm, "inputPcm").clone();
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
        return List.of(new AudioDeviceInfo(
                0,
                "Mock Device",
                NAME,
                2,
                2,
                48_000.0,
                List.of(SampleRate.HZ_44100, SampleRate.HZ_48000),
                0.0,
                0.0));
    }

    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(format, "format must not be null");
        support.markOpen(format, bufferFrames);
        this.inputCursor = 0;
        this.outputPcm.reset();
        this.directChannelOutput.clear();
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return support.inputBlocks();
    }

    @Override
    public void sink(AudioBlock block) {
        support.validateOutgoing(block);
        if (!support.isOpen()) {
            return;
        }
        byte[] pcm = JavaxSoundBackend.encodePcm16(block, 16);
        outputPcm.write(pcm, 0, pcm.length);
    }

    @Override
    public void writeToChannel(int channelIndex, float[] monoSamples) {
        if (channelIndex < 0) {
            throw new IllegalArgumentException(
                    "channelIndex must not be negative: " + channelIndex);
        }
        Objects.requireNonNull(monoSamples, "monoSamples must not be null");
        if (!support.isOpen()) {
            return;
        }
        java.io.ByteArrayOutputStream buf =
                directChannelOutput.computeIfAbsent(
                        channelIndex, _ -> new java.io.ByteArrayOutputStream());
        // Capture as float-to-bytes in little-endian for easy bit-exact asserts.
        for (float sample : monoSamples) {
            int bits = Float.floatToRawIntBits(sample);
            buf.write(bits & 0xFF);
            buf.write((bits >>> 8) & 0xFF);
            buf.write((bits >>> 16) & 0xFF);
            buf.write((bits >>> 24) & 0xFF);
        }
    }

    @Override
    public boolean isOpen() {
        return support.isOpen();
    }

    @Override
    public Flow.Publisher<AudioDeviceEvent> deviceEvents() {
        return support.deviceEvents();
    }

    /**
     * Simulates the host OS reporting that {@code device} has just
     * appeared, so tests can drive
     * {@link AudioBackend#deviceEvents()} subscribers through the
     * "device returned, auto-reconnect" flow without real hardware.
     *
     * @param device the device whose arrival to publish; must not be null
     */
    public void simulateDeviceArrived(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        support.publishDeviceEvent(new AudioDeviceEvent.DeviceArrived(device));
    }

    /**
     * Simulates the host OS reporting that {@code device} has just gone
     * away (USB unplug, driver crash, device disabled). Tests use this
     * to drive the {@code DEVICE_LOST} transition in
     * {@code AudioEngineController} without real hardware.
     *
     * @param device the device whose removal to publish; must not be null
     */
    public void simulateDeviceRemoved(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        support.publishDeviceEvent(new AudioDeviceEvent.DeviceRemoved(device));
    }

    /**
     * Simulates the driver renegotiating the device's native format
     * (sample rate / channel count / buffer size change) mid-session.
     *
     * @param device    the affected device id; must not be null
     * @param newFormat the device's new native format; must not be null
     */
    public void simulateDeviceFormatChanged(DeviceId device, AudioFormat newFormat) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(newFormat, "newFormat must not be null");
        support.publishDeviceEvent(
                new AudioDeviceEvent.DeviceFormatChanged(device, newFormat));
    }

    /**
     * Returns a runnable that records the invocation. Tests can assert
     * the count via {@link #controlPanelInvocationCount()} and verify
     * that the dialog re-queries device capabilities after the panel
     * closes.
     */
    @Override
    public Optional<Runnable> openControlPanel() {
        return Optional.of(controlPanelInvocations::incrementAndGet);
    }

    /**
     * Number of times the runnable returned by {@link #openControlPanel()}
     * has been invoked since this backend was constructed.
     *
     * @return invocation count (never negative)
     */
    public int controlPanelInvocationCount() {
        return controlPanelInvocations.get();
    }

    /**
     * Returns the buffer-size range configured via
     * {@link #setBufferSizeRange(BufferSizeRange)}, defaulting to
     * {@link BufferSizeRange#DEFAULT_RANGE}. Tests use this to verify
     * the dialog correctly expands a driver-reported four-tuple into
     * a discrete dropdown menu.
     */
    @Override
    public BufferSizeRange bufferSizeRange(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return bufferSizeRange;
    }

    /**
     * Returns the sample-rate set configured via
     * {@link #setSupportedSampleRates(Set)}, defaulting to the
     * canonical rate list. Tests use this to verify the dialog shows
     * the union of canonical and device-reported rates, with
     * unsupported entries greyed out and tooltipped.
     */
    @Override
    public Set<Integer> supportedSampleRates(DeviceId device) {
        Objects.requireNonNull(device, "device must not be null");
        return supportedSampleRates;
    }

    /**
     * Configures the {@link BufferSizeRange} this mock will report
     * from {@link #bufferSizeRange(DeviceId)}. Tests use this to drive
     * the Audio Settings dialog through the same code paths it would
     * exercise against a real driver reporting non-power-of-two
     * granularity.
     *
     * @param range the range to report (must not be null)
     */
    public void setBufferSizeRange(BufferSizeRange range) {
        this.bufferSizeRange = Objects.requireNonNull(range, "range must not be null");
    }

    /**
     * Configures the sample-rate set this mock will report from
     * {@link #supportedSampleRates(DeviceId)}. Tests use this to drive
     * the Audio Settings dialog's persisted-rate-fallback path.
     *
     * @param rates the rates (in Hz) to report (must not be null;
     *              defensively copied)
     */
    public void setSupportedSampleRates(Set<Integer> rates) {
        this.supportedSampleRates =
                Set.copyOf(Objects.requireNonNull(rates, "rates must not be null"));
    }

    @Override
    public void close() {
        support.close();
    }

    /**
     * Deterministically emits one {@link AudioBlock} of {@code frames} sample
     * frames drawn from the seeded input PCM buffer. When the buffer is
     * exhausted the remainder of the block is zero-padded.
     *
     * @param frames number of sample frames to emit (must be positive)
     */
    public void pumpInput(int frames) {
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be positive: " + frames);
        }
        if (!support.isOpen()) {
            throw new IllegalStateException("pumpInput called before open()");
        }
        AudioFormat fmt = support.format();
        int channels = fmt.channels();
        int byteCount = frames * channels * 2; // 16-bit PCM
        byte[] slice = new byte[byteCount];
        int available = Math.max(0, Math.min(byteCount, inputPcm.length - inputCursor));
        if (available > 0) {
            System.arraycopy(inputPcm, inputCursor, slice, 0, available);
            inputCursor += available;
        }
        ShortBuffer sb = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        float[] samples = new float[frames * channels];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = sb.get(i) / 32768.0f;
        }
        support.publishInput(new AudioBlock(fmt.sampleRate(), channels, frames, samples));
    }

    /**
     * Returns a copy of every byte written to {@link #sink(AudioBlock)} since
     * the most recent {@link #open(DeviceId, AudioFormat, int)} call, encoded
     * as little-endian 16-bit PCM.
     *
     * @return recorded output PCM (never null)
     */
    public byte[] recordedOutput() {
        return outputPcm.toByteArray();
    }

    /**
     * Returns every sample written to
     * {@link #writeToChannel(int, float[])} for the given physical channel
     * since the most recent {@link #open(DeviceId, AudioFormat, int)}. Used
     * by tests that assert on the metronome's direct-to-hardware side
     * output (story 136).
     *
     * @param channelIndex 0-based output channel index
     * @return concatenated float samples in write order (never null; empty
     *         when the channel was never written to)
     */
    public float[] recordedChannelOutput(int channelIndex) {
        java.io.ByteArrayOutputStream buf = directChannelOutput.get(channelIndex);
        if (buf == null) {
            return new float[0];
        }
        byte[] bytes = buf.toByteArray();
        float[] out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) {
            int b0 = bytes[i * 4] & 0xFF;
            int b1 = bytes[i * 4 + 1] & 0xFF;
            int b2 = bytes[i * 4 + 2] & 0xFF;
            int b3 = bytes[i * 4 + 3] & 0xFF;
            int bits = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            out[i] = Float.intBitsToFloat(bits);
        }
        return out;
    }
}
