package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioBackendSupportTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 32);
    private static final AudioBlock BLOCK = AudioBlock.silence(48_000.0, 2, 128);
    private static final AudioDeviceEvent EVENT = new AudioDeviceEvent.DeviceArrived(
            new DeviceId("ASIO", "Driver A"));

    @Test
    void publishDeviceEventToleratesCloseBetweenTheGuardAndOffer() {
        var devicePublisher = new CloseBeforeOfferPublisher<AudioDeviceEvent>();
        try (var support = new AudioBackendSupport(
                new SubmissionPublisher<>(), devicePublisher)) {
            assertThatCode(() -> support.publishDeviceEvent(EVENT))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void publishInputToleratesCloseBetweenTheGuardAndOffer() {
        var inputPublisher = new CloseBeforeOfferPublisher<AudioBlock>();
        try (var support = new AudioBackendSupport(
                inputPublisher, new SubmissionPublisher<>())) {
            support.markOpen(FORMAT, BLOCK.frames());

            assertThatCode(() -> support.publishInput(BLOCK))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void publishDoesNotMaskAnIllegalStateExceptionFromAnOpenPublisher() {
        var devicePublisher = new FailingPublisher<AudioDeviceEvent>();
        try (var support = new AudioBackendSupport(
                new SubmissionPublisher<>(), devicePublisher)) {
            assertThatThrownBy(() -> support.publishDeviceEvent(EVENT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unexpected publisher failure");
        }
    }

    private static final class CloseBeforeOfferPublisher<T> extends SubmissionPublisher<T> {

        @Override
        public int offer(
                T item,
                BiPredicate<Flow.Subscriber<? super T>, ? super T> dropHandler) {
            close();
            return super.offer(item, dropHandler);
        }
    }

    private static final class FailingPublisher<T> extends SubmissionPublisher<T> {

        @Override
        public int offer(
                T item,
                BiPredicate<Flow.Subscriber<? super T>, ? super T> dropHandler) {
            throw new IllegalStateException("unexpected publisher failure");
        }
    }
}
