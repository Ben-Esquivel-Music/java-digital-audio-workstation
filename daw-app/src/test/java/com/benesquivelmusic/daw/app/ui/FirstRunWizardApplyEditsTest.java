package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.BackupSettingsAccess;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import javafx.application.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 313 coverage for wizard apply completion, failure, and cleanup ordering. */
@ExtendWith(JavaFxToolkitExtension.class)
class FirstRunWizardApplyEditsTest {

    @Test
    void changedBackendReturnsTheCanonicalRestartBanner() {
        CompletionStage<Optional<String>> completion = runOnFxThread(() -> {
            SettingsModel model = Story307TestSupport.model("wizardChangedBackend");
            String changedBackend = model.getAudioBackend().equals("ASIO")
                    ? "Java Sound" : "ASIO";

            CompletionStage<Optional<String>> result = MainController.applyWizardEdits(
                    new SettingsDialog(model), Map.of("audio.backend", changedBackend));

            assertThat(model.getAudioBackend()).isEqualTo(changedBackend);
            return result;
        });

        assertThat(completion.toCompletableFuture())
                .as("restart-only backend changes have no live audio work")
                .isDone();
        Optional<String> notice = completion.toCompletableFuture().join();
        assertThat(notice).hasValueSatisfying(message -> assertThat(message)
                .contains("Restart required")
                .contains("Audio backend")
                .contains("relaunch"));
    }

    @Test
    void unchangedBackendDoesNotInventARestartNotice() {
        CompletionStage<Optional<String>> completion = runOnFxThread(() -> {
            SettingsModel model = Story307TestSupport.model("wizardUnchangedBackend");

            return MainController.applyWizardEdits(
                    new SettingsDialog(model),
                    Map.of("audio.backend", model.getAudioBackend()));
        });

        assertThat(completion.toCompletableFuture().join()).isEmpty();
    }

    @Test
    void nonAudioListenerFailureIsReturnedAsAnExceptionalStage() {
        CompletionStage<Optional<String>> completion = runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(
                    Story307TestSupport.model("wizardSynchronousListenerFailure"));
            dialog.setSettingsChangeListener(_ -> {
                throw new IllegalStateException("non-audio live update failed");
            });

            return MainController.applyWizardEdits(dialog, Map.of());
        });

        assertThatThrownBy(() -> completion.toCompletableFuture().join())
                .hasRootCauseMessage("non-audio live update failed");
    }

    @Test
    void hiddenSettingsDialogDetachesBackupWorkAfterApply() {
        ApplyContext context = runOnFxThread(() -> {
            SettingsModel model = Story307TestSupport.model("wizardBackupCleanup");
            SettingsDialog dialog = new SettingsDialog(model);
            int attachedKeepRecent = BackupRetentionPolicy.DEFAULT.keepRecent() + 7;
            dialog.setBackupSettingsAccess(new BackupSettingsAccess() {
                @Override
                public BackupRetentionPolicy currentPolicy() {
                    return BackupRetentionPolicy.DEFAULT
                            .withKeepRecent(attachedKeepRecent);
                }

                @Override
                public Optional<Path> projectDirectory() {
                    return Optional.empty();
                }

                @Override
                public void applyPolicy(BackupRetentionPolicy policy) {
                    throw new AssertionError("no backup edits should be applied");
                }
            });
            assertThat(dialog.getShell().settingRow("backups.keepRecent")
                    .orElseThrow().getValue()).isEqualTo(attachedKeepRecent);

            return new ApplyContext(dialog, MainController.applyWizardEdits(dialog, Map.of()));
        });
        context.completion().toCompletableFuture().join();
        runOnFxThread(() -> {
            SettingsDialog dialog = context.dialog();
            assertThat(dialog.getShell().settingRow("backups.keepRecent")
                    .orElseThrow().getValue())
                    .as("detaching backup access re-seeds the hidden dialog from defaults")
                    .isEqualTo(BackupRetentionPolicy.DEFAULT.keepRecent());
            return null;
        });
    }

    @Test
    void liveAudioCompletionWaitsForDriverAndFxListener() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockFirstConfiguration = true;
        controller.releaseFirstConfiguration = new CountDownLatch(1);
        SettingsModel model = Story307TestSupport.model("wizardAsyncAudioSuccess");
        int originalBuffer = model.getBufferSize();
        int changedBuffer = originalBuffer == 256 ? 512 : 256;
        AtomicBoolean listenerRan = new AtomicBoolean();
        AtomicBoolean listenerRanOnFx = new AtomicBoolean();

        ApplyContext context = runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.setSettingsChangeListener(_ -> {
                listenerRan.set(true);
                listenerRanOnFx.set(Platform.isFxApplicationThread());
            });
            return new ApplyContext(dialog, MainController.applyWizardEdits(
                    dialog, Map.of("audio.bufferSize", changedBuffer)));
        });

        assertThat(controller.firstConfigurationEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(context.completion().toCompletableFuture()).isNotDone();
        assertThat(model.getBufferSize())
                .as("audio persistence is deferred until driver success")
                .isEqualTo(originalBuffer);

        controller.releaseFirstConfiguration.countDown();
        assertThat(context.completion().toCompletableFuture().get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(listenerRan).isTrue();
        assertThat(listenerRanOnFx).isTrue();
        assertThat(model.getBufferSize()).isEqualTo(changedBuffer);
        assertThat(controller.applyThread.get().isVirtual()).isTrue();
    }

    @Test
    void liveAudioDriverFailureCompletesExceptionallyAndRestoresTheEdit() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.failNextConfiguration = true;
        SettingsModel model = Story307TestSupport.model("wizardAsyncAudioFailure");
        int originalBuffer = model.getBufferSize();
        int changedBuffer = originalBuffer == 256 ? 512 : 256;

        ApplyContext context = runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            return new ApplyContext(dialog, MainController.applyWizardEdits(
                    dialog, Map.of("audio.bufferSize", changedBuffer)));
        });

        assertThatThrownBy(() -> context.completion().toCompletableFuture()
                .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Could not apply audio configuration")
                .hasRootCauseMessage("configuration failed");
        assertThat(model.getBufferSize()).isEqualTo(originalBuffer);
        runOnFxThread(() -> {
            assertThat(context.dialog().getShell().isOperationNoticeVisible()).isTrue();
            assertThat(context.dialog().getShell().operationNoticeText())
                    .contains("Could not apply audio configuration")
                    .contains("configuration failed");
            return null;
        });
    }

    @Test
    void listenerFailureIsPartOfTheApplyCompletion() throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        SettingsModel model = Story307TestSupport.model("wizardAsyncListenerFailure");
        int changedBuffer = model.getBufferSize() == 256 ? 512 : 256;

        ApplyContext context = runOnFxThread(() -> {
            SettingsDialog dialog = new SettingsDialog(model);
            dialog.setAudioEngineController(controller);
            dialog.setSettingsChangeListener(_ -> {
                assertThat(Platform.isFxApplicationThread()).isTrue();
                throw new IllegalStateException("live listener rejected the update");
            });
            return new ApplyContext(dialog, MainController.applyWizardEdits(
                    dialog, Map.of("audio.bufferSize", changedBuffer)));
        });

        assertThatThrownBy(() -> context.completion().toCompletableFuture()
                .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Settings were saved, but a live update failed")
                .hasRootCauseMessage("live listener rejected the update");
        assertThat(model.getBufferSize())
                .as("the listener runs after the successful driver commit")
                .isEqualTo(changedBuffer);
    }

    @Test
    void hiddenDialogStaysAttachedUntilFailureRollbackCapturesLiveEndpoint()
            throws Exception {
        Story307TestSupport.StubController controller =
                new Story307TestSupport.StubController();
        controller.blockTestTone = true;
        controller.releaseTestTone = new CountDownLatch(1);
        controller.failNextConfiguration = true;
        SettingsModel model = Story307TestSupport.model("wizardDeferredAudioCleanup");
        model.setAudioBackend(controller.activeBackend);
        model.setAudioInputDevice("");
        model.setAudioOutputDevice("");

        SettingsDialog dialog = runOnFxThread(() -> {
            SettingsDialog created = new SettingsDialog(model);
            created.setAudioEngineController(controller);
            created.getTestToneButton().fire();
            return created;
        });
        assertThat(controller.testToneEntered.await(5, TimeUnit.SECONDS)).isTrue();

        ApplyContext context = runOnFxThread(() -> {
            dialog.getShell().replaceChoiceOptions(
                    "audio.outputDevice", List.of("", "Rejected output"), "");
            return new ApplyContext(
                    dialog,
                    MainController.applyWizardEdits(
                            dialog, Map.of("audio.outputDevice", "Rejected output")));
        });
        assertThat(context.completion().toCompletableFuture()).isNotDone();

        controller.releaseTestTone.countDown();
        assertThatThrownBy(() -> context.completion().toCompletableFuture()
                .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("configuration failed");
        assertThat(controller.requests).hasSize(2);
        assertThat(controller.requests.getFirst().outputDeviceName())
                .isEqualTo("Rejected output");
        assertThat(controller.requests.getLast().outputDeviceName())
                .as("rollback uses the live endpoint captured before hidden-dialog cleanup")
                .isEmpty();
    }

    private record ApplyContext(
            SettingsDialog dialog,
            CompletionStage<Optional<String>> completion) {}
}
