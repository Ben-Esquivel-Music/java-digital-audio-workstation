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
 * / removed) per addressable entity. Use a Java 21+ exhaustive
 * {@code switch} expression on the change to handle every event without
 * forgetting a case.</p>
 */
public sealed interface ProjectChange {

    /** Returns the id of the entity that changed (or the project id for {@link ProjectReplaced}). */
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

    record TrackAdded(UUID id, Track next) implements ProjectChange {
        public TrackAdded {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record TrackUpdated(UUID id, Track previous, Track next) implements ProjectChange {
        public TrackUpdated {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record TrackRemoved(UUID id, Track previous) implements ProjectChange {
        public TrackRemoved {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
        }
    }

    // ----- AudioClip ----------------------------------------------------------------------------

    record AudioClipAdded(UUID id, AudioClip next) implements ProjectChange {
        public AudioClipAdded {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record AudioClipUpdated(UUID id, AudioClip previous, AudioClip next) implements ProjectChange {
        public AudioClipUpdated {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record AudioClipRemoved(UUID id, AudioClip previous) implements ProjectChange {
        public AudioClipRemoved {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
        }
    }

    // ----- MidiClip -----------------------------------------------------------------------------

    record MidiClipAdded(UUID id, MidiClip next) implements ProjectChange {
        public MidiClipAdded {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record MidiClipUpdated(UUID id, MidiClip previous, MidiClip next) implements ProjectChange {
        public MidiClipUpdated {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record MidiClipRemoved(UUID id, MidiClip previous) implements ProjectChange {
        public MidiClipRemoved {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
        }
    }

    // ----- MixerChannel -------------------------------------------------------------------------

    record MixerChannelAdded(UUID id, MixerChannel next) implements ProjectChange {
        public MixerChannelAdded {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record MixerChannelUpdated(UUID id, MixerChannel previous, MixerChannel next) implements ProjectChange {
        public MixerChannelUpdated {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record MixerChannelRemoved(UUID id, MixerChannel previous) implements ProjectChange {
        public MixerChannelRemoved {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
        }
    }

    // ----- Return -------------------------------------------------------------------------------

    record ReturnAdded(UUID id, Return next) implements ProjectChange {
        public ReturnAdded {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record ReturnUpdated(UUID id, Return previous, Return next) implements ProjectChange {
        public ReturnUpdated {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record ReturnRemoved(UUID id, Return previous) implements ProjectChange {
        public ReturnRemoved {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
        }
    }

    // ----- AutomationLane -----------------------------------------------------------------------

    record AutomationLaneAdded(UUID id, AutomationLane next) implements ProjectChange {
        public AutomationLaneAdded {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record AutomationLaneUpdated(UUID id, AutomationLane previous, AutomationLane next) implements ProjectChange {
        public AutomationLaneUpdated {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
            Objects.requireNonNull(next, "next must not be null");
        }
    }

    record AutomationLaneRemoved(UUID id, AutomationLane previous) implements ProjectChange {
        public AutomationLaneRemoved {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(previous, "previous must not be null");
        }
    }
}
