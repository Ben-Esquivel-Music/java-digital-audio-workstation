package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link PluginEditorFactory.Panel} editor for the built-in
 * {@link MidSideWrapperPlugin} — story 302 (Plugin View Design Book §8.3
 * Phase 3) migration of the retired {@code daw-app} pair
 * {@code MidSideWrapperPluginView} + {@code InnerChainEditor} onto the SDK
 * editor contract. It doubles as a reference example of the {@code Panel}
 * mode: the plugin hands the host a {@link Region} and the host frames it in
 * standard chrome (§6.3).
 *
 * <p>Story 232's view requirements are preserved: a header with the
 * "Mid/Side Wrapper" title, a preset combo (Custom / Stereo Widener /
 * Mono Bass / Center Focus) backed by the wrapper's factory presets, a
 * Bypass toggle driving {@code getProcessor().setBypassed(...)} (encode →
 * decode is identity at unity gain, so bypass is a bit-exact null test), and
 * two stacked {@link InnerChainPane inner-chain editors} labelled "MID" and
 * "SIDE" whose tooltips document the (L+R)/2 and (L−R)/2 signals the inner
 * plugins see. Inner-chain edits flow through
 * {@link MidSideWrapperPlugin#addPlugin(ChainOwner, DawPlugin)} and
 * {@link MidSideWrapperPlugin#removePlugin(ChainOwner, DawPlugin)} so the
 * underlying {@code MidSideWrapperProcessor} stays in sync automatically.</p>
 *
 * <p>The {@link EditorContext#parameterStore() parameter store} is
 * deliberately unused: the wrapper itself exposes no parameters
 * ({@link MidSideWrapperPlugin#getParameters()} is empty by design), and
 * chain membership plus inner-plugin parameters are plugin-owned state the
 * store cannot express — per this package's conventions they go through the
 * plugin's own accessors.</p>
 *
 * <p><strong>Threading (§4.7):</strong> {@link #createPanel(EditorContext)}
 * and every control handler it wires run on the JavaFX Application Thread; no
 * marshalling is required.</p>
 */
public final class MidSideWrapperEditor implements PluginEditorFactory.Panel {

    static final String PRESET_NONE         = "Custom";
    static final String PRESET_STEREO_WIDEN = "Stereo Widener";
    static final String PRESET_MONO_BASS    = "Mono Bass";
    static final String PRESET_CENTER_FOCUS = "Center Focus";

    private final MidSideWrapperPlugin wrapper;

    /**
     * Creates the editor factory for the given wrapper plugin.
     *
     * @param wrapper the wrapper plugin this editor edits
     * @throws NullPointerException if {@code wrapper} is {@code null}
     */
    public MidSideWrapperEditor(MidSideWrapperPlugin wrapper) {
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the wrapper has not been
     *         {@linkplain MidSideWrapperPlugin#initialize initialized} (its
     *         processor is {@code null}); the host's fault harness (§2.7)
     *         degrades this to the in-frame fault banner
     */
    @Override
    public Region createPanel(EditorContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (wrapper.getProcessor() == null) {
            throw new IllegalStateException(
                    "wrapper must be initialized before opening its editor");
        }

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));

        // ── Header ─────────────────────────────────────────────────────
        Label title = new Label("Mid/Side Wrapper");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        ComboBox<String> presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(
                PRESET_NONE, PRESET_STEREO_WIDEN, PRESET_MONO_BASS, PRESET_CENTER_FOCUS);
        presetCombo.setValue(PRESET_NONE);
        presetCombo.setTooltip(new Tooltip("Apply a factory preset (replaces both chains)"));

        ToggleButton bypassToggle = new ToggleButton("Bypass");
        bypassToggle.setTooltip(new Tooltip(
                "Bypass the wrapper. Encode → decode is identity at unity gain, "
              + "so output equals input bit-for-bit (null test)."));
        bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
        bypassToggle.setOnAction(_ ->
                wrapper.getProcessor().setBypassed(bypassToggle.isSelected()));

        HBox header = new HBox(12, title, new Label("Preset:"), presetCombo, bypassToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));
        root.setTop(header);

        // ── Two stacked inner chain editors ────────────────────────────
        InnerChainPane midPane  = new InnerChainPane(wrapper, ChainOwner.MID,  "MID");
        InnerChainPane sidePane = new InnerChainPane(wrapper, ChainOwner.SIDE, "SIDE");
        root.setCenter(new VBox(8, midPane, sidePane));

        // Rebuilds both inner-chain lists and the bypass toggle from the
        // wrapper's current state (mirrors the old view's refresh()).
        Runnable refreshAll = () -> {
            midPane.refresh();
            sidePane.refresh();
            bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
        };
        presetCombo.setOnAction(_ -> applyPreset(presetCombo.getValue(), refreshAll));

        refreshAll.run();
        return root;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The two stacked chain lists plus header fit comfortably in the
     * standard 640×420 panel; both axes may resize.</p>
     */
    @Override
    public EditorHints hints() {
        return EditorHints.standard();
    }

    // ── Preset application ─────────────────────────────────────────────

    /**
     * Applies a factory preset: {@link #PRESET_NONE "Custom"} (or
     * {@code null}) keeps the user's hand-edited chains untouched; any
     * factory preset wipes both chains and re-adds the preset template's
     * plugins via the wrapper API so the DSP chains stay wired.
     */
    private void applyPreset(String preset, Runnable refreshAll) {
        if (preset == null || PRESET_NONE.equals(preset)) {
            return;
        }
        clearChain(ChainOwner.MID);
        clearChain(ChainOwner.SIDE);
        Supplier<MidSideWrapperPlugin> factory = switch (preset) {
            case PRESET_STEREO_WIDEN -> MidSideWrapperPlugin::stereoWidenerPreset;
            case PRESET_MONO_BASS    -> MidSideWrapperPlugin::monoBassPreset;
            case PRESET_CENTER_FOCUS -> MidSideWrapperPlugin::centerFocusPreset;
            default -> null;
        };
        if (factory == null) {
            return;
        }
        MidSideWrapperPlugin template = factory.get();
        for (DawPlugin p : new ArrayList<>(template.getMidChain())) {
            wrapper.addPlugin(ChainOwner.MID, p);
        }
        for (DawPlugin p : new ArrayList<>(template.getSideChain())) {
            wrapper.addPlugin(ChainOwner.SIDE, p);
        }
        refreshAll.run();
    }

    private void clearChain(ChainOwner owner) {
        for (DawPlugin p : new ArrayList<>(wrapper.getChain(owner))) {
            wrapper.removePlugin(owner, p);
        }
    }
}
