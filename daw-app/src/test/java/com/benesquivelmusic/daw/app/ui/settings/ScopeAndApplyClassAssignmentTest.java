package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.density.DensityManager;
import com.benesquivelmusic.daw.app.ui.motion.MotionManager;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 305 — locks the pinned §3.2 scope and §6.4 apply-class
 * assignments so the metadata is correct, not placeholder: the
 * project-defaults dual-reach pair, the single {@code RESTART_REQUIRED}
 * backend descriptor (replacing the audio tab's blanket restart hint
 * with per-setting truth), the engine-reconfigure audio set, the four
 * {@code LIVE} appearance settings, and the uniform key-binding shape.
 */
class ScopeAndApplyClassAssignmentTest {

    private final SettingsCatalogue catalogue = SettingsCatalogue.create();

    private SettingDescriptor<?> descriptor(String id) {
        return catalogue.byId(id).orElseThrow(
                () -> new AssertionError("No descriptor for setting: " + id));
    }

    private Scope scopeOf(String id) {
        return descriptor(id).scope();
    }

    private ApplyClass applyClassOf(String id) {
        return descriptor(id).applyClass();
    }

    @Test
    void projectDefaultsScopeShouldCoverTempoAndAutoSave() {
        // §3.2 dual reach: stored as project defaults, also live-applied to
        // the open project by LiveSettingsApplier — the tag records where
        // the value is stored.
        assertThat(scopeOf("project.defaultTempo")).isEqualTo(Scope.PROJECT_DEFAULTS);
        assertThat(scopeOf("project.autoSaveIntervalSeconds"))
                .isEqualTo(Scope.PROJECT_DEFAULTS);
    }

    @Test
    void applicationScopeShouldCoverAppearancePluginsAndJournalSettings() {
        assertThat(scopeOf(ThemeManager.PREF_KEY)).isEqualTo(Scope.APPLICATION);
        assertThat(scopeOf(DensityManager.PREF_KEY)).isEqualTo(Scope.APPLICATION);
        assertThat(scopeOf(MotionManager.PREF_KEY)).isEqualTo(Scope.APPLICATION);
        assertThat(scopeOf("appearance.uiScale")).isEqualTo(Scope.APPLICATION);
        assertThat(scopeOf("plugins.scanPaths")).isEqualTo(Scope.APPLICATION);
        assertThat(scopeOf("project.useJournaledPersistence")).isEqualTo(Scope.APPLICATION);
    }

    @Test
    void allTwelveAudioDescriptorsShouldBeAudioDeviceScope() {
        List<SettingDescriptor<?>> audioDescriptors = catalogue.descriptors().stream()
                .filter(descriptor -> descriptor.id().startsWith("audio."))
                .toList();
        assertThat(audioDescriptors).hasSize(12);
        assertThat(audioDescriptors)
                .allSatisfy(descriptor -> assertThat(descriptor.scope())
                        .as("scope of %s", descriptor.id())
                        .isEqualTo(Scope.AUDIO_DEVICE));
    }

    @Test
    void onlyMetronomeRoutingDescriptorsCarryThisProjectScope() {
        // Story 308 — THIS_PROJECT's first use (§3.2 "metronome routing"):
        // click routing round-trips through the project XML. Exactly the
        // five routing ids carry the scope; nothing else does.
        assertThat(catalogue.descriptors().stream()
                .filter(descriptor -> descriptor.scope() == Scope.THIS_PROJECT)
                .map(SettingDescriptor::id))
                .containsExactlyInAnyOrder(
                        "metronome.clickChannel", "metronome.clickGain",
                        "metronome.clickSideOutput", "metronome.clickMainMix",
                        "metronome.clickCueBus");
    }

    @Test
    void absorbedRecordingAndBackupsDescriptorsCarryTheirPinnedScopesAllLive() {
        // Story 308 §3.2 — enabled/count-in are prefs-authoritative at
        // startup → APPLICATION; retention is the application-wide
        // ~/.daw/backup-retention.json policy → APPLICATION. Everything
        // absorbed applies LIVE (no engine re-arm, no restart).
        assertThat(scopeOf("metronome.enabled")).isEqualTo(Scope.APPLICATION);
        assertThat(scopeOf("metronome.countIn")).isEqualTo(Scope.APPLICATION);
        List<String> absorbedIds = List.of(
                "metronome.enabled", "metronome.countIn", "metronome.clickChannel",
                "metronome.clickGain", "metronome.clickSideOutput",
                "metronome.clickMainMix", "metronome.clickCueBus",
                "backups.keepRecent", "backups.keepHourly", "backups.keepDaily",
                "backups.keepWeekly", "backups.maxAgeDays", "backups.maxDiskGiB");
        for (String id : absorbedIds) {
            assertThat(applyClassOf(id))
                    .as("apply class of %s", id)
                    .isEqualTo(ApplyClass.LIVE);
        }
        for (String id : List.of("backups.keepRecent", "backups.keepHourly",
                "backups.keepDaily", "backups.keepWeekly",
                "backups.maxAgeDays", "backups.maxDiskGiB")) {
            assertThat(scopeOf(id))
                    .as("scope of %s", id)
                    .isEqualTo(Scope.APPLICATION);
        }
    }

    @Test
    void returnToStartOnStopShouldBeApplicationScopedLiveToggle() {
        // Story 315 — one application preference governing every project's
        // transport (seeded per load, pushed live by LiveSettingsApplier);
        // the transport reads the volatile flag on each Stop, so the apply
        // class is LIVE (no engine re-arm).
        assertThat(scopeOf("transport.returnToStartOnStop"))
                .isEqualTo(Scope.APPLICATION);
        assertThat(applyClassOf("transport.returnToStartOnStop"))
                .isEqualTo(ApplyClass.LIVE);
        assertThat(descriptor("transport.returnToStartOnStop").controlKind())
                .isEqualTo(ControlKind.TOGGLE);
    }

    @Test
    void backendShouldBeTheOnlyRestartRequiredDescriptor() {
        assertThat(applyClassOf("audio.backend")).isEqualTo(ApplyClass.RESTART_REQUIRED);
        assertThat(catalogue.descriptors().stream()
                .filter(descriptor -> descriptor.applyClass() == ApplyClass.RESTART_REQUIRED)
                .map(SettingDescriptor::id))
                .as("exactly one RESTART_REQUIRED descriptor in the whole catalogue")
                .containsExactly("audio.backend");
    }

    @Test
    void engineReconfigureShouldCoverThePinnedAudioSettings() {
        // "Takes effect on the next engine restart" in the SettingsModel
        // Javadocs = a stream re-arm, not an application restart.
        assertThat(applyClassOf("audio.sampleRate")).isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
        assertThat(applyClassOf("audio.bufferSize")).isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
        assertThat(applyClassOf("audio.srcQuality")).isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
        assertThat(applyClassOf("audio.workerPoolSize"))
                .isEqualTo(ApplyClass.ENGINE_RECONFIGURE);
    }

    @Test
    void liveApplyClassShouldCoverTheFourAppearanceSettings() {
        assertThat(applyClassOf(ThemeManager.PREF_KEY)).isEqualTo(ApplyClass.LIVE);
        assertThat(applyClassOf(DensityManager.PREF_KEY)).isEqualTo(ApplyClass.LIVE);
        assertThat(applyClassOf(MotionManager.PREF_KEY)).isEqualTo(ApplyClass.LIVE);
        assertThat(applyClassOf("appearance.uiScale")).isEqualTo(ApplyClass.LIVE);
    }

    @Test
    void everyKeyBindingDescriptorShouldBeApplicationLiveKeyCapture() {
        List<SettingDescriptor<?>> bindings = catalogue.descriptors().stream()
                .filter(descriptor -> descriptor.id().startsWith("keybinding."))
                .toList();
        // Non-empty guard: an id-format drift must not pass vacuously.
        assertThat(bindings).isNotEmpty();
        assertThat(bindings).allSatisfy(descriptor -> {
            assertThat(descriptor.scope())
                    .as("scope of %s", descriptor.id())
                    .isEqualTo(Scope.APPLICATION);
            assertThat(descriptor.applyClass())
                    .as("apply class of %s", descriptor.id())
                    .isEqualTo(ApplyClass.LIVE);
            assertThat(descriptor.controlKind())
                    .as("control kind of %s", descriptor.id())
                    .isEqualTo(ControlKind.KEY_CAPTURE);
        });
    }
}
