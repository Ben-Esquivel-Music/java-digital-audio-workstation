package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 306 — per-setting reset, the first §5.7 reset tier (Settings View
 * Design Book §5.7 tier 1, §5.4). A {@link SettingRow} edited away from its
 * descriptor {@code default} shows the {@code .setting-row-reset} ↺ marker;
 * firing it restores the default and removes the marker; an untouched row
 * shows no marker (invisible AND unmanaged, so it reserves no space).
 *
 * <p>The rows under test render <em>real</em> story-305 catalogue
 * descriptors ({@code audio.sampleRate},
 * {@code audio.applyLatencyCompensation}) and are exercised off the dialog —
 * the row is a self-contained {@code Control}, so the reset contract needs no
 * shell. The edit is driven through the row's inner editor (the CHOICE
 * {@code ComboBox}), exercising the control→model leg of the two-way sync,
 * and the post-reset editor state proves the model→control push
 * ({@code feedback_bind_two_way_control_property} — listener + setter, never
 * {@code bind()}).</p>
 *
 * <p>Headless FX per {@code feedback_javafx_headless_test_pitfalls}: skin
 * lookups need a {@code Scene} plus {@code applyCss()}/{@code layout()};
 * FX-thread assertions are captured and rethrown by {@code runOnFxThread}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PerSettingResetTest {

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

    /** Resolves a real story-305 descriptor by id — never a hand-built stand-in. */
    private static SettingDescriptor<?> descriptor(String id) {
        return SettingsCatalogue.create().byId(id).orElseThrow(
                () -> new AssertionError("catalogue has no descriptor " + id));
    }

    /**
     * Realizes the row's skin (Scene + applyCss + layout — skins do not
     * attach headless without it) and returns the ↺ reset button.
     */
    private static Button showAndFindReset(SettingRow row) {
        StackPane root = new StackPane(row);
        new Scene(root, 800, 240);
        root.applyCss();
        root.layout();
        Button reset = (Button) row.lookup(".setting-row-reset");
        assertThat(reset)
                .as("the row skin exposes the .setting-row-reset button")
                .isNotNull();
        return reset;
    }

    @Test
    void editedRowShowsMarkerAndResetRestoresTheDefault() throws Exception {
        runOnFxThread(() -> {
            SettingDescriptor<?> sampleRate = descriptor("audio.sampleRate");
            Object defaultValue = sampleRate.defaultValue();
            assertThat(defaultValue)
                    .as("audio.sampleRate carries a canonical default")
                    .isNotNull();
            SettingRow row = new SettingRow(
                    sampleRate, defaultValue, SettingRow.ControlHints.none());
            Button reset = showAndFindReset(row);

            // At the default: no marker, no reserved space (§5.7 tier 1).
            assertThat(row.modifiedProperty().get()).isFalse();
            assertThat(reset.isVisible()).isFalse();
            assertThat(reset.isManaged()).isFalse();

            // Edit through the inner editor (control→model leg), never the
            // row property directly — the user path.
            @SuppressWarnings("unchecked")
            ComboBox<Object> combo = (ComboBox<Object>) row.lookup(".combo-box");
            assertThat(combo)
                    .as("the CHOICE row's editor is a ComboBox")
                    .isNotNull();
            // Any rate different from the canonical default (never assume
            // which one the default is).
            Object edited = Objects.equals(defaultValue, 96000.0) ? 44100.0 : 96000.0;
            combo.setValue(edited);

            assertThat(row.getValue()).isEqualTo(edited);
            assertThat(row.modifiedProperty().get()).isTrue();
            assertThat(reset.isVisible()).isTrue();
            assertThat(reset.isManaged()).isTrue();

            reset.fire();

            // Reset restores the descriptor default and removes the marker …
            assertThat(row.getValue()).isEqualTo(defaultValue);
            assertThat(row.modifiedProperty().get()).isFalse();
            assertThat(reset.isVisible()).isFalse();
            assertThat(reset.isManaged()).isFalse();
            // … and the editor follows (model→control push).
            assertThat(combo.getValue()).isEqualTo(defaultValue);
            return null;
        });
    }

    @Test
    void untouchedRowShowsNoMarker() throws Exception {
        runOnFxThread(() -> {
            SettingDescriptor<?> latency = descriptor("audio.applyLatencyCompensation");
            SettingRow row = new SettingRow(
                    latency, latency.defaultValue(), SettingRow.ControlHints.none());
            Button reset = showAndFindReset(row);

            assertThat(row.modifiedProperty().get())
                    .as("a row seeded at its default is not modified")
                    .isFalse();
            assertThat(reset.isVisible()).isFalse();
            assertThat(reset.isManaged()).isFalse();
            return null;
        });
    }
}
