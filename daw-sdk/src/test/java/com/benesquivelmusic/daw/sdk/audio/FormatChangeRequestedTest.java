package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AudioDeviceEvent.FormatChangeRequested} and
 * {@link FormatChangeReason} — the structured driver-initiated
 * "drop and reopen" signal from story 218.
 */
class FormatChangeRequestedTest {

    private static final DeviceId DEVICE = new DeviceId("Mock", "Mock Device");

    @Test
    void recordExposesAllThreeAccessors() {
        AudioFormat fmt = new AudioFormat(48_000.0, 2, 24);
        AudioDeviceEvent.FormatChangeRequested event =
                new AudioDeviceEvent.FormatChangeRequested(
                        DEVICE,
                        Optional.of(fmt),
                        new FormatChangeReason.BufferSizeChange());
        assertThat(event.device()).isEqualTo(DEVICE);
        assertThat(event.proposedFormat()).contains(fmt);
        assertThat(event.reason()).isInstanceOf(FormatChangeReason.BufferSizeChange.class);
    }

    @Test
    void emptyProposedFormatIsAllowed() {
        AudioDeviceEvent.FormatChangeRequested event =
                new AudioDeviceEvent.FormatChangeRequested(
                        DEVICE,
                        Optional.empty(),
                        new FormatChangeReason.DriverReset());
        assertThat(event.proposedFormat()).isEmpty();
    }

    @Test
    void deviceMustNotBeNull() {
        assertThatThrownBy(() -> new AudioDeviceEvent.FormatChangeRequested(
                null, Optional.empty(), new FormatChangeReason.DriverReset()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("device");
    }

    @Test
    void proposedFormatOptionalMustNotBeNull() {
        assertThatThrownBy(() -> new AudioDeviceEvent.FormatChangeRequested(
                DEVICE, null, new FormatChangeReason.DriverReset()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("proposedFormat");
    }

    @Test
    void reasonMustNotBeNull() {
        assertThatThrownBy(() -> new AudioDeviceEvent.FormatChangeRequested(
                DEVICE, Optional.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void allFourReasonCasesArePermitted() {
        // Just construct each one and verify they are FormatChangeReason
        // instances — keeps the sealed permits list honest as the codebase
        // evolves.
        FormatChangeReason buffer = new FormatChangeReason.BufferSizeChange();
        FormatChangeReason rate = new FormatChangeReason.SampleRateChange();
        FormatChangeReason clock = new FormatChangeReason.ClockSourceChange();
        FormatChangeReason reset = new FormatChangeReason.DriverReset();
        assertThat(buffer).isInstanceOf(FormatChangeReason.class);
        assertThat(rate).isInstanceOf(FormatChangeReason.class);
        assertThat(clock).isInstanceOf(FormatChangeReason.class);
        assertThat(reset).isInstanceOf(FormatChangeReason.class);
    }

    @Test
    void mockBackendSimulatesFormatChangeRequestedEvent() throws Exception {
        // Story 218 deliverable: MockAudioBackend can drive subscribers
        // through the FormatChangeRequested flow without real hardware.
        try (MockAudioBackend backend = new MockAudioBackend()) {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<AudioDeviceEvent> received =
                    new java.util.concurrent.atomic.AtomicReference<>();
            backend.deviceEvents().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                    s.request(Long.MAX_VALUE);
                }
                @Override public void onNext(AudioDeviceEvent e) {
                    received.set(e);
                    latch.countDown();
                }
                @Override public void onError(Throwable t) { /* ignore */ }
                @Override public void onComplete() { /* ignore */ }
            });
            // Allow the SubmissionPublisher subscription to settle before publishing.
            Thread.sleep(50);
            backend.simulateFormatChangeRequested(
                    DEVICE,
                    Optional.of(new AudioFormat(44_100.0, 2, 24)),
                    new FormatChangeReason.SampleRateChange());
            assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(received.get())
                    .isInstanceOf(AudioDeviceEvent.FormatChangeRequested.class);
            AudioDeviceEvent.FormatChangeRequested event =
                    (AudioDeviceEvent.FormatChangeRequested) received.get();
            assertThat(event.device()).isEqualTo(DEVICE);
            assertThat(event.proposedFormat()).isPresent();
            assertThat(event.reason()).isInstanceOf(FormatChangeReason.SampleRateChange.class);
        }
    }
}
