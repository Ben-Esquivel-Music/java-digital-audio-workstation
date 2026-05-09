package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.CueBus;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import javafx.application.Platform;
import javafx.scene.control.ChoiceBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Story-135 cue-bus selector embedded in
 * {@link MetronomeSettingsDialog}. Verifies that the dialog renders a "Send
 * click to" choice box only when cue buses are provided, that "Main mix only"
 * is the default selection, and that the result carries the chosen cue-bus id.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MetronomeSettingsDialogCueBusTest {

    private <T> T runOnFxAndGet(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { ref.set(action.call()); }
            catch (Throwable t) { err.set(t); }
            finally { latch.countDown(); }
        });
        latch.await(5, TimeUnit.SECONDS);
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
        return ref.get();
    }

    @Test
    void shouldSuppressCueSelectorWhenNoCueBuses() throws Exception {
        MetronomeSettingsDialog dialog = runOnFxAndGet(() ->
                new MetronomeSettingsDialog(
                        ClickOutput.MAIN_MIX_ONLY, List.of(), null));
        assertThat(dialog.cueBusChoiceForTest()).isNull();
    }

    @Test
    void shouldRenderCueSelectorWithMainMixOnlyDefault() throws Exception {
        CueBus singer = CueBus.create("Singer", 1);
        CueBus drummer = CueBus.create("Drummer", 2);
        MetronomeSettingsDialog dialog = runOnFxAndGet(() ->
                new MetronomeSettingsDialog(
                        ClickOutput.MAIN_MIX_ONLY,
                        List.of(singer, drummer), null));

        ChoiceBox<?> choice = dialog.cueBusChoiceForTest();
        assertThat(choice).isNotNull();
        // "Main mix only" + Singer + Drummer = 3 entries.
        assertThat(choice.getItems()).hasSize(3);
        assertThat(choice.getSelectionModel().getSelectedIndex()).isEqualTo(0);
    }

    @Test
    void shouldPreselectCurrentCueBus() throws Exception {
        CueBus singer = CueBus.create("Singer", 1);
        CueBus drummer = CueBus.create("Drummer", 2);
        MetronomeSettingsDialog dialog = runOnFxAndGet(() ->
                new MetronomeSettingsDialog(
                        ClickOutput.MAIN_MIX_ONLY,
                        List.of(singer, drummer), drummer.id()));

        ChoiceBox<?> choice = dialog.cueBusChoiceForTest();
        assertThat(choice).isNotNull();
        // Index 2 = drummer (after the "Main mix only" sentinel + singer).
        assertThat(choice.getSelectionModel().getSelectedIndex()).isEqualTo(2);
    }

    @Test
    void resultRecordRejectsNullClickOutput() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new MetronomeSettingsDialog.Result(null, UUID.randomUUID()));
    }

    @Test
    void resultRecordPermitsNullCueBusId() {
        // null cueBusId means "Main mix only" — the default.
        MetronomeSettingsDialog.Result r = new MetronomeSettingsDialog.Result(
                ClickOutput.MAIN_MIX_ONLY, null);
        assertThat(r.cueBusId()).isNull();
        assertThat(r.clickOutput()).isEqualTo(ClickOutput.MAIN_MIX_ONLY);
    }
}
