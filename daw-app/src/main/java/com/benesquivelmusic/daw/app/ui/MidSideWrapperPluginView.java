package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * JavaFX view for the built-in {@link MidSideWrapperPlugin}.
 *
 * <p>Presents a header (title, preset combo, bypass toggle) above two
 * stacked {@link InnerChainEditor inner chain editors} — one for the
 * Mid (M = (L+R)/2) chain and one for the Side (S = (L−R)/2) chain.
 * Inner-chain edits flow through {@link MidSideWrapperPlugin#addPlugin(ChainOwner, DawPlugin)}
 * and {@link MidSideWrapperPlugin#removePlugin(ChainOwner, DawPlugin)} so the
 * underlying {@link com.benesquivelmusic.daw.core.dsp.MidSideWrapperProcessor}
 * is kept in sync automatically.</p>
 *
 * <p>The {@link #PRESET_NONE} option keeps the user's hand-edited chains;
 * picking one of the factory presets wipes both chains and applies the
 * preset's plugins via the wrapper API.</p>
 *
 * <p><b>Story 157 — Mid/Side Processing Wrapper for Any Insert Slot.</b></p>
 */
public final class MidSideWrapperPluginView extends BorderPane {

    static final String PRESET_NONE          = "Custom";
    static final String PRESET_STEREO_WIDEN  = "Stereo Widener";
    static final String PRESET_MONO_BASS     = "Mono Bass";
    static final String PRESET_CENTER_FOCUS  = "Center Focus";

    private final MidSideWrapperPlugin wrapper;
    private final InnerChainEditor midEditor;
    private final InnerChainEditor sideEditor;
    private final ToggleButton bypassToggle;
    private final ComboBox<String> presetCombo;

    /**
     * Creates a view bound to {@code wrapper}. The wrapper must already be
     * {@link MidSideWrapperPlugin#initialize initialized} so its underlying
     * processor is non-null.
     *
     * @param wrapper the wrapper plugin to edit
     */
    public MidSideWrapperPluginView(MidSideWrapperPlugin wrapper) {
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper must not be null");
        if (wrapper.getProcessor() == null) {
            throw new IllegalStateException("wrapper must be initialized before opening its view");
        }

        setPadding(new Insets(12));
        setStyle("-fx-background-color: #2b2b2b;");

        // ── Header ─────────────────────────────────────────────────────
        Label title = new Label("Mid/Side Wrapper");
        title.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #eee;");

        presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(
                PRESET_NONE, PRESET_STEREO_WIDEN, PRESET_MONO_BASS, PRESET_CENTER_FOCUS);
        presetCombo.setValue(PRESET_NONE);
        presetCombo.setTooltip(new Tooltip("Apply a factory preset (replaces both chains)"));
        presetCombo.setOnAction(_ -> applyPreset(presetCombo.getValue()));

        bypassToggle = new ToggleButton("Bypass");
        bypassToggle.setTooltip(new Tooltip(
                "Bypass the wrapper. Encode → decode is identity at unity gain, "
              + "so output equals input bit-for-bit (null test)."));
        bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
        bypassToggle.setOnAction(_ -> wrapper.getProcessor().setBypassed(bypassToggle.isSelected()));

        HBox header = new HBox(12, title, new Label("Preset:"), presetCombo, bypassToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));
        for (var node : header.getChildren()) {
            if (node instanceof Label l && l != title) {
                l.setStyle("-fx-text-fill: #ccc;");
            }
        }
        setTop(header);

        // ── Two stacked inner chain editors ────────────────────────────
        midEditor  = new InnerChainEditor(wrapper, ChainOwner.MID,  "MID");
        sideEditor = new InnerChainEditor(wrapper, ChainOwner.SIDE, "SIDE");
        VBox center = new VBox(8, midEditor, sideEditor);
        setCenter(center);

        refresh();
    }

    /** Returns the underlying wrapper plugin. */
    public MidSideWrapperPlugin getWrapper() {
        return wrapper;
    }

    ToggleButton getBypassToggleForTest()    { return bypassToggle; }
    ComboBox<String> getPresetComboForTest() { return presetCombo; }
    InnerChainEditor getMidEditorForTest()   { return midEditor; }
    InnerChainEditor getSideEditorForTest()  { return sideEditor; }

    /** Rebuilds both inner-chain list views from the wrapper's current state. */
    public void refresh() {
        midEditor.refresh();
        sideEditor.refresh();
        bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
    }

    // ── Preset application ─────────────────────────────────────────────

    private void applyPreset(String preset) {
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
        refresh();
    }

    private void clearChain(ChainOwner owner) {
        for (DawPlugin p : new ArrayList<>(wrapper.getChain(owner))) {
            wrapper.removePlugin(owner, p);
        }
    }
}
