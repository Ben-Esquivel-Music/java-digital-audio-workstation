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
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
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
 * deliberately unused for writes: the wrapper itself exposes no parameters
 * ({@link MidSideWrapperPlugin#getParameters()} is empty by design), and
 * chain membership plus inner-plugin parameters are plugin-owned state the
 * store cannot express — per this package's conventions they go through the
 * plugin's own accessors. Each of those writes reads the store's
 * {@code uiWritesAdmitted()} first: while the host refuses UI writes, the
 * bypass, a factory preset, an inner chain's Add and Remove and the inner
 * parameter dialog's controls write nothing, and each control shows what it
 * showed before (PR #989 review 5711460812).</p>
 *
 * <p>Its inner parameter dialogs close with it: {@link #detach()} hides every
 * one it opened, so none outlives the session whose gate it reads (PR #989
 * review 5711460812).</p>
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
     * The preset combo's last admitted pick: the wrapper records no preset of
     * its own, so a refused pick shows this one again. One per editor: the
     * wrapper returns a new editor from every {@code editorFactory()} call,
     * and {@code createPanel} is called once.
     */
    private String lastPickedPreset = PRESET_NONE;

    /** The inner parameter dialogs this editor's panes opened that still show. FX-confined. */
    private final List<Stage> innerDialogs = new ArrayList<>();

    /** Set once the host has detached this editor: its panes then admit no edit. FX-confined. */
    private boolean detached;

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
        bypassToggle.setOnAction(_ -> {
            boolean bypass = bypassToggle.isSelected();
            if (bypass == wrapper.getProcessor().isBypassed()) {
                return;
            }
            if (!context.parameterStore().uiWritesAdmitted()) {
                bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
                return;
            }
            wrapper.getProcessor().setBypassed(bypass);
        });

        HBox header = new HBox(12, title, new Label("Preset:"), presetCombo, bypassToggle);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 8, 0));
        root.setTop(header);

        // ── Two stacked inner chain editors ────────────────────────────
        // A live read: the context outlives a reload's store. A disposed
        // session's gate is gone from the store, which then admits, so a
        // detached editor refuses on its own flag.
        BooleanSupplier editsAdmitted = () -> !detached && context.parameterStore().uiWritesAdmitted();
        InnerChainPane midPane  = new InnerChainPane(wrapper, ChainOwner.MID,  "MID", editsAdmitted,
                this::trackInnerDialog);
        InnerChainPane sidePane = new InnerChainPane(wrapper, ChainOwner.SIDE, "SIDE", editsAdmitted,
                this::trackInnerDialog);
        root.setCenter(new VBox(8, midPane, sidePane));

        // Rebuilds both inner-chain lists and the bypass toggle from the
        // wrapper's current state (mirrors the old view's refresh()).
        Runnable refreshAll = () -> {
            midPane.refresh();
            sidePane.refresh();
            bypassToggle.setSelected(wrapper.getProcessor().isBypassed());
        };
        presetCombo.setOnAction(_ -> onPresetPicked(presetCombo, context, refreshAll));

        refreshAll.run();
        return root;
    }

    /**
     * A preset pick. Echo guard: the combo already shows the last pick. A
     * factory preset is refused while the host refuses UI writes: the chains
     * are untouched and the combo shows the last pick again (PR #989 review
     * 5711460812). {@link #PRESET_NONE "Custom"} changes no chain, so it is
     * never refused.
     */
    private void onPresetPicked(ComboBox<String> presetCombo, EditorContext context, Runnable refreshAll) {
        String picked = presetCombo.getValue();
        if (Objects.equals(picked, lastPickedPreset)) {
            return;
        }
        boolean replacesTheChains = picked != null && !PRESET_NONE.equals(picked);
        if (replacesTheChains && !context.parameterStore().uiWritesAdmitted()) {
            presetCombo.setValue(lastPickedPreset);
            return;
        }
        lastPickedPreset = picked;
        applyPreset(picked, refreshAll);
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

    /**
     * {@inheritDoc}
     *
     * <p>Hides every inner parameter dialog this editor opened that still
     * shows, and its panes admit no edit from then on (PR #989 review
     * 5711460812).</p>
     */
    @Override
    public void detach() {
        detached = true;
        for (Stage dialog : List.copyOf(innerDialogs)) {
            dialog.hide();
        }
        innerDialogs.clear();
    }

    /** Keeps a parameter dialog a pane opened until it is hidden, so {@link #detach()} can close it. */
    private void trackInnerDialog(Stage dialog) {
        innerDialogs.add(dialog);
        dialog.addEventHandler(WindowEvent.WINDOW_HIDDEN, _ -> innerDialogs.remove(dialog));
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
