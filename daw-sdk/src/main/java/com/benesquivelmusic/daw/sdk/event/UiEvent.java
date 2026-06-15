package com.benesquivelmusic.daw.sdk.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Sealed family of <strong>UI-only discrete facts</strong> — the sibling of
 * {@link DawEvent} for changes that several unrelated <em>surfaces</em> care
 * about but that never need to reach the engine (Control Synchronization Design
 * Book §4.2, §7, Appendix A; story 292).
 *
 * <p>Selection and undo-state are owned by the UI (the {@code SelectionVM} and
 * {@code HistoryVM} view-models in {@code daw-app}), not by {@code daw-core}, so
 * modelling them as {@code DawEvent}s would wrongly couple the engine domain to
 * a UI concern. Instead they ride the <em>same</em> typed {@link EventBus} as a
 * separate sealed family under the shared {@link BusEvent} super-type — reusing
 * the bus, its per-subscription {@link DispatchMode} marshalling, and the
 * {@code EventBusPublisher} seam rather than a second bus (story 292).</p>
 *
 * <h2>Notification, not a state-bearing delta</h2>
 *
 * <p>Like {@link DawEvent}, a {@code UiEvent} carries only the minimal fact that
 * <em>something</em> changed plus a wall-clock {@link #timestamp()}; subscribers
 * re-read the post-change state from the relevant view-model (the menu bar
 * recomputes enablement from {@code SelectionVM}/{@code HistoryVM} via
 * {@code MenuEnablementPolicy}; the toolbar re-reads {@code HistoryVM}). This is
 * why a bare {@code SelectionChanged} (with no "what is now selected" payload) is
 * sufficient — the canonical state lives in the VM, not in the event.</p>
 *
 * <h2>Exhaustiveness</h2>
 *
 * <p>The {@code permits} clause is explicit and exhaustive, so an exhaustive
 * {@code switch} over {@code UiEvent} compiles without a {@code default} and
 * adding a variant forces every consumer to be updated at compile time
 * (story 202's sealing guarantee, mirrored here).</p>
 *
 * @see DawEvent
 * @see BusEvent
 */
public sealed interface UiEvent extends BusEvent
        permits UiEvent.SelectionChanged,
                UiEvent.UndoStateChanged,
                UiEvent.SettingsApplied {

    /** Returns the wall-clock instant at which this event was produced. */
    Instant timestamp();

    /**
     * Emitted when the UI selection changes — a different track, clip, or device
     * becomes selected or the selection is cleared (Control Synchronization
     * Design Book §5.5). Edit-tool changes do <em>not</em> raise this event: the
     * tool is bound directly (§2.7, {@code SelectionVM#setTool}), so it never
     * reaches {@code CoreSelectionIntentHandler}'s announce step. Subscribers (the inspector,
     * the menu-enablement policy, the canvas highlight, the mixer focus) re-read
     * the current selection from {@code SelectionVM}; the event itself is a bare
     * notification.
     *
     * @param timestamp wall-clock instant of the event
     */
    record SelectionChanged(Instant timestamp) implements UiEvent {
        public SelectionChanged {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the command-history (undo/redo) state changes — an action was
     * executed, undone, or redone (Control Synchronization Design Book §5.6,
     * §6.9). Subscribers (the Edit menu, the undo/redo toolbar buttons, the
     * history panel) re-read {@code HistoryVM}'s {@code canUndo}/{@code canRedo}
     * and the next undo/redo labels.
     *
     * @param timestamp wall-clock instant of the event
     */
    record UndoStateChanged(Instant timestamp) implements UiEvent {
        public UndoStateChanged {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
        }
    }

    /**
     * Emitted when the user applies appearance settings in the Preferences
     * dialog (Control Synchronization Design Book §6.7, story 294). Unlike the
     * other two variants — which are bare notifications whose canonical state
     * lives in a view-model — this event <em>does</em> carry the new appearance
     * values, because the three subscriber managers
     * ({@code ThemeManager}/{@code DensityManager}/{@code MotionManager}, all in
     * {@code daw-app}) are the canonical store for their own slice and have no
     * shared VM to re-read from. The Preferences dialog therefore publishes this
     * event instead of poking the managers directly, decoupling the dialog from
     * them: each manager subscribes and applies only its own field via its own
     * existing setter (which persists + re-applies).
     *
     * <p><strong>Why the ids are {@code String}s, not enums.</strong> This SDK
     * module must not depend on {@code daw-app}, where {@code ThemeManager.Theme}
     * and {@code DensityMode} live, so the appearance choices ride as their enum
     * {@link Enum#name()} strings. {@code themeId} is a {@code ThemeManager.Theme}
     * name (e.g. {@code "ONYX_REFINED"}) and {@code densityId} is a
     * {@code DensityMode} name (e.g. {@code "COMFORTABLE"}); the subscriber
     * managers parse them back via {@code valueOf}, defending against an
     * unrecognised name rather than throwing on the bus delivery thread.
     *
     * @param timestamp    wall-clock instant of the event
     * @param themeId      the chosen token theme as a {@code ThemeManager.Theme}
     *                     enum {@code name()} (never {@code null})
     * @param densityId    the chosen density profile as a {@code DensityMode}
     *                     enum {@code name()} (never {@code null})
     * @param reduceMotion the chosen Reduce Motion flag
     */
    record SettingsApplied(Instant timestamp, String themeId, String densityId,
                           boolean reduceMotion) implements UiEvent {
        public SettingsApplied {
            Objects.requireNonNull(timestamp, "timestamp must not be null");
            Objects.requireNonNull(themeId, "themeId must not be null");
            Objects.requireNonNull(densityId, "densityId must not be null");
        }
    }
}
