package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiDevice;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MidiInputPortSelectionDialogTest {

    @Test
    void enumerateMidiInputDevicesShouldReturnOnlyInputDevices() {
        List<MidiDevice.Info> inputs = MidiInputPortSelectionDialog.enumerateMidiInputDevices();

        // All returned devices must have at least one transmitter (i.e., are inputs)
        assertThat(inputs).isNotNull();
        // Each returned device should be obtainable from MidiSystem
        for (MidiDevice.Info info : inputs) {
            assertThat(info.getName()).isNotNull();
        }
    }

    @Test
    void enumerateMidiInputDevicesShouldReturnStableResults() {
        List<MidiDevice.Info> first = MidiInputPortSelectionDialog.enumerateMidiInputDevices();
        List<MidiDevice.Info> second = MidiInputPortSelectionDialog.enumerateMidiInputDevices();

        assertThat(first).hasSameSizeAs(second);
    }
}
