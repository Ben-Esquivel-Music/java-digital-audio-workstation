package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, Save As's modal progress ({@link SaveAsProgressDialog}; the
 * studio engineer's rulings R5, S6; Failure Surfacing book §2.4, §5.2): a
 * {@link DawgDialog} whose body is a plain pane with a determinate bar, its
 * buttons the content's own. Escape and the window's close box are a Cancel
 * while Cancel is enabled — run once — and do nothing once the copy can no
 * longer be cancelled (a temporary veto); a cancel the flow says came too
 * late keeps the dialog up. The flow's own close runs no callback.
 * {@code Continue} is never the default button. No inline style.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SaveAsProgressDialogTest {

    @Test
    void escapeAndTheCloseBoxCancelOnceWhileCancelIsEnabled() throws Exception {
        AtomicInteger cancels = new AtomicInteger();
        SaveAsProgressDialog dialog = shown(() -> {
            cancels.incrementAndGet();
            return true;
        });

        runOnFx(() -> escape(dialog));

        assertThat(onFx(dialog::isShowing)).isFalse();
        assertThat(cancels).hasValue(1);

        SaveAsProgressDialog second = shown(() -> {
            cancels.incrementAndGet();
            return true;
        });
        runOnFx(() -> windowOf(second).fireEvent(new WindowEvent(windowOf(second), WindowEvent.WINDOW_CLOSE_REQUEST)));
        assertThat(onFx(second::isShowing)).isFalse();
        assertThat(cancels).hasValue(2);
    }

    @Test
    void fromTheHeadsWriteOnNothingDismissesItButTheFlow() throws Exception {
        AtomicInteger cancels = new AtomicInteger();
        SaveAsProgressDialog dialog = shown(() -> {
            cancels.incrementAndGet();
            return true;
        });
        runOnFx(() -> dialog.showWritingHead("Writing project.daw…"));

        runOnFx(() -> escape(dialog));
        assertThat(onFx(dialog::isShowing)).as("Escape is vetoed").isTrue();
        runOnFx(() -> windowOf(dialog).fireEvent(new WindowEvent(windowOf(dialog), WindowEvent.WINDOW_CLOSE_REQUEST)));
        assertThat(onFx(dialog::isShowing)).as("the close box is vetoed").isTrue();
        runOnFx(() -> dialog.cancelButton().fire());
        runOnFx(dialog::close);

        assertThat(onFx(dialog::isShowing)).as("Cancel and a close are vetoed").isTrue();
        assertThat(cancels).hasValue(0);
        runOnFx(dialog::closeByFlow);
        assertThat(onFx(dialog::isShowing)).as("the flow's close is never vetoed").isFalse();
        assertThat(cancels).as("and runs no callback").hasValue(0);
    }

    @Test
    void aCancelTheFlowSaysCameTooLateKeepsTheDialogUpWithCancelDisabled() throws Exception {
        SaveAsProgressDialog dialog = shown(() -> false);

        runOnFx(() -> dialog.cancelButton().fire());

        assertThat(onFx(dialog::isShowing)).isTrue();
        assertThat(onFx(() -> dialog.cancelButton().isDisabled())).isTrue();
        runOnFx(dialog::closeByFlow);
    }

    @Test
    void continueIsShownWithTheNoteNeverAsTheDefaultAndRunsOnce() throws Exception {
        AtomicInteger continues = new AtomicInteger();
        SaveAsProgressDialog dialog = shown(() -> true);
        assertThat(onFx(() -> dialog.continueButton().isVisible())).isFalse();
        assertThat(onFx(dialog::noteText)).isEmpty();

        runOnFx(() -> {
            dialog.onContinue(continues::incrementAndGet);
            dialog.showOldStillClosing("the note");
        });
        runOnFx(() -> dialog.continueButton().fire());
        runOnFx(() -> dialog.continueButton().fire());

        assertThat(onFx(dialog::noteText)).isEqualTo("the note");
        assertThat(onFx(() -> dialog.continueButton().isDefaultButton())).isFalse();
        assertThat(continues).hasValue(1);
        assertThat(onFx(() -> dialog.getDialogPane().lookup(".save-as-progress-note"))).isNotNull();
        runOnFx(dialog::closeByFlow);
    }

    @Test
    void itIsADawgDialogWithNoInlineStyle() throws Exception {
        assertThat(DawgDialog.class).isAssignableFrom(SaveAsProgressDialog.class);
        String code = SourceScanSupport.stripComments(Files.readString(SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/SaveAsProgressDialog.java"),
                StandardCharsets.UTF_8));
        assertThat(code).doesNotContain("setStyle(").doesNotContain("ProgressIndicator");
    }

    private static SaveAsProgressDialog shown(java.util.function.BooleanSupplier onCancel) throws Exception {
        return onFx(() -> {
            SaveAsProgressDialog dialog = new SaveAsProgressDialog(Optional.empty());
            dialog.onCancel(onCancel);
            dialog.show();
            return dialog;
        });
    }

    private static Window windowOf(SaveAsProgressDialog dialog) {
        return dialog.getDialogPane().getScene().getWindow();
    }

    private static void escape(SaveAsProgressDialog dialog) {
        Node target = dialog.getDialogPane();
        Window window = windowOf(dialog);
        window.fireEvent(new KeyEvent(target, target, KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false,
                false, false));
    }
}
