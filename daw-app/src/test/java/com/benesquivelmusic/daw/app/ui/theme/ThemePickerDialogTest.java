package com.benesquivelmusic.daw.app.ui.theme;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-tests {@link ThemePickerDialog} on the JavaFX Application Thread.
 *
 * <p>Requires a display (Xvfb is fine) — see {@link JavaFxToolkitExtension}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ThemePickerDialogTest {

    @Test
    void dialogConstructsAndSelectsActiveTheme(@TempDir Path tmp) throws Exception {
        ThemeRegistry registry = new ThemeRegistry(tmp.resolve("user-themes"));
        AtomicReference<ThemePickerDialog> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new ThemePickerDialog(registry, "high-contrast"));
            } catch (Exception e) {
                err.set(e);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            throw err.get();
        }
        ThemePickerDialog dialog = ref.get();
        assertThat(dialog).isNotNull();

        // Selection should match the requested active id.
        CountDownLatch readLatch = new CountDownLatch(1);
        AtomicReference<String> selectedId = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                Theme t = dialog.selectedThemeProperty().getValue();
                selectedId.set(t == null ? null : t.id());
            } finally {
                dialog.close();
                readLatch.countDown();
            }
        });
        assertThat(readLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(selectedId.get()).isEqualTo("high-contrast");
    }
}
