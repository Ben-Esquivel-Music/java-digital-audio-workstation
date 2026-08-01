package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The Recording-category access seam (story 308, Settings View Design
 * Book §3.3 / §7 Stage 4) — everything the settings surface needs from
 * the live metronome authorities that the absorbed
 * {@code MetronomeSettingsDialog} used to read and write directly.
 *
 * <p>Implemented by {@code MetronomeController}. Reads come from the
 * live {@code Metronome} / controller state / side-output router;
 * {@link #apply(Snapshot)} performs exactly the old dialog-apply plus
 * context-menu writes: metronome setters, preference writes for
 * enabled/count-in, router cue-level clear+set, and the global
 * {@code ~/.daw/metronome-settings.json} save.</p>
 */
public interface RecordingSettingsAccess {

    /**
     * One immutable value carrier for the Recording facts the surface
     * edits (§3.3 Metronome · Click routing · Cue mix).
     *
     * @param enabled     whether the metronome is enabled
     * @param countIn     the count-in mode; never {@code null}
     * @param clickOutput the click routing; never {@code null}
     * @param cueBusId    the cue bus carrying the click, or empty for
     *                    "Main mix only"
     */
    record Snapshot(boolean enabled,
                    CountInMode countIn,
                    ClickOutput clickOutput,
                    Optional<UUID> cueBusId) {
        public Snapshot {
            Objects.requireNonNull(countIn, "countIn must not be null");
            Objects.requireNonNull(clickOutput, "clickOutput must not be null");
            Objects.requireNonNull(cueBusId, "cueBusId must not be null");
        }
    }

    /** @return the current live Recording facts */
    Snapshot current();

    /** @return the live cue buses available for "Send click to" (may be empty) */
    List<CueBus> cueBuses();

    /**
     * Applies an edited snapshot through the same authorities as the
     * absorbed dialog (live objects + prefs + global JSON store).
     *
     * @param snapshot the edited Recording facts; not {@code null}
     */
    void apply(Snapshot snapshot);
}
