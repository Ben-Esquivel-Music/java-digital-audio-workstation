package com.benesquivelmusic.daw.app.ui.help;

import javafx.scene.Node;

import java.util.Objects;
import java.util.Optional;

/**
 * Static helpers for tagging arbitrary {@link Node} instances with a help
 * topic slug. Stored in {@link Node#getProperties()} so any control —
 * including JavaFX-built-in ones — can advertise a help topic without a
 * subclass.
 *
 * <pre>{@code
 * Button playButton = new Button("Play");
 * HelpControls.setHelpTopic(playButton, "transport");
 * }</pre>
 *
 * <p>The {@link HelpKeyHandler} walks up the focus / hover chain looking
 * for the nearest ancestor with a topic, so containers can set a default
 * topic that applies to all their children.</p>
 */
public final class HelpControls {

    /** Key used in {@link Node#getProperties()} for the help topic slug. */
    public static final String HELP_TOPIC_KEY = "daw.help.topic";

    private HelpControls() {
        // utility class
    }

    /**
     * Tags {@code node} with a help topic slug. Pass {@code null} to clear.
     */
    public static void setHelpTopic(Node node, String slug) {
        Objects.requireNonNull(node, "node");
        if (slug == null) {
            node.getProperties().remove(HELP_TOPIC_KEY);
        } else {
            node.getProperties().put(HELP_TOPIC_KEY, slug);
        }
    }

    /**
     * Returns the topic slug attached directly to {@code node}, ignoring
     * ancestors. Use {@link #findHelpTopic(Node)} to walk the parent chain.
     */
    public static Optional<String> getHelpTopic(Node node) {
        if (node == null) {
            return Optional.empty();
        }
        Object value = node.getProperties().get(HELP_TOPIC_KEY);
        if (value instanceof String s && !s.isBlank()) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Walks up the parent chain of {@code node} returning the slug attached
     * to the nearest ancestor (inclusive) tagged with a help topic, or
     * {@link Optional#empty()} if none.
     */
    public static Optional<String> findHelpTopic(Node node) {
        Node current = node;
        while (current != null) {
            Optional<String> slug = getHelpTopic(current);
            if (slug.isPresent()) {
                return slug;
            }
            current = current.getParent();
        }
        return Optional.empty();
    }
}
