package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
     * A block the publisher drops is told only to the listener whose full
     * buffer dropped it (story 338, ruling E208). The publisher's executor
     * never runs a task, so no buffer ever drains, and each buffer holds one
     * block: a subscriber's first block fills it and every later one is
     * dropped for it.
     */
    @Test
    void aDroppedBlockIsToldOnlyToTheSubscriberThatDroppedIt() {
        try (AudioBackendSupport support = new AudioBackendSupport(
                new SubmissionPublisher<>(task -> { }, 1), new SubmissionPublisher<>())) {
            support.markOpen(FORMAT, BLOCK.frames());
            RecordingListener first = new RecordingListener();
            RecordingListener second = new RecordingListener();
            support.inputBlocks().subscribe(first);
            support.publishInput(AudioBlock.silence(48_000.0, 2, 128));
            support.inputBlocks().subscribe(second);
            support.inputBlocks().subscribe(new PlainSubscriber());

            AudioBlock secondBlock = AudioBlock.silence(48_000.0, 2, 128);
            support.publishInput(secondBlock);
            assertThat(first.dropped).as("the first listener's buffer was full").containsExactly(secondBlock);
            assertThat(second.dropped).as("the second listener took it").isEmpty();

            AudioBlock thirdBlock = AudioBlock.silence(48_000.0, 2, 128);
            support.publishInput(thirdBlock);
            assertThat(first.dropped).containsExactly(secondBlock, thirdBlock);
            assertThat(second.dropped).containsExactly(thirdBlock);
        }
    }

    /**
     * A listener whose notice throws costs only that notice: the publish does
     * not throw, and the publisher goes on to tell the next subscriber it
     * dropped the block for (story 338, ruling E208).
     */
    @Test
    void aListenerThatThrowsLeavesTheOtherDroppingSubscribersTold() {
        try (AudioBackendSupport support = new AudioBackendSupport(
                new SubmissionPublisher<>(task -> { }, 1), new SubmissionPublisher<>())) {
            support.markOpen(FORMAT, BLOCK.frames());
            RecordingListener throwing = new RecordingListener();
            throwing.failure = new IllegalStateException("a broken notice");
            RecordingListener told = new RecordingListener();
            support.inputBlocks().subscribe(throwing);
            support.inputBlocks().subscribe(told);
            support.publishInput(AudioBlock.silence(48_000.0, 2, 128));

            AudioBlock dropped = AudioBlock.silence(48_000.0, 2, 128);
            assertThatCode(() -> support.publishInput(dropped)).doesNotThrowAnyException();

            assertThat(throwing.dropped).containsExactly(dropped);
            assertThat(told.dropped).as("told after the first listener threw").containsExactly(dropped);
        }
    }

    /** Records each block dropped for it; requests nothing. */
    private static final class RecordingListener
            implements Flow.Subscriber<AudioBlock>, InputBlockLossListener {

        final List<AudioBlock> dropped = new CopyOnWriteArrayList<>();
        volatile RuntimeException failure;

        @Override
        public void inputBlockDropped(AudioBlock block) {
            dropped.add(block);
            RuntimeException thrown = failure;
            if (thrown != null) {
                throw thrown;
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
        }

        @Override
        public void onNext(AudioBlock item) {
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }

    /** A subscriber that is not a listener; requests nothing. */
    private static final class PlainSubscriber implements Flow.Subscriber<AudioBlock> {

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
        }

        @Override
        public void onNext(AudioBlock item) {
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
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
