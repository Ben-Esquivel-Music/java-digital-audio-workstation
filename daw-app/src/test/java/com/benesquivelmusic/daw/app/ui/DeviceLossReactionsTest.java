package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The window's device-loss reactions on their own (story 338, rulings E35,
 * E64): only a DEVICE_LOST delivery, of either cause, silences the input
 * meters and reads the lost drive's epoch; a window with no meters, no drain
 * or no transport is left alone. The drain half runs against a real drain in
 * {@code AudioHealthDrainTest}, the bridge's wiring in
 * {@code DeviceLossLeavesTheTransportAndMetersTruthfulTest}.
 */
class DeviceLossReactionsTest {

    private static final Optional<DeviceId> DEVICE = Optional.of(new DeviceId("Mock", "Interface A"));

    private final InputLevelMonitorRegistry meters = new InputLevelMonitorRegistry();
    private final AtomicInteger epochReads = new AtomicInteger();
    private final DeviceLossReactions reactions = new DeviceLossReactions(() -> null,
            () -> {
                epochReads.incrementAndGet();
                return 7L;
            }, Optional::empty, meters);

    @Test
    void aLossOfEitherCauseSilencesEveryInputMeter() {
        for (EngineStateChange.LossCause cause : new EngineStateChange.LossCause[] {
                EngineStateChange.LossCause.REMOVED, EngineStateChange.LossCause.STALLED}) {
            loud("a");
            loud("b");

            reactions.accept(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE, cause));

            assertThat(meters.get("a").snapshot().peakDbfs()).as("%s", cause).isEqualTo(InputLevelMeter.DB_FLOOR);
            assertThat(meters.get("b").snapshot().peakDbfs()).as("%s", cause).isEqualTo(InputLevelMeter.DB_FLOOR);
        }
        assertThat(epochReads).as("the lost drive's epoch is read at each loss").hasValue(2);
    }

    /**
     * A block the lost stream renders after the delivery shows no level: the
     * meters are muted through the lost drive's epoch until the engine starts
     * a newer drive (ruling E175).
     */
    @Test
    void aBlockRenderedAfterTheLossShowsNoLevelUntilTheEngineStartsANewerDrive() {
        loud("a");

        reactions.accept(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE, EngineStateChange.LossCause.STALLED));
        loud("a");
        assertThat(meters.get("a").snapshot().peakDbfs()).as("the late block is not shown")
                .isEqualTo(InputLevelMeter.DB_FLOOR);

        meters.liftAll(7L);
        loud("a");
        assertThat(meters.get("a").snapshot().peakDbfs()).as("a start of the lost drive lifts nothing")
                .isEqualTo(InputLevelMeter.DB_FLOOR);

        meters.liftAll(8L);
        loud("a");
        assertThat(meters.get("a").snapshot().peakDbfs()).as("the newer drive's block is shown")
                .isGreaterThan(-12.0);
        assertThat(epochReads).as("the epoch is read once per loss").hasValue(1);
    }

    /**
     * A loss whose delivery comes after the engine built a newer drive
     * changes nothing on the meters: no floor, no dip and no mute (ruling
     * E202).
     */
    @Test
    void aLossDeliveredAfterANewerDriveWasBuiltKeepsTheLevel() {
        meters.liftAll(8L);
        loud("a");
        InputLevelMeter level = meters.get("a").snapshot();

        reactions.accept(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE, EngineStateChange.LossCause.STALLED));

        assertThat(meters.get("a").snapshot()).as("the newer stream's level stays").isSameAs(level);
        loud("a");
        assertThat(meters.get("a").snapshot().peakDbfs()).as("and is not muted").isGreaterThan(-12.0);
        assertThat(epochReads).as("the lost drive's epoch was read").hasValue(1);
    }

    @Test
    void aLossWhoseEpochCannotBeReadFloorsTheMetersWithoutHoldingThem() {
        DeviceLossReactions unreadable = new DeviceLossReactions(() -> null,
                () -> {
                    throw new IllegalStateException("no epoch");
                }, Optional::empty, meters);
        loud("a");

        unreadable.accept(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE, EngineStateChange.LossCause.REMOVED));
        assertThat(meters.get("a").snapshot().peakDbfs()).as("the meters fell").isEqualTo(InputLevelMeter.DB_FLOOR);
        loud("a");

        assertThat(meters.get("a").snapshot().peakDbfs()).as("no drive would lift a mute through an unknown one")
                .isGreaterThan(-12.0);
    }

    @Test
    void everyOtherStateLeavesTheMetersAndTheDrainAlone() {
        loud("a");
        double level = meters.get("a").snapshot().peakDbfs();

        for (EngineState state : EngineState.values()) {
            if (state != EngineState.DEVICE_LOST) {
                reactions.accept(new EngineStateChange(state, DEVICE));
            }
        }

        assertThat(meters.get("a").snapshot().peakDbfs()).isEqualTo(level).isGreaterThan(-12.0);
        assertThat(epochReads).hasValue(0);
    }

    @Test
    void aWindowWithNoMetersNoDrainAndNoTransportIsLeftAlone() {
        DeviceLossReactions bare = new DeviceLossReactions(() -> null, () -> 3L, Optional::empty, null);

        assertThatCode(() -> bare.accept(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE,
                EngineStateChange.LossCause.STALLED))).doesNotThrowAnyException();
        assertThat(bare.takeRescued()).as("no transport: nothing was rescued").isFalse();
    }

    /**
     * Story 338: the bridge hands the change to the window's engine-health
     * model after these reactions, so a reaction that throws must neither
     * keep the other from running nor keep the model from the loss.
     */
    @Test
    void aReactionThatThrowsLeavesTheOtherDoneAndTheModelAfterThemTheLoss() {
        DeviceLossReactions failingDrain = new DeviceLossReactions(() -> null, () -> 5L,
                () -> {
                    throw new IllegalStateException("the drain is gone");
                }, meters);
        EngineHealthStatus model = new EngineHealthStatus();
        loud("a");

        failingDrain.andThen(model::onEngineStateChange).accept(new EngineStateChange(EngineState.DEVICE_LOST, DEVICE,
                EngineStateChange.LossCause.STALLED));

        assertThat(meters.get("a").snapshot().peakDbfs()).as("the meters fell anyway")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(model.engineStateProperty().get().state()).as("and the model has the loss")
                .isEqualTo(EngineState.DEVICE_LOST);
    }

    private void loud(String trackId) {
        float[] block = new float[128];
        Arrays.fill(block, .5f);
        meters.getOrCreate(trackId).process(block);
    }
}
