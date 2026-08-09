package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.AudioEngineController;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import javafx.scene.Node;
import javafx.scene.control.Button;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/** Story 313: enumeration failures are visible and retryable in step one. */
@ExtendWith(JavaFxToolkitExtension.class)
class FirstRunWizardDeviceEnumerationFailureTest {

    @Test
    void enumerationFailureShowsErrorAndRetryRecoversTheChoices() throws Exception {
        FailsOnceController controller = new FailsOnceController();
        FirstRunWizard wizard = runOnFxThread(() -> new FirstRunWizard(
                SettingsCatalogue.create(), newModel(), controller));

        await(Duration.ofSeconds(5), () -> runOnFxThread(
                wizard::isEnumerationErrorVisible));

        String errorText = runOnFxThread(wizard::enumerationErrorText);
        Node retry = runOnFxThread(() -> wizard.lookup(
                "#first-run-wizard-enumeration-retry"));
        assertThat(errorText)
                .contains("Audio devices could not be discovered")
                .contains("USB driver unavailable");
        assertThat(retry).isInstanceOf(Button.class);
        assertThat(retry.isVisible()).isTrue();
        assertThat(retry.isManaged()).isTrue();

        runOnFxThread(() -> {
            ((Button) retry).fire();
            return null;
        });

        await(Duration.ofSeconds(5), () -> runOnFxThread(() ->
                !wizard.isEnumerationErrorVisible()
                        && wizard.currentStepRows().getFirst()
                                .choiceOptionsProperty().get()
                                .contains("Recovered Backend")));

        assertThat(controller.attempts.get()).isEqualTo(2);
        runOnFxThread(() -> {
            wizard.close();
            return null;
        });
    }

    private static void await(Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean())
                .as("condition became true within " + timeout)
                .isTrue();
    }

    private static SettingsModel newModel() {
        return new SettingsModel(Preferences.userRoot()
                .node("firstRunWizardEnumerationFailureTest_" + System.nanoTime()));
    }

    private static final class FailsOnceController implements AudioEngineController {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String getActiveBackendName() {
            return "Recovered Backend";
        }

        @Override
        public List<String> getAvailableBackendNames() {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("USB driver unavailable");
            }
            return List.of("Recovered Backend");
        }

        @Override
        public List<AudioDeviceInfo> listDevices() {
            return List.of();
        }

        @Override
        public List<AudioDeviceInfo> listDevices(String backendName) {
            return List.of();
        }

        @Override
        public double getCpuLoadPercent() {
            return 0;
        }

        @Override
        public void applyConfiguration(Request request) {
            // not used by enumeration
        }

        @Override
        public void playTestTone(String outputDeviceName) {
            // not used by enumeration
        }
    }
}
