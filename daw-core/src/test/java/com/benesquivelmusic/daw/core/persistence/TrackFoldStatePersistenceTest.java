package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackFoldState;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that per-track {@link TrackFoldState} survives a serialize /
 * deserialize round-trip, and that projects saved before fold state
 * was persisted load with the {@link TrackFoldState#UNFOLDED} default.
 */
class TrackFoldStatePersistenceTest {

    @Test
    void shouldRoundTripFoldStateForEachTrack() throws IOException {
        DawProject project = new DawProject("Folds", AudioFormat.CD_QUALITY);

        Track expanded = project.createAudioTrack("Expanded");
        expanded.setFoldState(TrackFoldState.UNFOLDED);

        Track allFolded = project.createAudioTrack("AllFolded");
        allFolded.setFoldState(TrackFoldState.ALL_FOLDED);

        Track partial = project.createAudioTrack("Partial");
        partial.setFoldState(new TrackFoldState(true, false, true, 24.0));

        String xml = new ProjectSerializer().serialize(project);
        // Persisted fields are visible in the XML so a future migration
        // tool can reason about them without parsing the whole document.
        assertThat(xml).contains("automation-folded=\"true\"");
        assertThat(xml).contains("midi-folded=\"true\"");
        assertThat(xml).contains("header-height-override=\"24.0\"");

        DawProject restored = new ProjectDeserializer().deserialize(xml);
        Track[] tracks = restored.getTracks().toArray(new Track[0]);

        assertThat(tracks[0].getFoldState()).isEqualTo(TrackFoldState.UNFOLDED);
        assertThat(tracks[1].getFoldState()).isEqualTo(TrackFoldState.ALL_FOLDED);
        assertThat(tracks[2].getFoldState())
                .isEqualTo(new TrackFoldState(true, false, true, 24.0));
    }

    @Test
    void shouldDefaultToUnfoldedWhenAttributesAreAbsent() throws IOException {
        // Projects saved before fold state existed will not carry any
        // of the four attributes — the deserializer must keep the
        // track on its UNFOLDED default rather than fail.
        String legacyXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project name="Legacy">
                    <audio-format sample-rate="44100" bit-depth="16" channels="2"/>
                    <transport tempo="120.0"/>
                    <tracks>
                        <track id="t1" name="Old" type="AUDIO"
                               volume="1.0" pan="0.0" muted="false" solo="false"
                               armed="false" phase-inverted="false" collapsed="false"
                               color="#FF0000" automation-mode="READ"/>
                    </tracks>
                </project>
                """;

        DawProject restored = new ProjectDeserializer().deserialize(legacyXml);
        Track track = restored.getTracks().getFirst();

        assertThat(track.getFoldState()).isEqualTo(TrackFoldState.UNFOLDED);
    }

    @Test
    void shouldFallBackToUnfoldedOnInvalidHeaderOverride() throws IOException {
        // A negative header override is invalid; rather than crashing
        // the load, we silently coerce to the default.
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project name="Bad">
                    <audio-format sample-rate="44100" bit-depth="16" channels="2"/>
                    <transport tempo="120.0"/>
                    <tracks>
                        <track id="t1" name="Bad" type="AUDIO"
                               volume="1.0" pan="0.0" muted="false" solo="false"
                               armed="false" phase-inverted="false" collapsed="false"
                               color="#FF0000" automation-mode="READ"
                               automation-folded="true" takes-folded="false"
                               midi-folded="false" header-height-override="-99.0"/>
                    </tracks>
                </project>
                """;

        DawProject restored = new ProjectDeserializer().deserialize(xml);
        TrackFoldState state = restored.getTracks().getFirst().getFoldState();

        assertThat(state.automationFolded()).isTrue();
        assertThat(state.headerHeightOverride()).isEqualTo(0.0);
    }
}
