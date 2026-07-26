package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards localization and presentation of audio-capability fallback notices. */
@ExtendWith(JavaFxToolkitExtension.class)
class AudioCapabilityFallbackLocalizationTest {

    private static final String MESSAGE_KEY = "settings.audio.capabilityFallback";
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    @Test
    void fallbackUsesOneLocalizedFormattedMessageForTheLogAndVisibleNotice()
            throws Exception {
        var loggedMessage = new AtomicReference<String>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().startsWith("Persisted Sample Rate")) {
                    loggedMessage.compareAndSet(null, record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(SettingsDialog.class.getName());
        logger.addHandler(capture);
        var dialogRef = new AtomicReference<SettingsDialog>();
        try {
            var controller = new Story307TestSupport.StubController();
            var model = Story307TestSupport.model("audioCapabilityFallbackLocalization");
            model.setSampleRate(12_345.0);
            String expected = "Persisted Sample Rate (Hz) 12,345 is unavailable for "
                    + "the selected device; falling back to 48,000.";

            Story307TestSupport.onFx(() -> {
                var dialog = new SettingsDialog(model);
                dialogRef.set(dialog);
                dialog.setAudioEngineController(controller);
                return null;
            });
            assertThat(Story307TestSupport.awaitFxValue(
                    () -> dialogRef.get().getShell().operationNoticeText(),
                    expected, 5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(loggedMessage).hasValue(expected);
        } finally {
            if (dialogRef.get() != null) {
                Story307TestSupport.onFx(() -> {
                    dialogRef.get().setAudioEngineController(null);
                    return null;
                });
            }
            logger.removeHandler(capture);
        }
    }

    @Test
    void fallbackSentenceAndEveryInsertedSettingLabelComeFromMessages()
            throws Exception {
        Path source = SourceScanSupport.locateDawAppModule().resolve(
                "src/main/java/com/benesquivelmusic/daw/app/ui/SettingsDialog.java");
        String code = SourceScanSupport.stripComments(
                Files.readString(source, StandardCharsets.UTF_8));

        assertThat(code)
                .contains("formatMessage(")
                .contains("\"" + MESSAGE_KEY + "\"")
                .contains("new MessageFormat(msg(key), MESSAGES.getLocale())")
                .contains("msg(\"audio.inputDevice.label\")")
                .contains("msg(\"audio.outputDevice.label\")")
                .contains("msg(\"audio.sampleRate.label\")")
                .contains("msg(\"audio.bufferSize.label\")")
                .doesNotContain("is unavailable for the selected device; falling back to");
        assertThat(MESSAGES.getString(MESSAGE_KEY))
                .isEqualTo("Persisted {0} {1} is unavailable for the selected device; "
                        + "falling back to {2}.");
    }
}
