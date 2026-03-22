package com.benesquivelmusic.daw.core.spatial.objectbased;

import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AtmosSessionValidatorTest {

    @Test
    void shouldPassValidSession() {
        var beds = List.of(
                new BedChannel("bed-1", SpeakerLabel.L),
                new BedChannel("bed-2", SpeakerLabel.R));
        var objects = List.of(
                new AudioObject("obj-1"),
                new AudioObject("obj-2"));

        var errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldRejectExcessiveObjectCount() {
        var beds = List.<BedChannel>of();
        var objects = new ArrayList<AudioObject>();
        for (int i = 0; i < 119; i++) {
            objects.add(new AudioObject("obj-" + i));
        }

        var errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).anyMatch(e -> e.contains("119") && e.contains("118"));
    }

    @Test
    void shouldRejectExcessiveTotalTracks() {
        var beds = new ArrayList<BedChannel>();
        for (var label : SpeakerLabel.values()) {
            beds.add(new BedChannel("bed-" + label, label));
        }
        var objects = new ArrayList<AudioObject>();
        for (int i = 0; i < 118; i++) {
            objects.add(new AudioObject("obj-" + i));
        }

        // 12 beds + 118 objects = 130 > 128
        var errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).anyMatch(e -> e.contains("130") && e.contains("128"));
    }

    @Test
    void shouldAcceptMaximumAllowedConfiguration() {
        var beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        var objects = new ArrayList<AudioObject>();
        for (int i = 0; i < 118; i++) {
            objects.add(new AudioObject("obj-" + i));
        }

        // 2 beds + 118 objects = 120 ≤ 128
        var errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldRejectBedAssignedToSpeakerNotInLayout() {
        var beds = List.of(
                new BedChannel("bed-1", SpeakerLabel.LTF)); // height speaker not in stereo
        var objects = List.<AudioObject>of();

        var errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_STEREO);
        assertThat(errors).anyMatch(e -> e.contains("LTF") && e.contains("Stereo"));
    }

    @Test
    void shouldRejectDuplicateBedAssignment() {
        var beds = List.of(
                new BedChannel("bed-1", SpeakerLabel.L),
                new BedChannel("bed-2", SpeakerLabel.L)); // duplicate L
        var objects = List.<AudioObject>of();

        var errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).anyMatch(e -> e.contains("Duplicate") && e.contains("L"));
    }
}
