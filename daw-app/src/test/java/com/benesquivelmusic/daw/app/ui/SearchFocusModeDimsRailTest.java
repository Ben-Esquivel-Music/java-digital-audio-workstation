package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 309 — the §4.C search-focus mode (Concept C as a <em>mode</em>
 * of Concept A, never a separate surface). Ctrl/Cmd-F focuses the
 * always-on search field AND dims the rail (the rail-level
 * {@code :search-focus} pseudo-class carrying the same 0.4 treatment as
 * no-match dimming); Esc restores the rail. §6.5 stays intact: nothing
 * is globally swallowed, and the search-field Esc is consumed only
 * while the field has text.
 *
 * <p>The key event is asserted through a parent {@code addEventFilter}
 * on the PAYLOAD ({@code getCode()} + {@code isShortcutDown()}), never
 * {@code Event.getSource()} identity — JavaFX rewrites the source per
 * node ({@code feedback_javafx_bubbling_event_test_pitfall}).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SearchFocusModeDimsRailTest {

    private static final PseudoClass SEARCH_FOCUS =
            PseudoClass.getPseudoClass("search-focus");

    private <T> T runOnFxThread(Callable<T> callable) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(callable.call());
            } catch (Throwable t) {
                // Capture AssertionError too — an FX-thread assertion must
                // fail the test, not vanish into the FX exception handler.
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS))
                .as("FX task completed within the timeout")
                .isTrue();
        Throwable thrown = error.get();
        if (thrown instanceof Error e) {
            throw e;
        }
        if (thrown instanceof Exception e) {
            throw e;
        }
        return ref.get();
    }

    private static SettingsShell newShell(SettingsCatalogue catalogue) {
        Preferences prefs = Preferences.userRoot()
                .node("searchFocusModeDimsRailTest_" + System.nanoTime());
        SettingsModel model = new SettingsModel(prefs);
        SettingsShell.ValueAccess access = id -> switch (id) {
            case "appearance.uiScale" -> model.getUiScale();
            default -> catalogue.byId(id)
                    .map(descriptor -> (Object) descriptor.defaultValue())
                    .orElse(null);
        };
        return new SettingsShell(catalogue, access, null, null);
    }

    /** Shortcut modifier set for both platforms (control on Windows, meta on macOS). */
    private static KeyEvent shortcutF() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F,
                false, true, false, true);
    }

    private static KeyEvent escape() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE,
                false, false, false, false);
    }

    private record ObservedKey(KeyCode code, boolean shortcutDown) {}

    @Test
    void shortcutFShouldFocusSearchAndDimTheRailAndEscShouldRestore() throws Exception {
        runOnFxThread(() -> {
            SettingsShell shell = newShell(SettingsCatalogue.create());
            StackPane root = new StackPane(shell);
            Scene scene = new Scene(root, 1024, 720);
            root.applyCss();
            root.layout();

            // Parent-level capture filter recording the PAYLOAD — never
            // getSource() identity (the bubbling-event pitfall).
            List<ObservedKey> observed = new ArrayList<>();
            root.addEventFilter(KeyEvent.KEY_PRESSED, event ->
                    observed.add(new ObservedKey(event.getCode(), event.isShortcutDown())));

            Event.fireEvent(shell, shortcutF());

            assertThat(observed)
                    .as("the parent capture filter saw the shortcut-F payload")
                    .contains(new ObservedKey(KeyCode.F, true));
            Node field = shell.lookup(".settings-search-field");
            assertThat(field).isInstanceOf(TextField.class);
            assertThat(scene.getFocusOwner())
                    .as("Ctrl/Cmd-F focuses the always-on search field")
                    .isSameAs(field);
            assertThat(shell.isSearchFocusDimActive())
                    .as("the §4.C rail dim engages with search focus")
                    .isTrue();
            assertThat(shell.navRail().getPseudoClassStates())
                    .as("the rail carries :search-focus")
                    .contains(SEARCH_FOCUS);

            // Empty-field Esc: restores the rail and — per §6.5 — is NOT
            // consumed by the field (the dialog's close semantics remain
            // reachable). The parent-level bubbling handler proves it.
            List<KeyCode> bubbledToParent = new ArrayList<>();
            root.addEventHandler(KeyEvent.KEY_PRESSED,
                    event -> bubbledToParent.add(event.getCode()));
            Event.fireEvent(field, escape());

            assertThat(shell.isSearchFocusDimActive())
                    .as("Esc restores the rail")
                    .isFalse();
            assertThat(shell.navRail().getPseudoClassStates())
                    .doesNotContain(SEARCH_FOCUS);
            assertThat(bubbledToParent)
                    .as("an empty-field Esc still propagates (nothing globally "
                            + "swallowed — §6.5)")
                    .contains(KeyCode.ESCAPE);
            return null;
        });
    }

    /**
     * The NON-Esc exit: in production, clicking or Tabbing out of the
     * search field must restore the rail via the field's
     * {@code focusedProperty} listener — without it the rail would stay
     * dimmed at 0.4 for the rest of the session.
     */
    @Test
    void focusLossShouldRestoreTheRailWithoutEsc() throws Exception {
        runOnFxThread(() -> {
            SettingsShell shell = newShell(SettingsCatalogue.create());
            StackPane root = new StackPane(shell);
            Scene scene = new Scene(root, 1024, 720);
            // A SHOWN stage (headless Glass platform): only a focused
            // window makes focusedProperty live — and the exit path under
            // test IS the field's focus-loss listener.
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            try {
                root.applyCss();
                root.layout();

                Event.fireEvent(shell, shortcutF());
                TextField field = (TextField) shell.lookup(".settings-search-field");
                assertThat(field.isFocused())
                        .as("the field genuinely holds focus in the shown window")
                        .isTrue();
                assertThat(shell.isSearchFocusDimActive()).isTrue();
                assertThat(shell.navRail().getPseudoClassStates())
                        .contains(SEARCH_FOCUS);

                // Move focus to another node in the realized scene — the
                // focus-loss path, no Esc involved.
                shell.navRail().requestFocus();

                assertThat(scene.getFocusOwner())
                        .as("focus actually left the search field")
                        .isSameAs(shell.navRail());
                assertThat(shell.isSearchFocusDimActive())
                        .as("losing search focus restores the rail")
                        .isFalse();
                assertThat(shell.navRail().getPseudoClassStates())
                        .as(":search-focus is cleared on focus loss")
                        .doesNotContain(SEARCH_FOCUS);
            } finally {
                stage.hide();
            }
            return null;
        });
    }

    @Test
    void searchFocusDimShouldComposeWithMatchCountDimmingAndTextEscClears() throws Exception {
        runOnFxThread(() -> {
            SettingsShell shell = newShell(SettingsCatalogue.create());
            StackPane root = new StackPane(shell);
            new Scene(root, 1024, 720);
            root.applyCss();
            root.layout();

            Event.fireEvent(shell, shortcutF());
            assertThat(shell.isSearchFocusDimActive()).isTrue();

            // Typing a query while in focus mode: the §5.2 per-category
            // match-count dimming still works — the two dim sources must
            // compose without fighting (SearchFiltersAcrossCategoriesTest
            // semantics unchanged).
            TextField field = (TextField) shell.lookup(".settings-search-field");
            field.setText("latency");
            var audioItem = shell.navRail().getItems().stream()
                    .filter(item -> "audio".equals(item.categoryId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no audio rail item"));
            assertThat(audioItem.dimmedProperty().get())
                    .as("the matching category is still not item-dimmed while "
                            + "search focus mode is active")
                    .isFalse();
            assertThat(audioItem.matchCountProperty().get()).isGreaterThanOrEqualTo(1);
            assertThat(shell.isSearchFocusDimActive())
                    .as("typing keeps the focus dim (the field still owns focus)")
                    .isTrue();

            // Non-empty-field Esc: clears the text (consumed — existing
            // §6.5 semantics) and restores the rail.
            Event.fireEvent(field, escape());
            assertThat(field.getText()).isEmpty();
            assertThat(shell.isSearchFocusDimActive()).isFalse();
            return null;
        });
    }
}
