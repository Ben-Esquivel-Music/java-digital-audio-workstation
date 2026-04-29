package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;

import java.time.Clock;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Composition root for the Digital Audio Workstation application.
 *
 * <p>Constructs and owns the canonical collaborators that historically lived
 * behind static-singleton accessors (e.g. {@link ProcessorRegistry}). Every
 * collaborator created here is passed into downstream constructors —
 * eliminating hidden global state, making lifecycles explicit, and allowing
 * tests to substitute fakes without touching JVM-wide singletons.</p>
 *
 * <h2>Why a hand-rolled composition root?</h2>
 * <p>At this scale a DI framework (Spring, Guice, Dagger) is more complexity
 * than it solves: there are a handful of long-lived collaborators, all of
 * them constructed at startup from pure-Java dependencies. A single
 * {@code DawRuntime} that {@code new}s them up — and is itself constructed
 * once in {@link DawApplication#start} — is the simplest thing that could
 * possibly work, while still giving tests the full benefit of constructor
 * injection.</p>
 *
 * <h2>Migration status</h2>
 * <p>The singleton-removal migration is incremental: one singleton per PR.
 * Currently this runtime owns:</p>
 * <ul>
 *   <li>{@link ProcessorRegistry} — the {@code @InsertEffect} catalog. The
 *       deprecated {@link ProcessorRegistry#getInstance()} pass-through still
 *       exists for unmigrated call sites and will be removed in the final PR
 *       of the migration.</li>
 *   <li>{@link Clock} and {@link RandomGenerator} — exposed up front so that
 *       any future time- or randomness-dependent collaborator can be made
 *       deterministic in tests by passing alternative implementations
 *       (e.g. {@link Clock#fixed}, a seeded {@code RandomGenerator}) through
 *       the test-only constructor.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>Collaborators created here are immutable / thread-safe after
 * construction. Instances of {@code DawRuntime} are themselves immutable.</p>
 */
public final class DawRuntime {

    private final ProcessorRegistry processorRegistry;
    private final Clock clock;
    private final RandomGenerator random;

    /**
     * Production constructor: wires the canonical collaborators with their
     * default implementations ({@link Clock#systemUTC system UTC clock},
     * {@link RandomGenerator#getDefault default RNG}, fresh
     * {@link ProcessorRegistry}).
     */
    public DawRuntime() {
        this(new ProcessorRegistry(), Clock.systemUTC(), RandomGenerator.getDefault());
    }

    /**
     * Test/override constructor: lets callers substitute deterministic or
     * mock collaborators. Every argument is required and must not be
     * {@code null}.
     */
    public DawRuntime(ProcessorRegistry processorRegistry, Clock clock, RandomGenerator random) {
        this.processorRegistry = Objects.requireNonNull(processorRegistry, "processorRegistry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** The injected {@link ProcessorRegistry} for built-in DSP processors. */
    public ProcessorRegistry processorRegistry() {
        return processorRegistry;
    }

    /** The injected {@link Clock}; defaults to {@link Clock#systemUTC()}. */
    public Clock clock() {
        return clock;
    }

    /**
     * The injected {@link RandomGenerator}; defaults to
     * {@link RandomGenerator#getDefault()}.
     */
    public RandomGenerator random() {
        return random;
    }
}
