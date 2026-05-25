package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.layout.BuiltInLayouts;
import com.benesquivelmusic.daw.app.ui.layout.LayoutManager;
import com.benesquivelmusic.daw.app.ui.layout.NamedLayout;

import javafx.application.Platform;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 282 acceptance criterion ({@code ViewLayoutMenuTest}): assert
 * the View menu contains a "Layout" submenu with five radio entries
 * (one per built-in), that selecting one calls
 * {@link LayoutManager#load(String)}, and that the radio check reflects
 * {@link LayoutManager#currentLayoutProperty()}.
 *
 * <p>The {@link LayoutsMenu} builder is the production wire-up for the
 * View → Layout menu in {@code MainController}; testing it directly
 * exercises the same code path without needing to spin up an FXML
 * MainController (Skill: the menu-builder pattern mirrors
 * {@code WorkspacesMenu} so its tests stay headless).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LayoutsMenuTest {

    private static final class RecordingHost implements LayoutManager.Host {
        String current = "{}";
        @Override public String captureDockLayoutJson() { return current; }
        @Override public void applyDockLayoutJson(String json) { current = json; }
    }

    private static Menu buildMenuOnFxThread(LayoutManager mgr) throws Exception {
        AtomicReference<Menu> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            ref.set(new LayoutsMenu(mgr, () -> null, _ -> { }, () -> { }).build());
            latch.countDown();
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX thread did not respond");
        }
        return ref.get();
    }

    private static List<RadioMenuItem> radioItems(Menu menu) {
        return menu.getItems().stream()
                .takeWhile(mi -> !(mi instanceof SeparatorMenuItem))
                .filter(RadioMenuItem.class::isInstance)
                .map(RadioMenuItem.class::cast)
                .toList();
    }

    @Test
    void menuExposesAllFiveBuiltInsAsRadioItems() throws Exception {
        LayoutManager mgr = new LayoutManager(new RecordingHost());
        Menu menu = buildMenuOnFxThread(mgr);

        List<RadioMenuItem> radios = radioItems(menu);
        assertThat(radios)
                .extracting(MenuItem::getText)
                .containsExactly(BuiltInLayouts.DEFAULT,
                        BuiltInLayouts.TRACKING,
                        BuiltInLayouts.MIXING,
                        BuiltInLayouts.MASTERING,
                        BuiltInLayouts.LIVE);
        // "Default" radio is selected initially because
        // currentLayoutProperty() seeds to BuiltInLayouts.DEFAULT.
        assertThat(radios.stream().filter(RadioMenuItem::isSelected))
                .extracting(MenuItem::getText)
                .containsExactly(BuiltInLayouts.DEFAULT);
    }

    @Test
    void selectingARadioLoadsThatLayout() throws Exception {
        LayoutManager mgr = new LayoutManager(new RecordingHost());
        Menu menu = buildMenuOnFxThread(mgr);

        RadioMenuItem mixing = radioItems(menu).stream()
                .filter(rmi -> BuiltInLayouts.MIXING.equals(rmi.getText()))
                .findFirst().orElseThrow();

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            mixing.setSelected(true);
            mixing.fire();
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(mgr.currentLayoutProperty().get()).isEqualTo(BuiltInLayouts.MIXING);
    }

    @Test
    void currentLayoutPropertyDrivesRadioCheck() throws Exception {
        LayoutManager mgr = new LayoutManager(new RecordingHost());
        Menu menu = buildMenuOnFxThread(mgr);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            mgr.load(BuiltInLayouts.LIVE);
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        RadioMenuItem live = radioItems(menu).stream()
                .filter(rmi -> BuiltInLayouts.LIVE.equals(rmi.getText()))
                .findFirst().orElseThrow();
        assertThat(live.isSelected()).isTrue();
    }

    @Test
    void menuHasSaveAsAndManageBelowSeparator() throws Exception {
        LayoutManager mgr = new LayoutManager(new RecordingHost());
        Menu menu = buildMenuOnFxThread(mgr);

        // After the separator we expect "Save Layout As…" then
        // "Manage Layouts…".
        int sepIdx = -1;
        var items = menu.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof SeparatorMenuItem) {
                sepIdx = i;
                break;
            }
        }
        assertThat(sepIdx).isGreaterThan(0);
        assertThat(items.get(sepIdx + 1).getText()).contains("Save Layout As");
        assertThat(items.get(sepIdx + 2).getText()).contains("Manage Layouts");
    }

    @Test
    void userSavedLayoutAppearsInMenuAfterSave() throws Exception {
        LayoutManager mgr = new LayoutManager(new RecordingHost());
        Menu menu = buildMenuOnFxThread(mgr);

        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            mgr.saveCurrent("My Custom");
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(radioItems(menu))
                .extracting(MenuItem::getText)
                .endsWith("My Custom");
        assertThat(mgr.savedLayouts())
                .extracting(NamedLayout::name)
                .endsWith("My Custom");
    }
}
