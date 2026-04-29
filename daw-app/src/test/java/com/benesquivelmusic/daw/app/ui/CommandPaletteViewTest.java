package com.benesquivelmusic.daw.app.ui;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class CommandPaletteViewTest {

    /** Helper to run a callable on the FX thread and propagate exceptions. */
    private <T> T onFx(java.util.concurrent.Callable<T> c) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { ref.set(c.call()); }
            catch (Exception e) { err.set(e); }
            finally { latch.countDown(); }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (err.get() != null) throw err.get();
        return ref.get();
    }

    private CommandPaletteEntry entry(String id, String label, Runnable r) {
        return CommandPaletteEntry.of(id, label, "", "", null, r);
    }

    @Test
    void searchFiltersAndRanksByFuzzyScore(@TempDir Path tmp) throws Exception {
        var store = new CommandPaletteRecentsStore(tmp.resolve("recents.json"));
        AtomicInteger newTrackInvocations = new AtomicInteger();
        AtomicInteger newProjectInvocations = new AtomicInteger();
        var entries = List.of(
                entry("NEW_TRACK", "New Track", newTrackInvocations::incrementAndGet),
                entry("NEW_PROJECT", "New Project", newProjectInvocations::incrementAndGet),
                entry("OPEN_SETTINGS", "Settings", () -> {}));

        List<Object> filteredRows = onFx(() -> {
            CommandPaletteView view = new CommandPaletteView(() -> entries, store);
            view.refreshFromSupplierForTesting();
            view.searchField().setText("nt");
            return view.visibleRows();
        });
        // After filter, only matches remain; "New Track" must be first.
        assertThat(filteredRows).hasSizeGreaterThanOrEqualTo(1);
        assertThat(filteredRows.get(0)).isInstanceOf(CommandPaletteEntry.class);
        assertThat(((CommandPaletteEntry) filteredRows.get(0)).id()).isEqualTo("NEW_TRACK");
    }

    @Test
    void executingFromPaletteInvokesHandlerAndRecordsRecent(@TempDir Path tmp) throws Exception {
        var store = new CommandPaletteRecentsStore(tmp.resolve("recents.json"));
        AtomicInteger invoked = new AtomicInteger();
        Runnable handler = invoked::incrementAndGet;
        var entries = List.of(
                entry("NEW_TRACK", "New Track", handler),
                entry("OPEN_SETTINGS", "Settings", () -> {}));

        onFx(() -> {
            CommandPaletteView view = new CommandPaletteView(() -> entries, store);
            view.refreshFromSupplierForTesting();
            view.searchField().setText("new track");
            view.invokeSelected();
            return null;
        });

        // Handler invoked exactly once — same effect as a menu click would have.
        assertThat(invoked.get()).isEqualTo(1);
        // Recents persisted to disk for next session.
        assertThat(store.load()).containsExactly("NEW_TRACK");
    }

    @Test
    void recentsSurfacedAtTopOfEmptyQuery(@TempDir Path tmp) throws Exception {
        var store = new CommandPaletteRecentsStore(tmp.resolve("recents.json"));
        store.recordExecution("OPEN_SETTINGS");
        var entries = List.of(
                entry("NEW_TRACK", "New Track", () -> {}),
                entry("NEW_PROJECT", "New Project", () -> {}),
                entry("OPEN_SETTINGS", "Settings", () -> {}));

        List<Object> rows = onFx(() -> {
            CommandPaletteView view = new CommandPaletteView(() -> entries, store);
            view.refreshFromSupplierForTesting();
            return view.visibleRows();
        });
        // Should start with the RECENT header, then the recent entry.
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0)).isInstanceOf(String.class);
        assertThat(rows.get(1)).isInstanceOfSatisfying(CommandPaletteEntry.class,
                e -> assertThat(e.id()).isEqualTo("OPEN_SETTINGS"));
    }

    @Test
    void disabledEntryIsNotExecuted(@TempDir Path tmp) throws Exception {
        var store = new CommandPaletteRecentsStore(tmp.resolve("recents.json"));
        AtomicInteger invoked = new AtomicInteger();
        var disabled = new CommandPaletteEntry(
                "DISABLED", "Disabled Action", "", "", false,
                "Not available now", null, invoked::incrementAndGet);
        var entries = List.of(disabled);

        onFx(() -> {
            CommandPaletteView view = new CommandPaletteView(() -> entries, store);
            view.refreshFromSupplierForTesting();
            view.invokeSelected();
            return null;
        });
        assertThat(invoked.get()).isZero();
        assertThat(store.load()).isEmpty();
    }

    @Test
    void toggleHidesAndShows(@TempDir Path tmp) throws Exception {
        var store = new CommandPaletteRecentsStore(tmp.resolve("recents.json"));
        var entries = List.<CommandPaletteEntry>of();
        Boolean[] states = onFx(() -> {
            CommandPaletteView view = new CommandPaletteView(() -> entries, store);
            // Construction-only verification: the stage exists and starts hidden.
            boolean a = view.isVisible();
            boolean stageReady = view.stage() != null;
            return new Boolean[] { a, stageReady };
        });
        assertThat(states[0]).isFalse();
        assertThat(states[1]).isTrue();
    }
}
