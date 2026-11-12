package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.BackupSettingsAccess;
import com.benesquivelmusic.daw.app.ui.settings.SettingRow;
import com.benesquivelmusic.daw.app.ui.settings.SettingsNavRail;
import com.benesquivelmusic.daw.app.ui.settings.SettingsShell;
import com.benesquivelmusic.daw.core.persistence.AutoSaveConfig;
import com.benesquivelmusic.daw.core.persistence.CheckpointFileName;
import com.benesquivelmusic.daw.core.persistence.CheckpointManager;
import com.benesquivelmusic.daw.core.persistence.CheckpointOrigin;
import com.benesquivelmusic.daw.core.persistence.backup.BackupRetentionPolicyStore;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 (studio-engineer ruling on an unreadable retention file,
 * binding) — a missing {@code ~/.daw/backup-retention.json} still means the
 * default policy, but one that exists and cannot be read, parsed or validated
 * never becomes a policy the user did not choose: a checkpoint write deletes
 * nothing until a policy is read or applied in Settings, the Backups rows show
 * the default, and the user sees the WARNING, verbatim. An Apply from Settings
 * then delivers and saves as usual, replacing the file, and the next write
 * prunes by it.
 *
 * <p>Each case runs startup's delivery seam,
 * {@link MainController#deliverStoredRetention} — which {@code initialize()}
 * calls, pinned by source below: it builds a live audio engine, so no test
 * runs it — over a real {@link BackupRetentionController}, policy store and
 * {@link CheckpointManager}, on a project folder whose checkpoints beyond the
 * default's ten recent ones are older than its 30-day milestones reach (so the
 * default would delete them), and the production Settings surface over the
 * controller's access seam.</p>
 *
 * <p>"Apply it again" (studio ruling-3 Q1, binding): while the hold lasts, the
 * six Backups rows become pending once the Backups pane is displayed, by any
 * route, so a plain Apply or OK delivers and saves what they show — exactly the
 * default if unedited — ending the hold and replacing the bad file. A dialog
 * that never displayed Backups changes no retention; Discard and Cancel deliver
 * nothing; a confirmed Restore All Defaults arms them too; and without a hold
 * nothing is armed.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class UnreadableRetentionPolicyTest {

    private static final String NOT_READ = "Checkpoint retention could not be read, so no checkpoints are deleted."
            + " Apply it again in Settings ▸ Backups.";
    /** Studio ruling-3 Q1.4 — the footer notice at arming, copied from the ruling. */
    private static final String HOLD_NOTICE = "Checkpoint retention could not be read, so no checkpoints are deleted."
            + " Apply or OK uses the Backups values shown.";
    private static final String UNRELATED_ROW = "transport.returnToStartOnStop";
    private static final List<String> BACKUP_ROWS = List.of("backups.keepRecent", "backups.keepHourly",
            "backups.keepDaily", "backups.keepWeekly", "backups.maxAgeDays", "backups.maxDiskGiB");
    private static final long WAIT_SECONDS = 10;
    /**
     * Guard on one FX turn of these tests, none of which waits on the FX
     * thread; each method's guards together stay under the suite's 60 s.
     */
    private static final long FX_SECONDS = 10;
    /** Checkpoints of an earlier launch, 100 days old: beyond ten recent ones, the default keeps none. */
    private static final int OLD_CHECKPOINTS = 12;

    private final List<Preferences> nodes = new ArrayList<>();
    private final List<CheckpointManager> managers = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (CheckpointManager manager : managers) {
            manager.stop().get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        for (Preferences node : nodes) {
            node.removeNode();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filesThatCannotBeRead")
    void anUnreadableStoredPolicyDeletesNothingShowsTheDefaultAndWarnsUntilAnApply(String what, byte[] content,
                                                                                  @TempDir Path tempDir)
            throws Exception {
        Path policyFile = Files.write(tempDir.resolve("backup-retention.json"), content);
        Path project = Files.createDirectories(tempDir.resolve("Song"));
        List<String> old = plantOldCheckpoints(project);
        CheckpointManager checkpoints = manager();
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(policyFile), checkpoints, Runnable::run);
        List<String> warnings = new ArrayList<>();

        MainController.deliverStoredRetention(controller, warnings::add);

        assertThat(warnings).as("%s: the user is told, verbatim", what).containsExactly(NOT_READ);
        assertThat(controller.currentPolicy()).as("the rows' policy").isEqualTo(BackupRetentionPolicy.DEFAULT);
        BackupRetentionController defaultDelivered = defaultDelivered(tempDir);

        checkpoints.start(project).get(WAIT_SECONDS, TimeUnit.SECONDS);
        String written = write(checkpoints, 1);
        List<String> everything = new ArrayList<>(old);
        everything.add(written);
        assertThat(checkpointNames(project)).as("%s: a checkpoint write deletes nothing", what)
                .containsExactlyInAnyOrderElementsOf(everything);

        BackupRetentionPolicy edited = new BackupRetentionPolicy(2, 0, 0, 0, Duration.ZERO, 0L);
        AtomicInteger saveFailures = new AtomicInteger();
        // One FX turn for the rows and the Apply keeps the method's waits under its 60 s timeout.
        Story307TestSupport.onFx(() -> {
            assertThat(rowValues(controller)).as("the Backups rows show the default")
                    .isEqualTo(rowValues(defaultDelivered));
            SettingsDialog dialog = new SettingsDialog(model());
            dialog.setBackupSettingsAccess(controller.settingsAccess(Optional::empty, saveFailures::incrementAndGet));
            Map<String, Object> edits = Map.of(
                    "backups.keepRecent", 2, "backups.keepHourly", 0, "backups.keepDaily", 0,
                    "backups.keepWeekly", 0, "backups.maxAgeDays", 0.0, "backups.maxDiskGiB", 0.0);
            edits.forEach((id, value) -> dialog.getShell().settingRow(id).orElseThrow().setValue(value));
            dialog.applySettings();
            dialog.setBackupSettingsAccess(null);
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);
        assertThat(controller.awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS))).isTrue();
        assertThat(saveFailures).as("the Apply's save succeeded").hasValue(0);

        assertThat(checkpoints.getRetentionPolicy()).as("an Apply delivers as usual").contains(edited);
        assertThat(controller.currentPolicy()).isEqualTo(edited);
        assertThat(new BackupRetentionPolicyStore(policyFile).loadGlobal()).as("and replaces the file")
                .contains(edited);
        String next = write(checkpoints, 2);
        assertThat(checkpointNames(project)).as("the next write prunes by the applied policy")
                .containsExactlyInAnyOrder(written, next);
    }

    static Stream<Arguments> filesThatCannotBeRead() {
        String complete = BackupRetentionPolicyStore.toJson(BackupRetentionPolicy.DEFAULT);
        return Stream.of(
                Arguments.of("a zero-byte file", new byte[0]),
                Arguments.of("garbage", bytes("this is not a retention policy")),
                Arguments.of("a value the policy refuses", bytes(complete.replace("\"keepRecent\": 10",
                        "\"keepRecent\": -1"))),
                Arguments.of("a key missing", bytes(complete.replaceAll("(?m)^.*\"keepWeekly\".*$", ""))),
                Arguments.of("a value that does not parse", bytes(complete.replace("\"keepDaily\": 14",
                        "\"keepDaily\": fourteen"))),
                Arguments.of("bytes that are no text", new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x7B}),
                // Fix-review SHOULD-1: six keys that all parse, but "maxBytes": 2147 would delete almost every checkpoint.
                Arguments.of("a file cut inside its last value",
                        bytes(complete.substring(0, complete.indexOf("2147483648") + 4))));
    }

    @Test
    void aMissingStoredPolicyIsTheDefaultAndPrunesByItWithNoWarning(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("Song"));
        List<String> old = plantOldCheckpoints(project);
        CheckpointManager checkpoints = manager();
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(tempDir.resolve("backup-retention.json")), checkpoints, Runnable::run);
        List<String> warnings = new ArrayList<>();

        MainController.deliverStoredRetention(controller, warnings::add);

        assertThat(warnings).isEmpty();
        assertThat(checkpoints.getRetentionPolicy()).contains(BackupRetentionPolicy.DEFAULT);
        assertThat(controller.currentPolicy()).isEqualTo(BackupRetentionPolicy.DEFAULT);
        checkpoints.start(project).get(WAIT_SECONDS, TimeUnit.SECONDS);
        String written = write(checkpoints, 1);
        List<String> recent = new ArrayList<>(old.subList(OLD_CHECKPOINTS - 9, OLD_CHECKPOINTS));
        recent.add(written);
        assertThat(checkpointNames(project)).as("ten recent kept; the older ones are past the 30-day cap")
                .containsExactlyInAnyOrderElementsOf(recent);
    }

    /**
     * {@code initialize()} — which no test runs — delivers through the seam
     * and shows its message as a WARNING once the notification bar exists.
     */
    @Test
    void initializeDeliversThroughTheSeamAndShowsItsWarning() throws IOException {
        Path mainController = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        String raw = Files.readString(mainController, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: MainController was read").isGreaterThan(10_000);
        String code = SourceScanSupport.stripComments(raw).replaceAll("\\s+", " ");
        String initialize = methodBody(code, "private void initialize()");

        assertThat(initialize).contains("deliverStoredRetention(backupRetentionController, message -> postFx(() -> {"
                + " if (notificationBar != null) { notificationBar.show(NotificationLevel.WARNING, message); } }));")
                .doesNotContain("backupRetentionController.deliverStored()");
        assertThat(MainController.RETENTION_NOT_READ).isEqualTo(NOT_READ);
    }

    // ── Studio ruling-3 Q1: "Apply it again" works while the hold lasts ─────

    /** How the armed dialog commits. */
    enum Commit { APPLY, OK }

    /**
     * T1 — displaying Backups during the hold arms its six rows with the
     * values they show (dirty, the rail {@code •}, Apply enabled, the notice
     * verbatim, the ↺ markers untouched); a plain Apply or OK delivers and
     * saves exactly the default, ends the hold, replaces the bad file and
     * re-seeds the rows clean, and the next write keeps exactly ten recent.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Commit.class)
    void showingBackupsDuringTheHoldArmsItsRowsAndAPlainCommitDeliversAndSavesTheDefault(Commit commit,
                                                                                         @TempDir Path tempDir)
            throws Exception {
        Hold hold = hold(tempDir);
        AtomicInteger saveFailures = new AtomicInteger();
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialog(hold, saveFailures::incrementAndGet);
            try {
                SettingsShell shell = dialog.getShell();
                assertThat(shell.dirtyProperty().get()).as("nothing is armed when the dialog opens").isFalse();

                dialog.selectCategory("backups");

                assertArmed(dialog, "the deep link");
                Map<String, Object> shown = shownValues(shell);
                (commit == Commit.APPLY ? applyButton(dialog) : okButton(dialog)).fire();

                assertThat(shell.dirtyProperty().get()).as("%s: the rows re-seed clean", commit).isFalse();
                assertThat(shell.pendingEdits()).isEmpty();
                assertThat(shownValues(shell)).as("from the delivered policy").isEqualTo(shown);
                assertThat(shell.isOperationNoticeVisible()).as("the notice clears").isFalse();
                assertThat(shell.operationNoticeText()).isEmpty();
            } finally {
                dialog.setBackupSettingsAccess(null);
            }
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);
        assertThat(hold.controller().awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                .as("the save ended").isTrue();
        assertThat(saveFailures).as("the save succeeded").hasValue(0);

        assertThat(hold.checkpoints().getRetentionPolicy()).as("%s delivers what the rows show", commit)
                .contains(BackupRetentionPolicy.DEFAULT);
        assertThat(hold.access().storedPolicyUnreadable()).as("the hold ends").isFalse();
        assertThat(new BackupRetentionPolicyStore(hold.policyFile()).loadGlobal()).as("the bad file is replaced")
                .contains(BackupRetentionPolicy.DEFAULT);
        String written = write(hold.checkpoints(), 1);
        List<String> tenRecent = new ArrayList<>(hold.old().subList(OLD_CHECKPOINTS - 9, OLD_CHECKPOINTS));
        tenRecent.add(written);
        assertThat(checkpointNames(hold.project())).as("the next write keeps exactly the ten recent")
                .containsExactlyInAnyOrderElementsOf(tenRecent);
    }

    /** The ways the Backups pane comes to be displayed (studio ruling-3 Q1.2). */
    enum Route { DEEP_LINK, RAIL, REVEAL_SETTING, LEAVING_SEARCH, ATTACHED_WHILE_DISPLAYED }

    /** Every way the Backups pane comes to be displayed during the hold arms its rows. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Route.class)
    void everyRouteThatDisplaysTheBackupsPaneDuringTheHoldArmsItsRows(Route route, @TempDir Path tempDir)
            throws Exception {
        Hold hold = hold(tempDir);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model());
            SettingsShell shell = dialog.getShell();
            try {
                if (route != Route.ATTACHED_WHILE_DISPLAYED) {
                    dialog.setBackupSettingsAccess(hold.access());
                }
                assertThat(shell.dirtyProperty().get()).as("nothing is armed when the dialog opens").isFalse();
                switch (route) {
                    case DEEP_LINK -> dialog.selectCategory("backups");
                    case RAIL -> shell.navRail().setSelectedCategoryId("backups");
                    case REVEAL_SETTING -> shell.revealSetting("backups.keepDaily");
                    case LEAVING_SEARCH -> {
                        TextField search = (TextField) shell.lookup(".settings-search-field");
                        search.setText("keep");
                        shell.navRail().setSelectedCategoryId("backups");
                        assertThat(shell.pendingEdits()).as("search results are not the Backups pane").isEmpty();
                        search.clear();
                    }
                    case ATTACHED_WHILE_DISPLAYED -> {
                        dialog.selectCategory("backups");
                        assertThat(shell.pendingEdits()).as("no access, no hold to see").isEmpty();
                        dialog.setBackupSettingsAccess(hold.access());
                    }
                }
                assertArmed(dialog, route.name());
            } finally {
                dialog.setBackupSettingsAccess(null);
            }
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * T2 — the safety bound: in a dialog that never displayed Backups, an
     * Apply and then an OK of another row leave retention alone: the hold
     * continues, the file is untouched and a checkpoint write deletes nothing.
     */
    @Test
    void anApplyThenAnOkOfAnotherRowInADialogThatNeverShowedBackupsChangesNoRetention(@TempDir Path tempDir)
            throws Exception {
        Hold hold = hold(tempDir);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialog(hold, () -> { });
            try {
                SettingsShell shell = dialog.getShell();
                assertThat(shell.displayedCategoryProperty().get()).as("precondition").isNotEqualTo("backups");
                SettingRow unrelated = shell.settingRow(UNRELATED_ROW).orElseThrow();
                boolean initial = dialog.getModel().isReturnToStartOnStop();

                unrelated.setValue(!initial);
                assertThat(shell.pendingEdits()).as("only the edited row is pending").containsOnlyKeys(UNRELATED_ROW);
                applyButton(dialog).fire();
                assertThat(dialog.getModel().isReturnToStartOnStop()).as("the Apply took effect").isEqualTo(!initial);

                unrelated.setValue(initial);
                assertThat(shell.pendingEdits()).containsOnlyKeys(UNRELATED_ROW);
                okButton(dialog).fire();
                assertThat(dialog.getModel().isReturnToStartOnStop()).as("the OK took effect").isEqualTo(initial);
                assertThat(shell.isOperationNoticeVisible()).as("no notice: nothing was armed").isFalse();
            } finally {
                dialog.setBackupSettingsAccess(null);
            }
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);

        assertHoldContinues(hold);
        String written = write(hold.checkpoints(), 1);
        List<String> everything = new ArrayList<>(hold.old());
        everything.add(written);
        assertThat(checkpointNames(hold.project())).as("a checkpoint write deletes nothing")
                .containsExactlyInAnyOrderElementsOf(everything);
    }

    /** T3 — an armed row edited away and back stays pending, and the Apply delivers the default. */
    @Test
    void anArmedRowEditedAwayAndBackStaysPendingAndTheApplyDeliversTheDefault(@TempDir Path tempDir)
            throws Exception {
        Hold hold = hold(tempDir);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialog(hold, () -> { });
            try {
                SettingsShell shell = dialog.getShell();
                dialog.selectCategory("backups");
                SettingRow keepRecent = shell.settingRow("backups.keepRecent").orElseThrow();

                keepRecent.setValue(11);
                assertThat(shell.pendingEdits()).containsEntry("backups.keepRecent", 11);
                keepRecent.setValue(10);
                assertThat(shell.pendingEdits()).as("still pending at the value it shows")
                        .containsEntry("backups.keepRecent", 10)
                        .containsOnlyKeys(BACKUP_ROWS);
                assertThat(shell.dirtyProperty().get()).isTrue();
                applyButton(dialog).fire();
            } finally {
                dialog.setBackupSettingsAccess(null);
            }
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);
        assertThat(hold.controller().awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                .isTrue();

        assertThat(hold.checkpoints().getRetentionPolicy()).contains(BackupRetentionPolicy.DEFAULT);
        assertThat(new BackupRetentionPolicyStore(hold.policyFile()).loadGlobal())
                .contains(BackupRetentionPolicy.DEFAULT);
    }

    /**
     * T4 — Discard delivers nothing and never re-arms: the dirty-close prompt
     * runs exactly once, the dialog closes, and the hold and the file stay.
     */
    @Test
    void discardingAnArmedDialogPromptsOnceClosesItAndLeavesTheHoldAndTheFile(@TempDir Path tempDir)
            throws Exception {
        Hold hold = hold(tempDir);
        SettingsDialog dialog = Story307TestSupport.onFx(() -> {
            SettingsDialog created = dialog(hold, () -> { });
            created.selectCategory("backups");
            created.show();
            return created;
        }, FX_SECONDS, TimeUnit.SECONDS);
        AtomicInteger prompts = new AtomicInteger();
        try {
            Story307TestSupport.onFx(() -> {
                assertThat(dialog.isShowing()).as("precondition: shown").isTrue();
                assertArmed(dialog, "shown on Backups");
                dialog.setDirtyClosePrompt(() -> {
                    prompts.incrementAndGet();
                    return SettingsDialog.DirtyChoice.DISCARD;
                });

                cancelButton(dialog).fire();

                assertThat(prompts).as("the dirty-close prompt ran exactly once").hasValue(1);
                assertThat(dialog.isShowing()).as("the dialog closed").isFalse();
                assertThat(dialog.getShell().dirtyProperty().get()).as("Discard dropped the armed rows").isFalse();
                assertThat(dialog.getShell().isOperationNoticeVisible()).as("and the hold notice").isFalse();
                return null;
            }, FX_SECONDS, TimeUnit.SECONDS);
        } finally {
            Story307TestSupport.onFx(() -> {
                if (dialog.isShowing()) {
                    dialog.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.DISCARD);
                    dialog.close();
                }
                dialog.setBackupSettingsAccess(null);
                return null;
            }, FX_SECONDS, TimeUnit.SECONDS);
        }

        assertHoldContinues(hold);
    }

    /** The notice showing when the armed dialog is discarded. */
    enum NoticeAtDiscard { THE_HOLD_NOTICE, A_LATER_NOTICE }

    /**
     * Fix-review NIT-1 — a Discard while an audio reconfigure is still running
     * keeps the dialog open until the audio work ends; for that time the rows
     * are no longer armed, so the hold notice, which says what Apply or OK
     * would do with them, goes too. A later notice that replaced it (an audio
     * failure, say) is not the hold's and stays. The dialog closes when the
     * audio work ends, and the hold and the file stay.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(NoticeAtDiscard.class)
    void discardingWhileAnAudioReconfigureRunsDropsTheHoldNoticeWithTheArmedRows(NoticeAtDiscard notice,
                                                                                 @TempDir Path tempDir)
            throws Exception {
        String laterNotice = "A later notice that is not the hold's.";
        Hold hold = hold(tempDir);
        Story307TestSupport.StubController audio = new Story307TestSupport.StubController();
        audio.blockFirstConfiguration = true;
        audio.releaseFirstConfiguration = new CountDownLatch(1);
        AtomicReference<SettingsDialog> shown = new AtomicReference<>();
        try {
            Story307TestSupport.onFx(() -> {
                SettingsDialog dialog = dialog(hold, () -> { });
                shown.set(dialog);
                SettingsShell shell = dialog.getShell();
                dialog.setAudioEngineController(audio);
                dialog.show();
                // An audio change applied in a dialog that has not displayed Backups: the
                // reconfigure is outstanding until the test releases it, after this turn.
                int bufferSize = dialog.getModel().getBufferSize() == 512 ? 256 : 512;
                shell.settingRow("audio.bufferSize").orElseThrow().setValue(bufferSize);
                applyButton(dialog).fire();
                assertThat(shell.pendingEdits()).as("only the audio row was pending and it applied").isEmpty();

                dialog.selectCategory("backups");
                assertArmed(dialog, "displayed during the reconfigure");
                if (notice == NoticeAtDiscard.A_LATER_NOTICE) {
                    shell.showOperationNotice(laterNotice);
                }
                dialog.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.DISCARD);

                cancelButton(dialog).fire();

                assertThat(dialog.isShowing()).as("the dialog waits for the audio work").isTrue();
                assertThat(shell.dirtyProperty().get()).as("Discard dropped the armed rows").isFalse();
                if (notice == NoticeAtDiscard.THE_HOLD_NOTICE) {
                    assertThat(shell.isOperationNoticeVisible()).as("the hold notice goes with them").isFalse();
                    assertThat(shell.operationNoticeText()).isEmpty();
                } else {
                    assertThat(shell.isOperationNoticeVisible()).as("a notice that is not the hold's stays").isTrue();
                    assertThat(shell.operationNoticeText()).isEqualTo(laterNotice);
                }
                return null;
            }, FX_SECONDS, TimeUnit.SECONDS);

            audio.releaseFirstConfiguration.countDown();
            assertThat(Story307TestSupport.awaitFxValue(shown.get()::isShowing, false, WAIT_SECONDS, TimeUnit.SECONDS))
                    .as("the dialog closes when the audio work ends").isTrue();
        } finally {
            audio.releaseFirstConfiguration.countDown();
            Story307TestSupport.onFx(() -> {
                SettingsDialog dialog = shown.get();
                if (dialog != null) {
                    if (dialog.isShowing()) {
                        dialog.setDirtyClosePrompt(() -> SettingsDialog.DirtyChoice.DISCARD);
                        dialog.close();
                    }
                    dialog.setBackupSettingsAccess(null);
                }
                return null;
            }, FX_SECONDS, TimeUnit.SECONDS);
        }

        assertHoldContinues(hold);
    }

    /** T5 — a confirmed Restore All Defaults during the hold, Backups never displayed, delivers and saves the default. */
    @Test
    void aConfirmedRestoreAllDefaultsDuringTheHoldDeliversAndSavesTheDefault(@TempDir Path tempDir)
            throws Exception {
        Hold hold = hold(tempDir);
        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = dialog(hold, () -> { });
            try {
                assertThat(dialog.getShell().displayedCategoryProperty().get()).as("precondition")
                        .isNotEqualTo("backups");
                dialog.setRestoreAllConfirm(() -> true);

                dialog.restoreAllDefaults();

                assertThat(dialog.getShell().dirtyProperty().get()).as("the bulk Apply re-seeded clean").isFalse();
            } finally {
                dialog.setBackupSettingsAccess(null);
            }
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);
        assertThat(hold.controller().awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)))
                .isTrue();

        assertThat(hold.checkpoints().getRetentionPolicy()).as("delivered")
                .contains(BackupRetentionPolicy.DEFAULT);
        assertThat(hold.access().storedPolicyUnreadable()).as("the hold ends").isFalse();
        assertThat(new BackupRetentionPolicyStore(hold.policyFile()).loadGlobal()).as("and saved")
                .contains(BackupRetentionPolicy.DEFAULT);
    }

    /** T6 — with no file, and with a good one, there is no hold: displaying Backups arms nothing. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("filesThatAreNoHold")
    void withoutAHoldDisplayingBackupsArmsNothing(String what, Optional<BackupRetentionPolicy> stored,
                                                  @TempDir Path tempDir) throws Exception {
        Path policyFile = tempDir.resolve("backup-retention.json");
        if (stored.isPresent()) {
            Files.writeString(policyFile, BackupRetentionPolicyStore.toJson(stored.get()));
        }
        CheckpointManager checkpoints = manager();
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(policyFile), checkpoints, Runnable::run);
        List<String> warnings = new ArrayList<>();
        MainController.deliverStoredRetention(controller, warnings::add);
        assertThat(warnings).as("%s: no hold", what).isEmpty();

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(model());
            BackupSettingsAccess access = controller.settingsAccess(Optional::empty, () -> { });
            dialog.setBackupSettingsAccess(access);
            try {
                SettingsShell shell = dialog.getShell();
                dialog.selectCategory("backups");

                assertThat(access.storedPolicyUnreadable()).isFalse();
                assertThat(shell.pendingEdits()).as("%s: nothing is armed", what).isEmpty();
                assertThat(shell.dirtyProperty().get()).as("%s: the shell is clean", what).isFalse();
                assertThat(navItem(shell, "backups").dirtyProperty().get()).isFalse();
                assertThat(applyButton(dialog).isDisable()).as("%s: Apply is disabled", what).isTrue();
                assertThat(shell.isOperationNoticeVisible()).as("%s: no notice", what).isFalse();
            } finally {
                dialog.setBackupSettingsAccess(null);
            }
            return null;
        }, FX_SECONDS, TimeUnit.SECONDS);
        assertThat(checkpoints.getRetentionPolicy()).contains(stored.orElse(BackupRetentionPolicy.DEFAULT));
    }

    static Stream<Arguments> filesThatAreNoHold() {
        return Stream.of(
                Arguments.of("no file", Optional.empty()),
                Arguments.of("a good file", Optional.of(BackupRetentionPolicy.DEFAULT.withKeepRecent(5))));
    }

    // ── Fixture ───────────────────────────────────────────────────────────

    /**
     * A launch whose stored policy cannot be read: the hold is on, and the
     * project's checkpoint generation runs over its twelve old checkpoints.
     */
    private record Hold(Path policyFile, byte[] fileBytes, Path project, List<String> old,
                        CheckpointManager checkpoints, BackupRetentionController controller,
                        BackupSettingsAccess access) {}

    private Hold hold(Path tempDir) throws Exception {
        byte[] garbage = bytes("this is not a retention policy");
        Path policyFile = Files.write(tempDir.resolve("backup-retention.json"), garbage);
        Path project = Files.createDirectories(tempDir.resolve("Song"));
        List<String> old = plantOldCheckpoints(project);
        CheckpointManager checkpoints = manager();
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(policyFile), checkpoints, Runnable::run);
        List<String> warnings = new ArrayList<>();
        MainController.deliverStoredRetention(controller, warnings::add);
        assertThat(warnings).as("precondition: the hold").containsExactly(NOT_READ);
        BackupSettingsAccess access = controller.settingsAccess(Optional::empty, () -> { });
        assertThat(access.storedPolicyUnreadable()).as("the access reports the hold").isTrue();
        checkpoints.start(project).get(WAIT_SECONDS, TimeUnit.SECONDS);
        return new Hold(policyFile, garbage, project, old, checkpoints, controller, access);
    }

    /** The production Settings surface over the hold's controller; Backups is not displayed. */
    private SettingsDialog dialog(Hold hold, Runnable onSaveFailed) {
        SettingsDialog dialog = new SettingsDialog(model());
        dialog.setBackupSettingsAccess(hold.controller().settingsAccess(Optional::empty, onSaveFailed));
        return dialog;
    }

    /** Nothing delivered, nothing saved: the hold continues and the bad file is untouched. */
    private static void assertHoldContinues(Hold hold) throws IOException {
        assertThat(hold.checkpoints().getRetentionPolicy()).as("the manager still keeps every checkpoint")
                .contains(BackupRetentionController.KEEP_EVERY_CHECKPOINT);
        assertThat(hold.access().storedPolicyUnreadable()).as("the hold continues").isTrue();
        assertThat(Files.readAllBytes(hold.policyFile())).as("the file is untouched").isEqualTo(hold.fileBytes());
    }

    /** Ruling-3 Q1.2 and Q1.4 — the six rows pending at what they show, the usual dirty marking, the notice. */
    private static void assertArmed(SettingsDialog dialog, String route) {
        SettingsShell shell = dialog.getShell();
        Map<String, Object> pending = shell.pendingEdits();
        assertThat(pending).as("%s: the six Backups rows are pending", route).containsOnlyKeys(BACKUP_ROWS);
        for (String id : BACKUP_ROWS) {
            SettingRow row = shell.settingRow(id).orElseThrow();
            assertThat(pending.get(id)).as("%s: %s holds the value it shows", route, id).isEqualTo(row.getValue());
            assertThat(row.modifiedProperty().get()).as("%s: %s's ↺ marker is unaffected", route, id).isFalse();
        }
        assertThat(shell.dirtyProperty().get()).as("%s: dirty", route).isTrue();
        assertThat(navItem(shell, "backups").dirtyProperty().get()).as("%s: the Backups rail item is dirty", route)
                .isTrue();
        assertThat(applyButton(dialog).isDisable()).as("%s: Apply is enabled", route).isFalse();
        assertThat(shell.isOperationNoticeVisible()).as("%s: the notice shows", route).isTrue();
        assertThat(shell.operationNoticeText()).as("%s: the notice, verbatim", route).isEqualTo(HOLD_NOTICE);
    }

    /** The six Backups rows' values, as {@code shell} shows them. */
    private static Map<String, Object> shownValues(SettingsShell shell) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String id : BACKUP_ROWS) {
            values.put(id, shell.settingRow(id).orElseThrow().getValue());
        }
        return values;
    }

    private static SettingsNavRail.NavItem navItem(SettingsShell shell, String categoryId) {
        return shell.navRail().getItems().stream()
                .filter(item -> item.categoryId().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rail item " + categoryId));
    }

    /** The shell footer's Cancel, Apply and OK buttons. */
    private static List<Button> footerButtons(SettingsDialog dialog) {
        List<Button> buttons = new ArrayList<>();
        for (Node child : ((Parent) dialog.getShell().getBottom()).getChildrenUnmodifiable()) {
            if (child instanceof Button button) {
                buttons.add(button);
            }
        }
        assertThat(buttons).as("the footer carries Cancel, Apply and OK").hasSize(3);
        return buttons;
    }

    /** Apply is the footer button that is neither the cancel nor the default button. */
    private static Button applyButton(SettingsDialog dialog) {
        return footerButtons(dialog).stream()
                .filter(button -> !button.isCancelButton() && !button.isDefaultButton())
                .findFirst().orElseThrow();
    }

    private static Button okButton(SettingsDialog dialog) {
        return footerButtons(dialog).stream().filter(Button::isDefaultButton).findFirst().orElseThrow();
    }

    private static Button cancelButton(SettingsDialog dialog) {
        return footerButtons(dialog).stream().filter(Button::isCancelButton).findFirst().orElseThrow();
    }

    private CheckpointManager manager() {
        CheckpointManager manager = new CheckpointManager(AutoSaveConfig.DEFAULT);
        managers.add(manager);
        return manager;
    }

    /** A controller that delivered the default from a missing file: what the rows show for the default. */
    private BackupRetentionController defaultDelivered(Path tempDir) {
        BackupRetentionController controller = new BackupRetentionController(
                new BackupRetentionPolicyStore(tempDir.resolve("absent").resolve("backup-retention.json")),
                manager(), Runnable::run);
        assertThat(controller.deliverStored()).contains(BackupRetentionPolicy.DEFAULT);
        return controller;
    }

    /** FX thread: the six Backups rows' values in the production Settings surface over {@code controller}'s access. */
    private Map<String, Object> rowValues(BackupRetentionController controller) {
        SettingsDialog dialog = new SettingsDialog(model());
        dialog.setBackupSettingsAccess(controller.settingsAccess(Optional::empty, () -> { }));
        Map<String, Object> values = shownValues(dialog.getShell());
        dialog.setBackupSettingsAccess(null);
        return values;
    }

    /** Plants checkpoints 001-012 of an earlier launch, 100 days old, a minute apart; oldest first. */
    private static List<String> plantOldCheckpoints(Path project) throws IOException {
        Path folder = Files.createDirectories(project.resolve("checkpoints"));
        Instant first = Instant.now().minus(Duration.ofDays(100));
        List<String> names = new ArrayList<>();
        for (int index = 1; index <= OLD_CHECKPOINTS; index++) {
            String name = CheckpointFileName.format(index, first.plus(Duration.ofMinutes(index)),
                    ZoneId.systemDefault());
            Files.writeString(folder.resolve(name), "<project revision=\"0\"/>");
            names.add(name);
        }
        return names;
    }

    private static String write(CheckpointManager manager, long revision) throws Exception {
        return manager.writeCheckpoint("<project revision=\"" + revision + "\"/>", revision, CheckpointOrigin.USER)
                .get(WAIT_SECONDS, TimeUnit.SECONDS).getFileName().toString();
    }

    /** The checkpoints in the project's folder: only names the grammar accepts. */
    private static List<String> checkpointNames(Path project) throws IOException {
        try (Stream<Path> files = Files.list(project.resolve("checkpoints"))) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .map(file -> file.getFileName().toString())
                    .filter(name -> CheckpointFileName.parse(name).isPresent())
                    .sorted()
                    .toList();
        }
    }

    private SettingsModel model() {
        Preferences node = Preferences.userRoot().node("unreadableRetentionPolicy_" + System.nanoTime());
        nodes.add(node);
        return new SettingsModel(node);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("the source declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
