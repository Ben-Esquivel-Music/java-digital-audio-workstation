package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 215 — verifies that the cue-bus "New Cue Bus" dialog derives
 * its hardware-output-pair picker from the live driver-reported
 * {@link AudioChannelInfo} list (instead of a hard-coded 0..31 range)
 * and that out-of-range saved cue buses are surfaced via
 * {@link NotificationManager} on project load.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerViewCueBusOutputPickerTest {

    private MixerView createOnFxThread(DawProject project) throws Exception {
        AtomicReference<MixerView> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(new MixerView(project));
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        return ref.get();
    }

    private void runOnFxThread(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void buildsFourStereoPairOptionsForEightDriverChannels() {
        List<AudioChannelInfo> channels = List.of(
                new AudioChannelInfo(0, "Mic/Line 1 L"),
                new AudioChannelInfo(1, "Mic/Line 1 R"),
                new AudioChannelInfo(2, "Mic/Line 2 L"),
                new AudioChannelInfo(3, "Mic/Line 2 R"),
                new AudioChannelInfo(4, "Phones 1 L"),
                new AudioChannelInfo(5, "Phones 1 R"),
                new AudioChannelInfo(6, "Main Out L"),
                new AudioChannelInfo(7, "Main Out R"));

        List<MixerView.CueBusPairOption> opts =
                MixerView.buildCueBusOutputPairOptions(channels, _ -> false);

        // Exactly four entries — indices 4..31 must NOT be present.
        assertThat(opts).hasSize(4);
        assertThat(opts.stream().map(MixerView.CueBusPairOption::pairIndex))
                .containsExactly(0, 1, 2, 3);

        // Each label includes the driver-reported stem and the 1-based
        // physical output pair, e.g. "Phones 1 (Output 5 / 6)".
        assertThat(opts.get(0).displayName()).isEqualTo("Mic/Line 1 (Output 1 / 2)");
        assertThat(opts.get(1).displayName()).isEqualTo("Mic/Line 2 (Output 3 / 4)");
        assertThat(opts.get(2).displayName()).isEqualTo("Phones 1 (Output 5 / 6)");
        assertThat(opts.get(3).displayName()).isEqualTo("Main Out (Output 7 / 8)");
        assertThat(opts).allSatisfy(o -> assertThat(o.active()).isTrue());
    }

    @Test
    void fallsBackToLegacyZeroToThirtyOneRangeWhenSupplierIsEmpty() {
        List<MixerView.CueBusPairOption> opts =
                MixerView.buildCueBusOutputPairOptions(List.of(), _ -> false);

        // 0..31 inclusive == 32 entries.
        assertThat(opts).hasSize(MixerView.LEGACY_MAX_CUE_BUS_PAIR + 1);
        assertThat(opts.get(0).pairIndex()).isZero();
        assertThat(opts.get(0).displayName()).isEqualTo("Output 1 / 2");
        assertThat(opts.getLast().pairIndex()).isEqualTo(MixerView.LEGACY_MAX_CUE_BUS_PAIR);
        assertThat(opts.getLast().displayName()).isEqualTo("Output 63 / 64");
    }

    @Test
    void marksPairInactiveWhenDriverReportsEitherChannelDisabled() {
        List<AudioChannelInfo> channels = List.of(
                new AudioChannelInfo(0, "Phones 1 L", true),
                new AudioChannelInfo(1, "Phones 1 R", false));

        List<MixerView.CueBusPairOption> opts =
                MixerView.buildCueBusOutputPairOptions(channels, _ -> false);

        assertThat(opts).hasSize(1);
        assertThat(opts.get(0).active()).isFalse();
    }

    @Test
    void legacyFallbackOptionsAvailableWhenSupplierEmpty() throws Exception {
        // Even with an empty supplier the dialog must still open — exercised
        // here by computing the underlying picker options through the same
        // code path as promptCreateCueBus (ComboBox + legacy fallback).
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerView view = createOnFxThread(project);
        runOnFxThread(() -> view.setOutputChannelInfoSupplier(List::of));

        List<MixerView.CueBusPairOption> opts =
                MixerView.buildCueBusOutputPairOptions(List.of(), _ -> false);
        assertThat(opts).hasSizeGreaterThan(8);
        assertThat(opts.get(0).pairIndex()).isZero();
    }

    @Test
    void validateCueBusesAgainstDeviceEmitsExactlyOneNotificationForOutOfRangeBuses()
            throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        // Two out-of-range buses + one in-range bus.
        project.getCueBusManager().createCueBus("Drum HP", 5);
        project.getCueBusManager().createCueBus("Vocal HP", 6);
        project.getCueBusManager().createCueBus("In Range", 0);

        MixerView view = createOnFxThread(project);
        // Device only has one stereo pair (2 channels => pairCount=1).
        runOnFxThread(() -> view.setOutputChannelInfoSupplier(() -> List.of(
                new AudioChannelInfo(0, "Main Out L"),
                new AudioChannelInfo(1, "Main Out R"))));

        List<String> messages = new ArrayList<>();
        NotificationManager notifier = messages::add;

        int disabledCount = view.validateCueBusesAgainstDevice(notifier);

        assertThat(disabledCount).isEqualTo(2);
        // Exactly one notification — not one per affected bus.
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0))
                .contains("Drum HP")
                .contains("Vocal HP")
                .contains("current device has 1 pair")
                .doesNotContain("In Range");
        assertThat(view.getDisabledCueBusIds()).hasSize(2);

        // Re-running the validation does NOT spam a second notification —
        // the buses are already tracked as disabled.
        int secondCall = view.validateCueBusesAgainstDevice(notifier);
        assertThat(secondCall).isZero();
        assertThat(messages).hasSize(1);
    }

    @Test
    void validateCueBusesAgainstDeviceIsNoOpWhenSupplierIsEmpty() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.getCueBusManager().createCueBus("Drum HP", 5);

        MixerView view = createOnFxThread(project);
        // Default supplier returns List.of() — must not falsely disable
        // every bus before the device has finished opening.

        List<String> messages = new ArrayList<>();
        int disabled = view.validateCueBusesAgainstDevice(messages::add);

        assertThat(disabled).isZero();
        assertThat(messages).isEmpty();
        assertThat(view.getDisabledCueBusIds()).isEmpty();
    }

    @Test
    void disabledCueBusIdsAreReEnabledWhenDeviceGainsOutputs() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.getCueBusManager().createCueBus("Drum HP", 5);

        MixerView view = createOnFxThread(project);
        // Start with a 2-channel device (pair 0 only).
        runOnFxThread(() -> view.setOutputChannelInfoSupplier(() -> List.of(
                new AudioChannelInfo(0, "Main Out L"),
                new AudioChannelInfo(1, "Main Out R"))));

        List<String> messages = new ArrayList<>();
        view.validateCueBusesAgainstDevice(messages::add);
        assertThat(view.getDisabledCueBusIds()).hasSize(1);

        // Now simulate switching to a 12-channel device — pair 5 is in
        // range so the bus should be re-enabled.
        runOnFxThread(() -> view.setOutputChannelInfoSupplier(() -> {
            List<AudioChannelInfo> channels = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                channels.add(new AudioChannelInfo(i, "Ch " + (i + 1)));
            }
            return channels;
        }));

        view.validateCueBusesAgainstDevice(messages::add);
        assertThat(view.getDisabledCueBusIds()).isEmpty();
    }
}
