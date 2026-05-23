package com.benesquivelmusic.daw.core.event;

import com.benesquivelmusic.daw.core.audio.AddClipAction;
import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.CutClipsAction;
import com.benesquivelmusic.daw.core.audio.MoveClipAction;
import com.benesquivelmusic.daw.core.audio.RemoveClipAction;
import com.benesquivelmusic.daw.core.midi.AddMidiNoteAction;
import com.benesquivelmusic.daw.core.midi.MidiNoteData;
import com.benesquivelmusic.daw.core.midi.SetNoteVelocityAction;
import com.benesquivelmusic.daw.core.mixer.InsertEffectAction;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.SetPluginParameterAction;
import com.benesquivelmusic.daw.core.mixer.ToggleBypassAction;
import com.benesquivelmusic.daw.core.project.AddTrackAction;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.sdk.event.EventBus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 283 — canonical "are publishers wired?" smoke test.
 *
 * <p>Drives one representative {@code UndoableAction} from each
 * publishing family ({@code ClipEvent.Added}, {@code ClipEvent.Removed},
 * {@code ClipEvent.Moved}, {@code ClipEvent.Trimmed},
 * {@code PluginEvent.Loaded}, {@code PluginEvent.Bypassed},
 * {@code MixerEvent.ChannelAdded}) through its normal {@code execute()}
 * entry point and asserts that the bus's {@code publishedByType()} map
 * shows a non-zero count for each — proving the producer side is live
 * and not silently bypassed.</p>
 *
 * <p>This complements the per-action symmetry tests (which assert
 * payload identity for one action each) by guaranteeing that the
 * union of all publishing actions actually hits the bus.</p>
 */
class EventBusMetricsSmokeTest {

    @Test
    void publishedByTypeShowsAllRepresentativeEventFamilies() {
        EventBus bus = DefaultEventBus.builder().build();
        EventBusPublisher.setDefault(bus);
        try {
            DawProject project = new DawProject("Smoke", AudioFormat.CD_QUALITY);

            // 1. MixerEvent.ChannelAdded — AddTrackAction also drives
            //    DawProject.addTrack which creates the mixer channel.
            Track track = new Track("t", TrackType.AUDIO);
            new AddTrackAction(project, track).execute();

            // 2. ClipEvent.Added — AddClipAction on the track.
            AudioClip clip = new AudioClip("c", 0.0, 4.0, null);
            new AddClipAction(track, clip).execute();

            // 3. ClipEvent.Moved — MoveClipAction relocates the clip.
            new MoveClipAction(track, clip, 8.0).execute();

            // 4. ClipEvent.Removed — RemoveClipAction (also exercised
            //    indirectly by CutClipsAction below).
            AudioClip toCut = new AudioClip("d", 0.0, 4.0, null);
            track.addClip(toCut);
            new RemoveClipAction(track, toCut).execute();

            // 5. ClipEvent.Removed (per-leaf) — CutClipsAction.
            AudioClip cutA = new AudioClip("e", 16.0, 4.0, null);
            track.addClip(cutA);
            new CutClipsAction(List.of(Map.entry(track, cutA))).execute();

            // 6. PluginEvent.Loaded — InsertEffectAction (+
            //    PluginEvent.Bypassed via ToggleBypassAction).
            MixerChannel channel = project.getMixerChannelForTrack(track);
            InsertSlot slot = new InsertSlot("EQ",
                    InsertEffectFactory.createProcessor(
                            InsertEffectType.PARAMETRIC_EQ, 2, 44_100),
                    InsertEffectType.PARAMETRIC_EQ);
            new InsertEffectAction(channel, 0, slot).execute();
            new ToggleBypassAction(channel, 0, true).execute();

            // 7. ClipEvent.Trimmed — MIDI note add (story 283 maps
            //    note-level edits to Trimmed).
            Track midiTrack = new Track("m", TrackType.MIDI);
            new AddTrackAction(project, midiTrack).execute();
            MidiNoteData addedNote = new MidiNoteData(60, 0, 4, 100, 0);
            new AddMidiNoteAction(midiTrack.getMidiClip(), addedNote).execute();

            // 8. ClipEvent.Trimmed — velocity edit (story 283).
            new SetNoteVelocityAction(midiTrack.getMidiClip(), addedNote, 80)
                    .execute();

            // 9. PluginEvent.ParameterChanged — plugin parameter set.
            var paramHandler = InsertEffectFactory.createParameterHandler(
                    InsertEffectType.PARAMETRIC_EQ, slot.getProcessor());
            new SetPluginParameterAction(slot, 0, -10.0, -20.0, paramHandler)
                    .execute();

            Map<String, Long> published = bus.metrics().publishedByType();
            assertThat(published)
                    .containsKeys(
                            "ClipEvent.Added",
                            "ClipEvent.Removed",
                            "ClipEvent.Moved",
                            "ClipEvent.Trimmed",
                            "PluginEvent.Loaded",
                            "PluginEvent.Bypassed",
                            "PluginEvent.ParameterChanged",
                            "MixerEvent.ChannelAdded");
            assertThat(published.get("ClipEvent.Added")).isPositive();
            assertThat(published.get("ClipEvent.Removed")).isPositive();
            assertThat(published.get("ClipEvent.Moved")).isPositive();
            assertThat(published.get("ClipEvent.Trimmed")).isPositive();
            assertThat(published.get("PluginEvent.Loaded")).isPositive();
            assertThat(published.get("PluginEvent.Bypassed")).isPositive();
            assertThat(published.get("PluginEvent.ParameterChanged")).isPositive();
            assertThat(published.get("MixerEvent.ChannelAdded")).isPositive();

            // Sanity check that channelId == trackId by invariant.
            assertThat(channel.getId()).isEqualTo(UUID.fromString(track.getId()));
        } finally {
            EventBusPublisher.setDefault(null);
            bus.close();
        }
    }
}
