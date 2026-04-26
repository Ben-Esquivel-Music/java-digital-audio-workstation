package com.benesquivelmusic.daw.sdk.event;

import com.benesquivelmusic.daw.sdk.model.AudioClip;
import com.benesquivelmusic.daw.sdk.model.AutomationLane;
import com.benesquivelmusic.daw.sdk.model.MidiClip;
import com.benesquivelmusic.daw.sdk.model.MixerChannel;
import com.benesquivelmusic.daw.sdk.model.Project;
import com.benesquivelmusic.daw.sdk.model.Return;
import com.benesquivelmusic.daw.sdk.model.Track;

import java.util.Objects;
import java.util.UUID;

/**
 * Change-description event published by
 * {@link com.benesquivelmusic.daw.sdk.store.ProjectStore ProjectStore} after
 * a successful project mutation.
 *
 * <p>Sealed over a closed set of record permits, one pair (added / updated
 * / removed) per addressable entity. Each event derives its {@link #id()}
 * directly from the carried entity record(s) — there is no independent
 * {@code id} field that could drift out of sync. Use a Java 21+ exhaustive
 * {@code switch} expression on the change to handle every event without
 * forgetting a case.</p>
 */
public sealed interface ProjectChange {

    /**
     * Returns the id of the entity that changed.
     *
     * <p>For {@link ProjectReplaced} this is the project id; for every other
     * variant it is the id of the carried entity (the {@code next} value
     * for added/updated events, the {@code previous} value for removed
     * events). The implementation derives the id from the carried record
     * so {@code change.id().equals(change.entity().id())} always holds.</p>
     */
    UUID id();

    // ----- Project ------------------------------------------------------------------------------

    /** Emitted when the entire project snapshot is replaced (e.g. on undo / redo / load). */
    record ProjectReplaced(Project previous, Project next) implements ProjectChange {
        public ProjectReplaced {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }

        @Override public UUID id() { return next.id(); }
    }

    // ----- Track --------------------------------------------------------------------------------

    record TrackAdded(Track next) implements ProjectChange {
        public TrackAdded {
            Objects.requireNonNull(next, "next must not be null");
        }
        @Override public UUID id() { return next.id(); }
    }

    record TrackUpdated(Track previous, Track next) implements ProjectChange {
        public TrackUpdated {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
            if (!previous.id().equals(next.id())) {
                throw new IllegalArgumentException(
                        "previous.id and next.id must match: " + previous.id() + " vs " + next.id());
            }
        }
        @Override public UUID id() { return next.id(); }
    }

    record TrackRemoved(Track previous) implements ProjectChange {
        public TrackRemoved {
            Objects.requireNonNull(previous, "previous must not be null");
        }
        @Override public UUID id() { return previous.id(); }
    }

    // ----- AudioClip ----------------------------------------------------------------------------

    record AudioClipAdded(AudioClip next) implements ProjectChange {
        public AudioClipAdded {
            Objects.requireNonNull(next, "next must not be null");
        }
        @Override public UUID id() { return next.id(); }
    }

    record AudioClipUpdated(AudioClip previous, AudioClip next) implements ProjectChange {
        public AudioClipUpdated {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
            if (!previous.id().equals(next.id())) {
                throw new IllegalArgumentException(
                        "previous.id and next.id must match: " + previous.id() + " vs " + next.id());
            }
        }
        @Override public UUID id() { return next.id(); }
    }

    record AudioClipRemoved(AudioClip previous) implements ProjectChange {
        public AudioClipRemoved {
            Objects.requireNonNull(previous, "previous must not be null");
        }
        @Override public UUID id() { return previous.id(); }
    }

    // ----- MidiClip -----------------------------------------------------------------------------

    record MidiClipAdded(MidiClip next) implements ProjectChange {
        public MidiClipAdded {
            Objects.requireNonNull(next, "next must not be null");
        }
        @Override public UUID id() { return next.id(); }
    }

    record MidiClipUpdated(MidiClip previous, MidiClip next) implements ProjectChange {
        public MidiClipUpdated {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
            if (!previous.id().equals(next.id())) {
                throw new IllegalArgumentException(
                        "previous.id and next.id must match: " + previous.id() + " vs " + next.id());
            }
        }
        @Override public UUID id() { return next.id(); }
    }

    record MidiClipRemoved(MidiClip previous) implements ProjectChange {
        public MidiClipRemoved {
            Objects.requireNonNull(previous, "previous must not be null");
        }
        @Override public UUID id() { return previous.id(); }
    }

    // ----- MixerChannel -------------------------------------------------------------------------

    record MixerChannelAdded(MixerChannel next) implements ProjectChange {
        public MixerChannelAdded {
            Objects.requireNonNull(next, "next must not be null");
        }
        @Override public UUID id() { return next.id(); }
    }

    record MixerChannelUpdated(MixerChannel previous, MixerChannel next) implements ProjectChange {
        public MixerChannelUpdated {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
            if (!previous.id().equals(next.id())) {
                throw new IllegalArgumentException(
                        "previous.id and next.id must match: " + previous.id() + " vs " + next.id());
            }
        }
        @Override public UUID id() { return next.id(); }
    }

    record MixerChannelRemoved(MixerChannel previous) implements ProjectChange {
        public MixerChannelRemoved {
            Objects.requireNonNull(previous, "previous must not be null");
        }
        @Override public UUID id() { return previous.id(); }
    }

    // ----- Return -------------------------------------------------------------------------------

    record ReturnAdded(Return next) implements ProjectChange {
        public ReturnAdded {
            Objects.requireNonNull(next, "next must not be null");
        }
        @Override public UUID id() { return next.id(); }
    }

    record ReturnUpdated(Return previous, Return next) implements ProjectChange {
        public ReturnUpdated {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
            if (!previous.id().equals(next.id())) {
                throw new IllegalArgumentException(
                        "previous.id and next.id must match: " + previous.id() + " vs " + next.id());
            }
        }
        @Override public UUID id() { return next.id(); }
    }

    record ReturnRemoved(Return previous) implements ProjectChange {
        public ReturnRemoved {
            Objects.requireNonNull(previous, "previous must not be null");
        }
        @Override public UUID id() { return previous.id(); }
    }

    // ----- AutomationLane -----------------------------------------------------------------------

    record AutomationLaneAdded(AutomationLane next) implements ProjectChange {
        public AutomationLaneAdded {
            Objects.requireNonNull(next, "next must not be null");
        }
        @Override public UUID id() { return next.id(); }
    }

    record AutomationLaneUpdated(AutomationLane previous, AutomationLane next) implements ProjectChange {
        public AutomationLaneUpdated {
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
            if (!previous.id().equals(next.id())) {
                throw new IllegalArgumentException(
                        "previous.id and next.id must match: " + previous.id() + " vs " + next.id());
            }
        }
        @Override public UUID id() { return next.id(); }
    }

    record AutomationLaneRemoved(AutomationLane previous) implements ProjectChange {
        public AutomationLaneRemoved {
            Objects.requireNonNull(previous, "previous must not be null");
        }
        @Override public UUID id() { return previous.id(); }
    }
}
