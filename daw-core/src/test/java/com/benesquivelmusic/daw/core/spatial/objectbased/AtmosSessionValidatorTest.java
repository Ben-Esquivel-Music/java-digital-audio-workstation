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
        List<BedChannel> beds = List.of(
                new BedChannel("bed-1", SpeakerLabel.L),
                new BedChannel("bed-2", SpeakerLabel.R));
        List<AudioObject> objects = List.of(
                new AudioObject("obj-1"),
                new AudioObject("obj-2"));

        List<String> errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldRejectExcessiveObjectCount() {
        List<BedChannel> beds = List.<BedChannel>of();
        ArrayList<AudioObject> objects = new ArrayList<AudioObject>();
        for (int i = 0; i < 119; i++) {
            objects.add(new AudioObject("obj-" + i));
        }

        List<String> errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).anyMatch(e -> e.contains("119") && e.contains("118"));
    }

    @Test
    void shouldRejectExcessiveTotalTracks() {
        ArrayList<BedChannel> beds = new ArrayList<BedChannel>();
        for (SpeakerLabel label : SpeakerLabel.values()) {
            beds.add(new BedChannel("bed-" + label, label));
        }
        ArrayList<AudioObject> objects = new ArrayList<AudioObject>();
        for (int i = 0; i < 118; i++) {
            objects.add(new AudioObject("obj-" + i));
        }

        // 12 beds + 118 objects = 130 > 128
        List<String> errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).anyMatch(e -> e.contains("130") && e.contains("128"));
    }

    @Test
    void shouldAcceptMaximumAllowedConfiguration() {
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        ArrayList<AudioObject> objects = new ArrayList<AudioObject>();
        for (int i = 0; i < 118; i++) {
            objects.add(new AudioObject("obj-" + i));
        }

        // 2 beds + 118 objects = 120 ≤ 128
        List<String> errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).isEmpty();
    }

    @Test
    void shouldRejectBedAssignedToSpeakerNotInLayout() {
        List<BedChannel> beds = List.of(
                new BedChannel("bed-1", SpeakerLabel.LTF)); // height speaker not in stereo
        List<AudioObject> objects = List.<AudioObject>of();

        List<String> errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_STEREO);
        assertThat(errors).anyMatch(e -> e.contains("LTF") && e.contains("Stereo"));
    }

    @Test
    void shouldRejectDuplicateBedAssignment() {
        List<BedChannel> beds = List.of(
                new BedChannel("bed-1", SpeakerLabel.L),
                new BedChannel("bed-2", SpeakerLabel.L)); // duplicate L
        List<AudioObject> objects = List.<AudioObject>of();

        List<String> errors = AtmosSessionValidator.validate(beds, objects, SpeakerLayout.LAYOUT_7_1_4);
        assertThat(errors).anyMatch(e -> e.contains("Duplicate") && e.contains("L"));
    }
}
