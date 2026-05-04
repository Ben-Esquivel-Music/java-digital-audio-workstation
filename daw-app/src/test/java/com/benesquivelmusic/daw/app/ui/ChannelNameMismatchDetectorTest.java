package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;
import com.benesquivelmusic.daw.sdk.audio.ChannelKind;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 215: a project saved with channel name {@code "Mic 3"} that is
 * later loaded against a driver reporting {@code "Hi-Z Inst 3"} at the
 * same index must surface exactly one notification — not one per track
 * that references the renamed channel.
 */
class ChannelNameMismatchDetectorTest {

    private static AudioChannelInfo input(int idx, String name) {
        return new AudioChannelInfo(idx, name, ChannelKind.Generic.INSTANCE, true);
    }

    @Test
    void returnsEmptyWhenNoLiveChannelMetadataAvailable() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Drums");
        // No live driver info → no comparison possible → no notification.
        assertThat(ChannelNameMismatchDetector.detect(project, List.of(), List.of()))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenNoSavedDisplayNamesPresent() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("Drums");
        t.setInputRouting(new InputRouting(0, 1));
        // No saved display name (legacy project from before story 215).
        List<AudioChannelInfo> live = List.of(input(0, "Hi-Z Inst 1"));
        assertThat(ChannelNameMismatchDetector.detect(project, live, List.of()))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenSavedNameMatchesLiveName() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("Drums");
        t.setInputRouting(new InputRouting(2, 1));
        t.setInputRoutingDisplayName("Mic 3");
        List<AudioChannelInfo> live = List.of(
                input(0, "Mic 1"), input(1, "Mic 2"), input(2, "Mic 3"));
        assertThat(ChannelNameMismatchDetector.detect(project, live, List.of()))
                .isEmpty();
    }

    @Test
    void detectsMismatchAndReturnsOldArrowNewLabel() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("Bass");
        t.setInputRouting(new InputRouting(2, 1));
        t.setInputRoutingDisplayName("Mic 3");
        List<AudioChannelInfo> live = List.of(
                input(0, "Mic 1"), input(1, "Mic 2"), input(2, "Hi-Z Inst 3"));
        Optional<String> mismatch =
                ChannelNameMismatchDetector.detect(project, live, List.of());
        assertThat(mismatch).contains("'Mic 3' \u2192 'Hi-Z Inst 3'");
    }

    @Test
    void surfacesExactlyOneMismatchEvenWhenManyTracksReferenceRenamedChannels() {
        // Issue acceptance test: "a project saved with name 'Mic 3' and
        // loaded against a driver reporting 'Hi-Z Inst 3' surfaces
        // exactly one notification, not one per track."
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        for (int i = 0; i < 50; i++) {
            Track t = project.createAudioTrack("Track " + i);
            t.setInputRouting(new InputRouting(2, 1));
            t.setInputRoutingDisplayName("Mic 3");
        }
        List<AudioChannelInfo> live = List.of(
                input(0, "Mic 1"), input(1, "Mic 2"), input(2, "Hi-Z Inst 3"));
        // detect() returns at most one mismatch label — the caller fires
        // exactly one notification, no matter how many tracks reference
        // the renamed channel.
        Optional<String> result =
                ChannelNameMismatchDetector.detect(project, live, List.of());
        assertThat(result).contains("'Mic 3' \u2192 'Hi-Z Inst 3'");
    }

    @Test
    void detectsOutputSideMismatchWhenNoInputMismatch() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("Vocal");
        MixerChannel channel = project.getMixerChannelForTrack(t);
        channel.setOutputRouting(new OutputRouting(0, 2));
        channel.setOutputRoutingDisplayName("Main Out 1-2");
        List<AudioChannelInfo> liveOut = List.of(
                input(0, "Monitor L"), input(1, "Monitor R"));
        Optional<String> result =
                ChannelNameMismatchDetector.detect(project, List.of(), liveOut);
        assertThat(result).contains("'Main Out 1-2' \u2192 'Monitor L'");
    }

    @Test
    void ignoresOutOfRangeRoutingIndexes() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track t = project.createAudioTrack("Drums");
        // Saved routing references channel 7, but driver only reports 4.
        t.setInputRouting(new InputRouting(7, 1));
        t.setInputRoutingDisplayName("Mic 8");
        List<AudioChannelInfo> live = List.of(
                input(0, "Mic 1"), input(1, "Mic 2"),
                input(2, "Mic 3"), input(3, "Mic 4"));
        // Out-of-range indices are ignored — no spurious mismatch.
        assertThat(ChannelNameMismatchDetector.detect(project, live, List.of()))
                .isEmpty();
    }
}
