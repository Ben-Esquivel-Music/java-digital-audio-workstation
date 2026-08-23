package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 316 review — the "Active backend" utility readout must never show
 * the untranslated contract literal.
 *
 * <p>{@link AudioEngineController#getActiveBackendName()} answers
 * {@link AudioEngineController#BACKEND_NONE} ("None") whenever no stream is
 * open, which is the ordinary state of a stopped transport. Piping that
 * straight into a localized caption made the panel read like a missing
 * device or an error, in English, on every locale.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class SettingsDialogActiveBackendLabelTest {

    private static final String KEY = "audio.utility.activeBackend.none";

    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    @Test
    void stoppedTransportRendersALocalizedPhraseInsteadOfTheContractLiteral() {
        String rendered = SettingsDialog.displayActiveBackend(
                AudioEngineController.BACKEND_NONE);

        assertThat(rendered)
                .as("the contract literal must not reach a user-visible label")
                .isNotEqualTo(AudioEngineController.BACKEND_NONE);
        assertThat(rendered)
                .as("the readout must come from the bundle")
                .isEqualTo(MESSAGES.getString(KEY));
        assertThat(rendered)
                .as("SettingsDialog.msg() returns the KEY itself when the bundle "
                        + "lacks it, so a missing key would silently show '" + KEY + "'")
                .isNotEqualTo(KEY);
    }

    @Test
    void aStreamingBackendNameIsShownVerbatim() {
        assertThat(SettingsDialog.displayActiveBackend("ASIO"))
                .as("a real backend name is a device fact, not a translatable phrase")
                .isEqualTo("ASIO");
    }
}
