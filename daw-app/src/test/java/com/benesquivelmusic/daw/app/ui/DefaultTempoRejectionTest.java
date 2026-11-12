package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 — "Tempo rejection test" (studio ruling R5; amendments A1, A3,
 * A12): an unparseable or out-of-range default tempo produces a visible
 * row-level error — the message in place of the row's description, in the
 * {@code -danger} token through the row's {@code :rejected} pseudo-class, and
 * as the editor's accessible help — keeps the typed text and the dirty
 * state, writes nothing to the model, and lets the Apply's other valid edits
 * through. OK does not close while a row is rejected; every Apply reveals the
 * rejected row and focuses its editor; typing clears the error; Cancel ▸
 * Discard still discards.
 *
 * <p>The dialog is shown (headless) so the rows' skins, the stylesheets and
 * focus are real. Its theme manager is an isolated one and its
 * {@code SettingsApplied} publish goes to a private bus.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DefaultTempoRejectionTest {

    private static final String TEMPO_ID = "project.defaultTempo";
    private static final String INTERVAL_ID = "project.autoSaveIntervalSeconds";
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);
    private static final PseudoClass REJECTED = PseudoClass.getPseudoClass("rejected");

    private final List<Preferences> nodes = new ArrayList<>();
    private final List<SettingsDialog> shown = new ArrayList<>();
    private EventBus previousBus;
    private DefaultEventBus bus;

    @BeforeEach
    void isolateTheBusAndTheThemeManager() {
        previousBus = EventBusPublisher.getDefault();
        bus = DefaultEventBus.builder().uiExecutor(Runnable::run).build();
        EventBusPublisher.setDefault(bus);
        ThemeManager.setDefaultForTest(new ThemeManager(node("tempoRejectionTheme")));
    }

    @AfterEach
    void closeAndRestore() throws Exception {
        Story307TestSupport.onFx(() -> {
            for (SettingsDialog dialog : shown) {
                dialog.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.DISCARD);
                dialog.close();
            }
            return null;
        }, 30, TimeUnit.SECONDS);
        ThemeManager.setDefaultForTest(null);
        EventBusPublisher.setDefault(previousBus);
        bus.close();
        for (Preferences node : nodes) {
            try {
                node.removeNode();
            } catch (BackingStoreException | IllegalStateException alreadyGone) {
                // a node the model never flushed
            }
        }
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "abc|settings.reject.tempo.unparseable",
            "NaN|settings.reject.tempo.unparseable",
            "1000|settings.reject.tempo.outOfRange",
            "Infinity|settings.reject.tempo.outOfRange",
            "'   '|settings.reject.tempo.blank"})
    void aRejectedTempoIsShownAtTheRowKeepsTheTextWritesNothingAndTheOtherEditsApply(String typed, String key)
            throws Exception {
        SettingsModel model = new SettingsModel(node("tempoRejection"));
        double persistedTempo = model.getDefaultTempo();
        String expected = MessageFormat.format(MESSAGES.getString(key), typed.strip());

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = show(model);
            SettingsShell shell = dialog.getShell();
            SettingRow tempo = shell.settingRow(TEMPO_ID).orElseThrow();
            tempo.setValue(typed);
            shell.settingRow(INTERVAL_ID).orElseThrow().setValue(60);
            assertThat(shell.pendingEdits()).containsOnlyKeys(TEMPO_ID, INTERVAL_ID);

            dialog.applySettings();
            settle(dialog);

            assertThat(tempo.isRejected()).isTrue();
            assertThat(tempo.getPseudoClassStates()).contains(REJECTED);
            assertThat(tempo.rejectionProperty().get()).isEqualTo(expected);
            assertThat(tempo.getValue()).as("the typed text is kept, unstripped").isEqualTo(typed);
            assertThat(shell.pendingEdits()).as("only the rejected row stays pending").containsOnlyKeys(TEMPO_ID);
            assertThat(shell.dirtyProperty().get()).isTrue();
            assertThat(description(tempo).getText()).isEqualTo(expected);
            assertThat(editor(tempo).getAccessibleHelp()).isEqualTo(expected);

            dialog.applyAndCloseWhenReady();
            return null;
        }, 30, TimeUnit.SECONDS);

        assertThat(model.getDefaultTempo()).as("nothing written to the model").isEqualTo(persistedTempo);
        assertThat(model.getAutoSaveIntervalSeconds()).as("the other valid edit of that Apply").isEqualTo(60);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = shown.getLast();
            assertThat(dialog.isShowing()).as("OK does not close while a row is rejected").isTrue();
            SettingRow tempo = dialog.getShell().settingRow(TEMPO_ID).orElseThrow();
            assertThat(tempo.isRejected()).as("re-rejected by OK's Apply").isTrue();

            tempo.setValue("130");
            assertThat(tempo.isRejected()).as("the error clears when the text changes").isFalse();
            assertThat(tempo.getPseudoClassStates()).doesNotContain(REJECTED);
            assertThat(description(tempo).getText()).isEqualTo(tempo.descriptor().description());
            assertThat(editor(tempo).getAccessibleHelp()).isNull();
            return null;
        }, 30, TimeUnit.SECONDS);
    }

    @Test
    void theMessageIsShownInTheDangerToken() throws Exception {
        Color danger = Color.web(stylesToken("-danger"));
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = show(new SettingsModel(node("tempoRejectionColour")));
            SettingRow tempo = dialog.getShell().settingRow(TEMPO_ID).orElseThrow();
            Color muted = (Color) description(tempo).getTextFill();
            tempo.setValue("abc");
            dialog.applySettings();
            settle(dialog);

            assertThat(description(tempo).getTextFill()).as("the -danger token").isEqualTo(danger);
            assertThat(muted).as("precondition: the description was not already that colour").isNotEqualTo(danger);
            return null;
        }, 30, TimeUnit.SECONDS);
    }

    @Test
    void everyApplyRevealsTheRejectedRowAndFocusesItsEditor() throws Exception {
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = show(new SettingsModel(node("tempoRejectionReveal")));
            SettingsShell shell = dialog.getShell();
            SettingRow tempo = shell.settingRow(TEMPO_ID).orElseThrow();
            tempo.setValue("abc");
            dialog.selectCategory("appearance");
            settle(dialog);
            assertThat(shell.navRail().selectedCategoryIdProperty().get()).isEqualTo("appearance");

            dialog.applySettings();
            settle(dialog);

            assertThat(shell.navRail().selectedCategoryIdProperty().get())
                    .as("the rejected row's category is selected").isEqualTo("project");
            assertThat(dialog.getDialogPane().getScene().getFocusOwner())
                    .as("the tempo editor holds focus").isSameAs(editor(tempo));
            return null;
        }, 30, TimeUnit.SECONDS);
    }

    /** Amendment A1 — a rejection must not stop Cancel ▸ Discard from discarding. */
    @Test
    void cancelThenDiscardStillDiscardsARejectedRow() throws Exception {
        SettingsModel model = new SettingsModel(node("tempoRejectionDiscard"));
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = show(model);
            SettingsShell shell = dialog.getShell();
            SettingRow tempo = shell.settingRow(TEMPO_ID).orElseThrow();
            tempo.setValue("abc");
            dialog.applySettings();
            assertThat(tempo.isRejected()).isTrue();

            AtomicInteger prompts = new AtomicInteger();
            dialog.setDirtyClosePrompt(() -> {
                prompts.incrementAndGet();
                return SettingsDialog.DirtyChoice.DISCARD;
            });
            cancelButton(dialog).fire();

            assertThat(prompts).as("the dirty-close prompt ran exactly once").hasValue(1);
            assertThat(tempo.getValue()).as("the row shows the persisted tempo again")
                    .isEqualTo(model.getDefaultTempo());
            assertThat(tempo.isRejected()).isFalse();
            assertThat(shell.dirtyProperty().get()).isFalse();
            return null;
        }, 30, TimeUnit.SECONDS);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private SettingsDialog show(SettingsModel model) {
        SettingsDialog dialog = new SettingsDialog(model);
        shown.add(dialog);
        dialog.selectCategory("project");
        dialog.show();
        settle(dialog);
        return dialog;
    }

    private static void settle(SettingsDialog dialog) {
        dialog.getDialogPane().applyCss();
        dialog.getDialogPane().layout();
    }

    private static Label description(SettingRow row) {
        Node node = row.lookup(".setting-row-description");
        assertThat(node).as("the row's description line").isInstanceOf(Label.class);
        return (Label) node;
    }

    private static TextField editor(SettingRow row) {
        Node node = row.lookup(".text-field");
        assertThat(node).as("the TEXT row's editor").isInstanceOf(TextField.class);
        return (TextField) node;
    }

    private static Button cancelButton(SettingsDialog dialog) {
        Parent footer = (Parent) dialog.getShell().getBottom();
        return footer.getChildrenUnmodifiable().stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(Button::isCancelButton)
                .findFirst().orElseThrow(() -> new AssertionError("No Cancel button in the footer"));
    }

    /** A colour token's value as {@code styles.css} declares it — read, never guessed. */
    private static String stylesToken(String token) throws IOException {
        try (InputStream styles = MainController.class.getResourceAsStream("styles.css")) {
            assertThat(styles).as("styles.css on the class path").isNotNull();
            String css = new String(styles.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(token) + ":\\s*(#[0-9A-Fa-f]{6})\\s*;")
                    .matcher(css);
            assertThat(matcher.find()).as("styles.css declares %s", token).isTrue();
            return matcher.group(1);
        }
    }

    private Preferences node(String prefix) {
        Preferences node = Preferences.userRoot().node(prefix + "_" + System.nanoTime());
        nodes.add(node);
        return node;
    }
}
