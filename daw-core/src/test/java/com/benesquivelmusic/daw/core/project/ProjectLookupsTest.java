package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link ProjectLookups} — the small static seam that
 * Story 281's Workshop selection wiring uses to resolve a typed inspector
 * selection ({@code InsertSelection(trackId, insertIndex)},
 * {@code ClipSelection(clipId)}) to a domain object.
 *
 * <p>Pure logic — no JavaFX toolkit; runs as a vanilla unit test.</p>
 */
final class ProjectLookupsTest {

    @Test
    void findTrackReturnsTheTrackWhoseIdMatchesTheUuid() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass  = project.createAudioTrack("Bass");

        UUID drumsId = UUID.fromString(drums.getId());
        UUID bassId  = UUID.fromString(bass.getId());

        assertThat(ProjectLookups.findTrack(project, drumsId))
                .as("findTrack matches the Drums track by its UUID")
                .contains(drums);
        assertThat(ProjectLookups.findTrack(project, bassId))
                .as("findTrack matches the Bass track by its UUID")
                .contains(bass);
    }

    @Test
    void findTrackReturnsEmptyForUnknownUuid() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");

        assertThat(ProjectLookups.findTrack(project, UUID.randomUUID()))
                .as("an unknown UUID resolves to empty, never throws")
                .isEmpty();
    }

    @Test
    void findTrackTreatsNullUuidAsEmpty() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");

        assertThat(ProjectLookups.findTrack(project, null))
                .as("null UUID is normalised to Optional.empty")
                .isEmpty();
    }

    @Test
    void findTrackRejectsNullProject() {
        assertThatThrownBy(() -> ProjectLookups.findTrack(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findTrackIndexReturnsZeroBasedIndexOrNegativeOne() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass  = project.createAudioTrack("Bass");
        Track vox   = project.createAudioTrack("Vox");

        assertThat(ProjectLookups.findTrackIndex(project, UUID.fromString(drums.getId())))
                .as("Drums is the first track")
                .isEqualTo(0);
        assertThat(ProjectLookups.findTrackIndex(project, UUID.fromString(bass.getId())))
                .as("Bass is the second track")
                .isEqualTo(1);
        assertThat(ProjectLookups.findTrackIndex(project, UUID.fromString(vox.getId())))
                .as("Vox is the third track")
                .isEqualTo(2);
        assertThat(ProjectLookups.findTrackIndex(project, UUID.randomUUID()))
                .as("an unknown UUID returns -1")
                .isEqualTo(-1);
        assertThat(ProjectLookups.findTrackIndex(project, null))
                .as("a null UUID returns -1")
                .isEqualTo(-1);
    }

    @Test
    void findInsertSlotResolvesByTrackIdAndIndex() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(drums);
        InsertSlot slot0 = new InsertSlot("Comp", new StubProcessor(),
                InsertEffectType.COMPRESSOR);
        InsertSlot slot1 = new InsertSlot("Reverb", new StubProcessor(),
                InsertEffectType.REVERB);
        channel.addInsert(slot0);
        channel.addInsert(slot1);

        UUID trackId = UUID.fromString(drums.getId());

        assertThat(ProjectLookups.findInsertSlot(project, trackId, 0))
                .as("insert at index 0 resolves to the first added slot (Comp)")
                .contains(slot0);
        assertThat(ProjectLookups.findInsertSlot(project, trackId, 1))
                .as("insert at index 1 resolves to the second added slot (Reverb)")
                .contains(slot1);
    }

    @Test
    void findInsertSlotReturnsEmptyForMissingChannel() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");

        // Unknown channel UUID → empty.
        assertThat(ProjectLookups.findInsertSlot(project, UUID.randomUUID(), 0))
                .as("unknown channel UUID resolves to empty")
                .isEmpty();
    }

    @Test
    void findInsertSlotReturnsEmptyForOutOfRangeIndex() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        // Channel created, but ZERO inserts added.
        UUID trackId = UUID.fromString(drums.getId());

        assertThat(ProjectLookups.findInsertSlot(project, trackId, 0))
                .as("index 0 on an empty insert list resolves to empty")
                .isEmpty();
        assertThat(ProjectLookups.findInsertSlot(project, trackId, -1))
                .as("a negative index resolves to empty, never throws")
                .isEmpty();
        assertThat(ProjectLookups.findInsertSlot(project, trackId, 999))
                .as("a hugely out-of-range index resolves to empty, never throws")
                .isEmpty();
    }

    @Test
    void findInsertSlotTreatsNullTrackIdAsEmpty() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");

        assertThat(ProjectLookups.findInsertSlot(project, null, 0))
                .as("null track UUID resolves to empty")
                .isEmpty();
    }

    @Test
    void findAudioClipResolvesByUuidAcrossTracks() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass  = project.createAudioTrack("Bass");
        AudioClip kick  = new AudioClip("kick.wav",  0.0, 4.0, null);
        AudioClip snare = new AudioClip("snare.wav", 4.0, 4.0, null);
        AudioClip bassNote = new AudioClip("bass.wav", 0.0, 8.0, null);
        drums.addClip(kick);
        drums.addClip(snare);
        bass.addClip(bassNote);

        // Clip ids are stable UUID strings; round-trip through UUID.fromString.
        assertThat(ProjectLookups.findAudioClip(project, UUID.fromString(kick.getId())))
                .as("kick resolves on the Drums track")
                .contains(kick);
        assertThat(ProjectLookups.findAudioClip(project, UUID.fromString(snare.getId())))
                .as("snare resolves on the Drums track")
                .contains(snare);
        assertThat(ProjectLookups.findAudioClip(project, UUID.fromString(bassNote.getId())))
                .as("bass clip resolves on the Bass track")
                .contains(bassNote);
        assertThat(ProjectLookups.findAudioClip(project, UUID.randomUUID()))
                .as("unknown clip UUID resolves to empty")
                .isEmpty();
        assertThat(ProjectLookups.findAudioClip(project, null))
                .as("null clip UUID resolves to empty")
                .isEmpty();
    }

    @Test
    void findOwningTrackLocatesTheTrackThatHoldsTheClip() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track drums = project.createAudioTrack("Drums");
        Track bass  = project.createAudioTrack("Bass");
        AudioClip kick = new AudioClip("kick.wav", 0.0, 4.0, null);
        drums.addClip(kick);

        assertThat(ProjectLookups.findOwningTrack(project, kick))
                .as("kick's owner is the Drums track")
                .contains(drums);
        AudioClip orphan = new AudioClip("orphan.wav", 0.0, 1.0, null);
        assertThat(ProjectLookups.findOwningTrack(project, orphan))
                .as("a clip not attached to any track resolves to empty")
                .isEmpty();
        assertThat(ProjectLookups.findOwningTrack(project, null))
                .as("null clip resolves to empty")
                .isEmpty();
        // The Bass track owns nothing here.
        Optional<Track> bassOwner = ProjectLookups.findOwningTrack(project,
                new AudioClip("nothing.wav", 0.0, 1.0, null));
        assertThat(bassOwner).isEmpty();
        assertThat(bass.getClips()).isEmpty();
    }

    // ── Stub processor (mirrors InsertSlotTest's local fixture) ───────────

    private static final class StubProcessor implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }
        @Override public void reset() {}
        @Override public int getInputChannelCount()  { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
    }
}
