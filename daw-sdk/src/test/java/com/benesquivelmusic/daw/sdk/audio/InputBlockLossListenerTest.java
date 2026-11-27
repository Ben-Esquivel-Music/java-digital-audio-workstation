package com.benesquivelmusic.daw.sdk.audio;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared input drop handler (story 338, ruling E208): it tells the
 * subscriber that dropped a block when that subscriber is a listener, never
 * asks for a retry, and lets a listener's failure cost only its own notice.
 */
class InputBlockLossListenerTest {

    private static final AudioBlock BLOCK = AudioBlock.silence(48_000.0, 2, 64);

    @Test
    void aDropIsToldToAListenerAndNeverRetried() {
        Listener listener = new Listener(null);

        boolean retry = InputBlockLossListener.reportDrop(listener, BLOCK);

        assertThat(retry).as("the publisher drops the block rather than retry").isFalse();
        assertThat(listener.dropped).containsExactly(BLOCK);
        assertThat(listener.dropped.getFirst()).isSameAs(BLOCK);
    }

    @Test
    void aSubscriberThatIsNotAListenerIsToldNothing() {
        assertThat(InputBlockLossListener.reportDrop(new PlainSubscriber(), BLOCK)).isFalse();
    }

    @Test
    void aListenerThatThrowsCostsOnlyItsOwnNotice() {
        Listener listener = new Listener(new IllegalStateException("a broken notice"));

        boolean retry = InputBlockLossListener.reportDrop(listener, BLOCK);

        assertThat(retry).isFalse();
        assertThat(listener.dropped).as("the notice was made").containsExactly(BLOCK);
    }

    @Test
    void aVirtualMachineErrorFromAListenerPropagates() {
        InternalError fatal = new InternalError("the listener's fatal error");
        Listener listener = new Listener(fatal);

        assertThatThrownBy(() -> InputBlockLossListener.reportDrop(listener, BLOCK)).isSameAs(fatal);
    }

    /** A listener that records each block dropped for it and then throws its failure, if it has one. */
    private static final class Listener implements Flow.Subscriber<AudioBlock>, InputBlockLossListener {

        final List<AudioBlock> dropped = new ArrayList<>();
        private final Throwable failure;

        Listener(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void inputBlockDropped(AudioBlock block) {
            dropped.add(block);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
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
}
