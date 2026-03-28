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

    /**
     * Minimal stub for {@link javax.sound.midi.MidiDevice} to avoid depending
     * on real MIDI hardware in unit tests.
     */
    private static final class StubMidiDevice implements javax.sound.midi.MidiDevice {

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
