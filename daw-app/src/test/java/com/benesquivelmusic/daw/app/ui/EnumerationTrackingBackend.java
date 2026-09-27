package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.CaptureRequirement;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import javafx.application.Platform;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A streaming {@link AudioBackend} (delegating to a {@link MockAudioBackend})
 * that records how often — and on which thread — {@link #listDevices()} was
 * called. Story 322 fix round (S7): device enumeration is a driver walk that,
 * on ASIO, blocks on the driver control thread, so neither the arm listener
 * nor the record-start flow may call it on the FX thread; the tests that pin
 * that ({@code ArrangementArmInputCheckOffFxTest},
 * {@code TransportControllerTest}) count with this backend.
 */
final class EnumerationTrackingBackend implements AudioBackend {

    private final MockAudioBackend delegate = new MockAudioBackend();

    /** Every {@link #listDevices()} call. */
    final AtomicInteger enumerations = new AtomicInteger();
    /** The {@link #listDevices()} calls made on the JavaFX Application Thread. */
    final AtomicInteger enumerationsOnFxThread = new AtomicInteger();

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        enumerations.incrementAndGet();
        if (Platform.isFxApplicationThread()) {
            enumerationsOnFxThread.incrementAndGet();
        }
        return delegate.listDevices();
    }

    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames) {
        delegate.open(device, format, bufferFrames);
    }

    @Override
    public void open(DeviceId device, AudioFormat format, int bufferFrames, CaptureRequirement capture) {
        delegate.open(device, format, bufferFrames);
    }

    @Override
    public int openedInputChannels() {
        return delegate.openedInputChannels();
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return delegate.inputBlocks();
    }

    @Override
    public void sink(AudioBlock block) {
        delegate.sink(block);
    }

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
