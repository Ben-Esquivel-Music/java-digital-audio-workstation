package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
class DialogDismissibilityTest {

    @Test
    void repeatedInstallationReusesOneHiddenCancelAndPreservesCloseGlyph() {
        InstallationState state = runOnFxThread(() -> {
            DawgDialog<Void> dialog = new DawgDialog<>();
            Node originalCloseGlyph = dialog.getGraphic();

            Button first = DialogDismissibility.installHiddenCancel(dialog);
            Button second = DialogDismissibility.installHiddenCancel(dialog);

            return new InstallationState(
                    List.copyOf(dialog.getDialogPane().getButtonTypes()),
                    first,
                    second,
                    originalCloseGlyph,
                    dialog.getGraphic(),
                    second.isVisible(),
                    second.isManaged());
        });

        assertThat(state.originalCloseGlyph()).isNotNull();
        assertThat(state.second()).isSameAs(state.first());
        assertThat(state.buttonTypes()).containsExactly(ButtonType.CANCEL);
        assertThat(state.visible()).isFalse();
        assertThat(state.managed()).isFalse();
        assertThat(state.installedCloseGlyph()).isSameAs(state.originalCloseGlyph());
    }

    @Test
    void existingCanonicalCancelIsReusedAndHidden() {
        InstallationState state = runOnFxThread(() -> {
            DawgDialog<Void> dialog = new DawgDialog<>();
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
            Button existing = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);

            Button installed = DialogDismissibility.installHiddenCancel(dialog);

            return new InstallationState(
                    List.copyOf(dialog.getDialogPane().getButtonTypes()),
                    existing,
                    installed,
                    null,
                    dialog.getGraphic(),
                    installed.isVisible(),
                    installed.isManaged());
        });

        assertThat(state.second()).isSameAs(state.first());
        assertThat(state.buttonTypes()).containsExactly(ButtonType.CANCEL);
        assertThat(state.visible()).isFalse();
        assertThat(state.managed()).isFalse();
    }

    private record InstallationState(
            List<ButtonType> buttonTypes,
            Button first,
            Button second,
            Node originalCloseGlyph,
            Node installedCloseGlyph,
            boolean visible,
            boolean managed) {
    }
}
