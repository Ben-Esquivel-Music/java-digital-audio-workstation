package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Deterministic spy implementing the (unsealed, story 316) SDK
 * {@link AudioBackend} directly: records every {@code open} with its device
 * / format / buffer size, records the open/close order in an optional
 * shared lifecycle log, counts sunk blocks, publishes test-driven capture
 * blocks, and paces the render pump with a short bounded park instead of
 * the default full-timeout wall-clock park — no real hardware is ever
 * touched.
 *
 * <p>Shared story-316 fixture: {@link Story316StreamingSeamTest} (the
 * story's binding acceptance tests 1–4) and
 * {@link DefaultAudioEngineControllerTest}'s driver-initiated
 * format-change reopen test both stream through it.</p>
 */
final class SpyStreamingBackend implements AudioBackend {

    record OpenRecord(
            DeviceId device,
            com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
            int bufferFrames) {
    }

    private final String name;
    private final List<String> lifecycleLog;
    private final List<OpenRecord> opens =
            Collections.synchronizedList(new ArrayList<>());
    private final Semaphore sunkBlocks = new Semaphore(0);
    private final SubmissionPublisher<AudioBlock> inputPublisher =
            new SubmissionPublisher<>();
    private final SubmissionPublisher<
            com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent> deviceEventPublisher =
            new SubmissionPublisher<>();
    private volatile boolean open;
    private volatile RuntimeException openFailure;
    private volatile boolean available = true;
    private volatile boolean streamingSupported = true;
    private volatile List<AudioDeviceInfo> devices = List.of();
    private final AtomicInteger closeCount = new AtomicInteger();
    private volatile Runnable controlPanelAction;
    private final java.util.concurrent.atomic.AtomicReference<Runnable> nextCloseHook =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Wedges {@link #awaitSinkCapacity(long)} the way
     * {@code AudioEngineOutputTest.SynchronousTestBackend} does, so the
     * engine's bounded pump join times out and {@code stopAudioOutput()}
     * reports UNCONFIRMED quiescence — the one condition under which the
     * controller must not close this instance (story 316 review).
     */
    volatile boolean blockAwait;

    /** Counts entries into the wedge so tests can await it, never sleep. */
    final AtomicInteger blockedAwaitEntries = new AtomicInteger();

    SpyStreamingBackend(String name) {
        this(name, Collections.synchronizedList(new ArrayList<>()));
    }

    SpyStreamingBackend(String name, List<String> lifecycleLog) {
        this.name = name;
        this.lifecycleLog = lifecycleLog;
    }

    void failOpensWith(RuntimeException failure) {
        this.openFailure = failure;
    }

    /** Fails the controller's availability half of the provisioning gate. */
    void setAvailable(boolean available) {
        this.available = available;
    }

    /**
     * Fails the controller's streaming half of the provisioning gate — the
     * WASAPI / CoreAudio / JACK shape: present on the host, but with no
     * implemented streaming path.
     */
    void setSupportsStreaming(boolean streamingSupported) {
        this.streamingSupported = streamingSupported;
    }

    int closeCount() {
        return closeCount.get();
    }

    List<OpenRecord> opens() {
        synchronized (opens) {
            return List.copyOf(opens);
        }
    }

    boolean awaitSunkBlocks(int count, long timeout, TimeUnit unit)
            throws InterruptedException {
        return sunkBlocks.tryAcquire(count, timeout, unit);
    }

    void publishInput(AudioBlock block) {
        inputPublisher.submit(block);
    }

    /**
     * Announces a hot-unplug on THIS backend's own device-event stream.
     * Story 316 review: the only honest way to prove the controller's
     * subscription actually moved to the rung that won the open — a test
     * that only reads the reported active device would pass on a controller
     * that updated the field and left the subscription behind.
     */
    void simulateDeviceRemoved(DeviceId device) {
        publishDeviceEvent(
                new com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent.DeviceRemoved(device));
    }

    /**
     * Announces any driver-initiated event on THIS backend's own stream —
     * the production shape, since the controller subscribes to
     * {@link #deviceEvents()}. A test that drove an EXTERNAL publisher
     * through the package-private three-argument bind is now silently
     * unsubscribed by the engine's stream-open seam, which rebinds to the
     * winning rung's own {@code deviceEvents()} (story 316 review).
     */
    void publishDeviceEvent(com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent event) {
        deviceEventPublisher.submit(event);
    }

    @Override
    public Flow.Publisher<com.benesquivelmusic.daw.sdk.audio.AudioDeviceEvent> deviceEvents() {
        return deviceEventPublisher;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean supportsStreaming() {
        return streamingSupported;
    }

    @Override
    public List<AudioDeviceInfo> listDevices() {
        return devices;
    }

    /**
     * Seeds the enumeration this spy answers with, so a test can drive the
     * real settings chain — enumerate, filter to selectable outputs, choose
     * a name, apply — end to end (story 316 review, R4).
     *
     * @param enumerated the devices this backend should report
     */
    void setDevices(List<AudioDeviceInfo> enumerated) {
        this.devices = List.copyOf(enumerated);
    }

    @Override
    public void open(DeviceId device,
                     com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                     int bufferFrames) {
        RuntimeException failure = this.openFailure;
        if (failure != null) {
            throw failure;
        }
        if (open) {
            throw new IllegalStateException(
                    "A stream is already open on " + name);
        }
        opens.add(new OpenRecord(device, format, bufferFrames));
        open = true;
        lifecycleLog.add("open:" + name);
    }

    @Override
    public Flow.Publisher<AudioBlock> inputBlocks() {
        return inputPublisher;
    }

    @Override
    public void sink(AudioBlock block) {
        if (open) {
            sunkBlocks.release();
        }
    }

    @Override
    public void awaitSinkCapacity(long timeoutNanos) {
        if (blockAwait) {
            // Simulates a native wait that swallows interrupts: the pump
            // stop's interrupt/unpark cannot free it — only clearing
            // blockAwait can. The interrupt status is cleared each turn so
            // parkNanos really parks instead of spinning. Mirrors
            // AudioEngineOutputTest.SynchronousTestBackend's wedge.
            blockedAwaitEntries.incrementAndGet();
            while (blockAwait) {
                Thread.interrupted();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            return;
        }
        // Short bounded park: keeps the pump from hot-spinning while
        // letting tests observe several blocks well inside their
        // await budgets (never a full wall-clock block period).
        LockSupport.parkNanos(Math.min(timeoutNanos, 1_000_000L));
    }

    /**
     * Installs the native driver control-panel action this spy offers, so a
     * test can make it block the way a real modal driver dialog does (story
     * 316 re-review, stall audit A1).
     *
     * @param action the action {@link #openControlPanel()} should hand out,
     *               or {@code null} for a backend with no panel
     */
    void setControlPanelAction(Runnable action) {
        this.controlPanelAction = action;
    }

    /**
     * Arms a ONE-SHOT hook that runs at the top of the next {@link #close()}
     * and is then forgotten, so a test can make exactly one close as slow as
     * a real native teardown without also wedging the closes that shutdown
     * issues afterwards (story 316 re-review, stall audit A1 follow-up).
     *
     * @param hook the action the next {@code close()} should run first
     */
    void armNextCloseHook(Runnable hook) {
        this.nextCloseHook.set(hook);
    }

    @Override
    public Optional<Runnable> openControlPanel() {
        return Optional.ofNullable(controlPanelAction);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        Runnable hook = nextCloseHook.getAndSet(null);
        if (hook != null) {
            hook.run();
        }
        lifecycleLog.add("close:" + name);
        closeCount.incrementAndGet();
        open = false;
    }
}
