package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.controls.BreadcrumbBar;
import com.benesquivelmusic.daw.app.ui.design.SpacingTokens;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.SplitPane;

import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Workshop view — a 60/40 side-by-side layout that pairs the arrangement
 * with a focused plugin GUI (story 281, UI Design Book §4 Concept F).
 *
 * <h2>Layout</h2>
 *
 * <p>A horizontal {@link SplitPane} with two panes:</p>
 *
 * <ul>
 *   <li><strong>Left (60&nbsp;%):</strong> the arrangement — caller supplies
 *       the existing arrangement panel <em>verbatim</em> via
 *       {@link #setArrangementContent(Node)}. Workshop never re-implements
 *       the arrangement; it merely re-parents the existing nodes.</li>
 *   <li><strong>Right (40&nbsp;%):</strong> a vertical {@link VBox} of
 *       <ol>
 *         <li>the {@link BreadcrumbBar} breadcrumb header
 *             ({@code Track 03 ▸ Insert 1 ▸ Serum}),</li>
 *         <li>a {@code ◯ Detach} {@link Button} that fires a typed
 *             {@link DetachPluginRequestedEvent} (story 282 consumes it,
 *             this story stops at the event-fire stub),</li>
 *         <li>the focused {@link PluginViewContainer} (the same one across
 *             every selection change — see Selection plumbing),</li>
 *         <li>the clip-detail pane below the plugin pane — caller supplies
 *             the existing {@code AudioEditorView} or piano-roll node
 *             <em>verbatim</em> via {@link #setClipDetailContent(Node)}.</li>
 *       </ol>
 *       The plugin pane and the clip-detail pane both grow with
 *       {@link Priority#ALWAYS}, so a Workshop right-pane that has both
 *       populated splits the remaining vertical real-estate equally —
 *       matching the §4 Concept F mockup ("clip detail appears BELOW the
 *       plugin pane" at roughly half-and-half).</li>
 * </ul>
 *
 * <h2>Selection plumbing</h2>
 *
 * <p>This view is a <strong>passive presenter</strong>: it exposes
 * {@link #setFocusedPlugin(int, String, Node)} /
 * {@link #setFocusedPlugin(List, Node)} /
 * {@link #setClipDetailContent(Node)} as the push surface and never
 * subscribes to the {@code InspectorSelectionModel} itself. The single
 * authority over selection-driven push is
 * {@link WorkshopSelectionHostController}, which the application controller
 * builds alongside this view and wires to the shared
 * {@code InspectorSelectionModel}. The view-level listener was removed in
 * the story-281 review pass — the controller and view were both reacting
 * to the same model and stomping on each other's writes.</p>
 *
 * <h2>Design type — plain {@link BorderPane}, not Control/Skin</h2>
 *
 * <p>Per the JavaFX-design rules the Control/Skin pattern is for "reusable
 * widget with its own observable state". {@code WorkshopView} is a one-off
 * application layout, so it subclasses {@link BorderPane} directly —
 * forcing Control/Skin here would add ceremony with no payoff. Mirrors the
 * {@link PerformanceStageView} design type decision (story 280).</p>
 *
 * <h2>Theming &amp; density</h2>
 *
 * <p>All colours and spacings resolve from CSS tokens — the workshop adds
 * <em>no</em> hard-coded colour. A {@link com.benesquivelmusic.daw.app.ui.theme.ThemeManager}
 * palette swap re-tints the view with no code change (story 277 contract,
 * mirrored by {@code PerformanceStageView}).</p>
 *
 * <h2>Non-Goals (story 281)</h2>
 *
 * <ul>
 *   <li>Detached / floating plugin windows — story 282 consumes the
 *       {@link DetachPluginRequestedEvent} fired here.</li>
 *   <li>Plugin parameter automation overlay.</li>
 *   <li>Per-plugin layout persistence.</li>
 *   <li>Replacing the plugin-parameter renderer — Workshop REUSES
 *       {@link PluginViewContainer} and accepts whatever Node the caller
 *       passes.</li>
 *   <li>Interactive breadcrumb navigation — plain text only.</li>
 *   <li>Multi-plugin tabs.</li>
 * </ul>
 */
public final class WorkshopView extends BorderPane {

    /** Stable style class — selectable as {@code .workshop-view}. */
    public static final String STYLE_CLASS = "workshop-view";

    /**
     * The §6 Concept F 60/40 horizontal split. The user can drag the divider
     * — per the story "60/40 split is user-resizable; save split position
     * per project". Persistence is the caller's responsibility (the view
     * exposes {@link #dividerPosition()} for that purpose).
     */
    public static final double DEFAULT_DIVIDER_POSITION = 0.6;

    private final ResourceBundle messages;

    // ── Scene-graph nodes held for test seams / runtime updates ───────────
    private final SplitPane split;
    private final StackPane arrangementHost;
    private final StackPane clipDetailHost;
    private final BreadcrumbBar breadcrumb;
    private final Button detachButton;
    private final PluginViewContainer pluginContainer;
    private final Label emptyPlaceholder;
    private final VBox rightPane;

    /**
     * Creates a Workshop view backed by the supplied i18n bundle.
     *
     * <p>The view starts <em>empty</em>: arrangement and clip-detail panes
     * are unset (callers wire them via
     * {@link #setArrangementContent(Node)} / {@link #setClipDetailContent(Node)})
     * and the right pane shows the "No plugin focused" placeholder until a
     * caller pushes a focused-plugin Node via
     * {@link #setFocusedPlugin(int, String, Node)}.</p>
     *
     * <p>Selection-driven push is the responsibility of
     * {@link WorkshopSelectionHostController}; this view holds no reference
     * to the {@code InspectorSelectionModel}.</p>
     *
     * @param messages the {@code Messages} resource bundle for all
     *                 user-facing strings (Skill §14); must not be
     *                 {@code null}
     */
    public WorkshopView(ResourceBundle messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");

        getStyleClass().add(STYLE_CLASS);
        setAccessibleRole(AccessibleRole.NODE);
        setAccessibleRoleDescription("Workshop");

        // ── Right pane: breadcrumb / detach / plugin / clip detail ────────
        this.breadcrumb = new BreadcrumbBar();
        this.breadcrumb.getStyleClass().add("workshop-breadcrumb");
        // Source the separator glyph from the bundle so locale overrides
        // can swap it without touching code (Skill §14). BreadcrumbBar's
        // DEFAULT_SEPARATOR remains as a code-level fallback for non-
        // Workshop callers.
        this.breadcrumb.setSeparator(messages.getString("workshop.breadcrumb.separator"));

        // ◯ Detach button — typed event stub for story 282 (skill §12).
        this.detachButton = new Button(messages.getString("workshop.detach"));
        this.detachButton.getStyleClass().addAll("dawg-button", "workshop-detach-button");
        this.detachButton.setOnAction(_ ->
                detachButton.fireEvent(new DetachPluginRequestedEvent()));

        Region headerSpacer = new Region();
        // Only the spacer grows — the breadcrumb sits flush left and the
        // Detach button pins flush right. Letting BOTH the breadcrumb and
        // the spacer grow (review-flagged S7) split the remainder and
        // pulled the Detach button off the right edge.
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox breadcrumbHeader = new HBox(breadcrumb, headerSpacer, detachButton);
        breadcrumbHeader.setSpacing(SpacingTokens.SPACING_MD);
        breadcrumbHeader.getStyleClass().add("workshop-breadcrumb-header");

        this.pluginContainer = new PluginViewContainer();
        VBox.setVgrow(pluginContainer, Priority.ALWAYS);
        this.emptyPlaceholder = new Label(messages.getString("workshop.plugin.empty"));
        this.emptyPlaceholder.getStyleClass().add("workshop-empty-placeholder");
        this.pluginContainer.setPlaceholder(emptyPlaceholder);

        this.clipDetailHost = new StackPane();
        this.clipDetailHost.getStyleClass().add("workshop-clip-detail-host");
        // Both the plugin pane and the clip-detail pane grow equally — the
        // §4 Concept F mockup pairs them at roughly half-and-half of the
        // right pane's vertical real-estate. Without this both panes
        // share their parent's space according to pref-height, with no
        // growth headroom (review S8).
        VBox.setVgrow(clipDetailHost, Priority.ALWAYS);

        this.rightPane = new VBox(breadcrumbHeader, pluginContainer, clipDetailHost);
        this.rightPane.setSpacing(SpacingTokens.SPACING_MD);
        this.rightPane.setPadding(new Insets(SpacingTokens.SPACING_MD));
        this.rightPane.setFillWidth(true);
        this.rightPane.getStyleClass().add("workshop-right-pane");

        // ── Left pane: arrangement host (caller fills) ────────────────────
        this.arrangementHost = new StackPane();
        this.arrangementHost.getStyleClass().add("workshop-arrangement-host");

        // ── 60/40 split ────────────────────────────────────────────────────
        this.split = new SplitPane(arrangementHost, rightPane);
        this.split.setOrientation(Orientation.HORIZONTAL);
        this.split.getStyleClass().add("workshop-split");
        // Initial divider position is the §4 Concept F 60 % / 40 % default.
        // The divider is user-resizable per the story; the caller persists
        // any subsequent position through dividerPosition().
        this.split.setDividerPositions(DEFAULT_DIVIDER_POSITION);
        setCenter(split);
    }

    // ── Caller-supplied content seams ───────────────────────────────────────

    /**
     * Installs the arrangement panel into the left pane. Caller is
     * expected to pass the <em>existing</em> arrangement node verbatim per
     * the story's "reuse existing arrangement panel components verbatim"
     * rule. Passing {@code null} clears the left pane.
     *
     * @param node the arrangement node, or {@code null} to clear
     */
    public void setArrangementContent(Node node) {
        arrangementHost.getChildren().clear();
        if (node != null) {
            arrangementHost.getChildren().add(node);
        }
    }

    /**
     * Installs the clip-detail node (typically an {@code AudioEditorView}
     * for an audio clip or a piano-roll for a MIDI clip) into the slot
     * <em>below</em> the plugin pane. Caller is expected to pass an
     * existing editor node verbatim per the story's "reuses existing
     * AudioEditorView / piano-roll components" rule. Passing {@code null}
     * clears the clip-detail slot (no clip selected).
     *
     * @param node the clip-detail node, or {@code null} to clear
     */
    public void setClipDetailContent(Node node) {
        clipDetailHost.getChildren().clear();
        if (node != null) {
            clipDetailHost.getChildren().add(node);
        }
    }

    /**
     * Updates the right pane to show the focused plugin for the given
     * selection. The breadcrumb is set to
     * {@code Track NN ▸ Insert 1 ▸ pluginName} (note: the insert index is
     * currently hard-coded to {@code 1} as a placeholder — multi-insert
     * disambiguation lands with the inspector wiring story; use
     * {@link #setFocusedPlugin(List, Node)} to supply the real insert
     * index from the project model). The {@link PluginViewContainer}'s
     * focused-plugin slot is set to {@code pluginNode}.
     *
     * <p>The container itself is <strong>not</strong> rebuilt — only its
     * inner content slot — so the right pane's parent identity is stable
     * across selection switches. This is the contract pinned by
     * {@code WorkshopPluginSwitchTest}.</p>
     *
     * @param trackIndex the 1-based track index for the breadcrumb
     * @param pluginName the focused plugin's display name, or {@code null}
     *                   for unnamed
     * @param pluginNode the focused plugin's GUI node, or {@code null} to
     *                   reveal the empty-state placeholder
     */
    public void setFocusedPlugin(int trackIndex, String pluginName, Node pluginNode) {
        if (pluginNode == null) {
            // Empty state — clear breadcrumb so UI is self-consistent
            // with the "No plugin focused" placeholder.
            breadcrumb.setSegments(List.of());
        } else {
            // Breadcrumb segments: Track NN ▸ Insert 1 ▸ pluginName.
            // "Insert 1" is the placeholder the story specifies in the
            // motivation — Workshop today routes the active-insert focus
            // through this single segment; multi-insert disambiguation lands
            // with the inspector wiring story.
            breadcrumb.setSegments(buildSegments(messages, trackIndex, /* insertIndex= */ 1, pluginName));
        }
        pluginContainer.setPluginView(pluginNode);
    }

    /**
     * Clears the focused-plugin slot back to the empty placeholder and
     * empties the breadcrumb. Used by the host controller when the
     * inspector selection transitions to a non-insert (track / send /
     * bus / empty) so the right pane stops showing a stale plugin.
     *
     * <p>Per the {@code WorkshopPluginSwitchTest} contract, this swaps
     * the {@link PluginViewContainer}'s inner content only — the
     * container itself is preserved (its parent identity remains the
     * right pane VBox).</p>
     */
    public void clearFocusedPlugin() {
        breadcrumb.setSegments(List.of());
        pluginContainer.setPluginView(null);
    }

    /**
     * Variant of {@link #setFocusedPlugin(int, String, Node)} that takes
     * the rendered breadcrumb segments directly — useful when the host
     * controller has already composed the full {@code Track NN ▸ Insert M
     * ▸ <plugin name>} triple and wants to push it verbatim (the host
     * knows the real insert number; this view's default composer
     * hard-codes "Insert 1" because the standalone view has no project
     * context).
     *
     * @param segments   the breadcrumb segments to render
     *                   (e.g. {@code ["Track 03", "Insert 2", "Reverb"]});
     *                   may be {@code null}/empty to clear the breadcrumb
     * @param pluginNode the focused plugin GUI, or {@code null} to clear
     */
    public void setFocusedPlugin(List<String> segments, Node pluginNode) {
        breadcrumb.setSegments(segments == null ? List.of() : segments);
        pluginContainer.setPluginView(pluginNode);
    }

    /**
     * @return the currently-focused plugin Node, or {@code null} if the
     *         right pane is showing its empty-state placeholder — test
     *         seam exposed at the {@link WorkshopView} level for tests
     *         that assert on identity of the focused plugin across
     *         selection changes (in addition to the
     *         {@link PluginViewContainer}-level seam)
     */
    public Node focusedPluginNode() {
        return pluginContainer.getPluginView();
    }

    /**
     * @return the clip-detail Node currently shown in the clip-detail
     *         slot below the plugin pane, or {@code null} when no clip
     *         is selected — test seam
     */
    public Node clipDetailContent() {
        return clipDetailHost.getChildren().isEmpty()
                ? null
                : clipDetailHost.getChildren().get(0);
    }

    /**
     * Builds the breadcrumb segments for a track / insert / plugin trio.
     * Pure static helper used by both the view (default composer in
     * {@link #setFocusedPlugin(int, String, Node)}) and the primary caller
     * {@link WorkshopSelectionHostController} (which assembles the full
     * triple with the real insert index from the project model). Kept
     * accessible so the assembly logic lives in exactly one place — the
     * controller does not re-implement the format templates.
     *
     * <p>Format strings come from the {@code Messages} resource bundle
     * (keys {@code workshop.breadcrumb.trackFormat} and
     * {@code workshop.breadcrumb.insertFormat}) so locale overrides can
     * relabel without touching code (Skill §14).</p>
     *
     * @param bundle      the {@code Messages} resource bundle
     * @param trackIndex  the 1-based track index
     * @param insertIndex the 1-based insert index
     * @param pluginName  the plugin display name (may be {@code null} → omitted)
     * @return the ordered segment list
     */
    static List<String> buildSegments(ResourceBundle bundle, int trackIndex, int insertIndex, String pluginName) {
        String trackSegment = String.format(bundle.getLocale(), bundle.getString("workshop.breadcrumb.trackFormat"), trackIndex);
        String insertSegment = String.format(bundle.getLocale(), bundle.getString("workshop.breadcrumb.insertFormat"), insertIndex);
        if (pluginName == null || pluginName.isBlank()) {
            return List.of(trackSegment, insertSegment);
        }
        return List.of(trackSegment, insertSegment, pluginName);
    }

    // ── Test seams / runtime accessors ────────────────────────────────────

    /** @return the underlying 60/40 {@link SplitPane} (test seam). */
    public SplitPane splitPane() {
        return split;
    }

    /** @return the left arrangement host (test seam). */
    public StackPane arrangementHost() {
        return arrangementHost;
    }

    /** @return the right vertical pane (test seam). */
    public VBox rightPane() {
        return rightPane;
    }

    /** @return the right-pane breadcrumb bar (test seam). */
    public BreadcrumbBar breadcrumb() {
        return breadcrumb;
    }

    /** @return the right-pane Detach button (test seam). */
    public Button detachButton() {
        return detachButton;
    }

    /** @return the stable-identity plugin view container (test seam). */
    public PluginViewContainer pluginContainer() {
        return pluginContainer;
    }

    /** @return the clip-detail host below the plugin pane (test seam). */
    public StackPane clipDetailHost() {
        return clipDetailHost;
    }

    /**
     * @return the current divider position in {@code [0.0, 1.0]}; the
     *         caller persists this per-project per the story
     */
    public double dividerPosition() {
        double[] positions = split.getDividerPositions();
        return positions.length == 0 ? DEFAULT_DIVIDER_POSITION : positions[0];
    }

    /**
     * Sets the divider position — used by the host on project load to
     * restore the persisted split.
     *
     * @param position the new position in {@code [0.0, 1.0]}
     */
    public void setDividerPosition(double position) {
        split.setDividerPositions(position);
    }
}
