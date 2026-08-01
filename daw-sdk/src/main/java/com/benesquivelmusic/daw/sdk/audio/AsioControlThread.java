package com.benesquivelmusic.daw.sdk.audio;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single platform thread used for every ASIO host downcall.
 *
 * <p>Steinberg's Windows host glue initializes COM and creates an in-process
 * driver object. Keeping enumeration, init, capability calls, control-panel
 * dispatch, exit, and COM release on one long-lived platform thread preserves
 * that apartment affinity and makes it impossible to run these operations on
 * the real-time render thread.</p>
 */
final class AsioControlThread {

    private static volatile Thread controlThread;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform()
                    .name("asio-control")
                    .daemon(true)
                    .unstarted(() -> {
                        controlThread = Thread.currentThread();
                        task.run();
                    }));

    private AsioControlThread() {
    }

    static <T> T call(Operation<T> operation) throws Throwable {
        if (Thread.currentThread() == controlThread) {
            return operation.run();
        }
        try {
            return EXECUTOR.submit(() -> {
                try {
                    return operation.run();
                } catch (Throwable failure) {
                    throw new OperationFailure(failure);
                }
            }).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof OperationFailure wrapped) {
                throw wrapped.getCause();
            }
            throw cause;
        }
    }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws Throwable;
    }

    private static final class OperationFailure extends Exception {
        OperationFailure(Throwable cause) {
            super(cause);
        }
    }
}
