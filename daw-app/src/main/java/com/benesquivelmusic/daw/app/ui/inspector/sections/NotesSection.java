package com.benesquivelmusic.daw.app.ui.inspector.sections;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;

import javafx.beans.property.StringProperty;
import javafx.scene.control.TextArea;

/**
 * "NOTES" inspector section (UI Design Book §5.6, story 272).
 *
 * <p>Plain multi-line {@link TextArea} per track. Persisted in the
 * project model under a new schema field — see <em>story 188</em>
 * (project version migration) for the coordinated rollout. For this PR
 * the {@code @JsonIgnore} TODO lives on the controller side so older
 * projects still load.
 */
public final class NotesSection extends InspectorSection {

    public static final String DEFAULT_STYLE_CLASS = "inspector-notes-section";

    // TODO(story-188): wire notesProperty to a @JsonIgnore field on the
    // track model so older projects keep loading; full schema migration
    // lives in the project version-migration story.
    private final TextArea textArea = new TextArea();

    public NotesSection(String title) {
        super(title == null ? "NOTES" : title);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        textArea.setWrapText(true);
        textArea.setPrefRowCount(4);
        textArea.getStyleClass().add("inspector-notes-text");
        textArea.setFocusTraversable(true);
        setBody(textArea);
    }

    /** @return the underlying text-area for tests / direct binding. */
    public TextArea getTextArea() {
        return textArea;
    }

    /** Convenience: the {@code TextArea}'s text property. */
    public StringProperty notesProperty() {
        return textArea.textProperty();
    }
}
