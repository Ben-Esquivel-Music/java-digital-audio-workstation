package com.benesquivelmusic.daw.app.ui.inspector;

import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.event.EventType;

import java.util.Objects;

/**
 * Typed JavaFX event fired by {@link InspectorDrawer} whenever its
 * {@link InspectorSelectionModel#selectionProperty() selection} changes.
 *
 * <p>Per Skill §12 and UI Design Book §5.6 (story 272), the inspector's
 * propagation mechanism is a custom {@link Event} subclass that bubbles
 * through the scene graph — preferable to ad-hoc {@code Consumer<…>}
 * callbacks because it integrates with FXML and the standard event
 * dispatch chain. The model is the source of truth; events are the
 * propagation mechanism.
 *
 * <p>Subscribers attach via the standard {@code addEventHandler} /
 * {@code addEventFilter} chain:
 * <pre>{@code
 * drawer.addEventHandler(InspectorSelectionEvent.SELECTION_CHANGED, e -> {
 *     InspectorSelection s = e.getSelection();
 *     ...
 * });
 * }</pre>
 */
public final class InspectorSelectionEvent extends Event {

    private static final long serialVersionUID = 20260516L;

    /**
     * Event type fired by {@link InspectorDrawer} when
     * {@link InspectorSelectionModel#selectionProperty()} transitions to
     * a new value. Singular by design — additional supplemental event
     * types may be added later, but this one is the canonical "selection
     * changed" notification.
     */
    public static final EventType<InspectorSelectionEvent> SELECTION_CHANGED =
            new EventType<>(Event.ANY, "INSPECTOR_SELECTION_CHANGED");

    private final InspectorSelection selection;

    /**
     * Creates a selection-changed event with no explicit source/target
     * (the dispatch chain fills them in when fired via
     * {@link javafx.scene.Node#fireEvent(Event)}).
     *
     * @param selection the new selection — never {@code null}; use
     *                  {@link InspectorSelection#empty()} for the
     *                  no-selection state
     */
    public InspectorSelectionEvent(InspectorSelection selection) {
        super(SELECTION_CHANGED);
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    /**
     * @param source    the event source (typically the
     *                  {@link InspectorDrawer})
     * @param target    the event target
     * @param selection the new selection — never {@code null}
     */
    public InspectorSelectionEvent(Object source, EventTarget target,
                                   InspectorSelection selection) {
        super(source, target, SELECTION_CHANGED);
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    /** @return the new selection — never {@code null}. */
    public InspectorSelection getSelection() {
        return selection;
    }
}
