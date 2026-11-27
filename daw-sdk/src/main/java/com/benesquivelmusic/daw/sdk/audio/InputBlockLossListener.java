package com.benesquivelmusic.daw.sdk.audio;

import java.util.concurrent.Flow;

/**
 * A subscriber of {@link AudioBackend#inputBlocks()} that is told of each
 * captured block its publisher dropped for it because its buffer was full
 * (story 338, ruling E208).
 *
 * <p>Called on the thread that offered the block: a backend's capture or
 * input-drain thread, never a device's real-time callback. It runs while the
 * publisher holds its lock, so other threads' calls on that publisher wait
 * meanwhile. An implementation takes no lock, allocates nothing, never
 * blocks, logs nothing, and calls nothing but {@code java.util.concurrent.atomic}
 * and code held to the same rule.</p>
 *
 * <p>A subscriber can still be told of a drop after it cancelled, until its
 * publisher has processed the cancel; a listener that must count only while
 * it is live gates on its own state.</p>
 */
public interface InputBlockLossListener {

    /**
     * The publisher dropped {@code block} for this subscriber, which will
     * never receive it.
     *
     * @param block the block this subscriber will not receive
     */
    void inputBlockDropped(AudioBlock block);

    /**
     * The drop handler every input publisher passes to
     * {@link java.util.concurrent.SubmissionPublisher#offer(Object, java.util.function.BiPredicate)}.
     * It tells {@code subscriber} of the drop when that subscriber is a
     * listener, and never asks for a retry. A listener that throws costs only
     * its own notice, so the publisher goes on to the other subscribers it
     * dropped the block for; a {@link VirtualMachineError} propagates.
     *
     * @param subscriber the subscriber the publisher dropped {@code block} for
     * @param block      the dropped block
     * @return always {@code false}
     */
    static boolean reportDrop(Flow.Subscriber<? super AudioBlock> subscriber, AudioBlock block) {
        if (subscriber instanceof InputBlockLossListener listener) {
            try {
                listener.inputBlockDropped(block);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // A throw out of here skips the publisher's remaining subscribers, and the backend then
                // counts the block lost for every one of them.
            }
        }
        return false;
    }
}
