package com.benesquivelmusic.daw.app.diagnostics;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Test-side access to {@link FailureFunnel}'s test-only seam for how a
 * last-resort record names a tail (story 336), for a test outside this
 * package. Building that name throws only when the JVM runs out of memory,
 * so a test makes it throw here instead. The application never calls it.
 */
public final class FunnelTailNaming {

    private FunnelTailNaming() {
    }

    /**
     * A funnel whose naming of a tail throws {@code failure} for every tail
     * {@code unnameable} accepts, and names every other tail as the
     * application's funnel does.
     *
     * @param collaborators the funnel's collaborators
     * @param unnameable    the tails whose name cannot be built
     * @param failure       what building such a name throws
     * @return the funnel, not yet installed
     */
    public static FailureFunnel unableToName(FailureFunnel.Collaborators collaborators,
                                             Predicate<FingerprintRateLimiter.Tail> unnameable, Error failure) {
        Objects.requireNonNull(unnameable, "unnameable must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        return new FailureFunnel(collaborators, tail -> {
            if (unnameable.test(tail)) {
                throw failure;
            }
            return FailureFunnel.tailNamed(tail);
        });
    }
}
