package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.ApplyClass;
import com.benesquivelmusic.daw.app.ui.settings.ControlKind;
import com.benesquivelmusic.daw.app.ui.settings.Scope;
import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;

import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.layout.StackPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards localization and rendering of unavailable CHOICE options. */
@ExtendWith(JavaFxToolkitExtension.class)
class SettingRowUnavailableChoiceTooltipTest {

    private static final String MESSAGE_KEY = "settings.choice.unavailable";

    @Test
    void unavailableChoiceTooltipComesFromMessagesRatherThanAJavaLiteral() throws Exception {
        Path source = SourceScanSupport.locateDawAppModule().resolve(
                "src/main/java/com/benesquivelmusic/daw/app/ui/settings/SettingRowSkin.java");
        String code = SourceScanSupport.stripComments(
                Files.readString(source, StandardCharsets.UTF_8));

        assertThat(code)
                .contains("msg(\"" + MESSAGE_KEY + "\")")
                .doesNotContain("Not supported by the current audio device");
        assertThat(ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT)
                .getString(MESSAGE_KEY))
                .isEqualTo("Not supported by the current audio device");
    }

    @Test
    void unavailableChoiceCellRendersTheLocalizedTooltip() throws Exception {
        String expected = ResourceBundle.getBundle(
                "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT)
                .getString(MESSAGE_KEY);

        Story307TestSupport.onFx(() -> {
            var descriptor = new SettingDescriptor<>(
                    "test.sampleRate", "Sample rate", "Test sample-rate choice",
                    List.of(), Scope.AUDIO_DEVICE, ApplyClass.ENGINE_RECONFIGURE,
                    ControlKind.CHOICE, 48_000.0, value -> value != null && value > 0);
            var row = new SettingRow(
                    descriptor, descriptor.defaultValue(), SettingRow.ControlHints.none());
            row.replaceChoiceOptions(
                    List.of(44_100.0, 48_000.0), 48_000.0, List.of(44_100.0));
            var root = new StackPane(row);
            var stage = new Stage();
            stage.setScene(new Scene(root, 800, 240));
            stage.show();
            root.applyCss();
            root.layout();

            @SuppressWarnings("unchecked")
            ComboBox<Object> combo = (ComboBox<Object>) row.lookup(".combo-box");
            assertThat(combo).isNotNull();
            try {
                combo.show();
                Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(PopupWindow.class::isInstance)
                        .map(Window::getScene)
                        .filter(java.util.Objects::nonNull)
                        .map(Scene::getRoot)
                        .forEach(popupRoot -> {
                            popupRoot.applyCss();
                            popupRoot.layout();
                        });

                var unavailableCells = Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(PopupWindow.class::isInstance)
                        .map(Window::getScene)
                        .filter(java.util.Objects::nonNull)
                        .map(Scene::getRoot)
                        .flatMap(popupRoot -> popupRoot.lookupAll(".list-cell").stream())
                        .filter(ListCell.class::isInstance)
                        .map(node -> (ListCell<?>) node)
                        .filter(ListCell::isDisable)
                        .filter(cell -> cell.getTooltip() != null)
                        .toList();
                assertThat(unavailableCells)
                        .extracting(cell -> cell.getTooltip().getText())
                        .contains(expected);
            } finally {
                combo.hide();
                stage.hide();
            }
            return null;
        });
    }
}
