package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dock.DockManager;
import com.benesquivelmusic.daw.app.ui.dock.DockZone;
import com.benesquivelmusic.daw.app.ui.dock.Dockable;
import com.benesquivelmusic.daw.app.ui.dock.FloatingWindowStore;
import com.benesquivelmusic.daw.app.ui.layout.DockManifestModel;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;

import javafx.collections.FXCollections;
import javafx.event.EventHandler;
import javafx.geometry.Side;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The close's input fence follows the windows shown while it is up (story
 * 333, design §1.5; the studio engineer's ruling R-3.3; PR #989 review
 * 5711460812): a stage, a floating panel that a manifest button's accessible
 * {@code FIRE} shows and the list that a combo box's accessible
 * {@code EXPAND} opens — all shown after {@link WindowInputFence#raise()} —
 * take no click and no key, so the keys a fenced owner redirects to its
 * popups reach nothing; a window carrying the close-surface mark takes both,
 * and so do the popups and windows it owns, at any depth, through owners that
 * are each showing and not fenced — a window owned by a marked surface that
 * hid again or that the raise fenced, by a window hidden at the raise, or
 * shown from an unmarked owner's own showing handler is fenced;
 * {@link WindowInputFence#lower()} frees the
 * windows fenced after the raise too and stops watching, so a window shown
 * later is live; and a fenced window shown again is fenced once. The input is
 * real — mouse and key events fired at the control, whose dispatch chain
 * runs through its window's filter as the toolkit's events do — and it is
 * counted by event filters on each scene's root. Every window a test shows is
 * hidden, and the fence lowered, after it: the fence watches the global list
 * of windows.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class WindowInputFenceTest {

    @TempDir
    Path folder;

    /** Every window a test showed, in order: hidden after it, the last shown first. */
    private final List<Window> shown = new CopyOnWriteArrayList<>();
    private WindowInputFence fence;
    private Stage main;
    private VBox mainRoot;
    private Button mainButton;

    @BeforeEach
    void aMainWindowIsShowing() throws Exception {
        runOnFx(() -> {
            fence = new WindowInputFence();
            mainButton = new Button("Play");
            mainRoot = new VBox(mainButton);
            main = new Stage();
            main.setTitle("Main window");
            main.setScene(new Scene(mainRoot, 360, 240));
            show(main);
            mainButton.requestFocus();
        });
    }

    @AfterEach
    void theFenceIsLoweredAndEveryWindowHidden() throws Exception {
        runOnFx(() -> {
            fence.lower();
            List<Window> windows = new ArrayList<>(shown);
            Collections.reverse(windows);
            for (Window window : windows) {
                if (window.isShowing()) {
                    window.hide();
                }
            }
            shown.clear();
        });
    }

    @Test
    void aStageShownAfterTheRaiseConsumesAClickAndAKey() throws Exception {
        CountingStage late = onFx(() -> new CountingStage("Shown after the raise"));

        boolean fenced = onFx(() -> {
            fence.raise();
            show(late.stage);
            click(late.button);
            type(late.button, KeyCode.SPACE);
            return fence.fencedWindows().contains(late.stage);
        });

        assertThat(late.actions).as("neither the click nor Space reached its button").hasValue(0);
        assertThat(late.presses).as("nor its scene's root").hasValue(0);
        assertThat(late.keys).hasValue(0);
        assertThat(fenced).as("the stage shown after the raise is fenced").isTrue();
    }

    /**
     * The studio engineer's G8: each marked window is driven both ways — a
     * marked stage's default button by a click, then by Enter at its focus
     * owner; one marked Alert's OK by a click, another's by Enter.
     */
    @Test
    void aMarkedStageAndAMarkedAlertShownAfterTheRaiseTakeAClickAndEnter() throws Exception {
        CountingStage marked = onFx(() -> new CountingStage("A close surface"));

        boolean stageFenced = onFx(() -> {
            fence.raise();
            marked.button.setDefaultButton(true);
            marked.stage.initOwner(main);
            WindowInputFence.markCloseSurface(marked.stage);
            show(marked.stage);
            click(marked.button);
            return fence.fencedWindows().contains(marked.stage);
        });
        assertThat(marked.actions).as("the click reached the marked stage's button").hasValue(1);
        Node stageFocus = focusOwner(marked.stage.getScene());
        runOnFx(() -> type(stageFocus, KeyCode.ENTER));
        assertThat(marked.actions).as("and so did Enter at its focus owner").hasValue(2);

        Alert byClick = onFx(this::markedAlert);
        boolean byClickFenced = onFx(() -> {
            byClick.show();
            shown.add(windowOf(byClick));
            return fence.fencedWindows().contains(windowOf(byClick));
        });
        runOnFx(() -> click(byClick.getDialogPane().lookupButton(ButtonType.OK)));
        assertThat(onFx(byClick::getResult)).as("a click answered the marked Alert").isEqualTo(ButtonType.OK);
        assertThat(onFx(byClick::isShowing)).isFalse();

        Alert byEnter = onFx(this::markedAlert);
        boolean byEnterFenced = onFx(() -> {
            byEnter.show();
            shown.add(windowOf(byEnter));
            return fence.fencedWindows().contains(windowOf(byEnter));
        });
        Node alertFocus = focusOwner(windowOf(byEnter).getScene());
        runOnFx(() -> type(alertFocus, KeyCode.ENTER));
        assertThat(onFx(byEnter::getResult)).as("Enter answered the other").isEqualTo(ButtonType.OK);
        assertThat(onFx(byEnter::isShowing)).isFalse();

        assertThat(List.of(stageFenced, byClickFenced, byEnterFenced)).as("none of the marked windows is fenced")
                .containsExactly(false, false, false);
        assertThat(onFx(fence::isRaised)).as("while the fence is up").isTrue();
    }

    /**
     * The owner rule: a popup of a fenced window is fenced — the keys its
     * owner redirects to it reach no item — and a popup of a marked window is
     * not, so its item answers the same keys (the redirect of
     * {@code PopupWindow}'s event redirector).
     */
    @Test
    void aContextMenuOfTheFencedStageIsFencedAndOneOfTheMarkedDialogIsLive() throws Exception {
        AtomicInteger fencedItemActions = new AtomicInteger();
        AtomicInteger liveItemActions = new AtomicInteger();
        ContextMenu ofTheFencedStage = onFx(() -> menu("Fenced item", fencedItemActions));

        boolean menuFenced = onFx(() -> {
            fence.raise();
            ofTheFencedStage.show(mainButton, Side.BOTTOM, 0, 0);
            shown.add(ofTheFencedStage);
            return fence.fencedWindows().contains(ofTheFencedStage);
        });
        // The menu's scene takes its focus owner on a pulse, as the marked dialog's menu does below: until then a
        // redirected key targets its scene and reaches no item, fenced or not.
        awaitAPulseOf(ofTheFencedStage.getScene());
        Node mainFocus = focusOwner(main.getScene());
        runOnFx(() -> type(mainFocus, KeyCode.DOWN));
        runOnFx(() -> type(mainFocus, KeyCode.ENTER));
        boolean fencedMenuStillShowing = onFx(ofTheFencedStage::isShowing);
        runOnFx(ofTheFencedStage::hide);

        CountingStage dialog = onFx(() -> new CountingStage("A close surface"));
        ContextMenu ofTheMarkedDialog = onFx(() -> menu("Live item", liveItemActions));
        Tooltip tooltip = onFx(() -> new Tooltip("Saving project…"));
        List<Boolean> markedWindowsFenced = onFx(() -> {
            dialog.stage.initOwner(main);
            WindowInputFence.markCloseSurface(dialog.stage);
            show(dialog.stage);
            dialog.button.requestFocus();
            tooltip.show(dialog.button, 0, 0);
            shown.add(tooltip);
            boolean tooltipFenced = fence.fencedWindows().contains(tooltip);
            tooltip.hide();
            ofTheMarkedDialog.show(dialog.button, Side.BOTTOM, 0, 0);
            shown.add(ofTheMarkedDialog);
            return List.of(fence.fencedWindows().contains(dialog.stage), tooltipFenced,
                    fence.fencedWindows().contains(ofTheMarkedDialog));
        });
        awaitAPulseOf(ofTheMarkedDialog.getScene());
        Node dialogFocus = focusOwner(dialog.stage.getScene());
        runOnFx(() -> type(dialogFocus, KeyCode.DOWN));
        runOnFx(() -> type(dialogFocus, KeyCode.ENTER));

        assertThat(fencedItemActions).as("Down and Enter at the fenced stage, redirected to its menu, reached no item")
                .hasValue(0);
        assertThat(fencedMenuStillShowing).as("nor closed it").isTrue();
        assertThat(menuFenced).as("the fenced stage's context menu is fenced").isTrue();
        assertThat(liveItemActions).as("the marked dialog's menu took Down and Enter").hasValue(1);
        assertThat(dialog.actions).as("which never reached the dialog's own button").hasValue(0);
        assertThat(markedWindowsFenced).as("the marked dialog, its tooltip and its context menu are not fenced")
                .containsExactly(false, false, false);
    }

    /**
     * The window a control without an input event shows (ruling-2 F1): a
     * manifest button's accessible {@code FIRE} makes a hidden floating
     * panel visible — the dock manager's host shows its owned stage, as
     * {@code MainController}'s {@code ensureFloating} does — and that float,
     * shown after the raise, takes no click until the fence is lowered.
     */
    @Test
    void aFloatTheManifestButtonShowsAfterTheRaiseTakesNoInput() throws Exception {
        CountingStage floating = onFx(() -> new CountingStage("Mixer (floating)"));
        DockManifestModel manifest = onFx(() -> {
            floating.stage.initOwner(main);
            DockManager.Host host = layout -> layout.entry(FloatingPanel.ID)
                    .filter(entry -> entry.zone() == DockZone.FLOATING)
                    .ifPresent(entry -> {
                        if (entry.visible() && !floating.stage.isShowing()) {
                            show(floating.stage);
                        } else if (!entry.visible() && floating.stage.isShowing()) {
                            floating.stage.hide();
                        }
                    });
            DockManager docks = new DockManager(host, new FloatingWindowStore(folder.resolve("floating.json")));
            docks.register(new FloatingPanel());
            docks.float_(FloatingPanel.ID, new Rectangle2D(120, 80, 200, 120));
            docks.setVisible(FloatingPanel.ID, false);
            return new DockManifestModel(docks);
        });
        try {
            Button manifestButton = onFx(() -> {
                Button button = new Button("Mixer");
                button.setOnAction(_ -> manifest.focusPanel(FloatingPanel.ID));
                mainRoot.getChildren().add(button);
                return button;
            });
            assertThat(onFx(floating.stage::isShowing)).as("fixture: the float is hidden at the raise").isFalse();

            List<Boolean> shownAndFenced = onFx(() -> {
                fence.raise();
                manifestButton.executeAccessibleAction(AccessibleAction.FIRE);
                click(floating.button);
                return List.of(floating.stage.isShowing(), fence.fencedWindows().contains(floating.stage));
            });
            assertThat(floating.actions).as("the click at the float's button was consumed").hasValue(0);
            assertThat(floating.presses).hasValue(0);
            assertThat(shownAndFenced).as("the accessible FIRE showed the float, and it is fenced")
                    .containsExactly(true, true);

            runOnFx(() -> {
                fence.lower();
                click(floating.button);
            });
            assertThat(floating.actions).as("the same click once the fence is lowered").hasValue(1);
        } finally {
            runOnFx(manifest::dispose);
        }
    }

    /**
     * A combo box's list that an assistive technology's {@code EXPAND} opens
     * after the raise is fenced: the keys fired at the combo, which its
     * fenced window redirects to the list, neither move the selection nor
     * commit and close it.
     */
    @Test
    void aComboListThatExpandOpensAfterTheRaiseTakesNoKeys() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        ComboBox<String> combo = onFx(() -> {
            ComboBox<String> inputs = new ComboBox<>(FXCollections.observableArrayList("Input 1", "Input 2",
                    "Input 3"));
            inputs.setValue("Input 1");
            inputs.setOnAction(_ -> actions.incrementAndGet());
            mainRoot.getChildren().add(inputs);
            mainRoot.applyCss();
            mainRoot.layout();
            return inputs;
        });
        try {
            List<Window> opened = onFx(() -> {
                fence.raise();
                List<Window> before = List.copyOf(Window.getWindows());
                combo.executeAccessibleAction(AccessibleAction.EXPAND);
                List<Window> added = Window.getWindows().stream().filter(window -> !before.contains(window))
                        .toList();
                shown.addAll(added);
                return added;
            });
            assertThat(opened).as("EXPAND opened the list in one new window").hasSize(1);
            assertThat(onFx(combo::isShowing)).isTrue();
            boolean listFenced = onFx(() -> fence.fencedWindows().contains(opened.getFirst()));

            runOnFx(() -> combo.fireEvent(key(KeyEvent.KEY_PRESSED, KeyCode.DOWN)));
            runOnFx(() -> type(combo, KeyCode.ENTER));

            assertThat(actions).as("Down chose nothing, Enter committed nothing").hasValue(0);
            assertThat(onFx(combo::getValue)).isEqualTo("Input 1");
            assertThat(onFx(combo::isShowing)).as("Enter never reached the list, which would have closed").isTrue();
            assertThat(listFenced).as("the list's window is fenced").isTrue();
        } finally {
            runOnFx(combo::hide);
        }
    }

    @Test
    void afterLowerAPostRaiseStageTakesTheClickAndAStageShownAfterLowerIsNotFenced() throws Exception {
        CountingStage duringTheRaise = onFx(() -> new CountingStage("Shown while the fence is up"));
        CountingStage afterTheLower = onFx(() -> new CountingStage("Shown after the fence was lowered"));

        boolean fencedWhileUp = onFx(() -> {
            fence.raise();
            show(duringTheRaise.stage);
            return fence.fencedWindows().contains(duringTheRaise.stage);
        });
        List<Window> fencedAfterTheLower = onFx(() -> {
            fence.lower();
            click(duringTheRaise.button);
            show(afterTheLower.stage);
            click(afterTheLower.button);
            return fence.fencedWindows();
        });

        assertThat(fencedWhileUp).as("fixture: fenced while the fence was up").isTrue();
        assertThat(duringTheRaise.actions).as("lowering freed the stage fenced after the raise").hasValue(1);
        assertThat(afterTheLower.actions).as("and stopped the watch: a stage shown later takes the click")
                .hasValue(1);
        assertThat(fencedAfterTheLower).isEmpty();
        assertThat(onFx(fence::isRaised)).isFalse();
    }

    @Test
    void aFencedWindowHiddenAndShownAgainAppearsOnceInFencedWindows() throws Exception {
        CountingStage late = onFx(() -> new CountingStage("Shown, hidden and shown again"));

        List<Window> fenced = onFx(() -> {
            fence.raise();
            show(late.stage);
            late.stage.hide();
            late.stage.show();
            click(late.button);
            return fence.fencedWindows();
        });

        assertThat(Collections.frequency(fenced, late.stage)).as("fenced once").isEqualTo(1);
        assertThat(late.actions).as("and still fenced").hasValue(0);
    }

    // ── the owner rule: through owners that are showing, not fenced, and exempt themselves ──

    /** The owner-showing half: a window owned by a marked surface that hid again is fenced. */
    @Test
    void aWindowOwnedByAMarkedWindowThatHidAgainIsFenced() throws Exception {
        CountingStage marked = onFx(() -> new CountingStage("A close surface, hidden again"));
        CountingStage owned = onFx(() -> new CountingStage("Owned by it"));

        boolean fenced = onFx(() -> {
            fence.raise();
            WindowInputFence.markCloseSurface(marked.stage);
            show(marked.stage);
            marked.stage.hide();
            owned.stage.initOwner(marked.stage);
            show(owned.stage);
            click(owned.button);
            type(owned.button, KeyCode.SPACE);
            return fence.fencedWindows().contains(owned.stage);
        });

        assertThat(owned.actions).as("neither the click nor Space reached its button").hasValue(0);
        assertThat(owned.keys).as("nor its scene's root").hasValue(0);
        assertThat(fenced).as("its owner is not showing, so it is fenced").isTrue();
        assertTheButtonTakesTheClickOnceTheFenceIsLowered(owned);
    }

    /** The owner-not-fenced half: a window owned by a marked surface the raise fenced is fenced. */
    @Test
    void aWindowOwnedByAMarkedWindowShowingAtTheRaiseIsFenced() throws Exception {
        CountingStage marked = onFx(() -> new CountingStage("A close surface, showing at the raise"));
        CountingStage owned = onFx(() -> new CountingStage("Owned by it"));

        List<Boolean> fenced = onFx(() -> {
            WindowInputFence.markCloseSurface(marked.stage);
            show(marked.stage);
            fence.raise();
            owned.stage.initOwner(marked.stage);
            show(owned.stage);
            click(owned.button);
            type(owned.button, KeyCode.SPACE);
            return List.of(fence.fencedWindows().contains(marked.stage), fence.fencedWindows().contains(owned.stage));
        });

        assertThat(owned.actions).as("neither the click nor Space reached its button").hasValue(0);
        assertThat(owned.keys).as("nor its scene's root").hasValue(0);
        assertThat(fenced).as("the raise fenced its marked owner, so it is fenced too").containsExactly(true, true);
        assertTheButtonTakesTheClickOnceTheFenceIsLowered(owned);
    }

    /**
     * A window shown from its owner's own showing handler is classified while
     * that owner already reads as showing but has not yet joined the list of
     * windows, so the fence is not on it yet (fx Window.java, the showing
     * property's {@code invalidated}): the owner is classified by the same
     * rule, so an unmarked owner exempts nothing, and both are fenced.
     */
    @Test
    void aWindowShownFromItsUnmarkedOwnersShowingHandlerIsFencedWithIt() throws Exception {
        CountingStage owner = onFx(() -> new CountingStage("An unmarked owner"));
        CountingStage owned = onFx(() -> new CountingStage("Shown from its owner's showing handler"));
        AtomicBoolean once = new AtomicBoolean();
        EventHandler<WindowEvent> showTheOwned = _ -> {
            if (once.compareAndSet(false, true)) {
                show(owned.stage);
            }
        };

        List<Boolean> shownAndFenced;
        try {
            shownAndFenced = onFx(() -> {
                owned.stage.initOwner(owner.stage);
                owner.stage.addEventHandler(WindowEvent.WINDOW_SHOWING, showTheOwned);
                fence.raise();
                show(owner.stage);
                click(owned.button);
                type(owned.button, KeyCode.SPACE);
                return List.of(owned.stage.isShowing(), fence.fencedWindows().contains(owner.stage),
                        fence.fencedWindows().contains(owned.stage));
            });
        } finally {
            runOnFx(() -> owner.stage.removeEventHandler(WindowEvent.WINDOW_SHOWING, showTheOwned));
        }

        assertThat(owned.actions).as("neither the click nor Space reached its button").hasValue(0);
        assertThat(owned.keys).as("nor its scene's root").hasValue(0);
        assertThat(shownAndFenced).as("its owner's showing handler showed it, and both are fenced")
                .containsExactly(true, true, true);
        assertTheButtonTakesTheClickOnceTheFenceIsLowered(owned);
    }

    /** Depth: a window owned through an exempt window of a marked surface is exempt, and takes input. */
    @Test
    void aWindowOwnedThroughAnExemptWindowOfAMarkedSurfaceTakesInput() throws Exception {
        CountingStage marked = onFx(() -> new CountingStage("A close surface"));
        CountingStage first = onFx(() -> new CountingStage("Owned by the surface"));
        CountingStage second = onFx(() -> new CountingStage("Owned by that window"));

        List<Boolean> fenced = onFx(() -> {
            fence.raise();
            WindowInputFence.markCloseSurface(marked.stage);
            show(marked.stage);
            first.stage.initOwner(marked.stage);
            show(first.stage);
            second.stage.initOwner(first.stage);
            show(second.stage);
            click(second.button);
            type(second.button, KeyCode.SPACE);
            return List.of(fence.fencedWindows().contains(first.stage), fence.fencedWindows().contains(second.stage));
        });

        assertThat(second.actions).as("the click and Space reached its button").hasValue(2);
        assertThat(second.keys).as("through its scene's root").hasValue(1);
        assertThat(fenced).as("neither window under the surface is fenced").containsExactly(false, false);
    }

    /** A window owned by one hidden at the raise is fenced: an owner the fence is not on exempts nothing alone. */
    @Test
    void aWindowOwnedByAWindowHiddenAtTheRaiseIsFenced() throws Exception {
        CountingStage hidden = onFx(() -> new CountingStage("Hidden at the raise"));
        CountingStage owned = onFx(() -> new CountingStage("Owned by it"));

        boolean fenced = onFx(() -> {
            show(hidden.stage);
            hidden.stage.hide();
            fence.raise();
            owned.stage.initOwner(hidden.stage);
            show(owned.stage);
            click(owned.button);
            type(owned.button, KeyCode.SPACE);
            return fence.fencedWindows().contains(owned.stage);
        });

        assertThat(owned.actions).as("neither the click nor Space reached its button").hasValue(0);
        assertThat(owned.keys).as("nor its scene's root").hasValue(0);
        assertThat(fenced).as("it is fenced").isTrue();
        assertTheButtonTakesTheClickOnceTheFenceIsLowered(owned);
    }

    /** The positive control on the same button: once the fence is lowered, its click reaches it. */
    private void assertTheButtonTakesTheClickOnceTheFenceIsLowered(CountingStage window) throws Exception {
        runOnFx(() -> {
            fence.lower();
            click(window.button);
        });
        assertThat(window.actions).as("the same click once the fence is lowered").hasValue(1);
    }

    // ── real input ───────────────────────────────────────────────────────────

    /** A primary-button press and release fired at {@code node}: a click its window's filters see. FX thread. */
    static void click(Node node) {
        node.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
        node.fireEvent(new MouseEvent(MouseEvent.MOUSE_RELEASED, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, false, null));
    }

    /** {@code code} pressed and released at {@code node}. FX thread. */
    static void type(Node node, KeyCode code) {
        node.fireEvent(key(KeyEvent.KEY_PRESSED, code));
        node.fireEvent(key(KeyEvent.KEY_RELEASED, code));
    }

    private static KeyEvent key(javafx.event.EventType<KeyEvent> type, KeyCode code) {
        return new KeyEvent(type, "", "", code, false, false, false, false);
    }

    /**
     * The focus owner of {@code scene} once it has one — a few FX turns,
     * bounded — else its root. Not on the FX thread.
     */
    static Node focusOwner(Scene scene) throws Exception {
        for (int turn = 0; turn < 50; turn++) {
            Node owner = onFx(scene::getFocusOwner);
            if (owner != null) {
                return owner;
            }
        }
        return onFx(scene::getRoot);
    }

    /**
     * Waits, bounded, for the pulses of {@code scene} until it has a focus
     * owner, and asserts that it has one. JavaFX gives a popup's scene its
     * first focus owner at the start of a pulse after the popup shows (fx
     * Scene.java, {@code ScenePulseListener.focusCleanup}); until then a key
     * redirected to the popup targets its scene and reaches no item. A
     * person's key never comes before that pulse; a test's can, so the test
     * waits for it — a one-shot post-layout listener of each pulse, never a
     * sleep. The fence filters input events only, so a fenced popup gets its
     * focus owner too. Not on the FX thread.
     */
    static void awaitAPulseOf(Scene scene) throws Exception {
        long deadline = System.nanoTime() + SaveFlowFixture.GUARD.toNanos();
        while (onFx(scene::getFocusOwner) == null) {
            long remaining = deadline - System.nanoTime();
            assertThat(remaining).as("the popup's scene got its focus owner within the guard").isPositive();
            java.util.concurrent.CountDownLatch pulsed = new java.util.concurrent.CountDownLatch(1);
            Runnable listener = pulsed::countDown;
            runOnFx(() -> {
                scene.addPostLayoutPulseListener(listener);
                javafx.application.Platform.requestNextPulse();
            });
            try {
                assertThat(pulsed.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS))
                        .as("a pulse of the popup's scene ran").isTrue();
            } finally {
                runOnFx(() -> scene.removePostLayoutPulseListener(listener));
            }
        }
        assertThat(onFx(scene::getFocusOwner)).as("the popup's scene has its focus owner").isNotNull();
    }

    // ── windows ──────────────────────────────────────────────────────────────

    /** Shows {@code stage}, remembered first so that it is hidden after the test. FX thread. */
    private Stage show(Stage stage) {
        if (!shown.contains(stage)) {
            shown.add(stage);
        }
        stage.show();
        return stage;
    }

    /** An information Alert owned by the main window, carrying the close-surface mark. FX thread. */
    private Alert markedAlert() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, "Saving project…");
        alert.initOwner(main);
        WindowInputFence.markCloseSurface(alert);
        return alert;
    }

    private static Window windowOf(Alert alert) {
        return alert.getDialogPane().getScene().getWindow();
    }

    private static ContextMenu menu(String item, AtomicInteger actions) {
        MenuItem menuItem = new MenuItem(item);
        menuItem.setOnAction(_ -> actions.incrementAndGet());
        return new ContextMenu(menuItem);
    }

    /** A floating dock panel for the dock manager's host to show. */
    private record FloatingPanel(String dockId, String displayName) implements Dockable {
        static final String ID = "mixer";

        FloatingPanel() {
            this(ID, "Mixer");
        }
    }

    /**
     * A stage holding one button, with what reached it: the button's
     * actions, and the presses and key presses its scene's root saw first.
     * Built on the FX thread.
     */
    private static final class CountingStage {
        final Stage stage = new Stage();
        final Button button;
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger presses = new AtomicInteger();
        final AtomicInteger keys = new AtomicInteger();

        CountingStage(String title) {
            button = new Button(title);
            button.setOnAction(_ -> actions.incrementAndGet());
            VBox root = new VBox(button);
            root.addEventFilter(MouseEvent.MOUSE_PRESSED, _ -> presses.incrementAndGet());
            root.addEventFilter(KeyEvent.KEY_PRESSED, _ -> keys.incrementAndGet());
            stage.setTitle(title);
            stage.setScene(new Scene(root, 200, 120));
        }
    }
}
