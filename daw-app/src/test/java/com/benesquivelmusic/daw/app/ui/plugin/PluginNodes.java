package com.benesquivelmusic.daw.app.ui.plugin;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;

import java.util.ArrayList;
import java.util.List;

/** Small toolkit-free scene-graph walkers shared by the story-303 FX tests. */
final class PluginNodes {

    private PluginNodes() {
        // test utility
    }

    /** All {@link TextField}s in the subtree rooted at {@code root} (inclusive). */
    static List<TextField> findTextFields(Node root) {
        List<TextField> found = new ArrayList<>();
        collect(root, TextField.class, found);
        return found;
    }

    /** All {@link Label} text values in the subtree rooted at {@code root}. */
    static List<String> labelTexts(Node root) {
        List<Label> labels = new ArrayList<>();
        collect(root, Label.class, labels);
        return labels.stream().map(Label::getText).toList();
    }

    /** Whether the subtree rooted at {@code root} contains a {@link ProgressIndicator}. */
    static boolean hasProgressIndicator(Node root) {
        List<ProgressIndicator> found = new ArrayList<>();
        collect(root, ProgressIndicator.class, found);
        return !found.isEmpty();
    }

    private static <T> void collect(Node node, Class<T> type, List<? super T> out) {
        if (node == null) {
            return;
        }
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, out);
            }
        }
    }
}
