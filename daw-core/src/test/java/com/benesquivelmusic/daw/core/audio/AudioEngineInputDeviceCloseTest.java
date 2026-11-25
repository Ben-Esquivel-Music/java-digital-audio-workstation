package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.analysis.InputLevelMonitor;
import com.benesquivelmusic.daw.core.analysis.InputLevelMonitorRegistry;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.analysis.InputLevelMeter;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.CaptureRequirement;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.SampleRate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.time.Duration;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.offset;

/**
 * Closing an input device opened beside the stream waits, bounded, for the
 * block that device is delivering (story 338, ruling E196): the close returns
 * only after that block has left its tap, so the next stream's first block is
 * measured with nothing of it; a delivery that outlasts the wait lets the
 * close return once the wait has passed, with a warning naming the device.
 * The close decides under the engine's graph lock whether such a delivery is
 * left behind: one that ends while the close waits for that lock is not left
 * behind and is measured as the closed stream's last block, except what it
 * writes after the same close left an earlier device's delivery behind, from
 * which the meters refuse every device beside the stream, and one still
 * inside its claim when the close takes the lock is left behind. From that decision nothing more of a
 * left-behind delivery shows on any input meter, the stopped stream's or the
 * next stream's, whether it completes before the next stream is built or
 * after (ruling E205); one completing after that build is also refused as a
 * block of an older drive (ruling E201). The delivery is held inside its
 * claim by the graph's track list, which the tap reads; another thread's
 * {@code setTracks} is held the same way to keep the graph lock. A held
 * thread waits until the test releases it, in a {@code finally} at the
 * latest; {@link #GUARD} bounds only the test thread's own waits.
 */
class AudioEngineInputDeviceCloseTest {

    private static final String BACKEND = "Two Devices";
    private static final Duration GUARD = Duration.ofSeconds(10);
    private static final double MINUS_20_DBFS = Math.pow(10.0, -20.0 / 20.0);

    @Test
    void theCloseWaitsOneSecondInAll() {
        assertThat(AudioEngine.INPUT_DELIVERY_WAIT_NANOS).isEqualTo(TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    @Timeout(60)
    void theCloseReturnsOnlyAfterTheBlockTheDeviceIsDeliveringAndTheNextStreamMeasuresItsFirstBlockOnItsOwn()
            throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        // The close's wait ends only with the delivery the test holds; half the range keeps its deadline,
        // System.nanoTime() plus the wait, within a long.
        engine.setInputDeliveryWaitNanos(Long.MAX_VALUE / 2);
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        HoldingTrackList graphTracks = new HoldingTrackList(near, far);
        engine.setGraph(new Transport(), null, graphTracks);
        List<Track> armed = List.of(near, far);
        engine.startAudioInputOutput(armed);
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> beside = backend.sibling.subscribers.getFirst();
        Thread delivery = Thread.ofPlatform().name("beside-the-stream delivery").unstarted(
                () -> beside.onNext(block(riseToTheCrestOfASlowSine(0.99))));
        AtomicReference<InputLevelMeter> atTheClose = new AtomicReference<>();
        CountDownLatch nextStreamStarted = new CountDownLatch(1);
        Thread closer = Thread.ofPlatform().name("record stop").unstarted(() -> {
            engine.stopAudioOutput();
            atTheClose.set(registry.get(far.getId()).snapshot());
            engine.startAudioInputOutput(armed);
            engine.pauseAudioOutput();
            nextStreamStarted.countDown();
        });
        try {
            graphTracks.holdIn(delivery);
            delivery.start();
            assertThat(graphTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();
            closer.start();
            // A close that does not wait returns at once, and the next stream starts before the delivery resumes.
            nextStreamStarted.await(300, TimeUnit.MILLISECONDS);
            graphTracks.release();
            assertThat(delivery.join(GUARD)).as("the delivery finished").isTrue();
            assertThat(closer.join(GUARD)).as("the close and the next start returned").isTrue();

            assertThat(atTheClose.get().peakDbfs())
                    .as("the close returned only after the delivered block reached the meter")
                    .isCloseTo(20.0 * Math.log10(0.99), offset(0.1));
            assertThat(atTheClose.get().clippedSinceReset()).as("premise: the delivered block does not clip")
                    .isFalse();

            backend.sibling.subscribers.getFirst().onNext(block(sineFromZero(Math.pow(10.0, -20.0 / 20.0), 256)));
            InputLevelMeter first = registry.get(far.getId()).snapshot();
            assertThat(first.peakDbfs()).as("the next stream's first block reads its own level")
                    .isCloseTo(-20.0, offset(0.5));
            assertThat(first.clippedSinceReset()).as("and latches no clip from the junction with the closed one")
                    .isFalse();
        } finally {
            graphTracks.release();
            delivery.join(GUARD);
            closer.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    @Test
    @Timeout(60)
    void aDeliveryHeldPastTheWaitLetsTheCloseReturnOnceTheWaitHasPassedWithAWarningNamingTheDevice()
            throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        long wait = TimeUnit.MILLISECONDS.toNanos(200);
        engine.setInputDeliveryWaitNanos(wait);
        engine.setInputLevelMonitorRegistry(new InputLevelMonitorRegistry());
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        HoldingTrackList graphTracks = new HoldingTrackList(near, far);
        engine.setGraph(new Transport(), null, graphTracks);
        engine.startAudioInputOutput(List.of(near, far));
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> beside = backend.sibling.subscribers.getFirst();
        Thread delivery = Thread.ofPlatform().name("hung beside-the-stream delivery").unstarted(
                () -> beside.onNext(block(riseToTheCrestOfASlowSine(0.99))));
        AtomicLong closeTook = new AtomicLong(-1L);
        Thread closer = Thread.ofPlatform().name("record stop").unstarted(() -> {
            long started = System.nanoTime();
            engine.stopAudioOutput();
            closeTook.set(System.nanoTime() - started);
        });
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) warnings.add(String.valueOf(record.getMessage()));
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger log = Logger.getLogger(AudioEngine.class.getName());
        log.addHandler(handler);
        try {
            graphTracks.holdIn(delivery);
            delivery.start();
            assertThat(graphTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();
            closer.start();

            assertThat(closer.join(GUARD)).as("the close returned while the delivery was still held").isTrue();
            assertThat(delivery.isAlive()).as("premise: the delivery is still held").isTrue();
            assertThat(closeTook.get()).as("the close returned once the wait had passed").isGreaterThanOrEqualTo(wait);
            assertThat(warnings).as("a warning names the device whose delivery outlasted the wait")
                    .anySatisfy(message -> assertThat(message).contains("Interface B"));
        } finally {
            log.removeHandler(handler);
            graphTracks.release();
            delivery.join(GUARD);
            closer.join(GUARD);
            engine.shutdown();
        }
    }

    /**
     * Ruling E201's check: a block the closed device was delivering when its
     * close stopped waiting, carrying a +0.5 dBTP inter-sample overshoot, is
     * released after the next stream was built and its first block, a
     * −20 dBFS sine starting at 0, was measured. The meter reads that first
     * block and no clip. The held block reaches the meters' check of its
     * render drive.
     */
    @Test
    @Timeout(60)
    void aBlockOfTheClosedDeviceThatOutlastedTheWaitShowsNothingOnTheNextStreamsMeter() throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        engine.setInputDeliveryWaitNanos(TimeUnit.MILLISECONDS.toNanos(200));
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        HoldingTrackList graphTracks = new HoldingTrackList(near, far);
        engine.setGraph(new Transport(), null, graphTracks);
        List<Track> armed = List.of(near, far);
        engine.startAudioInputOutput(armed);
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> closedDevice = backend.sibling.subscribers.getFirst();
        Thread delivery = Thread.ofPlatform().name("hung beside-the-stream delivery").unstarted(
                () -> closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp())));
        try {
            graphTracks.holdIn(delivery);
            delivery.start();
            assertThat(graphTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();
            engine.stopAudioOutput();
            assertThat(delivery.isAlive()).as("premise: the delivery outlasted the close's wait").isTrue();
            engine.startAudioInputOutput(armed);
            engine.pauseAudioOutput();
            backend.sibling.subscribers.getFirst().onNext(block(sineFromZero(Math.pow(10.0, -20.0 / 20.0), 256)));

            graphTracks.release();
            assertThat(delivery.join(GUARD)).as("the held delivery finished").isTrue();

            InputLevelMeter meter = registry.get(far.getId()).snapshot();
            assertThat(meter.peakDbfs()).as("the next stream's first block, nothing of the closed stream's (E201)")
                    .isCloseTo(-20.0, offset(0.5));
            assertThat(meter.clippedSinceReset()).as("and no clip from the closed stream's block").isFalse();
        } finally {
            graphTracks.release();
            delivery.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * A block of the closed device too narrow for the track's routing,
     * released after the next stream's first block, does not mark that
     * stream's routing unavailable.
     */
    @Test
    @Timeout(60)
    void aTooNarrowBlockOfTheClosedDeviceLeavesTheNextStreamsRoutingAlone() throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        engine.setInputDeliveryWaitNanos(TimeUnit.MILLISECONDS.toNanos(200));
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        far.setInputRouting(new InputRouting(0, 2));
        HoldingTrackList graphTracks = new HoldingTrackList(near, far);
        engine.setGraph(new Transport(), null, graphTracks);
        List<Track> armed = List.of(near, far);
        engine.startAudioInputOutput(armed);
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> closedDevice = backend.sibling.subscribers.getFirst();
        Thread delivery = Thread.ofPlatform().name("hung beside-the-stream delivery").unstarted(
                () -> closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp())));
        try {
            graphTracks.holdIn(delivery);
            delivery.start();
            assertThat(graphTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();
            engine.stopAudioOutput();
            assertThat(delivery.isAlive()).as("premise: the delivery outlasted the close's wait").isTrue();
            engine.startAudioInputOutput(armed);
            engine.pauseAudioOutput();
            float[] first = sineFromZero(Math.pow(10.0, -20.0 / 20.0), 256);
            backend.sibling.subscribers.getFirst().onNext(stereo(first, first));

            graphTracks.release();
            assertThat(delivery.join(GUARD)).as("the held delivery finished").isTrue();

            InputLevelMonitor monitor = registry.get(far.getId());
            assertThat(monitor.isRoutingUnavailable()).as("the closed device's mono block marks nothing").isFalse();
            assertThat(monitor.snapshot()).as("the next stream's level stays").isNotSameAs(InputLevelMeter.SILENCE);
            assertThat(monitor.snapshot().peakDbfs()).isCloseTo(-20.0, offset(0.5));
        } finally {
            graphTracks.release();
            delivery.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * Ruling E205's check: from the moment the close stops waiting for a block
     * a device beside the stream is still delivering, the meters take nothing
     * more from that device, with or without a newer stream. The held block, a
     * +0.5 dBTP inter-sample overshoot, fits Near, on the stream's own
     * interface, Far, on the device's own, and Late, whose monitor the test
     * removes before the stop so that the close's own routing refresh creates
     * it after the close stopped waiting, and is too narrow for Wide, on the
     * stream's own interface. Released with no new stream, it leaves every
     * meter as it was at the close, with the clip latch the person cleared on
     * Far after the close still clear; the next stream starts at the floor
     * with no latch and no routing flag, and its −20 dBFS blocks read
     * −20 dBFS.
     */
    @Test
    @Timeout(60)
    void aDeliveryTheCloseStoppedWaitingForShowsNothingMoreOnTheStoppedStreamsMetersOrTheNextStreams()
            throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        engine.setInputDeliveryWaitNanos(TimeUnit.MILLISECONDS.toNanos(200));
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track wide = track("Wide", "Interface A");
        wide.setInputRouting(new InputRouting(0, 2));
        Track far = track("Far", "Interface B");
        Track late = track("Late", "Interface B");
        List<Track> armed = List.of(near, wide, far, late);
        HoldingTrackList graphTracks = new HoldingTrackList(near, wide, far, late);
        engine.setGraph(new Transport(), new Mixer(), graphTracks);
        engine.startAudioInputOutput(armed);
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> closedDevice = backend.sibling.subscribers.getFirst();
        closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp()));
        // Two blocks, so the second reads its own level with no overshoot left in the true-peak history.
        closedDevice.onNext(block(sineFromZero(MINUS_20_DBFS, 256)));
        closedDevice.onNext(block(sineFromZero(MINUS_20_DBFS, 256)));
        registry.remove(late.getId());
        Thread delivery = Thread.ofPlatform().name("hung beside-the-stream delivery").unstarted(
                () -> closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp())));
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = warningsInto(warnings);
        Logger log = Logger.getLogger(AudioEngine.class.getName());
        log.addHandler(handler);
        try {
            graphTracks.holdIn(delivery);
            delivery.start();
            assertThat(graphTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();
            engine.stopAudioOutput();
            assertThat(delivery.isAlive()).as("premise: the close stopped waiting for the delivery").isTrue();
            List<MeterReading> atTheClose = readings(registry, armed);
            registry.get(far.getId()).reset();
            List<MeterReading> afterTheClear = readings(registry, armed);
            assertThat(atTheClose.get(2).clipped()).as("premise: Far's clip, measured before the close, stays")
                    .isTrue();
            assertThat(atTheClose.get(2).snapshot().peakDbfs()).as("premise: and Far shows its last level")
                    .isCloseTo(-20.0, offset(0.5));
            assertThat(atTheClose).extracting(MeterReading::clipped).as("premise: no other latch at the close")
                    .containsExactly(false, false, true, false);
            assertThat(atTheClose).extracting(MeterReading::routingUnavailable)
                    .as("premise: no routing flag at the close").containsOnly(false);

            graphTracks.release();
            assertThat(delivery.join(GUARD)).as("the held delivery finished").isTrue();

            assertThat(readings(registry, armed))
                    .as("the stopped stream's meters show what they showed, Far's cleared latch included (E205)")
                    .isEqualTo(afterTheClear);
            assertThat(warnings).filteredOn(message -> message.contains("still delivering")).singleElement()
                    .asString().as("one warning, in the ruling's words")
                    .startsWith("Input device '").contains("Interface B")
                    .endsWith("' was still delivering a block when its close stopped waiting for it;"
                            + " the input meters show nothing more from it");

            engine.startAudioInputOutput(armed);
            engine.pauseAudioOutput();
            for (MeterReading atTheStart : readings(registry, armed)) {
                assertThat(atTheStart.snapshot().peakDbfs()).as("the next stream starts at the floor")
                        .isEqualTo(InputLevelMeter.DB_FLOOR);
                assertThat(atTheStart.clipped()).as("with no latch").isFalse();
                assertThat(atTheStart.routingUnavailable()).as("and no routing flag").isFalse();
            }
            float[] first = sineFromZero(MINUS_20_DBFS, 256);
            engine.processBlock(new float[][]{first, first}, new float[2][256], 256);
            backend.sibling.subscribers.getFirst().onNext(block(first));
            for (MeterReading measured : readings(registry, armed)) {
                assertThat(measured.snapshot().peakDbfs()).as("its own blocks read their level")
                        .isCloseTo(-20.0, offset(0.5));
                assertThat(measured.clipped()).as("with no clip").isFalse();
                assertThat(measured.routingUnavailable()).as("and no routing flag").isFalse();
            }
        } finally {
            log.removeHandler(handler);
            graphTracks.release();
            delivery.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * Ruling E205 at the moment itself: the close's first call out after it
     * stopped waiting is the device's subscription cancel, and the delivery
     * the close left behind is released there and runs to its end before the
     * close goes on. The held block, mono with a +0.5 dBTP inter-sample
     * overshoot, fits Far and is too narrow for Narrow, both on the device's
     * own interface; before the stop each shows a −20 dBFS level from the
     * device's stereo blocks, no latch and no routing flag. After the close
     * Far's and Narrow's meters each show exactly that.
     */
    @Test
    @Timeout(60)
    void aDeliveryResumingAtTheClosesFirstCallOutAfterItStoppedWaitingShowsNothingOnTheMeters() throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        engine.setInputDeliveryWaitNanos(TimeUnit.MILLISECONDS.toNanos(200));
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        Track narrow = track("Narrow", "Interface B");
        narrow.setInputRouting(new InputRouting(0, 2));
        HoldingTrackList graphTracks = new HoldingTrackList(near, far, narrow);
        engine.setGraph(new Transport(), new Mixer(), graphTracks);
        engine.startAudioInputOutput(List.of(near, far, narrow));
        engine.pauseAudioOutput();
        TwoDevices device = backend.sibling;
        Flow.Subscriber<? super AudioBlock> closedDevice = device.subscribers.getFirst();
        float[] quiet = sineFromZero(MINUS_20_DBFS, 256);
        closedDevice.onNext(stereo(quiet, quiet));
        closedDevice.onNext(stereo(quiet, quiet));
        List<Track> beside = List.of(far, narrow);
        List<MeterReading> beforeTheStop = readings(registry, beside);
        Thread delivery = Thread.ofPlatform().name("hung beside-the-stream delivery").unstarted(
                () -> closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp())));
        AtomicReference<Boolean> finishedAtTheCancel = new AtomicReference<>();
        device.onCancel(() -> {
            graphTracks.release();
            finishedAtTheCancel.set(joinWithin(delivery));
        });
        try {
            assertThat(beforeTheStop).extracting(reading -> reading.snapshot().peakDbfs())
                    .as("premise: both meters show the device's last level")
                    .allSatisfy(peak -> assertThat(peak).isCloseTo(-20.0, offset(0.5)));
            assertThat(beforeTheStop).extracting(MeterReading::clipped).as("premise: no latch").containsOnly(false);
            assertThat(beforeTheStop).extracting(MeterReading::routingUnavailable).as("premise: no routing flag")
                    .containsOnly(false);
            graphTracks.holdIn(delivery);
            delivery.start();
            assertThat(graphTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();

            engine.stopAudioOutput();

            assertThat(finishedAtTheCancel.get()).as("premise: the delivery ran to its end at the cancel").isTrue();
            assertThat(readings(registry, beside))
                    .as("nothing of the delivery shows: no level, no clip LED, no routing flag (E205)")
                    .isEqualTo(beforeTheStop);
        } finally {
            graphTracks.release();
            delivery.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * Ruling E205 at the decision: the wait passing with the delivery's claim
     * held decides nothing on its own. With a wait of zero, the close finds the
     * claim held at once and then waits for the graph lock, which another
     * thread's {@code setTracks} holds; the delivery, a +0.5 dBTP inter-sample
     * overshoot for Far, ends during that wait. It was the closed stream's last
     * block: Far shows its level and its latched clip, and nothing is left
     * behind, so no warning is logged.
     */
    @Test
    @Timeout(60)
    void aDeliveryThatEndsWhileTheCloseWaitsForTheGraphLockIsMeasuredAndLeavesNothingBehind() throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        engine.setInputDeliveryWaitNanos(0L);
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        HoldingTrackList deliveryTracks = new HoldingTrackList(near, far);
        HoldingTrackList lockedTracks = new HoldingTrackList(near, far);
        engine.setGraph(new Transport(), null, deliveryTracks);
        engine.startAudioInputOutput(List.of(near, far));
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> closedDevice = backend.sibling.subscribers.getFirst();
        Thread delivery = Thread.ofPlatform().name("beside-the-stream delivery").unstarted(
                () -> closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp())));
        Thread holder = Thread.ofPlatform().name("graph lock holder").unstarted(() -> engine.setTracks(lockedTracks));
        Thread closer = Thread.ofPlatform().name("record stop").unstarted(engine::stopAudioOutput);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = warningsInto(warnings);
        Logger log = Logger.getLogger(AudioEngine.class.getName());
        log.addHandler(handler);
        try {
            InputLevelMeter beforeTheDelivery = registry.get(far.getId()).snapshot();
            deliveryTracks.holdIn(delivery);
            delivery.start();
            assertThat(deliveryTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();
            lockedTracks.holdIn(holder);
            holder.start();
            assertThat(lockedTracks.awaitHeld()).as("premise: another thread holds the graph lock").isTrue();
            closer.start();
            awaitBlockedIn(closer, "closeAdditionalInputs");

            deliveryTracks.release();
            assertThat(delivery.join(GUARD)).as("premise: the delivery ended while the close waited for the lock")
                    .isTrue();
            lockedTracks.release();
            assertThat(holder.join(GUARD)).as("premise: the graph lock was let go").isTrue();
            assertThat(closer.join(GUARD)).as("the close returned").isTrue();

            InputLevelMeter afterTheClose = registry.get(far.getId()).snapshot();
            assertThat(beforeTheDelivery.peakDbfs()).as("premise: Far showed no level before the delivery")
                    .isEqualTo(InputLevelMeter.DB_FLOOR);
            assertThat(afterTheClose.peakDbfs()).as("the delivery was measured as the closed stream's last block")
                    .isCloseTo(0.5, offset(0.5));
            assertThat(afterTheClose.clippedSinceReset()).as("its clip latched").isTrue();
            assertThat(warnings).as("and nothing was left behind")
                    .noneMatch(message -> message.contains("still delivering"));
        } finally {
            log.removeHandler(handler);
            deliveryTracks.release();
            lockedTracks.release();
            delivery.join(GUARD);
            holder.join(GUARD);
            closer.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * The other side of that decision, with the same graph lock held: a
     * delivery still inside its claim when the close takes the lock is left
     * behind. Released after the close returned, its mono +0.5 dBTP block, which
     * fits Far and is too narrow for Narrow, shows nothing: Far's and Narrow's
     * meters each read what they read before the stop, and the close warns
     * once.
     */
    @Test
    @Timeout(60)
    void aDeliveryStillInsideItsClaimWhenTheCloseTakesTheGraphLockIsLeftBehindAndShowsNothing() throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        engine.setInputDeliveryWaitNanos(0L);
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        Track narrow = track("Narrow", "Interface B");
        narrow.setInputRouting(new InputRouting(0, 2));
        HoldingTrackList deliveryTracks = new HoldingTrackList(near, far, narrow);
        HoldingTrackList lockedTracks = new HoldingTrackList(near, far, narrow);
        engine.setGraph(new Transport(), null, deliveryTracks);
        engine.startAudioInputOutput(List.of(near, far, narrow));
        engine.pauseAudioOutput();
        Flow.Subscriber<? super AudioBlock> closedDevice = backend.sibling.subscribers.getFirst();
        float[] quiet = sineFromZero(MINUS_20_DBFS, 256);
        closedDevice.onNext(stereo(quiet, quiet));
        closedDevice.onNext(stereo(quiet, quiet));
        List<Track> beside = List.of(far, narrow);
        List<MeterReading> beforeTheStop = readings(registry, beside);
        Thread delivery = Thread.ofPlatform().name("hung beside-the-stream delivery").unstarted(
                () -> closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp())));
        Thread holder = Thread.ofPlatform().name("graph lock holder").unstarted(() -> engine.setTracks(lockedTracks));
        Thread closer = Thread.ofPlatform().name("record stop").unstarted(engine::stopAudioOutput);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = warningsInto(warnings);
        Logger log = Logger.getLogger(AudioEngine.class.getName());
        log.addHandler(handler);
        try {
            assertThat(beforeTheStop).extracting(reading -> reading.snapshot().peakDbfs())
                    .as("premise: both meters show the device's last level")
                    .allSatisfy(peak -> assertThat(peak).isCloseTo(-20.0, offset(0.5)));
            deliveryTracks.holdIn(delivery);
            delivery.start();
            assertThat(deliveryTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();
            lockedTracks.holdIn(holder);
            holder.start();
            assertThat(lockedTracks.awaitHeld()).as("premise: another thread holds the graph lock").isTrue();
            closer.start();
            awaitBlockedIn(closer, "closeAdditionalInputs");

            lockedTracks.release();
            assertThat(holder.join(GUARD)).as("premise: the graph lock was let go").isTrue();
            assertThat(closer.join(GUARD)).as("the close returned").isTrue();
            assertThat(delivery.isAlive()).as("premise: the delivery was inside its claim throughout the close")
                    .isTrue();
            deliveryTracks.release();
            assertThat(delivery.join(GUARD)).as("the left-behind delivery finished").isTrue();

            assertThat(readings(registry, beside))
                    .as("nothing of the left-behind delivery shows: no level, no clip LED, no routing flag (E205)")
                    .isEqualTo(beforeTheStop);
            assertThat(warnings).filteredOn(message -> message.contains("still delivering")).singleElement()
                    .asString().as("one warning, naming the device").contains("Interface B");
        } finally {
            log.removeHandler(handler);
            deliveryTracks.release();
            lockedTracks.release();
            delivery.join(GUARD);
            holder.join(GUARD);
            closer.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * The warning for a delivery left behind does not depend on the rest of
     * the close: here the cancel of that device's subscription, which comes
     * after the decision, throws, and the warning is logged all the same.
     */
    @Test
    @Timeout(60)
    void aDeliveryLeftBehindIsWarnedOfEvenWhenTheCancelOfItsSubscriptionThrows() throws Exception {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        engine.setInputDeliveryWaitNanos(0L);
        engine.setInputLevelMonitorRegistry(new InputLevelMonitorRegistry());
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        HoldingTrackList graphTracks = new HoldingTrackList(near, far);
        engine.setGraph(new Transport(), null, graphTracks);
        engine.startAudioInputOutput(List.of(near, far));
        engine.pauseAudioOutput();
        TwoDevices device = backend.sibling;
        Flow.Subscriber<? super AudioBlock> closedDevice = device.subscribers.getFirst();
        Thread delivery = Thread.ofPlatform().name("hung beside-the-stream delivery").unstarted(
                () -> closedDevice.onNext(block(interSamplePeakAtPlusHalfDbTp())));
        IllegalStateException cancelFailure = new IllegalStateException("the device's cancel failed");
        device.onCancel(() -> {
            throw cancelFailure;
        });
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = warningsInto(warnings);
        Logger log = Logger.getLogger(AudioEngine.class.getName());
        log.addHandler(handler);
        try {
            graphTracks.holdIn(delivery);
            delivery.start();
            assertThat(graphTracks.awaitHeld()).as("premise: the delivery is held in the tap, inside its claim")
                    .isTrue();

            Throwable thrown = catchThrowable(engine::stopAudioOutput);

            assertThat(thrown).as("premise: the close threw the cancel's failure").isSameAs(cancelFailure);
            assertThat(delivery.isAlive()).as("premise: the delivery was left behind").isTrue();
            assertThat(warnings).filteredOn(message -> message.contains("still delivering")).singleElement()
                    .asString().as("the warning naming the device was logged").contains("Interface B");
        } finally {
            device.onCancel(() -> { });
            log.removeHandler(handler);
            graphTracks.release();
            delivery.join(GUARD);
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /**
     * Every meter write of the tap passes the block's render drive, the
     * physical-input flag's included, so the monitor's refusal of a newer
     * drive's state (ruling E201) or of the input devices beside a stream
     * (ruling E205) covers each of them: a write of a device beside the
     * stream that finds a newer drive's or a refusing state is refused, and
     * one whose check came before that state's install lands in the state it
     * replaced, which nothing shows; what a left-behind delivery wrote to a
     * meter before that meter's install stays
     * ({@code refuseTheDevicesBesideTheStreamOnTheInputMeters}). The overloads
     * without a drive are the control thread's.
     */
    @Test
    void everyMeterWriteOfTheTapPassesTheBlocksDrive() throws IOException {
        ClassModel engine;
        try (InputStream bytes = AudioEngine.class.getResourceAsStream("AudioEngine.class")) {
            assertThat(bytes).as("AudioEngine's class file").isNotNull();
            engine = ClassFile.of().parse(bytes.readAllBytes());
        }
        MethodModel tap = engine.methods().stream()
                .filter(method -> method.methodName().equalsString("tapInputSource"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AudioEngine#tapInputSource is gone"));
        List<String> meterWrites = tap.code().orElseThrow().elementStream()
                .flatMap(element -> element instanceof InvokeInstruction invoke
                        && invoke.owner().asInternalName().equals(INPUT_LEVEL_MONITOR)
                        ? java.util.stream.Stream.of(invoke.name().stringValue() + invoke.type().stringValue())
                        : java.util.stream.Stream.empty())
                .distinct()
                .toList();

        assertThat(meterWrites).containsExactlyInAnyOrder("setPhysicalInput(ZJ)V", "setRoutingUnavailable(ZJ)V",
                "processInputChannels([[FIIIJ)V");
    }

    private static final String INPUT_LEVEL_MONITOR = "com/benesquivelmusic/daw/core/analysis/InputLevelMonitor";

    /** Polls {@code thread} until it is blocked on a monitor inside {@code method}; {@link #GUARD} bounds the poll. */
    private static void awaitBlockedIn(Thread thread, String method) throws InterruptedException {
        long deadline = System.nanoTime() + GUARD.toNanos();
        while (thread.getState() != Thread.State.BLOCKED || !isOnTheStack(thread, method)) {
            assertThat(System.nanoTime() - deadline).as("%s blocked inside %s", thread.getName(), method)
                    .isNegative();
            Thread.sleep(1);
        }
    }

    private static boolean isOnTheStack(Thread thread, String method) {
        return Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals(method));
    }

    private static boolean joinWithin(Thread thread) {
        try {
            return thread.join(GUARD);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Ruling E175 (2), before any newer build: a block the lost stream's
     * beside device still delivers shows no level, and its clip latches.
     */
    @Test
    @Timeout(60)
    void aClipInABlockOfTheLostStreamLatchesUnderTheMuteBeforeAnyNewerBuild() {
        TwoDevices backend = new TwoDevices();
        AudioEngine engine = engine(backend);
        InputLevelMonitorRegistry registry = new InputLevelMonitorRegistry();
        engine.setInputLevelMonitorRegistry(registry);
        Track near = track("Near", "Interface A");
        Track far = track("Far", "Interface B");
        engine.setGraph(new Transport(), null, List.of(near, far));
        try {
            engine.startAudioInputOutput(List.of(near, far));
            engine.pauseAudioOutput();
            registry.silenceAll(engine.audioHealth().renderDriveEpoch());

            backend.sibling.subscribers.getFirst().onNext(block(interSamplePeakAtPlusHalfDbTp()));

            InputLevelMeter meter = registry.get(far.getId()).snapshot();
            assertThat(meter.peakDbfs()).as("the lost stream's block shows no level").isEqualTo(InputLevelMeter.DB_FLOOR);
            assertThat(meter.clippedSinceReset()).as("and its clip latches").isTrue();
        } finally {
            engine.stopAudioOutput();
            engine.shutdown();
        }
    }

    /** What one track's input meter shows: its snapshot, its clip latch and its routing flag. */
    private record MeterReading(InputLevelMeter snapshot, boolean clipped, boolean routingUnavailable) {
    }

    private static List<MeterReading> readings(InputLevelMonitorRegistry registry, List<Track> tracks) {
        return tracks.stream().map(track -> registry.get(track.getId()))
                .map(monitor -> new MeterReading(monitor.snapshot(), monitor.isClippedSinceReset(),
                        monitor.isRoutingUnavailable()))
                .toList();
    }

    private static Handler warningsInto(List<String> warnings) {
        return new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) warnings.add(String.valueOf(record.getMessage()));
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
    }

    private static AudioEngine engine(TwoDevices backend) {
        AudioEngine engine = new AudioEngine(new AudioFormat(48_000, 2, 16, 256));
        engine.setStreamingProvision(new StreamingProvision(backend.name(),
                List.of(new BackendStreamRung(backend, new DeviceId(BACKEND, "Interface A")))));
        return engine;
    }

    private static Track track(String name, String device) {
        Track track = new Track(name, TrackType.AUDIO);
        track.setInputRouting(new InputRouting(0, 1));
        track.setInputDevice(Optional.of(new DeviceId(BACKEND, device + " [Test]")));
        track.setArmed(true);
        return track;
    }

    private static AudioBlock block(float[] samples) {
        return new AudioBlock(48_000, 1, samples.length, samples);
    }

    private static AudioBlock stereo(float[] left, float[] right) {
        float[] interleaved = new float[left.length * 2];
        for (int f = 0; f < left.length; f++) {
            interleaved[2 * f] = left[f];
            interleaved[2 * f + 1] = right[f];
        }
        return new AudioBlock(48_000, 2, left.length, interleaved);
    }

    /** Every sample under 0 dBFS, the true peak +0.5 dBTP: a fs/4 sine sampled at phase π/4. */
    private static float[] interSamplePeakAtPlusHalfDbTp() {
        double amplitude = Math.pow(10.0, 0.5 / 20.0);
        float[] samples = new float[256];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (amplitude * Math.sin(i * (Math.PI / 2.0) + Math.PI / 4.0));
        }
        return samples;
    }

    /** A quarter period of a 100 Hz sine at 48 kHz, from 0 to its crest at {@code amplitude}. */
    private static float[] riseToTheCrestOfASlowSine(double amplitude) {
        float[] samples = new float[121];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * 100.0 * i / 48_000.0));
        }
        return samples;
    }

    /** A 1 kHz sine at 48 kHz starting at 0. */
    private static float[] sineFromZero(double amplitude, int length) {
        float[] samples = new float[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * 1_000.0 * i / 48_000.0));
        }
        return samples;
    }

    /**
     * A graph's track list: the held thread's first read of it waits, wherever that thread reads it, until the
     * test releases it; the wait has no timeout of its own.
     */
    private static final class HoldingTrackList extends AbstractList<Track> {
        private final List<Track> tracks;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile Thread heldThread;

        HoldingTrackList(Track... tracks) {
            this.tracks = List.of(tracks);
        }

        void holdIn(Thread thread) {
            heldThread = thread;
        }

        boolean awaitHeld() throws InterruptedException {
            return held.await(GUARD.toMillis(), TimeUnit.MILLISECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override public Track get(int index) {
            if (Thread.currentThread() == heldThread && held.getCount() > 0) {
                held.countDown();
                try {
                    released.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return tracks.get(index);
        }

        @Override public int size() {
            return tracks.size();
        }
    }

    /** One backend for two input devices, each delivering only the blocks a test hands its subscriber. */
    private static final class TwoDevices implements AudioBackend {
        final List<Flow.Subscriber<? super AudioBlock>> subscribers = new CopyOnWriteArrayList<>();
        volatile TwoDevices sibling;
        private volatile boolean open;
        private volatile int openedWidth;
        private volatile Runnable cancelHook = () -> { };

        /** Runs {@code hook} on the cancelling thread at every cancel of a subscription to this device. */
        void onCancel(Runnable hook) {
            cancelHook = hook;
        }

        @Override public String name() { return BACKEND; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean isOpen() { return open; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public boolean supportsMultipleInputDevices() { return true; }
        @Override public boolean sharesClockDomain(DeviceId first, DeviceId second) { return true; }

        @Override public List<AudioDeviceInfo> listDevices() {
            return List.of(device(0, "Interface A"), device(1, "Interface B"));
        }

        private static AudioDeviceInfo device(int index, String name) {
            return new AudioDeviceInfo(index, name, "Test", 2, 2, 48_000, List.of(SampleRate.HZ_48000), 0, 0);
        }

        @Override public void open(DeviceId device, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                   int frames) {
            open(device, format, frames, CaptureRequirement.REQUIRED, device, format.channels());
        }

        @Override public void open(DeviceId output, com.benesquivelmusic.daw.sdk.audio.AudioFormat format, int frames,
                                   CaptureRequirement capture, DeviceId input, int width) {
            openedWidth = Math.min(2, width);
            open = true;
        }

        @Override public void openInput(DeviceId input, com.benesquivelmusic.daw.sdk.audio.AudioFormat format,
                                        int frames, int width) {
            openedWidth = Math.min(2, width);
            open = true;
        }

        @Override public int openedInputChannels() { return open ? openedWidth : 0; }
        @Override public double openedInputSampleRate() { return 48_000; }

        @Override public AudioBackend createInputBackend() {
            sibling = new TwoDevices();
            return sibling;
        }

        @Override public Flow.Publisher<AudioBlock> inputBlocks() {
            return subscriber -> {
                subscribers.add(subscriber);
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { }
                    @Override public void cancel() {
                        subscribers.remove(subscriber);
                        cancelHook.run();
                    }
                });
            };
        }

        @Override public void sink(AudioBlock block) { }
        @Override public void close() { open = false; }
    }
}
