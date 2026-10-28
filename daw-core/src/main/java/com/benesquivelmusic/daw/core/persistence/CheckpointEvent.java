package com.benesquivelmusic.daw.core.persistence;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A checkpoint fact a {@link CheckpointManager} reports to its event
 * listeners ({@link CheckpointManager#addEventListener}) — next to the SDK
 * {@link com.benesquivelmusic.daw.sdk.event.AutoSaveListener}, which keeps
 * hearing about every checkpoint without these details (story 331, rulings
 * G13 #2 and #9, K1). Each fact is reported once to each listener: what a
 * generation's {@link Seeded seeding} found, a checkpoint {@link Written} or
 * {@link Failed}, with its {@link CheckpointOrigin}, and every time the
 * cadence {@link Rearmed re-arms} its next tick.
 *
 * <p>Every fact names the {@linkplain #generation() checkpoint generation}
 * that reported it. A fact can reach a listener after its generation ended —
 * the project was closed, replaced, or its folder opened again — so a
 * consumer that shows the open project's checkpoints applies a fact only
 * while its generation is the one {@link CheckpointManager#runningGeneration()}
 * names, asked on the consumer's own thread.</p>
 */
public sealed interface CheckpointEvent permits CheckpointEvent.Seeded, CheckpointEvent.Written,
        CheckpointEvent.Failed, CheckpointEvent.Rearmed {

    /**
     * The identity of the checkpoint generation that reported this fact: an
     * opaque number, the same for every fact of one generation and different
     * for any two generations of one manager — two opens of the same folder
     * included. Compare it with {@link CheckpointManager#runningGeneration()}.
     *
     * @return the reporting generation's identity
     */
    long generation();

    /**
     * A generation finished seeding its index from {@code checkpoints/}
     * (ruling K1): reported once, before any other fact of the generation
     * except a re-arm, whether autosave is enabled or not.
     *
     * @param generation the reporting generation's identity
     * @param newest     the newest checkpoint seeding found — the one the
     *                   generation's first tick compares {@code project.daw}
     *                   with (ruling G3) — or empty when the folder held none
     *                   or could not be read
     */
    record Seeded(long generation, Optional<Path> newest) implements CheckpointEvent {

        /** @throws NullPointerException if {@code newest} is {@code null} */
        public Seeded {
            Objects.requireNonNull(newest, "newest must not be null");
        }
    }

    /**
     * A checkpoint was written.
     *
     * @param generation   the reporting generation's identity
     * @param checkpointId the id the SDK listeners heard for it
     * @param origin       why it was written
     * @param file         the checkpoint file
     * @param revision     the edit revision it holds
     * @param journalSequence the journal boundary observed at that particular model capture, if available
     */
    record Written(long generation, String checkpointId, CheckpointOrigin origin, Path file, long revision,
                   OptionalLong journalSequence)
            implements CheckpointEvent {

        public Written(long generation, String checkpointId, CheckpointOrigin origin, Path file, long revision) {
            this(generation, checkpointId, origin, file, revision, OptionalLong.empty());
        }

        /** @throws NullPointerException if an object component is {@code null} */
        public Written {
            Objects.requireNonNull(checkpointId, "checkpointId must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
            Objects.requireNonNull(file, "file must not be null");
            Objects.requireNonNull(journalSequence, "journalSequence must not be null");
        }
    }

    /**
     * A checkpoint could not be captured or written; nothing was published.
     *
     * @param generation   the reporting generation's identity
     * @param checkpointId the id the SDK listeners heard for it
     * @param origin       why it was to be written
     * @param cause        what ended it, as thrown
     */
    record Failed(long generation, String checkpointId, CheckpointOrigin origin, Throwable cause)
            implements CheckpointEvent {

        /** @throws NullPointerException if an object component is {@code null} */
        public Failed {
            Objects.requireNonNull(checkpointId, "checkpointId must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
        }
    }

    /**
     * The cadence armed its next scheduled tick: a full interval after the
     * event that re-armed it (ruling F3: chained, not a fixed rate). Its
     * delivery can trail a {@link CheckpointManager#reconfigure} that
     * disabled autosave, or a {@link CheckpointManager#stop()}, made on
     * another thread, and that tick then never comes: a consumer re-checks
     * {@link CheckpointManager#isRunning()} on its own thread when it handles
     * this event, and shows no countdown when it is {@code false}.
     *
     * @param generation the reporting generation's identity
     * @param nextDue    when the next tick is due
     * @param interval   the cadence's interval
     * @param reason     what re-armed it
     */
    record Rearmed(long generation, Instant nextDue, Duration interval, RearmReason reason)
            implements CheckpointEvent {

        /** @throws NullPointerException if an object component is {@code null} */
        public Rearmed {
            Objects.requireNonNull(nextDue, "nextDue must not be null");
            Objects.requireNonNull(interval, "interval must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** What re-armed the cadence. */
    enum RearmReason {

        /** The checkpoints of a project started: its directory was seeded. */
        STARTED,

        /** The interval changed ({@link CheckpointManager#reconfigure}). */
        RECONFIGURED,

        /** A tick came while the previous tick's checkpoint was still being captured or written. */
        TICK_DROPPED,

        /** A checkpoint was due but its revision was already checkpointed: nothing was written. */
        SKIPPED,

        /** A checkpoint was written, of any origin. */
        WRITTEN,

        /** A checkpoint failed, of any origin. */
        FAILED
    }
}
