package com.benesquivelmusic.daw.app.ui.dock;

import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FloatingWindowStore} — particularly the property
 * that a floating-window position survives an "app restart" (a fresh
 * store instance reading the same file).
 */
class FloatingWindowStoreTest {

    @Test
    void putThenGetReturnsTheSameBounds(@TempDir Path tmp) {
        FloatingWindowStore store = new FloatingWindowStore(tmp.resolve("f.json"));
        Rectangle2D bounds = new Rectangle2D(120, 80, 800, 480);
        store.putBounds("mixer", bounds);
        assertThat(store.getBounds("mixer")).contains(bounds);
    }

    @Test
    void boundsSurviveAppRestart(@TempDir Path tmp) {
        Path file = tmp.resolve("f.json");
        FloatingWindowStore first = new FloatingWindowStore(file);
        Rectangle2D bounds = new Rectangle2D(10, 20, 300, 400);
        first.putBounds("browser", bounds);

        // Simulate a JVM restart with a fresh instance of the store.
        FloatingWindowStore second = new FloatingWindowStore(file);
        assertThat(second.getBounds("browser")).contains(bounds);
    }

    @Test
    void corruptedFileIsTreatedAsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("f.json");
        java.nio.file.Files.writeString(file, "this is not valid JSON {{{ ");
        FloatingWindowStore store = new FloatingWindowStore(file);
        assertThat(store.getBounds("anything")).isEmpty();
        // Should still accept new writes after the corrupted state.
        Rectangle2D bounds = new Rectangle2D(0, 0, 100, 100);
        store.putBounds("x", bounds);
        assertThat(store.getBounds("x")).contains(bounds);
    }

    @Test
    void removeForgetsBounds(@TempDir Path tmp) {
        FloatingWindowStore store = new FloatingWindowStore(tmp.resolve("f.json"));
        store.putBounds("a", new Rectangle2D(0, 0, 100, 100));
        store.remove("a");
        assertThat(store.getBounds("a")).isEmpty();
    }

    @Test
    void snapshotIsImmutable(@TempDir Path tmp) {
        FloatingWindowStore store = new FloatingWindowStore(tmp.resolve("f.json"));
        store.putBounds("a", new Rectangle2D(0, 0, 100, 100));
        var snap = store.snapshot();
        assertThat(snap).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                snap.put("b", new Rectangle2D(0, 0, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
