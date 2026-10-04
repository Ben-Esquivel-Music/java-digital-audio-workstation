package com.benesquivelmusic.daw.core.midi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MidiRecorderTest {

    @Test
    void shouldConvertTimestampToColumnAtTempo120() {
        // At 120 BPM:
        //   1 beat = 0.5 seconds
        //   1 column = 0.25 beats = 0.125 seconds = 125000 microseconds
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThat(recorder.timestampToColumn(0)).isZero();
        assertThat(recorder.timestampToColumn(125_000)).isEqualTo(1);
        assertThat(recorder.timestampToColumn(250_000)).isEqualTo(2);
        assertThat(recorder.timestampToColumn(500_000)).isEqualTo(4);
        assertThat(recorder.timestampToColumn(1_000_000)).isEqualTo(8);
    }

    @Test
    void shouldConvertTimestampToColumnAtTempo60() {
        // At 60 BPM:
        //   1 beat = 1 second
        //   1 column = 0.25 beats = 0.25 seconds = 250000 microseconds
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 60.0, 0);

        assertThat(recorder.timestampToColumn(0)).isZero();
        assertThat(recorder.timestampToColumn(250_000)).isEqualTo(1);
        assertThat(recorder.timestampToColumn(1_000_000)).isEqualTo(4);
    }

    @Test
    void shouldReturnZeroForNegativeTimestamp() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThat(recorder.timestampToColumn(-1)).isZero();
    }

    @Test
    void shouldNotBeRecordingInitially() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThat(recorder.isRecording()).isFalse();
    }

    @Test
    void shouldReturnClip() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThat(recorder.getClip()).isSameAs(clip);
    }

    @Test
    void shouldRejectNullDevice() {
        MidiClip clip = new MidiClip();
        assertThatThrownBy(() -> new MidiRecorder(null, clip, 120.0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullClip() {
        assertThatThrownBy(() -> new MidiRecorder(new StubMidiDevice(), null, 120.0, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNonPositiveTempo() {
        MidiClip clip = new MidiClip();
        assertThatThrownBy(() -> new MidiRecorder(new StubMidiDevice(), clip, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidChannel() {
        MidiClip clip = new MidiClip();
        assertThatThrownBy(() -> new MidiRecorder(new StubMidiDevice(), clip, 120.0, 16))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefaultStartColumnOffsetToZero() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThat(recorder.getStartColumnOffset()).isZero();
    }

    @Test
    void shouldSetStartColumnOffset() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);
        recorder.setStartColumnOffset(16);

        assertThat(recorder.getStartColumnOffset()).isEqualTo(16);
    }

    @Test
    void shouldRejectNegativeStartColumnOffset() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThatThrownBy(() -> recorder.setStartColumnOffset(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDefaultCountInDurationToZero() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThat(recorder.getCountInDurationUs()).isZero();
    }

    @Test
    void shouldSetCountInDuration() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);
        recorder.setCountInDurationUs(2_000_000L);

        assertThat(recorder.getCountInDurationUs()).isEqualTo(2_000_000L);
    }

    @Test
    void shouldRejectNegativeCountInDuration() {
        MidiClip clip = new MidiClip();
        MidiRecorder recorder = new MidiRecorder(new StubMidiDevice(), clip, 120.0, 0);

        assertThatThrownBy(() -> recorder.setCountInDurationUs(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparedInputsIgnoreSetupEventsAndUseOneOriginDespiteLaterActivation() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(-1_000_000_000L);
        CapturingMidiDevice firstDevice = new CapturingMidiDevice();
        CapturingMidiDevice secondDevice = new CapturingMidiDevice();
        MidiRecorder first = new MidiRecorder(firstDevice, new MidiClip(), 120, 0, clock::get);
        MidiRecorder second = new MidiRecorder(secondDevice, new MidiClip(), 120, 0, clock::get);
        var events = new java.util.concurrent.atomic.AtomicInteger();
        first.addEventListener(_ -> events.incrementAndGet());
        first.prepareRecording();
        try {
            clock.addAndGet(2_000_000_000L);
            firstDevice.send(javax.sound.midi.ShortMessage.NOTE_ON, 59, 100, -1);
            firstDevice.send(javax.sound.midi.ShortMessage.NOTE_OFF, 59, 0, -1);
            second.prepareRecording();
            assertThat(first.isRecording()).isFalse();
            assertThat(second.isRecording()).isFalse();
            assertThat(first.getRecordedNotes()).isEmpty();
            assertThat(events).hasValue(0);
            long origin = clock.get();
            first.beginRecording(origin);
            // Even a callback between sequential activations cannot give the second a later origin.
            clock.addAndGet(250_000_000L);
            firstDevice.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, -1);
            second.beginRecording(origin);
            secondDevice.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, -1);
            clock.addAndGet(250_000_000L);
            firstDevice.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, -1);
            secondDevice.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, -1);
            assertThat(first.getRecordedNotes()).containsExactly(new MidiNoteData(60, 2, 2, 100, 0));
            assertThat(second.getRecordedNotes()).isEqualTo(first.getRecordedNotes());
        } finally {
            second.stopRecording();
            first.stopRecording();
        }
    }

    @Test
    void cancellingPreparedSharedInputsReleasesOnlyOwnedResourcesAndCanRestart() throws Exception {
        SharedMidiDevice device = new SharedMidiDevice();
        MidiRecorder first = new MidiRecorder(device, new MidiClip(), 120, 0);
        MidiRecorder second = new MidiRecorder(device, new MidiClip(), 120, 0);
        first.prepareRecording();
        try {
            second.prepareRecording();
            first.stopRecording();
            assertThat(device.isOpen()).isTrue();
            assertThat(device.transmitters.get(0).closed).isTrue();
            assertThat(device.transmitters.get(1).closed).isFalse();
            second.stopRecording();
            assertThat(device.closes).isEqualTo(1);
            assertThat(first.getRecordedNotes()).isEmpty();
            assertThat(second.getRecordedNotes()).isEmpty();
            first.startRecording();
            assertThat(first.isRecording()).isTrue();
            first.stopRecording();
            assertThat(device.closes).isEqualTo(2);

            device.open(); // An external opening remains borrowed on cancellation.
            second.prepareRecording();
            second.stopRecording();
            assertThat(device.isOpen()).isTrue();
            assertThat(device.closes).isEqualTo(2);
        } finally {
            second.stopRecording();
            first.stopRecording();
            if (device.isOpen()) device.close();
        }
    }

    @Test
    void failedSharedPreparationLeavesTheFirstInputReadyForActivation() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        SharedMidiDevice device = new SharedMidiDevice();
        MidiRecorder first = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        MidiRecorder second = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        first.prepareRecording();
        try {
            device.failNextTransmitter = true;
            assertThatThrownBy(second::prepareRecording)
                    .isInstanceOf(javax.sound.midi.MidiUnavailableException.class);
            assertThat(device.closes).isZero();
            clock.set(2_000_000_000L);
            first.beginRecording(clock.get());
            clock.addAndGet(250_000_000L);
            device.send(0, javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, -1);
            clock.addAndGet(250_000_000L);
            device.send(0, javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, -1);
            assertThat(first.getRecordedNotes()).containsExactly(new MidiNoteData(60, 2, 2, 100, 0));
        } finally {
            second.stopRecording();
            first.stopRecording();
        }
        assertThat(device.closes).isEqualTo(1);
    }

    @Test
    void failedPreparationWithAnErrorClosesTheTransmitterAndDeviceAndCanRestart() throws Exception {
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        CapturingMidiDevice device = new CapturingMidiDevice() {
            @Override public javax.sound.midi.Transmitter getTransmitter() {
                var delegate = super.getTransmitter();
                return new javax.sound.midi.Transmitter() {
                    @Override public void setReceiver(javax.sound.midi.Receiver receiver) {
                        delegate.setReceiver(receiver);
                        if (failOnce.getAndSet(false)) throw new AssertionError("receiver failed");
                    }
                    @Override public javax.sound.midi.Receiver getReceiver() { return delegate.getReceiver(); }
                    @Override public void close() { delegate.close(); }
                };
            }
        };
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0);
        assertThatThrownBy(recorder::prepareRecording).isInstanceOf(AssertionError.class).hasMessage("receiver failed");
        assertThat(device.open).isFalse();
        assertThat(device.closedTransmitter).isTrue();
        assertThat(recorder.isRecording()).isFalse();
        recorder.startRecording();
        try {
            assertThat(recorder.isRecording()).isTrue();
        } finally {
            recorder.stopRecording();
        }
    }

    @Test
    void directStartExcludesDeviceOpeningDelayFromFallbackTime() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        CapturingMidiDevice device = new CapturingMidiDevice() {
            @Override public void open() {
                super.open();
                clock.set(2_000_000_000L);
            }
        };
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        recorder.startRecording();
        try {
            clock.addAndGet(250_000_000L);
            device.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, -1);
            clock.addAndGet(250_000_000L);
            device.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, -1);
            assertThat(recorder.getRecordedNotes()).containsExactly(new MidiNoteData(60, 2, 2, 100, 0));
        } finally {
            recorder.stopRecording();
        }
    }

    @Test
    void unknownTimestampsUseElapsedMonotonicTimeIncludingDelayedFirstNote() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(-1_000_000_000L);
        CapturingMidiDevice device = new CapturingMidiDevice();
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        recorder.setStartColumnOffset(16);
        recorder.startRecording();
        clock.addAndGet(500_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, -1);
        clock.addAndGet(250_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, -1);
        recorder.stopRecording();
        assertThat(recorder.getRecordedNotes()).containsExactly(new MidiNoteData(60, 20, 2, 100, 0));
        assertThat(device.open).isFalse();
        assertThat(device.closedTransmitter).isTrue();
    }

    @Test
    void unknownTimestampsHonorCountInAndMixedTimestampsNeverMoveBackwards() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        CapturingMidiDevice device = new CapturingMidiDevice();
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        recorder.setCountInDurationUs(250_000);
        recorder.startRecording();
        clock.set(100_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 59, 100, -1);
        clock.set(500_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, -1);
        clock.set(750_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, 1_000_000);
        recorder.stopRecording();
        assertThat(recorder.getRecordedNotes()).containsExactly(new MidiNoteData(60, 2, 2, 100, 0));
    }

    @Test
    void providerCloseCanWaitForAReceiverCallbackWithoutDeadlockingStop() throws Exception {
        var callbackBlocked = new java.util.concurrent.atomic.AtomicBoolean();
        CapturingMidiDevice device = new CapturingMidiDevice() {
            @Override public void close() {
                Thread callback = Thread.ofVirtual().start(() -> {
                    try { send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, -1); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                });
                try {
                    callback.join(1000);
                    callbackBlocked.set(callback.isAlive());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                super.close();
            }
        };
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0);
        recorder.startRecording();
        recorder.stopRecording();
        assertThat(callbackBlocked.get()).as("provider cleanup holds no receiver monitor").isFalse();
        assertThat(device.open).isFalse();
        assertThat(device.closedTransmitter).isTrue();
        assertThat(recorder.isRecording()).isFalse();
    }

    @Test
    void failedSharedTransmitterAcquisitionLeavesTheFirstRecorderConnected() throws Exception {
        SharedMidiDevice device = new SharedMidiDevice();
        MidiRecorder first = new MidiRecorder(device, new MidiClip(), 120, 0);
        MidiRecorder second = new MidiRecorder(device, new MidiClip(), 120, 0);
        first.startRecording();
        device.failNextTransmitter = true;
        try {
            assertThatThrownBy(second::startRecording)
                    .isInstanceOf(javax.sound.midi.MidiUnavailableException.class);
            assertThat(second.isRecording()).isFalse();
            assertThat(first.isRecording()).isTrue();
            assertThat(device.isOpen()).isTrue();
            device.send(0, javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, 1_000_000);
            device.send(0, javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, 1_250_000);
            assertThat(first.getRecordedNotes()).containsExactly(new MidiNoteData(60, 0, 2, 100, 0));
        } finally {
            first.stopRecording();
        }
        assertThat(device.closes).isEqualTo(1);
    }

    @Test
    void failedSharedReceiverInstallationClosesOnlyItsTransmitter() throws Exception {
        SharedMidiDevice device = new SharedMidiDevice();
        MidiRecorder first = new MidiRecorder(device, new MidiClip(), 120, 0);
        MidiRecorder second = new MidiRecorder(device, new MidiClip(), 120, 0);
        first.startRecording();
        device.failNextReceiver = true;
        try {
            assertThatThrownBy(second::startRecording).hasMessage("receiver unavailable");
            assertThat(device.isOpen()).isTrue();
            assertThat(device.transmitters.get(0).closed).isFalse();
            assertThat(device.transmitters.get(1).closed).isTrue();
        } finally {
            first.stopRecording();
        }
        assertThat(device.closes).isEqualTo(1);
    }

    @Test
    void sharedDeviceStaysOpenWhenItsOpeningRecorderStopsFirst() throws Exception {
        SharedMidiDevice device = new SharedMidiDevice();
        MidiRecorder first = new MidiRecorder(device, new MidiClip(), 120, 0);
        MidiRecorder second = new MidiRecorder(device, new MidiClip(), 120, 0);
        first.startRecording();
        second.startRecording();
        try {
            first.stopRecording();
            assertThat(device.isOpen()).isTrue();
            assertThat(device.transmitters.get(0).closed).isTrue();
            assertThat(device.transmitters.get(1).closed).isFalse();
            device.send(1, javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, 1_000_000);
            device.send(1, javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, 1_250_000);
            assertThat(second.getRecordedNotes()).containsExactly(new MidiNoteData(60, 0, 2, 100, 0));
        } finally {
            second.stopRecording();
        }
        assertThat(device.closes).isEqualTo(1);
        assertThat(device.isOpen()).isFalse();
    }

    @Test
    void borrowedDeviceIsNotClosedOnSuccessfulOrFailedRecording() throws Exception {
        SharedMidiDevice device = new SharedMidiDevice();
        device.open();
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0);
        recorder.startRecording();
        recorder.stopRecording();
        device.failNextTransmitter = true;
        assertThatThrownBy(recorder::startRecording)
                .isInstanceOf(javax.sound.midi.MidiUnavailableException.class);
        assertThat(device.isOpen()).isTrue();
        assertThat(device.closes).isZero();
    }

    @Test
    void ownedStartupFailureClosesTheDeviceAndAllowsAnotherAttempt() throws Exception {
        SharedMidiDevice device = new SharedMidiDevice();
        device.failNextTransmitter = true;
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0);
        assertThatThrownBy(recorder::startRecording)
                .isInstanceOf(javax.sound.midi.MidiUnavailableException.class);
        assertThat(device.isOpen()).isFalse();
        assertThat(device.closes).isEqualTo(1);
        recorder.startRecording();
        recorder.stopRecording();
        assertThat(device.closes).isEqualTo(2);
    }

    @Test
    void timestampedFirstNoteAndFallbackNoteOffUseTheSameOrigin() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        CapturingMidiDevice device = new CapturingMidiDevice();
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        recorder.startRecording();
        clock.set(500_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, 1_000_000);
        clock.set(750_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, -1);
        recorder.stopRecording();
        assertThat(recorder.getRecordedNotes()).containsExactly(new MidiNoteData(60, 0, 2, 100, 0));
    }

    @Test
    void stoppingAHeldTimestampedNoteUsesItsCalibratedOrigin() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        CapturingMidiDevice device = new CapturingMidiDevice();
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        recorder.setStartColumnOffset(16);
        recorder.startRecording();
        clock.set(500_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, 1_000_000);
        clock.set(750_000_000L);
        recorder.stopRecording();
        assertThat(recorder.getRecordedNotes()).containsExactly(new MidiNoteData(60, 16, 2, 100, 0));
    }

    @Test
    void switchingBackToDeviceTimestampsKeepsTheSameNoteTimeline() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        CapturingMidiDevice device = new CapturingMidiDevice();
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        recorder.startRecording();
        clock.set(500_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, 1_000_000);
        clock.set(750_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, -1);
        clock.set(1_000_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 62, 90, -1);
        clock.set(1_250_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_OFF, 62, 0, 1_750_000);
        recorder.stopRecording();
        assertThat(recorder.getRecordedNotes()).containsExactly(
                new MidiNoteData(60, 0, 2, 100, 0), new MidiNoteData(62, 4, 2, 90, 0));
    }

    @Test
    void timestampOnlyRecordingStillStartsAtTheFirstDeviceEvent() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        CapturingMidiDevice device = new CapturingMidiDevice();
        MidiRecorder recorder = new MidiRecorder(device, new MidiClip(), 120, 0, clock::get);
        recorder.startRecording();
        clock.set(500_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_ON, 60, 100, 1_000_000);
        clock.set(750_000_000L);
        device.send(javax.sound.midi.ShortMessage.NOTE_OFF, 60, 0, 1_250_000);
        recorder.stopRecording();
        assertThat(recorder.getRecordedNotes()).containsExactly(new MidiNoteData(60, 0, 2, 100, 0));
    }

    private static final class SharedMidiDevice extends StubMidiDevice {
        boolean open;
        int closes;
        boolean failNextTransmitter;
        boolean failNextReceiver;
        final java.util.List<InputTransmitter> transmitters = new java.util.ArrayList<>();

        @Override public void open() { open = true; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; closes++; }
        @Override public javax.sound.midi.Transmitter getTransmitter() throws javax.sound.midi.MidiUnavailableException {
            if (failNextTransmitter) {
                failNextTransmitter = false;
                throw new javax.sound.midi.MidiUnavailableException("transmitter unavailable");
            }
            InputTransmitter transmitter = new InputTransmitter(failNextReceiver);
            failNextReceiver = false;
            transmitters.add(transmitter);
            return transmitter;
        }
        void send(int input, int command, int note, int velocity, long timestamp) throws Exception {
            assertThat(open).as("provider is connected").isTrue();
            InputTransmitter transmitter = transmitters.get(input);
            assertThat(transmitter.closed).isFalse();
            transmitter.receiver.send(new javax.sound.midi.ShortMessage(command, 0, note, velocity), timestamp);
        }

        private static final class InputTransmitter implements javax.sound.midi.Transmitter {
            private final boolean failReceiver;
            private javax.sound.midi.Receiver receiver;
            private boolean closed;
            InputTransmitter(boolean failReceiver) { this.failReceiver = failReceiver; }
            @Override public void setReceiver(javax.sound.midi.Receiver value) {
                if (failReceiver) throw new IllegalStateException("receiver unavailable");
                receiver = value;
            }
            @Override public javax.sound.midi.Receiver getReceiver() { return receiver; }
            @Override public void close() { closed = true; }
        }
    }

    private static class CapturingMidiDevice extends StubMidiDevice {
        boolean open;
        boolean closedTransmitter;
        javax.sound.midi.Receiver receiver;
        @Override public boolean isOpen() { return open; }
        @Override public void open() { open = true; }
        @Override public void close() { open = false; }
        @Override public javax.sound.midi.Transmitter getTransmitter() {
            return new javax.sound.midi.Transmitter() {
                @Override public void setReceiver(javax.sound.midi.Receiver value) { receiver = value; }
                @Override public javax.sound.midi.Receiver getReceiver() { return receiver; }
                @Override public void close() { closedTransmitter = true; }
            };
        }
        void send(int command, int note, int velocity, long timestamp) throws Exception {
            receiver.send(new javax.sound.midi.ShortMessage(command, 0, note, velocity), timestamp);
        }
    }

    /**
     * Minimal stub for {@link javax.sound.midi.MidiDevice} to avoid depending
     * on real MIDI hardware in unit tests.
     */
    private static class StubMidiDevice implements javax.sound.midi.MidiDevice {

        @Override
        public Info getDeviceInfo() {
            return new Info("Stub", "Test", "Stub MIDI Device", "1.0") {};
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public long getMicrosecondPosition() {
            return 0;
        }

        @Override
        public int getMaxReceivers() {
            return 0;
        }

        @Override
        public int getMaxTransmitters() {
            return 1;
        }

        @Override
        public javax.sound.midi.Receiver getReceiver() {
            return new javax.sound.midi.Receiver() {
                @Override
                public void send(javax.sound.midi.MidiMessage message, long timeStamp) {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public java.util.List<javax.sound.midi.Receiver> getReceivers() {
            return java.util.List.of();
        }

        @Override
        public javax.sound.midi.Transmitter getTransmitter() throws javax.sound.midi.MidiUnavailableException {
            return new javax.sound.midi.Transmitter() {
                private javax.sound.midi.Receiver receiver;

                @Override
                public void setReceiver(javax.sound.midi.Receiver receiver) {
                    this.receiver = receiver;
                }

                @Override
                public javax.sound.midi.Receiver getReceiver() {
                    return receiver;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public java.util.List<javax.sound.midi.Transmitter> getTransmitters() {
            return java.util.List.of();
        }
    }
}
