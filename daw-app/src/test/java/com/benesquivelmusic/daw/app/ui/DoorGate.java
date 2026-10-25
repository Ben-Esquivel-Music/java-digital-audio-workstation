package com.benesquivelmusic.daw.app.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test helpers for the project-close gate
 * ({@link ProjectLifecycleController#whenProjectMayClose}), which is
 * continuation-passing since story 331: a door that may replace the open
 * project runs its continuation — at once while no save this application
 * asked for is still running, later once it finished.
 */
final class DoorGate {

    /** The door name the helpers pass: what a refusal INFO names. */
    static final String TEST_DOOR = "Test door";

    private DoorGate() {
    }

    /**
     * Asks the gate on the calling thread — the FX thread — and answers
     * whether it let the door proceed within that call: {@code false} when it
     * refused, the prompt was cancelled, or the door waits.
     *
     * @param lifecycle the controller whose gate is asked
     * @return whether the door proceeded at once
     */
    static boolean proceedsAtOnce(ProjectLifecycleController lifecycle) {
        AtomicBoolean proceeded = new AtomicBoolean();
        lifecycle.whenProjectMayClose(TEST_DOOR, () -> proceeded.set(true));
        return proceeded.get();
    }

    /**
     * Asks the gate on the calling thread — the FX thread — and returns a
     * latch counted down when, and if, the door proceeds, now or later.
     *
     * @param lifecycle the controller whose gate is asked
     * @return counted down once the door proceeds
     */
    static CountDownLatch whenItProceeds(ProjectLifecycleController lifecycle) {
        CountDownLatch proceeded = new CountDownLatch(1);
        lifecycle.whenProjectMayClose(TEST_DOOR, proceeded::countDown);
        return proceeded;
    }
}
