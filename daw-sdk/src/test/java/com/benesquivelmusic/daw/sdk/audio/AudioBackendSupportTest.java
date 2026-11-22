package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * A block that at least one subscriber dropped counts once, however many
     * subscribers dropped it; a block every subscriber took counts nothing.
     */
    @Test
    void inputBlocksDroppedCountsEachDroppedBlockOnce() {
        ScriptedOfferPublisher<AudioBlock> inputPublisher = new ScriptedOfferPublisher<>(-2, 5, -1, 0);
        try (AudioBackendSupport support = new AudioBackendSupport(
                inputPublisher, new SubmissionPublisher<>())) {
            support.markOpen(FORMAT, BLOCK.frames());

            support.publishInput(BLOCK);
            assertThat(support.inputBlocksDropped())
                    .as("dropped by two subscribers, counted as one block")
                    .isEqualTo(1L);
            support.publishInput(BLOCK);
            assertThat(support.inputBlocksDropped())
                    .as("a block every subscriber took is no drop")
                    .isEqualTo(1L);
            support.publishInput(BLOCK);
            support.publishInput(BLOCK);
            assertThat(support.inputBlocksDropped()).isEqualTo(2L);
        }
    }

    @Test
    void aCloseRaceIsNotCountedAsADroppedBlock() {
        CloseBeforeOfferPublisher<AudioBlock> inputPublisher = new CloseBeforeOfferPublisher<>();
        try (AudioBackendSupport support = new AudioBackendSupport(
                inputPublisher, new SubmissionPublisher<>())) {
            support.markOpen(FORMAT, BLOCK.frames());

            support.publishInput(BLOCK);

            assertThat(support.inputBlocksDropped()).isZero();
        }
    }

    /** Answers each {@code offer} with the next scripted value, delivering nothing. */
    private static final class ScriptedOfferPublisher<T> extends SubmissionPublisher<T> {

        private final int[] answers;
        private int next;

        ScriptedOfferPublisher(int... answers) {
            this.answers = answers.clone();
        }

        @Override
        public int offer(
                T item,
                BiPredicate<Flow.Subscriber<? super T>, ? super T> dropHandler) {
            return answers[next++];
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
