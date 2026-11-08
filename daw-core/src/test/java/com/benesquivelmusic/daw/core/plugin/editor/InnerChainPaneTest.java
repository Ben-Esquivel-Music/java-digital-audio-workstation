package com.benesquivelmusic.daw.core.plugin.editor;

import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin.MenuEntry;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin;
import com.benesquivelmusic.daw.core.plugin.MidSideWrapperPlugin.ChainOwner;
import com.benesquivelmusic.daw.core.plugin.ParametricEqPlugin;
import com.benesquivelmusic.daw.core.plugin.SpectrumAnalyzerPlugin;
import com.benesquivelmusic.daw.core.plugin.VirtualKeyboardPlugin;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import javafx.application.Platform;
import javafx.scene.AccessibleAction;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.GUARD;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.nodes;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.onFx;
import static com.benesquivelmusic.daw.core.plugin.editor.HeadlessFxToolkit.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of {@link InnerChainPane}, the story 302 §8.3 Mid/Side editor's
 * inner-chain list: the pure picker filter
 * {@link InnerChainPane#effectMenuEntries(List)}, and, on the headless
 * JavaFX toolkit ({@link HeadlessFxToolkit}), its edits under the host's
 * UI-write admission (PR #989 review 5711460812): Add reads it before its
 * picker opens and again once the picker returns, so a pick answered after
 * the host began refusing adds nothing, and Remove reads it before it
 * removes. The picker's nested loop runs inside the posted Add, as the
 * application runs it.
 */
class InnerChainPaneTest {

    private static final MenuEntry EQ = new MenuEntry(
            ParametricEqPlugin.class, "Parametric EQ", "eq", BuiltInPluginCategory.EFFECT);
    private static final MenuEntry WRAPPER = new MenuEntry(
            MidSideWrapperPlugin.class, "Mid/Side Wrapper", "stereo", BuiltInPluginCategory.EFFECT);
    private static final MenuEntry KEYBOARD = new MenuEntry(
            VirtualKeyboardPlugin.class, "Virtual Keyboard", "keyboard",
            BuiltInPluginCategory.INSTRUMENT);
    private static final MenuEntry ANALYZER = new MenuEntry(
            SpectrumAnalyzerPlugin.class, "Spectrum Analyzer", "spectrum",
            BuiltInPluginCategory.ANALYZER);

    private static final String PICKER_HEADER = "Select an effect to add to the MID chain";

    @Test
    void keepsOnlyEffectCategoryEntries() {
        List<MenuEntry> filtered =
                InnerChainPane.effectMenuEntries(List.of(EQ, KEYBOARD, ANALYZER));
        assertThat(filtered).containsExactly(EQ);
    }

    @Test
    void excludesTheWrapperItselfSoChainsCannotNest() {
        List<MenuEntry> filtered =
                InnerChainPane.effectMenuEntries(List.of(WRAPPER, EQ));
        assertThat(filtered).containsExactly(EQ);
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertThat(InnerChainPane.effectMenuEntries(List.of())).isEmpty();
    }

    @Test
    void rejectsNullEntries() {
        assertThatThrownBy(() -> InnerChainPane.effectMenuEntries(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The picker opened while edits were admitted; the host began refusing
     * while it was open; its OK, fired by an assistive technology's action,
     * adds nothing: the chain and the list are as they were.
     */
    @Test
    void anAddWhosePickerAnswersAfterTheHostBeganRefusingAddsNothing() throws Exception {
        AtomicBoolean admitted = new AtomicBoolean(true);
        MidSideWrapperPlugin wrapper = new MidSideWrapperPlugin();
        Shown shown = show(wrapper, admitted);
        try {
            CountDownLatch addReturned = fireAddLater(shown.pane());
            DialogPane picker = awaitThePicker();
            admitted.set(false);
            runOnFx(() -> ((Button) picker.lookupButton(ButtonType.OK)).executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(addReturned.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the Add returned once its picker did").isTrue();

            assertThat(onFx(() -> showingPicker().isPresent())).as("the picker is hidden").isFalse();
            assertThat(onFx(wrapper::getMidChain)).as("refused: the chain is as it was").isEmpty();
            assertThat(onFx(() -> listOf(shown.pane()).getItems())).as("and so is the list").isEmpty();
        } finally {
            close(shown);
        }
    }

    /** The positive control: the same flow, edits admitted throughout, adds the picked plug-in. */
    @Test
    void anAddWhosePickerAnswersWhileEditsAreAdmittedAddsThePickedPlugin() throws Exception {
        AtomicBoolean admitted = new AtomicBoolean(true);
        MidSideWrapperPlugin wrapper = new MidSideWrapperPlugin();
        Shown shown = show(wrapper, admitted);
        try {
            CountDownLatch addReturned = fireAddLater(shown.pane());
            DialogPane picker = awaitThePicker();
            runOnFx(() -> ((Button) picker.lookupButton(ButtonType.OK)).executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(addReturned.await(GUARD.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the Add returned once its picker did").isTrue();

            assertThat(onFx(() -> wrapper.getMidChain().size())).as("one plug-in added").isOne();
            assertThat(onFx(() -> listOf(shown.pane()).getItems())).as("and listed")
                    .containsExactlyElementsOf(onFx(wrapper::getMidChain));
        } finally {
            close(shown);
        }
    }

    /** Add while the host refuses opens no picker at all. */
    @Test
    void anAddWhileTheHostRefusesOpensNoPicker() throws Exception {
        AtomicBoolean admitted = new AtomicBoolean(false);
        MidSideWrapperPlugin wrapper = new MidSideWrapperPlugin();
        Shown shown = show(wrapper, admitted);
        try {
            runOnFx(() -> addButtonOf(shown.pane()).executeAccessibleAction(AccessibleAction.FIRE));
            assertThat(onFx(() -> showingPicker().isPresent())).as("no picker opened").isFalse();
            assertThat(onFx(wrapper::getMidChain)).isEmpty();
        } finally {
            close(shown);
        }
    }

    /** Remove, as its context-menu item fires it, keeps the chain while the host refuses and removes once admitted. */
    @Test
    void aRemoveWhileTheHostRefusesKeepsTheChainAndRemovesOnceAdmitted() throws Exception {
        AtomicBoolean admitted = new AtomicBoolean(false);
        MidSideWrapperPlugin wrapper = new MidSideWrapperPlugin();
        DawPlugin eq = new ParametricEqPlugin();
        wrapper.addPlugin(ChainOwner.MID, eq);
        Shown shown = show(wrapper, admitted);
        try {
            MenuItem remove = onFx(() -> cellOf(shown.pane(), eq).getContextMenu().getItems().getFirst());
            runOnFx(remove::fire);
            assertThat(onFx(wrapper::getMidChain)).as("refused: the chain keeps the plug-in").containsExactly(eq);
            assertThat(onFx(() -> listOf(shown.pane()).getItems())).as("and the list shows it").containsExactly(eq);

            admitted.set(true);
            runOnFx(remove::fire);
            assertThat(onFx(wrapper::getMidChain)).as("admitted: removed").isEmpty();
            assertThat(onFx(() -> listOf(shown.pane()).getItems())).isEmpty();
        } finally {
            close(shown);
        }
    }

    // ── rig ──────────────────────────────────────────────────────────────────

    /** A pane over the wrapper's MID chain, shown in its own stage so its list has cells. */
    private record Shown(InnerChainPane pane, Stage stage) {
    }

    private static Shown show(MidSideWrapperPlugin wrapper, AtomicBoolean admitted) throws Exception {
        HeadlessFxToolkit.start();
        return onFx(() -> {
            InnerChainPane pane = new InnerChainPane(wrapper, ChainOwner.MID, "MID", admitted::get, _ -> { });
            pane.refresh();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 420, 240));
            stage.show();
            pane.applyCss();
            pane.layout();
            return new Shown(pane, stage);
        });
    }

    /** Hides the pane's stage and any picker still showing. */
    private static void close(Shown shown) throws Exception {
        runOnFx(() -> {
            showingPicker().ifPresent(Stage::hide);
            shown.stage().hide();
        });
    }

    /**
     * Fires Add on a later FX turn, because its picker's nested loop runs
     * inside it; the latch counts down once the Add returned.
     */
    private static CountDownLatch fireAddLater(InnerChainPane pane) {
        CountDownLatch returned = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                addButtonOf(pane).executeAccessibleAction(AccessibleAction.FIRE);
            } finally {
                returned.countDown();
            }
        });
        return returned;
    }

    private static DialogPane awaitThePicker() throws Exception {
        HeadlessFxToolkit.await(() -> showingPicker().isPresent(), "the Add picker shows");
        return onFx(() -> (DialogPane) showingPicker().orElseThrow().getScene().getRoot());
    }

    /** The Add picker of the MID chain, while it shows. FX thread. */
    private static Optional<Stage> showingPicker() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(Stage::isShowing)
                .filter(stage -> stage.getScene() != null && stage.getScene().getRoot() instanceof DialogPane pane
                        && PICKER_HEADER.equals(pane.getHeaderText()))
                .findFirst();
    }

    private static Button addButtonOf(InnerChainPane pane) {
        return nodes(pane).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> "+ Add".equals(button.getText())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ListView<DawPlugin> listOf(InnerChainPane pane) {
        return nodes(pane).filter(ListView.class::isInstance).map(node -> (ListView<DawPlugin>) node)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ListCell<DawPlugin> cellOf(InnerChainPane pane, DawPlugin item) {
        return nodes(pane).filter(ListCell.class::isInstance).map(node -> (ListCell<DawPlugin>) node)
                .filter(cell -> cell.getItem() == item).findFirst().orElseThrow();
    }
}
