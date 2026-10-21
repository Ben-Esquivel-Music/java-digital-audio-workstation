package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audioimport.AdmClipAudio;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The row details a decoded ADM file gives the clips its names do not select (decisions D26, D43). */
class DecodedAudioTest {

    private static final int RATE = 48_000;

    @Test
    void anAdmFilesUnknownNamesDetailSaysHowManyOfTheClipsSelectNothingInOneClause() {
        DecodedAudio.AdmAudio adm = new DecodedAudio.AdmAudio(List.of(
                new AdmClipAudio("Bed (Stereo)", true, new float[2][16]),
                new AdmClipAudio("Object 1", false, new float[1][16]),
                new AdmClipAudio("Object 2", false, new float[1][16])), RATE);

        assertThat(adm.unknownNamesDetail(1, 3)).as("one of three holders")
                .isEqualTo("1 of them selects no bed or object of the file (it has: Bed (Stereo), Object 1,"
                        + " Object 2)");
        assertThat(adm.unknownNamesDetail(2, 3)).as("two of three holders")
                .isEqualTo("2 of them select no bed or object of the file (it has: Bed (Stereo), Object 1,"
                        + " Object 2)");
        assertThat(adm.unknownNamesDetail(3, 3)).as("every holder")
                .isEqualTo("the clip name selects no bed or object of the file (it has: Bed (Stereo), Object 1,"
                        + " Object 2)");
    }
}
