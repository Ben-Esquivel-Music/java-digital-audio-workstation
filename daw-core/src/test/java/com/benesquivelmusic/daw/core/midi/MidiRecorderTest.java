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
        public javax.sound.midi.Transmitter getTransmitter() {
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
