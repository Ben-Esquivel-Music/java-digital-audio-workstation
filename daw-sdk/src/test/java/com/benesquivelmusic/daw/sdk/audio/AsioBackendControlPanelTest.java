package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the FFM bridge to Steinberg's
 * {@code ASIOControlPanel()} via {@link AsioBackend#openControlPanel()}
 * and {@code invokeAsioControlPanel()} (story 212).
 *
 * <p>Tests inject a stub {@link AsioCapabilityShim} via
 * {@link AsioBackend#setCapabilityShimFactory(java.util.function.Supplier)}
 * so the symbol-present, symbol-absent and {@code ASE_NotPresent} paths
 * can all be exercised without requiring the native shim or a Windows
 * host with the Steinberg ASIO SDK installed.</p>
 */
class AsioBackendControlPanelTest {

    private static final long AWAIT_SECONDS = 5;

    @AfterEach
    void restoreFactory() {
        AsioBackend.resetCapabilityShimFactory();
    }

    @Test
    void openControlPanelInvokesNativeSymbolExactlyOnceWhenSymbolResolves() throws InterruptedException {
        // Story 212, test 1: when asioshim_openControlPanel resolves,
        // AsioBackend#openControlPanel() returns a non-empty Optional
        // whose Runnable performs exactly one call to the mocked symbol.
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch invoked = new CountDownLatch(1);
        AsioBackend.setCapabilityShimFactory(() -> new ControlPanelStubShim(true, () -> {
            calls.incrementAndGet();
            invoked.countDown();
            return 1; // ASE_OK
        }));

        AsioBackend backend = new AsioBackend();
        Optional<Runnable> action = backend.openControlPanel();

        assertThat(action).isPresent();
        action.get().run();

        assertThat(invoked.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("the dispatched worker thread should call the native symbol")
                .isTrue();
        // Give the worker a brief moment after the latch in case it
        // somehow re-entered (it must not).
        Thread.sleep(50);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void openControlPanelReturnsEmptyWhenSymbolIsAbsent() {
        // Story 212, test 2: when the asioshim library or the
        // openControlPanel symbol is absent, openControlPanel() returns
        // Optional.empty(); the dialog's existing disabled-button path
        // (refreshControlPanelButton) handles this without changes.
        AsioBackend.setCapabilityShimFactory(ControlPanelStubShim::unavailable);

        AsioBackend backend = new AsioBackend();
        Optional<Runnable> action = backend.openControlPanel();

        assertThat(action).isEmpty();
    }

    @Test
    void aseNotPresentTranslatesToAudioBackendExceptionAndDoesNotEscapeRunnable()
            throws InterruptedException {
        // Story 212, test 3: a return code of 0 (ASE_NotPresent) becomes
        // a clear AudioBackendException, but the supervising
        // uncaught-exception handler logs it — the runnable must not
        // throw past the dialog's onOpenControlPanel handler.
        CountDownLatch invoked = new CountDownLatch(1);
        AsioBackend.setCapabilityShimFactory(() -> new ControlPanelStubShim(true, () -> {
            invoked.countDown();
            return AsioCapabilityShim.CONTROL_PANEL_NOT_PRESENT; // 0 → ASE_NotPresent
        }));

        // Capture WARNING records emitted by AsioBackend so we can assert
        // the AudioBackendException's message.
        AtomicReference<LogRecord> captured = new AtomicReference<>();
        CountDownLatch logged = new CountDownLatch(1);
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.compareAndSet(null, record);
                    logged.countDown();
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger backendLog = Logger.getLogger(AsioBackend.class.getName());
        backendLog.addHandler(handler);
        boolean wasUseParent = backendLog.getUseParentHandlers();
        Level wasLevel = backendLog.getLevel();
        backendLog.setUseParentHandlers(false);
        backendLog.setLevel(Level.ALL);
        try {
            AsioBackend backend = new AsioBackend();
            Optional<Runnable> action = backend.openControlPanel();
            assertThat(action).isPresent();

            // The runnable itself must return normally — failures live on
            // the worker thread and are routed through the supervisor.
            action.get().run();

            assertThat(invoked.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(logged.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the supervisor should log the AudioBackendException")
                    .isTrue();
            LogRecord record = captured.get();
            assertThat(record).isNotNull();
            assertThat(record.getThrown()).isInstanceOf(AudioBackendException.class);
            assertThat(record.getThrown().getMessage())
                    .isEqualTo("Driver does not provide a control panel");
        } finally {
            backendLog.removeHandler(handler);
            backendLog.setUseParentHandlers(wasUseParent);
            backendLog.setLevel(wasLevel);
        }
    }

    @Test
    void genericFailureCodeIsTranslatedWithCodeInMessage() throws InterruptedException {
        // Any non-ASE_OK, non-ASE_NotPresent code surfaces as
        // "Could not launch ASIO control panel: <code>".
        AsioBackend.setCapabilityShimFactory(() -> new ControlPanelStubShim(true, () -> -1));

        AtomicReference<LogRecord> captured = new AtomicReference<>();
        CountDownLatch logged = new CountDownLatch(1);
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.compareAndSet(null, record);
                    logged.countDown();
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger backendLog = Logger.getLogger(AsioBackend.class.getName());
        backendLog.addHandler(handler);
        boolean wasUseParent = backendLog.getUseParentHandlers();
        Level wasLevel = backendLog.getLevel();
        backendLog.setUseParentHandlers(false);
        backendLog.setLevel(Level.ALL);
        try {
            AsioBackend backend = new AsioBackend();
            backend.openControlPanel().orElseThrow().run();

            assertThat(logged.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().getThrown())
                    .isInstanceOf(AudioBackendException.class)
                    .hasMessage("Could not launch ASIO control panel: -1");
        } finally {
            backendLog.removeHandler(handler);
            backendLog.setUseParentHandlers(wasUseParent);
            backendLog.setLevel(wasLevel);
        }
    }

    @Test
    void productionShimReportsControlPanelUnavailableWhenLibraryMissing() {
        // Sanity check: in this CI sandbox the asioshim library is not
        // present, so the production shim must report
        // isControlPanelAvailable() = false rather than throwing.
        try (AsioCapabilityShim shim = new AsioCapabilityShim()) {
            assertThat(shim.isControlPanelAvailable()).isFalse();
            // openControlPanel() returns a generic failure (negative)
            // when the symbol is absent; it must never throw.
            assertThat(shim.openControlPanel()).isLessThan(0);
        }
    }

    /**
     * Test stub: an {@link AsioCapabilityShim} whose
     * {@code isControlPanelAvailable()} / {@code openControlPanel()}
     * answers come from a caller-supplied lambda, so each test can
     * deterministically simulate the symbol-present, symbol-absent
     * and {@code ASE_NotPresent} paths.
     */
    private static final class ControlPanelStubShim extends AsioCapabilityShim {
        private final boolean present;
        private final java.util.function.IntSupplier nativeCall;

        ControlPanelStubShim(boolean present,
                             java.util.function.IntSupplier nativeCall) {
            super();
            this.present = present;
            this.nativeCall = nativeCall;
        }

        static ControlPanelStubShim unavailable() {
            return new ControlPanelStubShim(false, () -> -1);
        }

        @Override
        boolean isAvailable() {
            return present;
        }

        @Override
        boolean isControlPanelAvailable() {
            return present;
        }

        @Override
        int openControlPanel() {
            return nativeCall.getAsInt();
        }
    }
}
