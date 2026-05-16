package com.benesquivelmusic.daw.app.ui.inspector.sections;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorSection;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * "ROUTING" inspector section (UI Design Book §5.6, story 272).
 *
 * <p>Collapsible <em>by default</em> — for most tracks the routing
 * graph is implied by Input / Output choices in {@link TrackSection}.
 * For buses and routing-dense sessions this section shows the channel's
 * full routing graph.
 */
public final class RoutingSection extends InspectorSection {

    public static final String DEFAULT_STYLE_CLASS = "inspector-routing-section";

    private final VBox graph = new VBox();

    public RoutingSection(String title) {
        super(title == null ? "ROUTING" : title);
        getStyleClass().add(DEFAULT_STYLE_CLASS);

        // Collapsed by default — see §5.6.
        setExpanded(false);

        graph.getStyleClass().add("inspector-routing-graph");
        setBody(graph);
    }

    /** Replaces the displayed routing graph. */
    public void setGraph(java.util.List<String> edges) {
        graph.getChildren().clear();
        if (edges == null) return;
        for (String edge : edges) {
            Label l = new Label(edge);
            l.getStyleClass().add("inspector-routing-edge");
            graph.getChildren().add(l);
        }
    }

    public VBox getGraphContainer() { return graph; }
}
