package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.audio.AudioEngineOutputTest.SynchronousTestBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AudioEngine#lastOpenedStream()}: one record per open, published
 * before the stream reads as open and replaced only by a later open's, so it
 * stays through a pause, a resume, a close and a start that fails after its
 * open, and its serial only rises.
 */
class AudioEngineOpenedStreamTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 512);

    private AudioEngine engine;
    private SynchronousTestBackend backend;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        backend = new SynchronousTestBackend("Stub");
        engine.setStreamingProvision(new StreamingProvision("Stub",
                List.of(new BackendStreamRung(backend, DeviceId.defaultFor("Stub")))));
    }

    @AfterEach
    void tearDown() {
        engine.stopAudioOutput();
        engine.stop();
    }

    @Test
    void nothingIsPublishedBeforeTheFirstOpen() {
        assertThat(engine.lastOpenedStream()).isEmpty();
    }

    @Test
    void anOpenPublishesTheDeviceItOpenedAndTheEnginesRate() {
        engine.startAudioOutput();

        OpenedStream opened = engine.lastOpenedStream().orElseThrow();
        assertThat(opened.device()).isEqualTo(engine.openStreamDevice().orElseThrow());
        assertThat(opened.sampleRate()).isEqualTo(44_100);
    }

    @Test
    void aPauseAndAResumeAreTheSameStream() {
        engine.startAudioOutput();
        OpenedStream opened = engine.lastOpenedStream().orElseThrow();

        engine.pauseAudioOutput();
        Optional<OpenedStream> paused = engine.lastOpenedStream();
        engine.startAudioOutput();

        assertThat(backend.openCount.get()).as("premise: the resume did not reopen").isEqualTo(1);
        assertThat(paused).as("a pause keeps the stream").contains(opened);
        assertThat(engine.lastOpenedStream()).as("a resume publishes nothing").contains(opened);
    }

    @Test
    void aStopKeepsTheRecordAndTheNextOpenHasAGreaterSerial() {
        engine.startAudioOutput();
        OpenedStream first = engine.lastOpenedStream().orElseThrow();
        engine.stopAudioOutput();
        Optional<OpenedStream> afterTheStop = engine.lastOpenedStream();

        engine.startAudioOutput();
        OpenedStream second = engine.lastOpenedStream().orElseThrow();

        assertThat(afterTheStop).as("the record survives the close").contains(first);
        assertThat(second.serial()).as("a later open has a greater serial").isGreaterThan(first.serial());
        assertThat(second.device()).isEqualTo(first.device());
    }

    /**
     * The record is published before the stream reads as open and replaced
     * only by a later open's: whoever sees the stream open and then reads the
     * record gets that stream's or a later one, never an earlier one.
     */
    @Test
    void theRecordIsTheNewStreamsWhenTheEngineStartsTheOpenedStream() {
        engine.startAudioOutput();
        OpenedStream first = engine.lastOpenedStream().orElseThrow();
        engine.stopAudioOutput();
        AtomicBoolean openWhenStarted = new AtomicBoolean();
        AtomicReference<Optional<OpenedStream>> seenWhenStarted = new AtomicReference<>();
        backend.pumpStartBarrier = () -> {
            openWhenStarted.set(engine.isStreamOpen());
            seenWhenStarted.set(engine.lastOpenedStream());
        };

        engine.startAudioOutput();

        assertThat(openWhenStarted).as("premise: the stream already reads as open there").isTrue();
        assertThat(seenWhenStarted.get()).as("published before the stream reads as open")
                .contains(engine.lastOpenedStream().orElseThrow());
        assertThat(seenWhenStarted.get().orElseThrow().serial()).isGreaterThan(first.serial());
    }

    /**
     * A stream whose start fails after its open opened and closed at once
     * (ruling E143): the record is that stream's, on the device it opened,
     * with a serial greater than the earlier open's.
     */
    @Test
    void aStreamWhoseStartFailsAfterItsOpenLeavesItsOwnRecord() {
        engine.startAudioOutput();
        OpenedStream first = engine.lastOpenedStream().orElseThrow();
        engine.stopAudioOutput();
        engine.stop();
        SynchronousTestBackend other = new SynchronousTestBackend("Other");
        DeviceId otherDevice = new DeviceId("Other", "Interface B");
        engine.setStreamingProvision(new StreamingProvision("Other",
                List.of(new BackendStreamRung(other, otherDevice))));
        other.failOnPumpStart = true;

        assertThatThrownBy(engine::startAudioOutput).isInstanceOf(AudioBackendException.class);

        OpenedStream failed = engine.lastOpenedStream().orElseThrow();
        assertThat(other.openCount.get()).as("premise: the stream was opened").isEqualTo(1);
        assertThat(engine.isStreamOpen()).as("premise: its start failed").isFalse();
        assertThat(failed.serial()).as("the failed stream's own serial").isGreaterThan(first.serial());
        assertThat(failed.device()).as("the device that opened").isEqualTo(otherDevice)
                .isEqualTo(other.lastOpenDevice);
    }

    @Test
    void aFirstStreamWhoseStartFailsLeavesItsOwnRecord() {
        backend.failOnPumpStart = true;

        assertThatThrownBy(engine::startAudioOutput).isInstanceOf(AudioBackendException.class);

        assertThat(backend.openCount.get()).as("premise: the stream was opened").isEqualTo(1);
        assertThat(engine.lastOpenedStream()).as("the failed stream's own record")
                .hasValueSatisfying(record -> assertThat(record.device()).isEqualTo(backend.lastOpenDevice));
    }

    /**
     * A reader inside a start that fails after its open sees the stream open
     * and that stream's record, and the same record is published after the
     * failure (ruling E143).
     */
    @Test
    void aReaderInsideAFailingStartSeesTheRecordThatStaysAfterTheFailure() {
        engine.startAudioOutput();
        OpenedStream first = engine.lastOpenedStream().orElseThrow();
        engine.stopAudioOutput();
        backend.failOnPumpStart = true;
        AtomicBoolean openInTheStart = new AtomicBoolean();
        AtomicReference<Optional<OpenedStream>> seenInTheStart = new AtomicReference<>();
        backend.pumpStartBarrier = () -> {
            openInTheStart.set(engine.isStreamOpen());
            seenInTheStart.set(engine.lastOpenedStream());
        };

        assertThatThrownBy(engine::startAudioOutput).isInstanceOf(AudioBackendException.class);

        assertThat(openInTheStart).as("premise: the stream read as open inside its start").isTrue();
        assertThat(engine.isStreamOpen()).as("premise: its start failed").isFalse();
        assertThat(seenInTheStart.get().orElseThrow().serial()).as("the failing stream's own record")
                .isGreaterThan(first.serial());
        assertThat(engine.lastOpenedStream()).as("the record the reader saw stays after the failure")
                .isEqualTo(seenInTheStart.get());
    }

    /**
     * Read after every step (an open, a stop, a start that fails after its
     * open, and an open) and inside the failing start.
     */
    @Test
    void theSerialNeverDecreasesOverAnOpenAStopAFailedStartAndAnOpen() {
        List<Long> serials = new ArrayList<>();
        engine.startAudioOutput();
        serials.add(engine.lastOpenedStream().orElseThrow().serial());
        engine.stopAudioOutput();
        serials.add(engine.lastOpenedStream().orElseThrow().serial());
        backend.failOnPumpStart = true;
        backend.pumpStartBarrier = () -> serials.add(engine.lastOpenedStream().orElseThrow().serial());
        assertThatThrownBy(engine::startAudioOutput).isInstanceOf(AudioBackendException.class);
        serials.add(engine.lastOpenedStream().orElseThrow().serial());
        backend.failOnPumpStart = false;
        engine.startAudioOutput();
        serials.add(engine.lastOpenedStream().orElseThrow().serial());

        assertThat(serials).as("premise: read five times").hasSize(5);
        assertThat(serials).as("never decreasing").isSorted();
        assertThat(serials.get(2)).as("the failing start published its own").isGreaterThan(serials.get(1));
        assertThat(serials.get(3)).as("and it stays after the failure").isEqualTo(serials.get(2));
        assertThat(serials.get(4)).as("the next open its own").isGreaterThan(serials.get(3));
    }

    /** A failed start does not give its serial back: the next open's serial was never used. */
    @Test
    void aSerialIsNeverReusedAfterAFailedStart() {
        engine.startAudioOutput();
        OpenedStream first = engine.lastOpenedStream().orElseThrow();
        engine.stopAudioOutput();
        backend.failOnPumpStart = true;
        AtomicReference<Optional<OpenedStream>> seenByTheFailedStart = new AtomicReference<>();
        backend.pumpStartBarrier = () -> seenByTheFailedStart.set(engine.lastOpenedStream());
        assertThatThrownBy(engine::startAudioOutput).isInstanceOf(AudioBackendException.class);
        backend.failOnPumpStart = false;

        engine.startAudioOutput();

        long failedSerial = seenByTheFailedStart.get().orElseThrow().serial();
        assertThat(failedSerial).as("premise: the failed start had its own serial").isGreaterThan(first.serial());
        assertThat(engine.lastOpenedStream().orElseThrow().serial()).isGreaterThan(failedSerial);
    }
}
