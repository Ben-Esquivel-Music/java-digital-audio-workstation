package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelection;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelectionModel;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.plugin.EditorFrame;
import com.benesquivelmusic.daw.app.ui.plugin.PluginEditorSession;
import com.benesquivelmusic.daw.app.ui.views.WorkshopSelectionHostController;
import com.benesquivelmusic.daw.app.ui.views.WorkshopView;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.CompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.ConvolutionReverbPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import com.benesquivelmusic.daw.core.plugin.TransientShaperPlugin;
import com.benesquivelmusic.daw.core.plugin.TunerPlugin;
import com.benesquivelmusic.daw.core.plugin.parameter.ABComparison;
import com.benesquivelmusic.daw.core.plugin.parameter.ParameterPreset;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.event.DawEvent;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.PluginEvent;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plug-in editors' frames and grids, the Workshop's plug-in panel, the
 * CPU-budget dialog and the Plugin Fault Log read the window's edit gate
 * (PR #989 review 5711460812; rulings R-1.2, R-5.1 C, ruling-3 G9): a grid's
 * toggle and segments, a preset pick, an A/B swap and the editor's bypass;
 * the Workshop panel's parameter slider, A/B toggle and preset pick; the CPU
 * budget's Apply; and a fault row's Re-enable, in the log that its controller,
 * built with the gate as the application builds it, opens. While the gate
 * says no, the act on the real control — an assistive technology's action,
 * but for the two preset picks, made through the selector's selection model
 * or value, and the CPU budget's Apply, run through the dialog's result
 * converter as its Apply button runs it — changes neither the store, the
 * processor nor the slot's bypass, publishes nothing, and the control shows
 * the plug-in; once it says yes, the same act edits. A plug-in editor body's
 * own writes are refused the same way, through the gate the session installs
 * on the slot's parameter store or the body's read of it: the Convolution
 * Reverb's Mix, the Transient Shaper's monitor toggle, the Tuner's A4, the
 * Mid/Side wrapper's bypass and Add, and a third-party panel's slider, alone
 * and in two sessions over one slot. A Mid/Side body's inner parameter
 * dialog closes when its session is disposed, so none outlives the gate.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest {

    private static final long GUARD_SECONDS = 20;
    private static final String FACTORY_PRESET = "Gentle Vocal Compression";
    /** The Convolution Reverb's Mix and the Transient Shaper's input-monitor toggle, by parameter id. */
    private static final int REVERB_MIX = 5;
    private static final int SHAPER_MONITOR = 3;

    @TempDir
    Path home;

    private final AtomicBoolean allowed = new AtomicBoolean(false);
    private final List<DawEvent> published = new CopyOnWriteArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;
    private EventBus.Subscription recording;
    private Stage stage;

    @BeforeEach
    void recordThePublications() {
        previousBus = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        recording = bus.onPublication(DawEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
    }

    @AfterEach
    void restore() throws Exception {
        try {
            if (stage != null) {
                runOnFx(stage::hide);
            }
        } finally {
            recording.close();
            EventBusPublisher.setDefault(previousBus);
            bus.close();
        }
    }

    /** One plug-in handler: how its refusal and its admission show. */
    record Row(String name, Act act) {
        Row {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(act, "act must not be null");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface Act {
        void refusedThenAdmitted(PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest test) throws Exception;
    }

    static Stream<Row> rows() {
        return Stream.of(
                new Row("grid toggle shows the store", PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::gridToggle),
                new Row("segment shows the store", PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::segment),
                new Row("preset pick: the frame shows the applied preset",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::presetPick),
                new Row("A/B swap", PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::abSwap),
                new Row("editorBypassIsRefusedAndTheButtonShowsTheSlot",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::editorBypass),
                new Row("workshopParameterSliderShowsTheProcessor",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::workshopSlider),
                new Row("workshopAbToggleStaysOnTheSideThePluginPlays",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::workshopAbToggle),
                new Row("workshopPresetPickKeepsTheAppliedPreset",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::workshopPresetPick),
                new Row("CPU budget apply", PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::cpuBudgetApply),
                new Row("theFaultLogsReEnableIsRefusedWhileTheGateSaysNo",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::faultLogReEnable),
                new Row("convolutionReverbBodysMixSliderShowsTheStore",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::reverbMix),
                new Row("transientShaperBodysMonitorToggleShowsTheStore",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::shaperMonitor),
                new Row("tunerBodysA4SpinnerShowsThePlugIn",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::tunerA4),
                new Row("midSideBodysBypassShowsTheProcessor",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::midSideBypass),
                new Row("midSideBodysAddOpensNoPicker",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::midSideAdd),
                new Row("midSideBodysPresetPickKeepsTheChains",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::midSidePreset),
                new Row("thirdPartyPanelsSliderShowsTheStore",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::thirdPartySlider),
                new Row("thirdPartyPanelsWriteWhileItIsBuiltIsShownOnceItIsOpen",
                        PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::thirdPartyWriteWhileBuilt));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void aPluginEditIsRefusedWhileTheGateSaysNoAndAdmittedOnceItSaysYes(Row row) throws Exception {
        row.act().refusedThenAdmitted(this);
    }

    // ── the editor session's rows ────────────────────────────────────────────

    private void gridToggle() throws Exception {
        Editor editor = openOn(gridSlot());
        try {
            ToggleButton toggle = onFx(() -> nodes(editor.frame().getBody()).filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast).filter(button -> "OFF".equals(button.getText()))
                    .findFirst().orElseThrow());
            runOnFx(() -> toggle.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.session().store().valueById(GridPlugin.ENABLED))).as("refused: the store")
                    .isZero();
            assertThat(onFx(toggle::isSelected)).as("the toggle shows the store").isFalse();
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> toggle.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.session().store().valueById(GridPlugin.ENABLED))).isOne();
        } finally {
            editor.close();
        }
    }

    private void segment() throws Exception {
        Editor editor = openOn(gridSlot());
        try {
            ToggleButton two = onFx(() -> segmentLabelled(editor.frame(), "2"));
            ToggleButton zero = onFx(() -> segmentLabelled(editor.frame(), "0"));
            runOnFx(() -> two.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.session().store().valueById(GridPlugin.MODE))).as("refused: the store")
                    .isZero();
            assertThat(onFx(() -> zero.isSelected() && !two.isSelected())).as("the segments show the store").isTrue();
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> two.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.session().store().valueById(GridPlugin.MODE))).isEqualTo(2.0);
        } finally {
            editor.close();
        }
    }

    private void presetPick() throws Exception {
        // The editor's preset manager reads the user's presets under user.home: point it at this row's own
        // folder. The session fixes that folder when it opens, and its asynchronous load only reads it, so nothing
        // writes there; the session is disposed before user.home is restored. The row waits only until the
        // factory presets, which the session lists as it opens, are listed.
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            presetPickUnderTheRowsHome();
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    @SuppressWarnings("unchecked")
    private void presetPickUnderTheRowsHome() throws Exception {
        Editor editor = openOn(InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000));
        try {
            awaitTheFactoryPresets(editor.frame());
            ComboBox<String> combo = onFx(() -> (ComboBox<String>) editor.frame().lookup(".editor-frame-preset"));
            double threshold = onFx(() -> editor.session().store().valueById(0));
            runOnFx(() -> combo.getSelectionModel().select(FACTORY_PRESET));
            assertThat(onFx(() -> editor.session().store().valueById(0))).as("refused: the plug-in is untouched")
                    .isEqualTo(threshold);
            assertThat(onFx(combo::getValue)).as("the selector shows the preset applied last: none").isNull();
            assertThat(onFx(() -> editor.frame().getSelectedPreset())).isNull();

            allowed.set(true);
            runOnFx(() -> combo.getSelectionModel().select(FACTORY_PRESET));
            assertThat(onFx(() -> editor.session().store().valueById(0))).isNotEqualTo(threshold);
            assertThat(onFx(() -> editor.frame().getSelectedPreset())).isEqualTo(FACTORY_PRESET);
        } finally {
            editor.close();
        }
    }

    private void abSwap() throws Exception {
        Editor editor = openOn(InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000));
        try {
            // The seed is a fixture write: the store refuses a UI write while the session's gate says no.
            allowed.set(true);
            runOnFx(() -> editor.session().store().writeFromUiById(0, -36.0));
            allowed.set(false);
            Button swap = onFx(() -> (Button) editor.frame().lookup(".editor-ab-swap"));
            runOnFx(() -> swap.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.frame().getAbActiveSlot())).as("refused: A still plays")
                    .isEqualTo(ABComparison.Slot.A);
            assertThat(onFx(() -> editor.session().store().valueById(0))).as("and the store holds A").isEqualTo(-36.0);

            allowed.set(true);
            runOnFx(() -> swap.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.frame().getAbActiveSlot())).isEqualTo(ABComparison.Slot.B);
            assertThat(onFx(() -> editor.session().store().valueById(0))).isNotEqualTo(-36.0);
        } finally {
            editor.close();
        }
    }

    private void editorBypass() throws Exception {
        InsertSlot slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
        Editor editor = openOn(slot);
        try {
            ToggleButton bypass = onFx(() -> (ToggleButton) editor.frame().lookup(".editor-frame-bypass"));
            runOnFx(() -> bypass.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(slot.isBypassed()).as("refused: the slot plays").isFalse();
            assertThat(onFx(bypass::isSelected)).as("the button shows the slot, at once").isFalse();
            assertThat(onFx(() -> editor.frame().isBypassed())).as("and so does the frame").isFalse();

            allowed.set(true);
            runOnFx(() -> bypass.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(slot.isBypassed()).isTrue();
            assertThat(onFx(bypass::isSelected)).isTrue();
        } finally {
            editor.close();
        }
    }

    // ── the Workshop panel's rows ────────────────────────────────────────────

    private void workshopSlider() throws Exception {
        Workshop workshop = onFx(Workshop::new);
        PluginParameterEditorPanel panel = onFx(() -> workshop.panel(allowed));
        PluginParameter first = InsertEffectFactory.getParameterDescriptors(InsertEffectType.COMPRESSOR).getFirst();
        Slider slider = onFx(() -> nodes(panel).filter(Slider.class::isInstance).map(Slider.class::cast)
                .findFirst().orElseThrow());
        double before = onFx(() -> workshop.processorValue(first.id()));
        double target = first.minValue() + (first.maxValue() - first.minValue()) * 0.25;
        runOnFx(() -> slider.executeAccessibleAction(AccessibleAction.SET_VALUE, target));
        assertThat(onFx(() -> workshop.processorValue(first.id()))).as("refused: the processor").isEqualTo(before);
        assertThat(onFx(slider::getValue)).as("the slider shows the processor").isEqualTo(before);
        assertThat(published).as("nothing published").isEmpty();

        allowed.set(true);
        runOnFx(() -> slider.executeAccessibleAction(AccessibleAction.SET_VALUE, target));
        assertThat(onFx(() -> workshop.processorValue(first.id()))).isEqualTo(target);
        assertThat(published).as("the change is published").anyMatch(PluginEvent.ParameterChanged.class::isInstance);
    }

    private void workshopAbToggle() throws Exception {
        Workshop workshop = onFx(Workshop::new);
        allowed.set(true);
        PluginParameterEditorPanel panel = onFx(() -> workshop.panel(allowed));
        PluginParameter first = InsertEffectFactory.getParameterDescriptors(InsertEffectType.COMPRESSOR).getFirst();
        Slider slider = onFx(() -> nodes(panel).filter(Slider.class::isInstance).map(Slider.class::cast)
                .findFirst().orElseThrow());
        double onA = onFx(() -> workshop.processorValue(first.id()));
        double onB = first.minValue() + (first.maxValue() - first.minValue()) * 0.25;
        ToggleButton ab = onFx(panel::getAbToggleButton);
        runOnFx(() -> {
            ab.executeAccessibleAction(AccessibleAction.FIRE);           // to B
            slider.setValue(onB);                                         // B's value
            ab.executeAccessibleAction(AccessibleAction.FIRE);           // back to A
        });
        assertThat(onFx(() -> workshop.processorValue(first.id()))).as("fixture: A plays again").isEqualTo(onA);
        Map<Integer, Double> slotB = onFx(() -> Map.copyOf(panel.getAbComparison().getSlotValues(ABComparison.Slot.B)));
        published.clear();

        allowed.set(false);
        runOnFx(() -> ab.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> panel.getAbComparison().getActiveSlot())).as("refused: A still plays")
                .isEqualTo(ABComparison.Slot.A);
        assertThat(onFx(ab::isSelected)).as("the toggle shows A").isFalse();
        assertThat(onFx(ab::getText)).isEqualTo("A");
        assertThat(onFx(() -> Map.copyOf(panel.getAbComparison().getSlotValues(ABComparison.Slot.B))))
                .as("B is not overwritten").isEqualTo(slotB);
        assertThat(onFx(() -> workshop.processorValue(first.id()))).as("the processor holds A").isEqualTo(onA);
        assertThat(onFx(() -> panel.getState().getAllValues())).as("the panel shows the processor")
                .isEqualTo(onFx(workshop::processorValues));
        assertThat(published).as("nothing published").isEmpty();

        allowed.set(true);
        runOnFx(() -> ab.executeAccessibleAction(AccessibleAction.FIRE));
        assertThat(onFx(() -> panel.getAbComparison().getActiveSlot())).isEqualTo(ABComparison.Slot.B);
        assertThat(onFx(() -> workshop.processorValue(first.id()))).isEqualTo(onB);
    }

    private void workshopPresetPick() throws Exception {
        Workshop workshop = onFx(Workshop::new);
        allowed.set(true);
        PluginParameterEditorPanel panel = onFx(() -> workshop.panel(allowed));
        PluginParameter first = InsertEffectFactory.getParameterDescriptors(InsertEffectType.COMPRESSOR).getFirst();
        Map<Integer, Double> values = onFx(workshop::processorValues);
        double p = first.minValue() + (first.maxValue() - first.minValue()) * 0.25;
        double q = first.minValue() + (first.maxValue() - first.minValue()) * 0.75;
        Map<Integer, Double> pValues = new java.util.HashMap<>(values);
        pValues.put(first.id(), p);
        Map<Integer, Double> qValues = new java.util.HashMap<>(values);
        qValues.put(first.id(), q);
        // Test-only: no production caller sets presets on the Workshop's panel today.
        runOnFx(() -> {
            panel.setPresets(List.of(ParameterPreset.user("P", pValues), ParameterPreset.user("Q", qValues)));
            panel.getPresetComboBox().setValue("P");
        });
        assertThat(onFx(() -> workshop.processorValue(first.id()))).as("fixture: P loads").isEqualTo(p);
        published.clear();

        allowed.set(false);
        runOnFx(() -> panel.getPresetComboBox().setValue("Q"));
        assertThat(onFx(() -> panel.getPresetComboBox().getValue())).as("refused: the combo shows P").isEqualTo("P");
        assertThat(onFx(() -> workshop.processorValue(first.id()))).as("the processor holds P").isEqualTo(p);
        assertThat(published).as("nothing published").isEmpty();

        allowed.set(true);
        runOnFx(() -> panel.getPresetComboBox().setValue("Q"));
        assertThat(onFx(() -> workshop.processorValue(first.id()))).isEqualTo(q);
    }

    // ── the CPU budget's row ─────────────────────────────────────────────────

    private void cpuBudgetApply() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        AtomicInteger applied = new AtomicInteger();
        ChannelCpuBudgetDialog dialog = onFx(() -> new ChannelCpuBudgetDialog(channel, applied::incrementAndGet,
                allowed::get));
        runOnFx(() -> dialog.getResultConverter().call(ButtonType.APPLY));
        assertThat(channel.getCpuBudget()).as("refused: no budget is written").isNull();
        assertThat(applied).as("and the enforcer is not re-registered").hasValue(0);

        allowed.set(true);
        runOnFx(() -> dialog.getResultConverter().call(ButtonType.APPLY));
        assertThat(channel.getCpuBudget()).isNotNull();
        assertThat(applied).hasValue(1);
    }

    // ── the Plugin Fault Log's row ───────────────────────────────────────────

    /**
     * The fault log as the application opens it: its controller built as
     * {@code MainController.initializePluginFaultIsolation} builds it, handed
     * the gate, and the log opened through {@link PluginFaultUiController#openFaultLog()},
     * so the controller's hand-off of the gate to the log is what is tested.
     */
    private void faultLogReEnable() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        InsertSlot slot = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
        channel.addInsert(slot);
        PluginInvocationSupervisor supervisor = new PluginInvocationSupervisor(home.resolve("plugin-faults.log"));
        FxDispatcher dispatcher = new FxDispatcher();
        PluginFaultUiController faults = onFx(() -> new PluginFaultUiController(supervisor, new NotificationBar(),
                dispatcher, allowed::get));
        try {
            supervisor.reportAudioFault(slot, new IllegalStateException("a parameter callback fault"));
            assertThat(slot.isBypassed()).as("fixture: the fault bypassed the slot").isTrue();
            runOnFx(faults::openFaultLog);
            Button reenable = awaitTheFaultsReEnableButton();

            runOnFx(() -> reenable.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(slot.isBypassed()).as("refused: the slot stays bypassed").isTrue();
            assertThat(onFx(reenable::getText)).as("and the button is as it was").isEqualTo("Re-enable plugin");
            assertThat(onFx(reenable::isDisabled)).isFalse();
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> reenable.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(slot.isBypassed()).as("the same button re-enables once admitted").isFalse();
            assertThat(onFx(reenable::getText)).isEqualTo("Re-enabled");
            assertThat(onFx(reenable::isDisabled)).isTrue();
        } finally {
            runOnFx(faults::dispose);
            supervisor.close();
        }
    }

    /**
     * The fault row's Re-enable button in the shown Plugin Fault Log, once
     * the fault has reached the log's list and the list has laid it out —
     * one FX turn at a time, bounded.
     */
    private static Button awaitTheFaultsReEnableButton() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GUARD_SECONDS);
        while (System.nanoTime() < deadline) {
            Optional<Button> found = onFx(() -> javafx.stage.Window.getWindows().stream()
                    .filter(Stage.class::isInstance).map(Stage.class::cast)
                    .filter(window -> "Plugin Fault Log".equals(window.getTitle()) && window.isShowing())
                    .findFirst()
                    .flatMap(window -> {
                        Parent root = window.getScene().getRoot();
                        root.applyCss();
                        root.layout();
                        return nodes(root).filter(Button.class::isInstance).map(Button.class::cast)
                                .filter(button -> "Re-enable plugin".equals(button.getText())).findFirst();
                    }));
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new AssertionError("the fault's row never showed its Re-enable button");
    }

    // ── the plug-in bodies' rows ─────────────────────────────────────────────

    /**
     * The Convolution Reverb's own Panel body binds its Mix slider to the
     * slot's store; the store refuses the slider's write through the gate
     * the session installed: the store, the capture a save takes and the
     * processor after a drain keep Mix, and the slider shows the store.
     */
    private void reverbMix() throws Exception {
        ConvolutionReverbPlugin plugin = new ConvolutionReverbPlugin();
        InsertSlot slot = pluginSlot(plugin);
        Editor editor = openOn(slot);
        try {
            Slider mix = onFx(() -> sliderLabelled(editor.frame().getBody(), "Mix"));
            double before = onFx(() -> editor.session().store().valueById(REVERB_MIX));
            Map<Integer, Double> captured = onFx(slot::snapshotParameterValues);
            runOnFx(() -> mix.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8));
            assertThat(onFx(() -> editor.session().store().valueById(REVERB_MIX))).as("refused: the store")
                    .isEqualTo(before);
            assertThat(onFx(slot::snapshotParameterValues)).as("and what a save captures").isEqualTo(captured);
            slot.drainParametersToAudio();
            assertThat(plugin.getProcessor().getMix()).as("and the processor after a drain").isEqualTo(before);
            assertThat(onFx(mix::getValue)).as("the slider shows the store").isEqualTo(before);
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> mix.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8));
            assertThat(onFx(() -> editor.session().store().valueById(REVERB_MIX))).isEqualTo(0.8);
            slot.drainParametersToAudio();
            assertThat(plugin.getProcessor().getMix()).isEqualTo(0.8);
        } finally {
            editor.close();
            plugin.dispose();
        }
    }

    /** The Transient Shaper's body toggle, fired: the store refuses its write and the toggle shows the store. */
    private void shaperMonitor() throws Exception {
        TransientShaperPlugin plugin = new TransientShaperPlugin();
        InsertSlot slot = pluginSlot(plugin);
        Editor editor = openOn(slot);
        try {
            CheckBox monitor = onFx(() -> nodes(editor.frame().getBody()).filter(CheckBox.class::isInstance)
                    .map(CheckBox.class::cast).findFirst().orElseThrow());
            runOnFx(() -> monitor.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.session().store().valueById(SHAPER_MONITOR))).as("refused: the store")
                    .isZero();
            assertThat(onFx(() -> slot.getParameterStore().snapshotPendingUiValues())).as("nothing pending").isEmpty();
            slot.drainParametersToAudio();
            assertThat(plugin.getProcessor().isInputMonitor()).as("the processor after a drain").isFalse();
            assertThat(onFx(monitor::isSelected)).as("the toggle shows the store").isFalse();
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> monitor.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> editor.session().store().valueById(SHAPER_MONITOR))).isOne();
            slot.drainParametersToAudio();
            assertThat(plugin.getProcessor().isInputMonitor()).isTrue();
        } finally {
            editor.close();
            plugin.dispose();
        }
    }

    /** The Tuner's A4 spinner writes its plug-in directly; it reads the gate first, both ways. */
    private void tunerA4() throws Exception {
        TunerPlugin plugin = new TunerPlugin();
        Editor editor = openOn(pluginSlot(plugin));
        try {
            @SuppressWarnings("unchecked")
            Spinner<Integer> a4 = onFx(() -> (Spinner<Integer>) nodes(editor.frame().getBody())
                    .filter(Spinner.class::isInstance).findFirst().orElseThrow());
            runOnFx(() -> a4.executeAccessibleAction(AccessibleAction.INCREMENT));
            assertThat(plugin.getReferencePitchHz()).as("refused INCREMENT: the plug-in").isEqualTo(440.0);
            assertThat(onFx(a4::getValue)).as("the spinner shows the plug-in").isEqualTo(440);
            runOnFx(() -> a4.executeAccessibleAction(AccessibleAction.DECREMENT));
            assertThat(plugin.getReferencePitchHz()).as("refused DECREMENT: the plug-in").isEqualTo(440.0);
            assertThat(onFx(a4::getValue)).isEqualTo(440);
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> a4.executeAccessibleAction(AccessibleAction.INCREMENT));
            assertThat(plugin.getReferencePitchHz()).isEqualTo(441.0);
            runOnFx(() -> a4.executeAccessibleAction(AccessibleAction.DECREMENT));
            assertThat(plugin.getReferencePitchHz()).isEqualTo(440.0);
        } finally {
            editor.close();
            plugin.dispose();
        }
    }

    /** The Mid/Side wrapper's Bypass writes its processor directly; it reads the gate first. */
    private void midSideBypass() throws Exception {
        MidSideWrapperPlugin plugin = new MidSideWrapperPlugin();
        Editor editor = openOn(pluginSlot(plugin));
        try {
            ToggleButton bypass = onFx(() -> nodes(editor.frame().getBody()).filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast).filter(button -> "Bypass".equals(button.getText()))
                    .findFirst().orElseThrow());
            runOnFx(() -> bypass.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(plugin.getProcessor().isBypassed()).as("refused: the wrapper plays").isFalse();
            assertThat(onFx(bypass::isSelected)).as("the toggle shows the processor").isFalse();

            allowed.set(true);
            runOnFx(() -> bypass.executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(plugin.getProcessor().isBypassed()).isTrue();
            assertThat(onFx(bypass::isSelected)).isTrue();
        } finally {
            editor.close();
            plugin.dispose();
        }
    }

    /**
     * The Mid/Side wrapper's Add reads the gate before its picker opens: a
     * refused Add shows no window; once admitted, the same action opens the
     * picker, which is then cancelled.
     */
    private void midSideAdd() throws Exception {
        MidSideWrapperPlugin plugin = new MidSideWrapperPlugin();
        Editor editor = openOn(pluginSlot(plugin));
        List<Window> shown = new CopyOnWriteArrayList<>();
        ListChangeListener<Window> recordAndHide = change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    shown.add(window);
                    Platform.runLater(window::hide);
                }
            }
        };
        try {
            Button add = onFx(() -> addButtonOf(editor.frame().getBody()));
            runOnFx(() -> Window.getWindows().addListener(recordAndHide));
            try {
                runOnFx(() -> add.executeAccessibleAction(AccessibleAction.FIRE));
            } finally {
                runOnFx(() -> Window.getWindows().removeListener(recordAndHide));
            }
            assertThat(shown).as("refused: no picker opened").isEmpty();
            assertThat(plugin.getMidChain()).isEmpty();

            allowed.set(true);
            CountDownLatch addReturned = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    add.executeAccessibleAction(AccessibleAction.FIRE);
                } finally {
                    addReturned.countDown();
                }
            });
            awaitOnFx(() -> showingAddPicker().isPresent(), "admitted: the picker opens");
            runOnFx(() -> ((Button) ((DialogPane) showingAddPicker().orElseThrow().getScene().getRoot())
                    .lookupButton(ButtonType.CANCEL)).executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(addReturned.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the cancelled Add returned").isTrue();
            assertThat(plugin.getMidChain()).as("a cancelled pick adds nothing").isEmpty();
        } finally {
            runOnFx(() -> showingAddPicker().ifPresent(Stage::hide));
            editor.close();
            plugin.dispose();
        }
    }

    /** The Mid/Side wrapper's factory preset replaces both chains; a refused pick keeps them and shows Custom. */
    @SuppressWarnings("unchecked")
    private void midSidePreset() throws Exception {
        MidSideWrapperPlugin plugin = new MidSideWrapperPlugin();
        Editor editor = openOn(pluginSlot(plugin));
        try {
            ComboBox<String> preset = onFx(() -> (ComboBox<String>) nodes(editor.frame().getBody())
                    .filter(ComboBox.class::isInstance).findFirst().orElseThrow());
            runOnFx(() -> preset.getSelectionModel().select("Mono Bass"));
            assertThat(plugin.getMidChain()).as("refused: the mid chain is kept").isEmpty();
            assertThat(plugin.getSideChain()).as("and so is the side chain").isEmpty();
            assertThat(onFx(preset::getValue)).as("the combo shows the last pick").isEqualTo("Custom");

            allowed.set(true);
            runOnFx(() -> preset.getSelectionModel().select("Mono Bass"));
            assertThat(plugin.getSideChain()).as("admitted: the preset's chain").isNotEmpty();
            assertThat(onFx(preset::getValue)).isEqualTo("Mono Bass");
        } finally {
            editor.close();
            plugin.dispose();
        }
    }

    /**
     * A third-party Panel body on the SDK alone, with no gate read of its
     * own: the store refuses its slider's write, the session echoes the kept
     * value through {@code parameterChanged}, and the drain sets nothing.
     */
    private void thirdPartySlider() throws Exception {
        PanelPlugin plugin = new PanelPlugin();
        InsertSlot slot = pluginSlot(plugin);
        Editor editor = openOn(slot);
        try {
            LevelPanel body = plugin.panels.getLast();
            Slider level = onFx(() -> body.slider);
            runOnFx(body.echoes::clear);
            runOnFx(() -> level.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8));
            assertThat(onFx(() -> editor.session().store().valueById(PanelPlugin.LEVEL))).as("refused: the store")
                    .isEqualTo(0.5);
            assertThat(onFx(level::getValue)).as("the slider shows the store").isEqualTo(0.5);
            assertThat(body.echoes).as("echoed once, with the kept value").containsExactly(0.5);
            slot.drainParametersToAudio();
            assertThat(plugin.writes).as("the drain sets nothing").isEmpty();
            assertThat(published).as("nothing published").isEmpty();

            allowed.set(true);
            runOnFx(() -> level.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8));
            assertThat(onFx(() -> editor.session().store().valueById(PanelPlugin.LEVEL))).isEqualTo(0.8);
            slot.drainParametersToAudio();
            assertThat(plugin.writes).containsExactly(0.8);
        } finally {
            editor.close();
        }
    }

    /**
     * A body that writes its store while it is being built: the store keeps
     * its value, the body is not called back before its {@code createPanel}
     * returns, and once the editor is open the slider shows the store.
     */
    private void thirdPartyWriteWhileBuilt() throws Exception {
        PanelPlugin plugin = new PanelPlugin();
        plugin.writeWhileBuilt = true;
        Editor editor = openOn(pluginSlot(plugin));
        try {
            LevelPanel body = plugin.panels.getLast();
            assertThat(onFx(() -> editor.session().store().valueById(PanelPlugin.LEVEL))).as("refused: the store")
                    .isEqualTo(0.5);
            assertThat(body.echoedWhileBuilt).as("no echo before createPanel returned").isFalse();
            assertThat(onFx(() -> body.slider.getValue())).as("open: the slider shows the store").isEqualTo(0.5);

            allowed.set(true);
            runOnFx(() -> body.slider.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8));
            assertThat(onFx(() -> editor.session().store().valueById(PanelPlugin.LEVEL))).isEqualTo(0.8);
        } finally {
            editor.close();
        }
    }

    // ── the session's gate over its lifetime ─────────────────────────────────

    /** (g1) A disposed session leaves no gate: the store admits, and a write lands, while the window's gate says no. */
    @Test
    void aDisposedSessionLeavesTheStoreAdmittingWrites() throws Exception {
        InsertSlot slot = pluginSlot(new PanelPlugin());
        Editor editor = openOn(slot);
        try {
            PluginParameterStore store = onFx(() -> editor.session().store());
            assertThat(onFx(store::uiWritesAdmitted)).as("fixture: the open session's gate says no").isFalse();

            editor.close();

            assertThat(onFx(store::uiWritesAdmitted)).as("disposed: the store admits").isTrue();
            runOnFx(() -> store.writeFromUiById(PanelPlugin.LEVEL, 0.8));
            assertThat(onFx(() -> store.valueById(PanelPlugin.LEVEL))).isEqualTo(0.8);
        } finally {
            editor.close();
        }
    }

    /**
     * A Mid/Side body's inner parameter dialog closes with its session: the
     * dialog the inner chain's double-click handler opened is no longer
     * showing once the session is disposed, so none outlives the gate its
     * controls read.
     */
    @Test
    void aMidSideBodysInnerParameterDialogClosesWhenItsSessionIsDisposed() throws Exception {
        MidSideWrapperPlugin plugin = new MidSideWrapperPlugin();
        InsertSlot slot = pluginSlot(plugin);
        DawPlugin inner = new CompressorPlugin();
        plugin.addPlugin(MidSideWrapperPlugin.ChainOwner.MID, inner);
        Editor editor = openOn(slot);
        try {
            runOnFx(() -> {
                Node body = editor.frame().getBody();
                body.applyCss();
                ((Parent) body).layout();
                ListCell<?> cell = nodes(body).filter(ListCell.class::isInstance).map(node -> (ListCell<?>) node)
                        .filter(candidate -> candidate.getItem() == inner).findFirst().orElseThrow();
                cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 2,
                        false, false, false, false, true, false, false, true, false, false, null));
            });
            assertThat(onFx(() -> showingInnerParameterDialogs().size())).as("fixture: the double click opened it")
                    .isOne();

            editor.close();

            assertThat(onFx(PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::showingInnerParameterDialogs))
                    .as("disposed: no inner parameter dialog shows").isEmpty();
        } finally {
            runOnFx(() -> showingInnerParameterDialogs().forEach(Stage::hide));
            editor.close();
            plugin.dispose();
        }
    }

    /**
     * (g2) Two sessions over one slot: a refused write in one shows the
     * kept value in both, each body called once; once the newer session is
     * disposed the older one still refuses, and once both are, the store
     * admits.
     */
    @Test
    void twoSessionsOverOneSlotEachShowTheRefusalAndTheNewerOnesDisposeLeavesTheOlderRefusing() throws Exception {
        PanelPlugin plugin = new PanelPlugin();
        Editors editors = openTwoOn(pluginSlot(plugin));
        LevelPanel older = plugin.panels.get(0);
        LevelPanel newer = plugin.panels.get(1);
        PluginParameterStore store = onFx(() -> editors.older().store());
        try {
            runOnFx(() -> {
                older.echoes.clear();
                newer.echoes.clear();
                older.slider.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8);
            });
            assertThat(onFx(() -> store.valueById(PanelPlugin.LEVEL))).as("refused: the store").isEqualTo(0.5);
            assertThat(onFx(() -> List.of(older.slider.getValue(), newer.slider.getValue())))
                    .as("both bodies show the store").containsExactly(0.5, 0.5);
            assertThat(List.of(older.echoes.size(), newer.echoes.size())).as("each body echoed once")
                    .containsExactly(1, 1);

            runOnFx(editors.newer()::dispose);
            runOnFx(() -> {
                older.echoes.clear();
                older.slider.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8);
            });
            assertThat(onFx(() -> store.valueById(PanelPlugin.LEVEL))).as("the older session still refuses")
                    .isEqualTo(0.5);
            assertThat(onFx(() -> older.slider.getValue())).as("and shows the store").isEqualTo(0.5);
            assertThat(older.echoes).containsExactly(0.5);

            runOnFx(editors.older()::dispose);
            assertThat(onFx(store::uiWritesAdmitted)).as("both disposed: the store admits").isTrue();
        } finally {
            runOnFx(() -> {
                editors.older().dispose();
                editors.newer().dispose();
            });
        }
    }

    /** (g3) The same two sessions, the older disposed first: the newer one still refuses and shows the store. */
    @Test
    void twoSessionsOverOneSlotTheOlderOnesDisposeLeavesTheNewerRefusing() throws Exception {
        PanelPlugin plugin = new PanelPlugin();
        Editors editors = openTwoOn(pluginSlot(plugin));
        LevelPanel newer = plugin.panels.get(1);
        PluginParameterStore store = onFx(() -> editors.newer().store());
        try {
            runOnFx(editors.older()::dispose);
            runOnFx(() -> {
                newer.echoes.clear();
                newer.slider.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8);
            });
            assertThat(onFx(() -> store.valueById(PanelPlugin.LEVEL))).as("the newer session still refuses")
                    .isEqualTo(0.5);
            assertThat(onFx(() -> newer.slider.getValue())).as("and shows the store").isEqualTo(0.5);
            assertThat(newer.echoes).containsExactly(0.5);

            runOnFx(editors.newer()::dispose);
            assertThat(onFx(store::uiWritesAdmitted)).as("both disposed: the store admits").isTrue();
        } finally {
            runOnFx(() -> {
                editors.older().dispose();
                editors.newer().dispose();
            });
        }
    }

    /**
     * (g4) A body whose first {@code createPanel} throws, recovered by the
     * fault banner's Reload: the reload replaces the session's gate, so a
     * refused write calls the body back exactly once, and the dispose leaves
     * the store admitting.
     */
    @Test
    void aReloadReplacesTheSessionsGateAndItsDisposeRemovesIt() throws Exception {
        PanelPlugin plugin = new PanelPlugin();
        plugin.createPanelFaults.set(1);
        InsertSlot slot = pluginSlot(plugin);
        Editor editor = openOn(slot);
        PluginParameterStore store = onFx(() -> editor.session().store());
        try {
            Button reload = onFx(() -> {
                editor.frame().applyCss();
                editor.frame().layout();
                return (Button) editor.frame().lookup(".editor-fault-reload");
            });
            runOnFx(() -> reload.executeAccessibleAction(AccessibleAction.FIRE));
            LevelPanel body = plugin.panels.getLast();
            assertThat(onFx(() -> editor.frame().getBody() == body.root)).as("fixture: Reload recovered the body")
                    .isTrue();

            runOnFx(() -> {
                body.echoes.clear();
                body.slider.executeAccessibleAction(AccessibleAction.SET_VALUE, 0.8);
            });
            assertThat(onFx(() -> store.valueById(PanelPlugin.LEVEL))).as("refused: the store").isEqualTo(0.5);
            assertThat(body.echoes).as("the body was called back exactly once").containsExactly(0.5);
        } finally {
            editor.close();
        }
        assertThat(onFx(store::uiWritesAdmitted)).as("disposed: the store admits").isTrue();
    }

    // ── rigs ─────────────────────────────────────────────────────────────────

    /** An open editor session, framed in a shown stage so its skin exists. */
    private record Editor(PluginEditorSession session, EditorFrame frame) {
        void close() throws Exception {
            runOnFx(session::dispose);
        }
    }

    private Editor openOn(InsertSlot slot) throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        channel.addInsert(slot);
        return onFx(() -> {
            PluginEditorSession session = PluginEditorSession.open(channel, slot,
                    new PluginEditorSession.Deps(() -> 48_000, null, null, null, allowed::get));
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(session.frame()), 900, 600));
            stage.show();
            session.frame().applyCss();
            session.frame().layout();
            return new Editor(session, session.frame());
        });
    }

    /** Waits, on the frame's own change notification, until the factory presets are listed. */
    private static void awaitTheFactoryPresets(EditorFrame frame) throws Exception {
        CountDownLatch listed = new CountDownLatch(1);
        ListChangeListener<String> watch = _ -> {
            if (frame.getPresetItems().contains(FACTORY_PRESET)) {
                listed.countDown();
            }
        };
        runOnFx(() -> {
            frame.getPresetItems().addListener(watch);
            if (frame.getPresetItems().contains(FACTORY_PRESET)) {
                listed.countDown();
            }
        });
        try {
            assertThat(listed.await(GUARD_SECONDS, TimeUnit.SECONDS)).as("the factory presets were listed").isTrue();
        } finally {
            runOnFx(() -> frame.getPresetItems().removeListener(watch));
        }
    }

    private static ToggleButton segmentLabelled(EditorFrame frame, String label) {
        return nodes(frame.getBody()).filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast)
                .filter(button -> label.equals(button.getText())).findFirst().orElseThrow();
    }

    /** A Declarative third-party plug-in whose grid has a knob, a segmented selector and a toggle. */
    private static InsertSlot gridSlot() {
        return pluginSlot(new GridPlugin());
    }

    /** {@code plugin}, initialized, in an insert slot of its own, as the plug-in menu inserts it. */
    private static InsertSlot pluginSlot(DawPlugin plugin) {
        plugin.initialize(new PluginContext() {
            @Override public double getSampleRate() { return 48_000; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        });
        return InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
    }

    /** Two editor sessions over one slot, the older opened first. */
    private record Editors(PluginEditorSession older, PluginEditorSession newer) {
    }

    /** Opens two sessions over {@code slot}, both framed in this test's shown stage. */
    private Editors openTwoOn(InsertSlot slot) throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        channel.addInsert(slot);
        return onFx(() -> {
            PluginEditorSession.Deps deps = new PluginEditorSession.Deps(() -> 48_000, null, null, null,
                    allowed::get);
            PluginEditorSession older = PluginEditorSession.open(channel, slot, deps);
            PluginEditorSession newer = PluginEditorSession.open(channel, slot, deps);
            stage = new Stage();
            stage.setScene(new Scene(new HBox(older.frame(), newer.frame()), 1600, 600));
            stage.show();
            return new Editors(older, newer);
        });
    }

    /** The slider a body shows under the label {@code text}, as the Convolution Reverb lays out its sliders. */
    private static Slider sliderLabelled(Node body, String text) {
        return nodes(body).filter(VBox.class::isInstance).map(VBox.class::cast)
                .filter(box -> box.getChildren().size() == 2
                        && box.getChildren().get(0) instanceof Label label && text.equals(label.getText())
                        && box.getChildren().get(1) instanceof Slider)
                .map(box -> (Slider) box.getChildren().get(1)).findFirst().orElseThrow();
    }

    /** The first inner chain's Add button in the Mid/Side wrapper's body. */
    private static Button addButtonOf(Node body) {
        return nodes(body).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "+ Add".equals(button.getText())).findFirst().orElseThrow();
    }

    /** The MID chain's Add picker, while it shows. FX thread. */
    private static Optional<Stage> showingAddPicker() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(window -> window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane
                        && "Select an effect to add to the MID chain".equals(pane.getHeaderText()))
                .findFirst();
    }

    /** The Mid/Side wrapper's inner parameter dialogs over its MID chain that show. FX thread. */
    private static List<Stage> showingInnerParameterDialogs() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(window -> window.isShowing() && window.getTitle() != null
                        && window.getTitle().endsWith(" — Parameters (MID chain)"))
                .toList();
    }

    /** Checks {@code condition} on the FX thread, one turn at a time, until it holds; bounded. */
    private static void awaitOnFx(Supplier<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GUARD_SECONDS);
        while (!onFx(condition)) {
            assertThat(System.nanoTime() - deadline < 0).as("%s within %d s", what, GUARD_SECONDS).isTrue();
        }
    }

    /**
     * A third-party plug-in whose editor is a Panel on the SDK alone: each
     * {@code editorFactory()} call returns a new {@link LevelPanel}. It
     * records the drain's writes of its level, and can fail its panel's
     * first builds or have the panel write its store while it is built.
     */
    private static final class PanelPlugin implements DawPlugin, AudioProcessor {
        static final int LEVEL = 1;

        final List<LevelPanel> panels = new CopyOnWriteArrayList<>();
        final List<Double> writes = new CopyOnWriteArrayList<>();
        final AtomicInteger createPanelFaults = new AtomicInteger();
        volatile boolean writeWhileBuilt;

        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.test.gate.panel", "Panel Plugin", "1.0.0", "Test Vendor",
                    PluginType.EFFECT);
        }
        @Override public Optional<AudioProcessor> asAudioProcessor() { return Optional.of(this); }
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public List<PluginParameter> getParameters() {
            return List.of(new PluginParameter(LEVEL, "Level", 0.0, 1.0, 0.5));
        }
        @Override public PluginEditorFactory editorFactory() {
            LevelPanel panel = new LevelPanel(this);
            panels.add(panel);
            return panel;
        }
        @Override public void setAutomatableParameter(int parameterId, double value) {
            if (parameterId == LEVEL) {
                writes.add(value);
            }
        }
    }

    /**
     * A naive third-party body: its slider writes every change to the store,
     * an echo's included, and reads no gate; {@code parameterChanged} sets the
     * slider. It records each echo, and whether one came before
     * {@code createPanel} returned.
     */
    private static final class LevelPanel implements PluginEditorFactory.Panel {
        private final PanelPlugin plugin;
        final List<Double> echoes = new CopyOnWriteArrayList<>();
        volatile boolean echoedWhileBuilt;
        Slider slider;
        Region root;
        private boolean built;

        LevelPanel(PanelPlugin plugin) {
            this.plugin = plugin;
        }

        @Override public Region createPanel(EditorContext context) {
            if (plugin.createPanelFaults.getAndUpdate(faults -> Math.max(0, faults - 1)) > 0) {
                throw new IllegalStateException("a createPanel fault");
            }
            double initial = plugin.writeWhileBuilt ? 0.9 : context.parameterStore().valueById(PanelPlugin.LEVEL);
            slider = new Slider(0.0, 1.0, initial);
            slider.valueProperty().addListener((_, _, now) ->
                    context.parameterStore().writeFromUiById(PanelPlugin.LEVEL, now.doubleValue()));
            if (plugin.writeWhileBuilt) {
                context.parameterStore().writeFromUiById(PanelPlugin.LEVEL, initial);
            }
            root = new StackPane(slider);
            built = true;
            return root;
        }

        @Override public void parameterChanged(int parameterId, double value) {
            if (!built) {
                echoedWhileBuilt = true;
                return;
            }
            echoes.add(value);
            slider.setValue(value);
        }
    }

    /** The Workshop's real plug-in panel path: a selected compressor insert. */
    private static final class Workshop {
        private final WorkshopView view;
        private final InspectorSelectionModel selectionModel = new InspectorSelectionModel();
        private final DawProject project = new DawProject("Workshop", AudioFormat.CD_QUALITY);
        private final Track drums = project.createAudioTrack("Drums");
        private final InsertSlot compressor = new InsertSlot("Comp",
                InsertEffectFactory.createProcessor(InsertEffectType.COMPRESSOR, 2, 44_100),
                InsertEffectType.COMPRESSOR);

        Workshop() {
            view = new WorkshopView(ResourceBundle.getBundle("com.benesquivelmusic.daw.app.i18n.Messages",
                    Locale.ROOT));
            project.getMixerChannelForTrack(drums).addInsert(compressor);
            new Scene(view, 1280, 800);
        }

        PluginParameterEditorPanel panel(AtomicBoolean allowed) {
            new WorkshopSelectionHostController(view, selectionModel, () -> project, () -> true,
                    ResourceBundle.getBundle("com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT), null,
                    allowed::get);
            selectionModel.setSelection(new InspectorSelection.InsertSelection(UUID.fromString(drums.getId()), 0));
            return (PluginParameterEditorPanel) view.focusedPluginNode();
        }

        Map<Integer, Double> processorValues() {
            return InsertEffectFactory.getParameterValues(InsertEffectType.COMPRESSOR, compressor.getProcessor());
        }

        double processorValue(int id) {
            return processorValues().get(id);
        }
    }

    /** A third-party plug-in with an explicitly Declarative editor over three parameters. */
    private static final class GridPlugin implements DawPlugin, AudioProcessor {
        static final int GAIN = 1;
        static final int MODE = 2;
        static final int ENABLED = 3;

        @Override public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.test.gate.grid", "Grid Plugin", "1.0.0", "Test Vendor",
                    PluginType.EFFECT);
        }
        @Override public Optional<AudioProcessor> asAudioProcessor() { return Optional.of(this); }
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int channel = 0; channel < output.length; channel++) {
                System.arraycopy(input[channel], 0, output[channel], 0, frames);
            }
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public void initialize(PluginContext context) { }
        @Override public void activate() { }
        @Override public void deactivate() { }
        @Override public void dispose() { }
        @Override public List<PluginParameter> getParameters() {
            return List.of(
                    new PluginParameter(GAIN, "Gain", -24.0, 24.0, 0.0),
                    new PluginParameter(MODE, "Mode", 0.0, 3.0, 0.0),
                    new PluginParameter(ENABLED, "Enabled Toggle", 0.0, 1.0, 0.0));
        }
        @Override public PluginEditorFactory editorFactory() {
            return new PluginEditorFactory.Declarative(getParameters(), EditorHints.compact());
        }
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream()
                        .flatMap(PluginEditsAreRefusedWhileProjectActionsAreNotAllowedTest::nodes))
                : Stream.of(node);
    }
}
