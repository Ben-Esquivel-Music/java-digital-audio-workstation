package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.settings.SettingDescriptor;
import com.benesquivelmusic.daw.app.ui.settings.SettingsCatalogue;
import com.benesquivelmusic.daw.app.ui.settings.SettingsProfileIO;
import com.benesquivelmusic.daw.core.audio.performance.TrackCpuBudgetEnforcer;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 335 — "CPU-policy truth test", under the removal decision (studio
 * ruling R3): the master CPU degradation policy row, setting and read path
 * are gone, the stale preference key is deleted on load, an old exported
 * profile reports the key as an unknown setting, and the master cascade sheds
 * each track with that track's own CPU Budget policy — the policy the project
 * file stores — so no write-only setting survives.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MasterCpuPolicyRemovedWiringTest {

    private static final String KEY = "audio.masterCpuBudgetPolicy";
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle(
            "com.benesquivelmusic.daw.app.i18n.Messages", Locale.ROOT);

    @Test
    void theCatalogueAndTheSettingsDialogHaveNoCascadePolicyRow() throws Exception {
        SettingsCatalogue catalogue = SettingsCatalogue.create();
        assertThat(catalogue.byId(KEY)).isEmpty();
        assertThat(catalogue.descriptors()).extracting(SettingDescriptor::label).doesNotContain("Cascade policy");
        assertThat(MESSAGES.containsKey(KEY + ".label")).isFalse();
        assertThat(MESSAGES.containsKey(KEY + ".description")).isFalse();
        assertThat(MESSAGES.getString("audio.masterCpuBudgetFraction.description"))
                .isEqualTo("Master CPU ceiling per audio block; when exceeded, the highest-CPU tracks are degraded"
                        + " by the policy in each track's CPU Budget dialog. 1.0 disables the cap.");

        Story307TestSupport.onFx(() -> {
            SettingsDialog dialog = new SettingsDialog(Story307TestSupport.model("masterCpuPolicyRemoved"));
            assertThat(dialog.getShell().settingRow(KEY)).isEmpty();
            assertThat(dialog.getShell().settingRow("audio.masterCpuBudgetFraction")).isPresent();
            return null;
        }, 30, TimeUnit.SECONDS);
        assertThat(Arrays.stream(SettingsModel.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .as("no getter or setter is left to persist a policy nothing reads")
                .doesNotContain("getMasterCpuBudgetPolicy", "setMasterCpuBudgetPolicy");
    }

    @Test
    void theStalePreferenceKeyIsDeletedOnLoad() throws BackingStoreException {
        Preferences node = Preferences.userRoot().node("masterCpuPolicyStaleKey_" + System.nanoTime());
        try {
            node.put(KEY, "BypassExpensive");
            node.putDouble("audio.masterCpuBudgetFraction", 0.8);

            SettingsModel model = new SettingsModel(node);

            assertThat(node.get(KEY, null)).as("no persisted-but-never-read value survives").isNull();
            assertThat(node.keys()).doesNotContain(KEY);
            assertThat(model.getMasterCpuBudgetFraction()).as("the fraction is still read").isEqualTo(0.8);
        } finally {
            node.removeNode();
        }
    }

    @Test
    void anOldExportedProfileReportsTheKeyAsAnUnknownSetting() {
        SettingsProfileIO.ImportOutcome outcome = SettingsProfileIO.parseAndValidate(SettingsCatalogue.create(),
                EnumSet.allOf(SettingsProfileIO.ProfileScope.class),
                "# " + SettingsProfileIO.HEADER + "\naudioDevice|" + KEY + "=BypassExpensive\n");

        assertThat(outcome.applied()).doesNotContainKey(KEY);
        assertThat(outcome.rejects()).containsExactly(new SettingsProfileIO.Reject(KEY,
                SettingsProfileIO.RejectKind.UNKNOWN_SETTING, "BypassExpensive"));
        SettingsProfileIO.Reject reject = outcome.rejects().getFirst();
        assertThat(MessageFormat.format(MESSAGES.getString("settings.profile.reject.unknown"),
                reject.key(), reject.value()))
                .as("as the profile sheet reports it").isEqualTo(KEY + " — unknown setting");
    }

    @Test
    void theMasterCascadeShedsATrackWithItsOwnCpuBudgetPolicy() throws InterruptedException {
        double sampleRate = 48_000;
        int bufferSize = 512;
        long blockNanos = (long) ((bufferSize / sampleRate) * 1_000_000_000.0);
        DegradationPolicy highTracksOwn = new DegradationPolicy.ReduceOversampling(2);
        try (TrackCpuBudgetEnforcer enforcer = new TrackCpuBudgetEnforcer(sampleRate, bufferSize, 0.5, () -> 0L)) {
            Events events = new Events(1);
            enforcer.performanceEvents().subscribe(events);
            enforcer.registerTrack("high", new TrackCpuBudget(0.9, highTracksOwn));
            enforcer.registerTrack("low", new TrackCpuBudget(0.9, new DegradationPolicy.BypassExpensive()));
            enforcer.recordTrackCpu("high", (long) (blockNanos * 0.45));
            enforcer.recordTrackCpu("low", (long) (blockNanos * 0.10));

            assertThat(enforcer.evaluateMasterBudget()).containsExactly("high");
            assertThat(events.arrived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events.received).singleElement()
                    .isInstanceOfSatisfying(TrackPerformanceEvent.TrackDegraded.class, degraded -> {
                        assertThat(degraded.trackId()).isEqualTo("high");
                        assertThat(degraded.appliedPolicy()).as("the shed track's own policy").isEqualTo(highTracksOwn);
                    });
        }
    }

    private static final class Events implements Flow.Subscriber<TrackPerformanceEvent> {
        final List<TrackPerformanceEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch arrived;

        Events(int expected) {
            arrived = new CountDownLatch(expected);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(TrackPerformanceEvent event) {
            received.add(event);
            arrived.countDown();
        }

        @Override
        public void onError(Throwable failure) {
            // not expected; the assertions report what arrived
        }

        @Override
        public void onComplete() {
            // the enforcer closes its publisher at the end of the test
        }
    }
}
