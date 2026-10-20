package com.benesquivelmusic.daw.core.comping;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.FadeCurveType;
import com.benesquivelmusic.daw.core.audio.StretchQuality;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.ClipGainEnvelope;
import com.benesquivelmusic.daw.sdk.audio.CurveShape;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link ClipSaveValue} — the "would a save write the same clip"
 * comparison behind the audition dirty rule — to what the project writer
 * actually writes: every field class it writes, varied alone, changes both
 * the serialized document and the value; what it does not write (identity,
 * audio, comp-output tag) changes neither.
 */
class ClipSaveValueTest {

    private final ProjectSerializer serializer = new ProjectSerializer();

    static Stream<Arguments> savedFieldEdits() {
        return Stream.of(
                edit("name", clip -> clip.setName("Renamed")),
                edit("position", clip -> clip.setStartBeat(clip.getStartBeat() + 0.5)),
                edit("length", clip -> clip.setDurationBeats(clip.getDurationBeats() - 0.5)),
                edit("source offset", clip -> clip.setSourceOffsetBeats(clip.getSourceOffsetBeats() + 0.25)),
                edit("gain", clip -> clip.setGainDb(-6.0)),
                edit("reverse", clip -> clip.setReversed(true)),
                edit("lock", clip -> clip.setLocked(true)),
                edit("fade-in length", clip -> clip.setFadeInBeats(0.5)),
                edit("fade-out length", clip -> clip.setFadeOutBeats(0.5)),
                edit("fade-in curve", clip -> clip.setFadeInCurveType(
                        otherThan(clip.getFadeInCurveType()))),
                edit("fade-out curve", clip -> clip.setFadeOutCurveType(
                        otherThan(clip.getFadeOutCurveType()))),
                edit("time stretch", clip -> clip.setTimeStretchRatio(1.5)),
                edit("pitch shift", clip -> clip.setPitchShiftSemitones(2.0)),
                edit("stretch quality", clip -> clip.setStretchQuality(
                        clip.getStretchQuality() == StretchQuality.HIGH ? StretchQuality.LOW : StretchQuality.HIGH)),
                edit("segment list", clip -> clip.setSourceSegmentPaths(
                        List.of("/takes/take-1/segment-000.wav", "/takes/take-1/segment-009.wav"))),
                edit("source file (segment head)", clip -> clip.setSourceSegmentPaths(
                        List.of("/takes/take-9/segment-000.wav", "/takes/take-1/segment-001.wav"))),
                edit("gain envelope", clip -> clip.setGainEnvelope(new ClipGainEnvelope(List.of(
                        new ClipGainEnvelope.BreakpointDb(0L, -3.0, CurveShape.LINEAR))))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("savedFieldEdits")
    void anEditToASavedFieldChangesBothTheDocumentAndTheValue(String field, Consumer<AudioClip> change)
            throws IOException {
        AudioClip original = recordedClip();
        AudioClip edited = recordedClip();
        change.accept(edited);

        assertThat(serialize(edited)).as("the writer saves the %s", field).isNotEqualTo(serialize(original));
        assertThat(ClipSaveValue.of(edited)).as(field).isNotEqualTo(ClipSaveValue.of(original));
        assertThat(ClipSaveValue.sameSavedClips(List.of(original), List.of(edited))).isFalse();
    }

    @Test
    void theSourceFileOfASingleFileClipIsASavedField() throws IOException {
        AudioClip original = new AudioClip("Import", 0.0, 4.0, "/media/a.wav");
        AudioClip edited = new AudioClip("Import", 0.0, 4.0, "/media/b.wav");

        assertThat(serialize(edited)).isNotEqualTo(serialize(original));
        assertThat(ClipSaveValue.of(edited)).isNotEqualTo(ClipSaveValue.of(original));
    }

    @Test
    void identityAudioAndCompTagAreNotSavedAndDoNotCount() throws IOException {
        AudioClip original = recordedClip();
        AudioClip twin = recordedClip();
        twin.setAudioData(new float[][] { new float[] { 0.5f } });
        twin.setCompOutputOwner(new CompOutputOwnerStub());

        assertThat(twin.getId()).isNotEqualTo(original.getId());
        assertThat(serialize(twin)).isEqualTo(serialize(original));
        assertThat(ClipSaveValue.of(twin)).isEqualTo(ClipSaveValue.of(original));
    }

    @Test
    void listsCompareAsMultisetsRegardlessOfOrder() {
        AudioClip first = recordedClip();
        AudioClip second = recordedClip();
        second.setStartBeat(8.0);

        assertThat(ClipSaveValue.sameSavedClips(List.of(first, second), List.of(recordedAt(8.0), recordedClip())))
                .isTrue();
        assertThat(ClipSaveValue.sameSavedClips(List.of(first, first), List.of(first, second))).isFalse();
        assertThat(ClipSaveValue.sameSavedClips(List.of(first), List.of(first, second))).isFalse();
    }

    private static AudioClip recordedAt(double startBeat) {
        AudioClip clip = recordedClip();
        clip.setStartBeat(startBeat);
        return clip;
    }

    private static AudioClip recordedClip() {
        AudioClip clip = new AudioClip("Take 1", 2.0, 3.0, null);
        clip.setSourceSegmentPaths(List.of("/takes/take-1/segment-000.wav", "/takes/take-1/segment-001.wav"));
        clip.setSourceOffsetBeats(2.0);
        return clip;
    }

    private static FadeCurveType otherThan(FadeCurveType type) {
        return type == FadeCurveType.S_CURVE ? FadeCurveType.LINEAR : FadeCurveType.S_CURVE;
    }

    private static Arguments edit(String field, Consumer<AudioClip> change) {
        return Arguments.of(field, change);
    }

    /** The project document of one track holding {@code clip}, its {@code <clips>} element only (ids and metadata vary per project). */
    private String serialize(AudioClip clip) throws IOException {
        DawProject project = new DawProject("Values", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vox").addClip(clip);
        String xml = serializer.serialize(project);
        return xml.substring(xml.indexOf("<clips>"), xml.indexOf("</clips>"));
    }

    private static final class CompOutputOwnerStub implements com.benesquivelmusic.daw.core.audio.CompOutputOwner {
    }
}
