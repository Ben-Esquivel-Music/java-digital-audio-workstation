package com.benesquivelmusic.daw.app.ui.views;

import com.benesquivelmusic.daw.app.ui.controls.LevelMeter;
import com.benesquivelmusic.daw.app.ui.controls.TrackStrip;
import com.benesquivelmusic.daw.app.ui.design.SpacingTokens;
import com.benesquivelmusic.daw.app.ui.theme.HardcodedColorAllowed;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Performance Stage view — an oversized-control "cockpit" for live use
 * (story 280, UI Design Book §2.5 / §3.7 / §4 Concept E).
 *
 * <p>Performance Stage is a <em>mode</em>, not a separate application. When
 * activated it replaces the whole standard chrome (toolbar, track list,
 * inspector) with a giant-control layout designed to be readable on stage,
 * on a touch device, or with the screen 1.5&nbsp;m away. Following the §2.5
 * principle — "every fader is the same {@code Control} underneath, just
 * skinned at a larger size" — this view constructs fresh instances of the
 * same {@link LevelMeter} and {@link TrackStrip} {@code Control} classes
 * used by the standard view (fresh instances, not shared references),
 * distinguished only by their {@code .size-performance} style class, and
 * plain {@code .dawg-button.size-tile-action} CUE buttons. Theming
 * therefore "just works": a {@code ThemeManager} palette swap re-tints
 * this view with no code change because every colour resolves from the
 * cascade.</p>
 *
 * <h2>Layout (§4 Concept E)</h2>
 *
 * <ul>
 *   <li><strong>Top band</strong>: a stereo bus {@link LevelMeter}
 *       ({@code .size-performance}, 24&nbsp;×&nbsp;320&nbsp;px) on the
 *       left; LUFS / true-peak / PLR readouts centred.</li>
 *   <li><strong>Centre band</strong>: an oversized 48&nbsp;px monospaced
 *       transport clock ({@code .numeric-display-stage}).</li>
 *   <li><strong>Transport row</strong>: 64&nbsp;px tall PLAY / STOP / REC /
 *       LOOP buttons ({@code .dawg-button.size-stage}), text only.</li>
 *   <li><strong>Track tile grid</strong>: one {@link TrackStrip}
 *       ({@code .size-performance}, 80&nbsp;px tall) per project track,
 *       each paired with a 28&nbsp;px CUE button
 *       ({@code .dawg-button.size-tile-action}).</li>
 *   <li><strong>Floating {@code ☰} hamburger</strong> bottom-right that
 *       opens a translucent overlay (switch to Standard view, Audio
 *       Settings, Project/File menu, Exit Performance Stage).</li>
 * </ul>
 *
 * <h2>Design type — plain {@code BorderPane}, not Control/Skin</h2>
 *
 * <p>Per the JavaFX design rules, the Control/Skin pattern is for a
 * <em>reusable widget with its own observable state</em>. {@code
 * PerformanceStageView} is a one-off application layout, so it subclasses
 * {@link BorderPane} directly — forcing Control/Skin here would add
 * ceremony with no payoff.</p>
 *
 * <h2>Same-engine wiring</h2>
 *
 * <p>The transport buttons and the exit/menu items are wired through
 * {@link Host} callbacks supplied by the application controller, so they
 * drive the <em>same</em> transport engine and view navigation as the
 * standard toolbar — never a parallel copy.</p>
 *
 * <h2>Cue stub</h2>
 *
 * <p>Story 280 does not implement clip launch. Each CUE button fires a
 * typed {@link CueLaunchRequestedEvent} via {@link #fireEvent}, so it
 * bubbles and a future audio-engine consumer can listen without this view
 * knowing who. See {@link CueLaunchRequestedEvent}.</p>
 */
@HardcodedColorAllowed("track-tile swatch is Color.web(TrackColor#getHexColor()) — "
        + "user track-colour DATA, not a theme token; shares the convention of "
        + "TrackStripCell which converts the same per-track hex the same way")
public final class PerformanceStageView extends BorderPane {

    /** Stable style class — selectable as {@code .performance-stage-view}. */
    public static final String STYLE_CLASS = "performance-stage-view";

    /**
     * Callbacks the application controller supplies so the stage drives
     * the same engine / navigation as the standard chrome.
     */
    public interface Host {
        /** Toggle play / pause — equivalent to the standard Play button. */
        void onPlay();
        /** Stop transport — equivalent to the standard Stop button. */
        void onStop();
        /** Toggle record-arm — equivalent to the standard Record button. */
        void onRecord();
        /** Toggle loop — equivalent to the standard Loop button. */
        void onToggleLoop();
        /** Leave Performance Stage and return to the previous standard view. */
        void onExitPerformanceStage();
        /** Open the Audio Settings dialog. */
        void onOpenAudioSettings();
        /** New project — invoked from the file sub-overlay. */
        void onNewProject();
        /** Open project — invoked from the file sub-overlay. */
        void onOpenProject();
        /** Save project — invoked from the file sub-overlay. */
        void onSaveProject();
        /** Recent projects — invoked from the file sub-overlay. */
        void onRecentProjects();
    }

    private final ResourceBundle messages;
    private final Host host;

    // ── Scene-graph nodes held for test seams / runtime updates ───────────
    private final LevelMeter busMeter;
    private final Label clockLabel;
    private final Button playButton;
    private final Button stopButton;
    private final Button recordButton;
    private final Button loopButton;
    private final VBox trackTileColumn;
    private final List<TrackStrip> trackTiles = new ArrayList<>();
    private final Button hamburgerButton;
    private final StackPane overlay;
    /** Main overlay panel (Standard View / Audio Settings / Project / Exit). */
    private VBox overlayMainPanel;
    /** File sub-overlay panel (New / Open / Save / Recent / Back). */
    private VBox overlayFilePanel;

    /**
     * Creates a Performance Stage view bound to the given project.
     *
     * @param project  the project whose tracks become stage tiles; must
     *                 not be {@code null}
     * @param messages the {@code Messages} resource bundle for all
     *                 user-facing strings (Skill §14); must not be
     *                 {@code null}
     * @param host     the application callbacks; must not be {@code null}
     */
    public PerformanceStageView(DawProject project,
                                ResourceBundle messages,
                                Host host) {
        Objects.requireNonNull(project, "project must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");

        getStyleClass().add(STYLE_CLASS);
        setAccessibleRole(AccessibleRole.NODE);
        setAccessibleRoleDescription("Performance Stage");

        this.busMeter = buildBusMeter();
        this.clockLabel = buildClock();
        this.playButton = stageButton("performanceStage.transport.play", host::onPlay);
        this.stopButton = stageButton("performanceStage.transport.stop", host::onStop);
        this.recordButton = stageButton("performanceStage.transport.record", host::onRecord);
        this.loopButton = stageButton("performanceStage.transport.loop", host::onToggleLoop);
        this.recordButton.getStyleClass().add("danger");

        VBox centreStack = new VBox(
                topBand(),
                clockBand(),
                transportRow());
        centreStack.setSpacing(SpacingTokens.SPACING_XXL);
        centreStack.setAlignment(Pos.TOP_CENTER);
        centreStack.setPadding(new Insets(SpacingTokens.SPACING_XXXL));

        this.trackTileColumn = new VBox();
        this.trackTileColumn.setSpacing(SpacingTokens.SPACING_SM);
        this.trackTileColumn.getStyleClass().add("performance-stage-tiles");
        rebuildTrackTiles(project);

        ScrollPane tileScroller = new ScrollPane(trackTileColumn);
        tileScroller.setFitToWidth(true);
        tileScroller.getStyleClass().add("performance-stage-tile-scroller");

        VBox content = new VBox(centreStack, tileScroller);
        VBox.setVgrow(tileScroller, Priority.ALWAYS);
        content.setFillWidth(true);

        this.hamburgerButton = buildHamburger();
        this.overlay = buildOverlay();
        overlay.setVisible(false);
        overlay.setManaged(false);

        // The hamburger floats bottom-right above the content; the overlay
        // covers the whole view when open. A StackPane layers them.
        StackPane root = new StackPane(content, hamburgerButton, overlay);
        StackPane.setAlignment(hamburgerButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(hamburgerButton, new Insets(SpacingTokens.SPACING_XL));
        setCenter(root);
    }

    // ── Top band — stereo bus meter + loudness readouts ───────────────────

    private HBox topBand() {
        Label lufs = numericReadout("LUFS  −∞");
        Label truePeak = numericReadout("TP  −∞ dB");
        Label plr = numericReadout("PLR  —");
        VBox readouts = new VBox(lufs, truePeak, plr);
        readouts.setSpacing(SpacingTokens.SPACING_SM);
        readouts.setAlignment(Pos.CENTER);

        Region leftSpacer = new Region();
        Region rightSpacer = new Region();
        HBox.setHgrow(leftSpacer, Priority.ALWAYS);
        HBox.setHgrow(rightSpacer, Priority.ALWAYS);

        HBox band = new HBox(busMeter, leftSpacer, readouts, rightSpacer);
        band.setSpacing(SpacingTokens.SPACING_XL);
        band.setAlignment(Pos.CENTER_LEFT);
        band.getStyleClass().add("performance-stage-top-band");
        return band;
    }

    private static LevelMeter buildBusMeter() {
        // The SAME LevelMeter Control as the standard view — only the
        // size-performance style class differs (24 × 320 px, dB ticks).
        LevelMeter meter = LevelMeter.create()
                .channels(2)
                .orientation(Orientation.VERTICAL)
                .size("performance")
                .build();
        meter.setAccessibleText("Stereo bus level meter");
        return meter;
    }

    private Label numericReadout(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("numeric-value");
        return label;
    }

    // ── Centre band — oversized clock ─────────────────────────────────────

    private HBox clockBand() {
        HBox band = new HBox(clockLabel);
        band.setAlignment(Pos.CENTER);
        band.getStyleClass().add("performance-stage-clock-band");
        return band;
    }

    private static Label buildClock() {
        // The story-266 transport time display at a new 48 px stage
        // variant: .numeric-display-stage mirrors .numeric-display (mono /
        // weight 500) at hero stage size.
        Label clock = new Label("00:00:00.0");
        clock.getStyleClass().addAll("time-display", "numeric-display-stage");
        clock.setAccessibleText("Transport time");
        return clock;
    }

    // ── Transport row — 64 px stage buttons ───────────────────────────────

    private HBox transportRow() {
        HBox row = new HBox(playButton, stopButton, recordButton, loopButton);
        row.setSpacing(SpacingTokens.SPACING_LG);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("performance-stage-transport");
        return row;
    }

    private Button stageButton(String messageKey, Runnable action) {
        Button button = new Button(messages.getString(messageKey));
        button.getStyleClass().addAll("dawg-button", "size-stage");
        button.setOnAction(_ -> action.run());
        return button;
    }

    // ── Track tile grid ───────────────────────────────────────────────────

    /**
     * (Re)builds the track tile column from the project's current tracks.
     * Each tile is a {@link TrackStrip} with the {@code .size-performance}
     * style class (80&nbsp;px row, 18&nbsp;px name, M/S/R toggles) paired
     * with a CUE button that fires a {@link CueLaunchRequestedEvent}.
     */
    private void rebuildTrackTiles(DawProject project) {
        Objects.requireNonNull(project, "project must not be null");
        trackTileColumn.getChildren().clear();
        trackTiles.clear();
        List<Track> tracks = project.getTracks();
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            int displayIndex = i + 1;
            TrackStrip tile = TrackStrip.create()
                    .trackIndex(displayIndex)
                    .name(track.getName())
                    .color(Color.web(track.getColor().getHexColor()))
                    .muted(track.isMuted())
                    .soloed(track.isSolo())
                    .armed(track.isArmed())
                    .showMeter(true)
                    .size("performance")
                    .build();
            trackTiles.add(tile);

            Button cue = new Button(messages.getString("performanceStage.cue"));
            cue.getStyleClass().addAll("dawg-button", "size-tile-action", "performance-stage-cue");
            // Story-280 Non-Goal: no clip-launch engine. Fire a typed,
            // bubbling CueLaunchRequestedEvent so a future consumer can
            // listen at any ancestor (skill §12) — NOT an ad-hoc callback.
            cue.setOnAction(_ -> cue.fireEvent(new CueLaunchRequestedEvent(displayIndex)));

            HBox.setHgrow(tile, Priority.ALWAYS);
            HBox tileRow = new HBox(tile, cue);
            tileRow.setSpacing(SpacingTokens.SPACING_MD);
            tileRow.setAlignment(Pos.CENTER_LEFT);
            tileRow.getStyleClass().add("performance-stage-tile-row");
            trackTileColumn.getChildren().add(tileRow);
        }
    }

    // ── Floating hamburger + translucent overlay ──────────────────────────

    private Button buildHamburger() {
        Button button = new Button("☰");
        button.getStyleClass().addAll("dawg-button", "size-stage", "performance-stage-menu");
        button.setAccessibleText(messages.getString("performanceStage.menu.tooltip"));
        button.setOnAction(_ -> setOverlayVisible(true));
        return button;
    }

    private StackPane buildOverlay() {
        Button standardView = overlayItem("performanceStage.overlay.standardView",
                host::onExitPerformanceStage);
        Button audioSettings = overlayItem("performanceStage.overlay.audioSettings",
                host::onOpenAudioSettings);
        // The "Project / File…" item is a panel-switch, not an action: it
        // pivots the overlay to the file sub-panel without closing it.
        Button projectMenu = new Button(messages.getString("performanceStage.overlay.projectMenu"));
        projectMenu.getStyleClass().addAll("dawg-button", "size-stage", "performance-stage-overlay-item");
        projectMenu.setOnAction(_ -> showOverlayPanel(overlayFilePanel));
        Button exit = overlayItem("performanceStage.overlay.exit",
                host::onExitPerformanceStage);

        overlayMainPanel = new VBox(standardView, audioSettings, projectMenu, exit);
        overlayMainPanel.setSpacing(SpacingTokens.SPACING_MD);
        overlayMainPanel.setAlignment(Pos.CENTER);
        overlayMainPanel.setPadding(new Insets(SpacingTokens.SPACING_XXL));
        overlayMainPanel.getStyleClass().add("performance-stage-overlay-panel");

        // File sub-panel — the four canonical file actions plus Back. File
        // items close the overlay and run via overlayItem; Back returns to
        // the main panel without closing.
        Button fileNew = overlayItem("performanceStage.overlay.fileNew", host::onNewProject);
        Button fileOpen = overlayItem("performanceStage.overlay.fileOpen", host::onOpenProject);
        Button fileSave = overlayItem("performanceStage.overlay.fileSave", host::onSaveProject);
        Button fileRecent = overlayItem("performanceStage.overlay.fileRecent", host::onRecentProjects);
        Button fileBack = new Button(messages.getString("performanceStage.overlay.fileBack"));
        fileBack.getStyleClass().addAll("dawg-button", "size-stage", "performance-stage-overlay-item");
        fileBack.setOnAction(_ -> showOverlayPanel(overlayMainPanel));

        overlayFilePanel = new VBox(fileNew, fileOpen, fileSave, fileRecent, fileBack);
        overlayFilePanel.setSpacing(SpacingTokens.SPACING_MD);
        overlayFilePanel.setAlignment(Pos.CENTER);
        overlayFilePanel.setPadding(new Insets(SpacingTokens.SPACING_XXL));
        overlayFilePanel.getStyleClass().add("performance-stage-overlay-panel");
        overlayFilePanel.setVisible(false);
        overlayFilePanel.setManaged(false);

        StackPane panels = new StackPane(overlayMainPanel, overlayFilePanel);

        // A separate translucent backdrop Region carries the opacity so the
        // panel itself stays fully opaque (a low -fx-opacity on the scrim
        // would dim the panel too). Keeps the scrim colour a role token.
        Region backdrop = new Region();
        backdrop.getStyleClass().add("performance-stage-overlay-backdrop");

        StackPane scrim = new StackPane(backdrop, panels);
        scrim.getStyleClass().add("performance-stage-overlay");
        // Clicking the scrim/backdrop (outside the panel) dismisses it.
        scrim.setOnMouseClicked(event -> {
            if (event.getTarget() == scrim || event.getTarget() == backdrop) {
                setOverlayVisible(false);
            }
        });
        return scrim;
    }

    /**
     * Shows the given overlay panel and hides the other. Toggling between
     * the main panel and the file sub-panel is a pure visibility swap — the
     * overlay itself stays open.
     */
    private void showOverlayPanel(VBox panel) {
        boolean showMain = panel == overlayMainPanel;
        overlayMainPanel.setVisible(showMain);
        overlayMainPanel.setManaged(showMain);
        overlayFilePanel.setVisible(!showMain);
        overlayFilePanel.setManaged(!showMain);
    }

    private Button overlayItem(String messageKey, Runnable action) {
        Button button = new Button(messages.getString(messageKey));
        button.getStyleClass().addAll("dawg-button", "size-stage", "performance-stage-overlay-item");
        button.setOnAction(_ -> {
            setOverlayVisible(false);
            action.run();
        });
        return button;
    }

    /**
     * Shows or hides the translucent {@code ☰} overlay. Closing always
     * resets the overlay to its main panel so the next open starts there
     * (the file sub-panel is a transient drill-down).
     */
    private void setOverlayVisible(boolean visible) {
        if (!visible) {
            showOverlayPanel(overlayMainPanel);
        }
        overlay.setVisible(visible);
        overlay.setManaged(visible);
        hamburgerButton.setVisible(!visible);
    }

    // ── Test seams / runtime accessors ────────────────────────────────────

    /**
     * @return the oversized transport clock {@link Label}. The host keeps
     *         it in sync with the standard time display.
     */
    public Label clockLabel() {
        return clockLabel;
    }

    /** @return the stereo bus {@link LevelMeter} (top band). */
    public LevelMeter busMeter() {
        return busMeter;
    }

    /** @return the PLAY transport button. */
    public Button playButton() {
        return playButton;
    }

    /** @return an unmodifiable view of the track tile {@link TrackStrip}s. */
    public List<TrackStrip> trackTiles() {
        return List.copyOf(trackTiles);
    }

    /** @return the floating {@code ☰} hamburger button (test seam). */
    Button hamburgerButton() {
        return hamburgerButton;
    }

    /** @return the translucent overlay scrim (test seam). */
    StackPane overlay() {
        return overlay;
    }

    /** @return the main overlay panel (test seam). */
    VBox overlayMainPanel() {
        return overlayMainPanel;
    }

    /** @return the file sub-overlay panel (test seam). */
    VBox overlayFilePanel() {
        return overlayFilePanel;
    }
}
