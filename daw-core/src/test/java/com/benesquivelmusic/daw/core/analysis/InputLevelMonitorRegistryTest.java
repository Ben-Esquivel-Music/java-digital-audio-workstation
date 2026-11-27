package com.benesquivelmusic.daw.core.analysis;

import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InputLevelMonitorRegistryTest {

    /** The render drive a device loss lost. */
    private static final long LOST_DRIVE = 3L;

    /** The render drive of a stream whose close stopped waiting for a block an input device beside it delivers. */
    private static final long STREAM_DRIVE = 7L;

    private static final double MINUS_20_DBFS_LINEAR = Math.pow(10.0, -20.0 / 20.0);

    @Test
    void getOrCreateReturnsSameInstanceForSameTrackId() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor a = registry.getOrCreate("track-1");
        InputLevelMonitor b = registry.getOrCreate("track-1");
        assertThat(a).isSameAs(b);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void getReturnsNullForUnknownTrack() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        assertThat(registry.get("nope")).isNull();
        assertThat(registry.get(null)).isNull();
    }

    @Test
    void getOrCreateFromTrackKeysByTrackId() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        Track track = new Track("Vocals", TrackType.AUDIO);
        InputLevelMonitor m1 = registry.getOrCreate(track);
        InputLevelMonitor m2 = registry.get(track.getId());
        assertThat(m1).isSameAs(m2);
    }

    @Test
    void resetAllClearsLatchOnEveryMonitor() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor a = registry.getOrCreate("a");
        InputLevelMonitor b = registry.getOrCreate("b");

        float[] clipping = {1.5f, -1.5f, 1.5f, -1.5f, 1.5f, -1.5f, 1.5f, -1.5f};
        a.process(clipping);
        b.process(clipping);

        assertThat(a.isClippedSinceReset()).isTrue();
        assertThat(b.isClippedSinceReset()).isTrue();

        registry.resetAll();

        assertThat(a.isClippedSinceReset()).isFalse();
        assertThat(b.isClippedSinceReset()).isFalse();
    }

    @Test
    void removeDiscardsMonitor() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor m = registry.getOrCreate("x");
        assertThat(registry.remove("x")).isSameAs(m);
        assertThat(registry.get("x")).isNull();
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void silenceAllDropsEveryMonitorToTheFloor() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        float[] loud = new float[64];
        java.util.Arrays.fill(loud, .5f);
        for (String id : new String[]{"a", "b"}) {
            registry.getOrCreate(id).process(loud);
        }

        registry.silenceAll(LOST_DRIVE);

        for (String id : new String[]{"a", "b"}) {
            assertThat(registry.get(id).snapshot().peakDbfs())
                    .isEqualTo(InputLevelMeter.DB_FLOOR);
        }
    }

    @Test
    void aBlockRenderedAfterTheLossShowsNoLevelUntilANewDriveStarts() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("a");
        monitor.process(loud());

        registry.silenceAll(LOST_DRIVE);
        monitor.processInputChannels(new float[][]{clipping(), clipping()}, 0, 2, 64);

        InputLevelMeter late = monitor.snapshot();
        assertThat(late.peakDbfs()).as("the in-flight render's block is not shown").isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(late.rmsDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(late.clippedSinceReset()).as("but its clip is latched").isTrue();

        registry.liftAll(LOST_DRIVE + 1);
        assertThat(monitor.snapshot().peakDbfs()).as("nor once a new drive starts").isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(monitor.snapshot().clippedSinceReset()).as("whose start keeps the latch").isTrue();

        monitor.process(loud());
        assertThat(monitor.snapshot().peakDbfs()).as("the new drive's block is shown").isGreaterThan(-12.0);
    }

    @Test
    void aStartOfTheLostDriveOrAnOlderOneDoesNotLiftTheMute() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("a");
        registry.silenceAll(LOST_DRIVE);

        registry.liftAll(LOST_DRIVE - 1);
        registry.liftAll(LOST_DRIVE);
        monitor.process(loud());

        assertThat(monitor.snapshot().peakDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
    }

    /**
     * The loss's FX delivery can come after a newer drive was built: it
     * changes nothing on the meters, no floor, no dip and no mute, and the
     * clip latch stays as it is (ruling E202).
     */
    @Test
    void aLossDeliveredAfterANewerDriveWasBuiltChangesNothingOnTheMeters() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("a");
        registry.liftAll(LOST_DRIVE);
        monitor.process(clipping());
        registry.liftAll(LOST_DRIVE + 1);
        monitor.process(sineFromZero(MINUS_20_DBFS_LINEAR, 480));
        InputLevelMeter level = monitor.snapshot();
        assertThat(level.peakDbfs()).as("premise: the newer stream's level")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(level.clippedSinceReset()).as("premise: the latch carried into it").isTrue();

        registry.silenceAll(LOST_DRIVE);

        assertThat(monitor.snapshot()).as("no floor and no dip (E202)").isSameAs(level);
        assertThat(monitor.isClippedSinceReset()).as("the latch is unchanged").isTrue();
        monitor.process(loud());
        assertThat(monitor.snapshot().peakDbfs()).as("and no mute").isGreaterThan(-12.0);
    }

    /** A loss of the newest built drive floors and holds every meter until a newer build (E175, E202). */
    @Test
    void aLossOfTheNewestBuiltDriveFloorsAndHoldsTheMetersUntilANewerBuild() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("a");
        registry.liftAll(LOST_DRIVE + 1);
        monitor.process(sineFromZero(MINUS_20_DBFS_LINEAR, 480));

        registry.silenceAll(LOST_DRIVE + 1);
        assertThat(monitor.snapshot().peakDbfs()).as("the loss floors the meter").isEqualTo(InputLevelMeter.DB_FLOOR);
        monitor.process(loud());
        assertThat(monitor.snapshot().peakDbfs()).as("and holds it").isEqualTo(InputLevelMeter.DB_FLOOR);

        registry.liftAll(LOST_DRIVE + 2);
        monitor.process(loud());
        assertThat(monitor.snapshot().peakDbfs()).as("until a newer build").isGreaterThan(-12.0);
    }

    @Test
    void aLossOfAnUnknownDriveAfterABuildFloorsEveryMeterAndMutesNothing() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor a = registry.getOrCreate("a");
        InputLevelMonitor b = registry.getOrCreate("b");
        registry.liftAll(LOST_DRIVE + 1);
        a.process(loud());
        b.process(loud());

        registry.silenceAll(-1L);
        assertThat(a.snapshot().peakDbfs()).as("a loss of an unknown drive floors every meter")
                .isEqualTo(InputLevelMeter.DB_FLOOR);
        assertThat(b.snapshot().peakDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);

        a.process(loud());
        assertThat(a.snapshot().peakDbfs()).as("no mute").isGreaterThan(-12.0);
    }

    /**
     * A monitor created after a build carries that build: a block of that
     * drive, or of the render thread, is measured, and a block of an older
     * drive is refused (ruling E201).
     */
    @Test
    void aMonitorCreatedAfterABuildMeasuresThatDrivesBlocksAndRefusesAnOlderOnes() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        registry.liftAll(LOST_DRIVE + 1);
        InputLevelMonitor created = registry.getOrCreate("late");

        assertThatTheMonitorCarriesTheBuild(created, LOST_DRIVE + 1, LOST_DRIVE);
    }

    /**
     * Whichever comes first, a monitor's creation or a build, the monitor
     * carries the build: the build installs on a monitor created before it,
     * and a monitor created after it is constructed with it.
     */
    @Test
    void aMonitorCreatedBeforeOrAfterABuildCarriesThatBuild() {
        InputLevelMonitorRegistry createdFirst = new InputLevelMonitorRegistry();
        createdFirst.liftAll(LOST_DRIVE);
        InputLevelMonitor beforeTheBuild = createdFirst.getOrCreate("late");
        createdFirst.liftAll(LOST_DRIVE + 1);
        InputLevelMonitorRegistry builtFirst = new InputLevelMonitorRegistry();
        builtFirst.liftAll(LOST_DRIVE);
        builtFirst.liftAll(LOST_DRIVE + 1);
        InputLevelMonitor afterTheBuild = builtFirst.getOrCreate("late");

        assertThatTheMonitorCarriesTheBuild(beforeTheBuild, LOST_DRIVE + 1, LOST_DRIVE);
        assertThatTheMonitorCarriesTheBuild(afterTheBuild, LOST_DRIVE + 1, LOST_DRIVE);
    }

    /**
     * A monitor's creation, a build and the refusal of the input devices
     * beside a stream take one lock, the registry's own, so a monitor is
     * never inserted with an older state than the registry requires; the
     * lookup the render and delivery threads use takes none, and nothing
     * else in the registry takes a lock.
     */
    @Test
    void aCreationABuildAndTheRefusalTakeTheRegistrysLockAndNothingElseTakesALock() throws IOException {
        assertThat(MonitorEntries.lockedFieldsByMethod(InputLevelMonitorRegistry.class)).isEqualTo(Map.of(
                "getOrCreate", Set.of("drivesLock"),
                "liftAll", Set.of("drivesLock"),
                "refuseTheDevicesBesideTheStream", Set.of("drivesLock")));
    }

    private static void assertThatTheMonitorCarriesTheBuild(InputLevelMonitor monitor, long build, long older) {
        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480, build);
        InputLevelMeter level = monitor.snapshot();
        assertThat(level.peakDbfs()).as("a block of the build is measured")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));

        monitor.processInputChannels(new float[][]{clipping()}, 0, 1, 64, older);
        assertThat(monitor.snapshot()).as("a block of an older drive is refused").isSameAs(level);
        assertThat(monitor.isClippedSinceReset()).as("and latches no clip").isFalse();

        monitor.processInputChannels(new float[][]{loud()}, 0, 1, 64);
        assertThat(monitor.snapshot().peakDbfs()).as("the render thread's block is measured")
                .isGreaterThan(-12.0);
    }

    /**
     * From the moment the close of the input devices beside a stream stops
     * waiting for a block one of them is still delivering, a write of that
     * stream's drive is refused: no level, no clip, and no routing flag set or
     * cleared; the meters keep what they showed. The render thread's block is
     * measured, its clip counted on from the frames the stream had measured
     * (ruling E205).
     */
    @Test
    void afterTheCloseStopsWaitingAWriteOfTheStreamsDriveIsRefusedAndTheRenderThreadsBlockIsMeasured() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor measured = registry.getOrCreate("measured");
        InputLevelMonitor unrouted = registry.getOrCreate("unrouted");
        registry.liftAll(STREAM_DRIVE);
        measured.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480,
                STREAM_DRIVE);
        unrouted.setRoutingUnavailable(true, STREAM_DRIVE);
        InputLevelMeter atTheGiveUp = measured.snapshot();

        registry.refuseTheDevicesBesideTheStream();
        InputLevelMeter afterTheGiveUp = measured.snapshot();
        measured.processInputChannels(new float[][]{clipping()}, 0, 1, 64, STREAM_DRIVE);
        measured.setRoutingUnavailable(true, STREAM_DRIVE);
        unrouted.setRoutingUnavailable(false, STREAM_DRIVE);

        assertThat(afterTheGiveUp).as("the meter keeps what it showed").isSameAs(atTheGiveUp);
        assertThat(measured.snapshot()).as("no level from the device beside the stream (E205)").isSameAs(atTheGiveUp);
        assertThat(measured.isClippedSinceReset()).as("no clip").isFalse();
        assertThat(measured.isRoutingUnavailable()).as("no routing flag set").isFalse();
        assertThat(unrouted.isRoutingUnavailable()).as("none cleared").isTrue();

        measured.processInputChannels(new float[][]{clipping()}, 0, 1, 64);
        assertThat(measured.snapshot().peakDbfs()).as("the render thread's block is measured").isGreaterThan(-12.0);
        assertThat(measured.snapshot().lastClipFrameIndex()).as("its clip counted on from the stream's frames")
                .isGreaterThanOrEqualTo(480L);
    }

    /**
     * The physical-input fact a block's writer passes with its drive is refused as that writer's other writes
     * are: after the close stopped waiting for a delivery of the stream's drive (ruling E205), or once a newer
     * drive is built (ruling E201), so a track the person set to no input keeps showing nothing. The render
     * thread's write, and that of the newer drive's stream, apply.
     */
    @Test
    void aWriteOfThePhysicalInputFactWithADriveIsRefusedAsThatDrivesOtherWritesAre() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(STREAM_DRIVE);
        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480,
                STREAM_DRIVE);
        monitor.setPhysicalInput(false);
        registry.refuseTheDevicesBesideTheStream();

        monitor.setPhysicalInput(true, STREAM_DRIVE);
        InputLevelMeter afterTheRefusedWrite = monitor.snapshot();
        monitor.setPhysicalInput(true, Long.MAX_VALUE);
        InputLevelMeter afterTheRenderThreadsWrite = monitor.snapshot();
        monitor.setPhysicalInput(false);
        registry.liftAll(STREAM_DRIVE + 1);
        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480,
                STREAM_DRIVE + 1);
        monitor.setPhysicalInput(true, STREAM_DRIVE);
        InputLevelMeter afterTheOlderDrivesWrite = monitor.snapshot();
        monitor.setPhysicalInput(true, STREAM_DRIVE + 1);

        assertThat(afterTheRefusedWrite).as("the write of the refused devices' drive changes nothing (E205)")
                .isSameAs(InputLevelMeter.SILENCE);
        assertThat(afterTheRenderThreadsWrite.peakDbfs()).as("the render thread's write applies")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(afterTheOlderDrivesWrite).as("the write of an older drive changes nothing (E201)")
                .isSameAs(InputLevelMeter.SILENCE);
        assertThat(monitor.snapshot().peakDbfs()).as("the newer drive's write applies")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
    }

    /**
     * A delivery's physical-input write whose check came before the close stopped waiting lands in the state
     * that close replaced, which nothing shows: the person's edit to no input, made after that moment,
     * applies (ruling E205).
     */
    @Test
    void aPhysicalInputWriteCheckedBeforeTheCloseStoppedWaitingLeavesALaterEditToNoInputStanding() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(STREAM_DRIVE);
        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480,
                STREAM_DRIVE);
        InputLevelMeter beforeTheWrite = monitor.snapshot();

        monitor.setPhysicalInput(true, STREAM_DRIVE, () -> {
            registry.refuseTheDevicesBesideTheStream();
            monitor.setPhysicalInput(false);
        });

        assertThat(beforeTheWrite.peakDbfs()).as("premise: the meter shows the stream's level")
                .isCloseTo(-20.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(monitor.snapshot()).as("the edit to no input applies; the late write shows nothing")
                .isSameAs(InputLevelMeter.SILENCE);
    }

    /**
     * The same write, its check made before a newer drive's build, lands in the older drive's state, which
     * nothing shows: the person's edit to no input, made after that build, applies, and the clip latch the
     * newer state carries lights no LED on a track with no input (ruling E201).
     */
    @Test
    void aPhysicalInputWriteCheckedBeforeANewerBuildLeavesALaterEditToNoInputStanding() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("guitar");
        registry.liftAll(STREAM_DRIVE);
        monitor.processInputChannels(new float[][]{clipping()}, 0, 1, 64, STREAM_DRIVE);

        monitor.setPhysicalInput(true, STREAM_DRIVE, () -> {
            registry.liftAll(STREAM_DRIVE + 1);
            monitor.setPhysicalInput(false);
        });

        assertThat(monitor.isClippedSinceReset()).as("premise: the newer state carries the clip latch").isTrue();
        assertThat(monitor.snapshot()).as("the edit to no input applies: no level and no clip LED")
                .isSameAs(InputLevelMeter.SILENCE);
    }

    /** A block being measured when the close stops waiting completes into state nothing shows (ruling E205). */
    @Test
    void aBlockBeingMeasuredWhenTheCloseStopsWaitingShowsNothing() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("a");
        registry.liftAll(STREAM_DRIVE);
        monitor.processInputChannels(new float[][]{sineFromZero(MINUS_20_DBFS_LINEAR, 480)}, 0, 1, 480,
                STREAM_DRIVE);
        InputLevelMeter atTheGiveUp = monitor.snapshot();

        monitor.processInputChannels(new float[][]{clipping()}, 0, 1, 64, STREAM_DRIVE,
                registry::refuseTheDevicesBesideTheStream);

        assertThat(monitor.snapshot()).as("no level from the block").isSameAs(atTheGiveUp);
        assertThat(monitor.isClippedSinceReset()).as("and no clip").isFalse();
    }

    /**
     * A monitor created before the close stopped waiting or after it, before
     * any newer build, refuses that stream's writes: the refusal installs on
     * the one, and the other is constructed refusing (ruling E205).
     */
    @Test
    void aMonitorCreatedBeforeOrAfterTheCloseStoppedWaitingRefusesTheStreamsWrites() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        registry.liftAll(STREAM_DRIVE);
        InputLevelMonitor beforeTheRefusal = registry.getOrCreate("early");
        registry.refuseTheDevicesBesideTheStream();
        InputLevelMonitor afterTheRefusal = registry.getOrCreate("late");

        assertThatTheMonitorRefusesTheStreamsWrites(beforeTheRefusal);
        assertThatTheMonitorRefusesTheStreamsWrites(afterTheRefusal);
    }

    private static void assertThatTheMonitorRefusesTheStreamsWrites(InputLevelMonitor monitor) {
        InputLevelMeter atItsCreation = monitor.snapshot();
        monitor.processInputChannels(new float[][]{clipping()}, 0, 1, 64, STREAM_DRIVE);
        monitor.setRoutingUnavailable(true, STREAM_DRIVE);

        assertThat(monitor.snapshot()).as("no level").isSameAs(atItsCreation);
        assertThat(monitor.isClippedSinceReset()).as("no clip").isFalse();
        assertThat(monitor.isRoutingUnavailable()).as("no routing flag").isFalse();
        monitor.processInputChannels(new float[][]{loud()}, 0, 1, 64);
        assertThat(monitor.snapshot().peakDbfs()).as("the render thread's block is measured").isGreaterThan(-12.0);
    }

    /** A newer build after the close stopped waiting gives every monitor an ordinary state of its own drive. */
    @Test
    void aBuildAfterTheCloseStoppedWaitingMeasuresItsOwnDrivesBlocks() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor existing = registry.getOrCreate("existing");
        registry.liftAll(STREAM_DRIVE);
        registry.refuseTheDevicesBesideTheStream();
        registry.liftAll(STREAM_DRIVE + 1);
        InputLevelMonitor created = registry.getOrCreate("created");

        assertThatTheMonitorCarriesTheBuild(existing, STREAM_DRIVE + 1, STREAM_DRIVE);
        assertThatTheMonitorCarriesTheBuild(created, STREAM_DRIVE + 1, STREAM_DRIVE);
    }

    /**
     * What the stream measured before the close stopped waiting stays, and
     * its clip latch is carried into the next stream (ruling E185); a clear of
     * the latch or a routing edit made after that moment holds, and is carried
     * too (ruling E205).
     */
    @Test
    void whatTheStreamMeasuredBeforeTheCloseStoppedWaitingStaysAndAClearOrARoutingEditAfterItHolds() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor kept = registry.getOrCreate("kept");
        InputLevelMonitor cleared = registry.getOrCreate("cleared");
        InputLevelMonitor edited = registry.getOrCreate("edited");
        registry.liftAll(STREAM_DRIVE);
        kept.processInputChannels(new float[][]{clipping()}, 0, 1, 64, STREAM_DRIVE);
        cleared.processInputChannels(new float[][]{clipping()}, 0, 1, 64, STREAM_DRIVE);
        edited.setRoutingUnavailable(true, STREAM_DRIVE);

        registry.refuseTheDevicesBesideTheStream();
        boolean keptAtTheGiveUp = kept.isClippedSinceReset();
        cleared.reset();
        cleared.processInputChannels(new float[][]{clipping()}, 0, 1, 64, STREAM_DRIVE);
        edited.setRoutingUnavailable(false);
        boolean clearedBeforeTheNextStream = cleared.isClippedSinceReset();
        boolean editedBeforeTheNextStream = edited.isRoutingUnavailable();
        registry.liftAll(STREAM_DRIVE + 1);

        assertThat(keptAtTheGiveUp).as("the clip measured before that moment stays").isTrue();
        assertThat(kept.isClippedSinceReset()).as("and is carried into the next stream").isTrue();
        assertThat(clearedBeforeTheNextStream).as("a latch cleared after that moment stays clear").isFalse();
        assertThat(cleared.isClippedSinceReset()).as("in the next stream too").isFalse();
        assertThat(editedBeforeTheNextStream).as("a routing edit after that moment applies").isFalse();
        assertThat(edited.isRoutingUnavailable()).as("in the next stream too").isFalse();
    }

    @Test
    void aMonitorCreatedDuringTheLossIsMutedUntilANewDriveStarts() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        registry.getOrCreate("a");
        registry.silenceAll(LOST_DRIVE);

        InputLevelMonitor created = registry.getOrCreate("b");
        created.process(loud());
        assertThat(created.snapshot().peakDbfs()).as("muted by construction").isEqualTo(InputLevelMeter.DB_FLOOR);

        registry.liftAll(LOST_DRIVE + 1);
        created.process(loud());
        assertThat(created.snapshot().peakDbfs()).isGreaterThan(-12.0);
    }

    @Test
    void aLossOfAnUnknownDriveFloorsTheMetersWithoutHoldingThem() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        InputLevelMonitor monitor = registry.getOrCreate("a");
        monitor.process(loud());

        registry.silenceAll(-1L);
        assertThat(monitor.snapshot().peakDbfs()).isEqualTo(InputLevelMeter.DB_FLOOR);
        monitor.process(loud());

        assertThat(monitor.snapshot().peakDbfs()).isGreaterThan(-12.0);
    }

    private static float[] loud() {
        float[] block = new float[64];
        java.util.Arrays.fill(block, .5f);
        return block;
    }

    private static float[] clipping() {
        float[] block = new float[64];
        for (int i = 0; i < block.length; i++) {
            block[i] = (i % 2 == 0) ? 1.2f : -1.2f;
        }
        return block;
    }

    /** A 1 kHz sine at 48 kHz starting at 0. */
    private static float[] sineFromZero(double amplitude, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * 1_000.0 * i / 48_000.0));
        }
        return samples;
    }

    @Test
    void clearDiscardsAllMonitors() {
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        registry.getOrCreate("a");
        registry.getOrCreate("b");
        registry.clear();
        assertThat(registry.size()).isEqualTo(0);
    }
}
