package com.benesquivelmusic.daw.core.audioimport;

import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The clip names an ADM BWF import gives its bed and object clips, and the form they share. */
class AdmClipAudioTest {

    @Test
    void theNamesAnImportGivesHaveTheImportNameForm() {
        assertThat(AdmClipAudio.hasImportNameForm(AdmClipAudio.bedClipName(SpeakerLayout.LAYOUT_STEREO))).isTrue();
        assertThat(AdmClipAudio.hasImportNameForm(AdmClipAudio.objectClipName(1))).isTrue();
        assertThat(AdmClipAudio.hasImportNameForm(AdmClipAudio.objectClipName(128))).isTrue();
        assertThat(AdmClipAudio.hasImportNameForm("Bed (7.1.4)")).as("any layout name").isTrue();
    }

    @Test
    void namesAnImportNeverGivesDoNotHaveTheForm() {
        assertThat(AdmClipAudio.hasImportNameForm("Bed ()")).as("no layout name").isFalse();
        assertThat(AdmClipAudio.hasImportNameForm("Bed (Stereo")).isFalse();
        assertThat(AdmClipAudio.hasImportNameForm("Object 0")).isFalse();
        assertThat(AdmClipAudio.hasImportNameForm("Object 01")).as("objectClipName writes no leading zero").isFalse();
        assertThat(AdmClipAudio.hasImportNameForm("Object ")).isFalse();
        assertThat(AdmClipAudio.hasImportNameForm("Object 1 (split)")).as("suffixes are the caller's to strip")
                .isFalse();
        assertThat(AdmClipAudio.hasImportNameForm("Lead vocal")).isFalse();
    }
}
