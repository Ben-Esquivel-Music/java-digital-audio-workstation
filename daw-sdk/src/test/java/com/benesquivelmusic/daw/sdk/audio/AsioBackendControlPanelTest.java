package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 *
 * <p>Per the {@link AudioBackend#openControlPanel()} contract, launch
 * failures are surfaced as {@link AudioBackendException} on the calling
 * thread so the dialog can present a user-visible notification.</p>
 */
class AsioBackendControlPanelTest {

    @AfterEach
    void restoreFactory() {
        AsioBackend.resetCapabilityShimFactory();
    }

    @Test
    void openControlPanelInvokesNativeSymbolExactlyOnceWhenSymbolResolves() {
        // Story 212, test 1: when asioshim_openControlPanel resolves,
        // AsioBackend#openControlPanel() returns a non-empty Optional
        // whose Runnable performs exactly one call to the mocked symbol.
        AtomicInteger calls = new AtomicInteger();
        AsioBackend.setCapabilityShimFactory(() -> new ControlPanelStubShim(true, () -> {
            calls.incrementAndGet();
            return 1; // ASE_OK
        }));

        AsioBackend backend = new AsioBackend();
        Optional<Runnable> action = backend.openControlPanel();

        assertThat(action).isPresent();
        // On success the runnable completes normally without throwing.
        action.get().run();
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
    void aseNotPresentThrowsAudioBackendExceptionOnCallingThread() {
        // Story 212, test 3: a return code of 0 (ASE_NotPresent) is
        // propagated as an AudioBackendException on the calling thread
        // per the AudioBackend#openControlPanel() contract, so the
        // dialog's onOpenControlPanel handler can surface the error.
        AsioBackend.setCapabilityShimFactory(() -> new ControlPanelStubShim(true,
                () -> AsioCapabilityShim.CONTROL_PANEL_NOT_PRESENT));

        AsioBackend backend = new AsioBackend();
        Optional<Runnable> action = backend.openControlPanel();
        assertThat(action).isPresent();

        assertThatThrownBy(() -> action.get().run())
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("Driver does not provide a control panel");
    }

    @Test
    void genericFailureCodeThrowsWithCodeInMessage() {
        // Any non-ASE_OK, non-ASE_NotPresent code surfaces as
        // "Could not launch ASIO control panel: <code>" on the
        // calling thread.
        AsioBackend.setCapabilityShimFactory(() -> new ControlPanelStubShim(true, () -> -1));

        AsioBackend backend = new AsioBackend();
        Runnable runnable = backend.openControlPanel().orElseThrow();

        assertThatThrownBy(runnable::run)
                .isInstanceOf(AudioBackendException.class)
                .hasMessage("Could not launch ASIO control panel: -1");
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
