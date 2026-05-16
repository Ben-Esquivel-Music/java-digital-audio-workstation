package com.benesquivelmusic.daw.app.ui.inspector;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Objects;

/**
 * State holder for the Inspector's current {@link InspectorSelection}
 * (UI Design Book §5.6, story 272). Single source of truth — the
 * drawer, sections, and any external listener observe this one
 * property.
 *
 * <p>Per Skill §12, state and events are kept distinct: this class is
 * the state layer; {@link InspectorSelectionEvent} is the propagation
 * layer. {@link InspectorDrawer} watches
 * {@link #selectionProperty()} and fires the typed event each time it
 * transitions to a new value.
 *
 * <p>Writes are clamped to a non-null value — passing {@code null} to
 * {@link #setSelection(InspectorSelection)} is treated as
 * {@link InspectorSelection#empty()}.
 */
public final class InspectorSelectionModel {

    private final ObjectProperty<InspectorSelection> selection =
            new SimpleObjectProperty<>(this, "selection", InspectorSelection.empty()) {
                @Override
                public void set(InspectorSelection newValue) {
                    super.set(newValue == null ? InspectorSelection.empty() : newValue);
                }
            };

    /** Stable name read by the drawer's selection listener. */
    private final ReadOnlyObjectWrapper<InspectorSelection> previousSelection =
            new ReadOnlyObjectWrapper<>(this, "previousSelection",
                    InspectorSelection.empty());

    /** Creates a model whose initial selection is {@link InspectorSelection#empty()}. */
    public InspectorSelectionModel() {
        selection.addListener((obs, oldV, newV) -> previousSelection.set(
                oldV == null ? InspectorSelection.empty() : oldV));
    }

    /** @return the current selection — never {@code null}. */
    public InspectorSelection getSelection() {
        return selection.get();
    }

    /**
     * Replaces the current selection. {@code null} is normalised to
     * {@link InspectorSelection#empty()} so listeners never see
     * {@code null}.
     *
     * @param newSelection the new selection, or {@code null} for empty
     */
    public void setSelection(InspectorSelection newSelection) {
        selection.set(newSelection);
    }

    /** Convenience: clear to {@link InspectorSelection#empty()}. */
    public void clear() {
        selection.set(InspectorSelection.empty());
    }

    /**
     * @return the current selection property; observers attach to this
     *         via {@link ObjectProperty#addListener(javafx.beans.value.ChangeListener)}
     */
    public ObjectProperty<InspectorSelection> selectionProperty() {
        return selection;
    }

    /** @return the value of the selection immediately before the most recent change. */
    public ReadOnlyObjectProperty<InspectorSelection> previousSelectionProperty() {
        return previousSelection.getReadOnlyProperty();
    }

    @Override
    public String toString() {
        return "InspectorSelectionModel{selection=" + Objects.toString(getSelection()) + "}";
    }
}
