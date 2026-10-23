package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Mixer channel order equals track order: {@code getMixer().getChannels()} is
 * {@code getTracks()} mapped through {@link DawProject#getMixerChannelForTrack(Track)},
 * in order. The render path pairs track {@code t} with mixer channel {@code t}
 * by position, so a channel out of place plays one track through another
 * track's strip — which is what duplicating any track but the last did while
 * the copy's channel was appended to the mixer.
 */
class ChannelOrderMatchesTrackOrderTest {

    private static final int FRAMES = 16;
    private static final long SWEEP_SEED = 0x5EEDL;
    private static final int SWEEP_STEPS = 400;

    /** One kind of edit the sweep applies; every kind must run at least once. */
    private enum Edit {
        CREATE_TRACK, ADD_TRACK_ACTION, DUPLICATE_FIRST, DUPLICATE_MIDDLE, DUPLICATE_LAST,
        REMOVE_TRACK_ACTION, MOVE_TRACK_ACTION, MOVE_TRACK, UNDO, REDO
    }

    @Test
    void duplicatingTheFirstMiddleOrLastTrackPutsTheCopysChannelRightAfterTheOriginals() {
        for (int position = 0; position < 3; position++) {
            DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
            List<Track> tracks = List.of(
                    project.createAudioTrack("A"), project.createAudioTrack("B"), project.createAudioTrack("C"));
            Track original = tracks.get(position);

            Track copy = project.duplicateTrack(original);

            assertThat(project.getTracks().indexOf(copy))
                    .as("the copy of %s follows it in the track list", original.getName())
                    .isEqualTo(position + 1);
            assertThat(project.getMixer().getChannels().get(position + 1))
                    .as("the channel after %s's channel is the copy's", original.getName())
                    .isSameAs(project.getMixerChannelForTrack(copy));
            assertMixerOrderMatchesTrackOrder(project, "duplicating " + original.getName());
        }
    }

    @Test
    void editsAndTheirUndoKeepChannelOrderEqualToTrackOrderAfterEveryStep() {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UndoManager history = new UndoManager();
        Track a = project.createAudioTrack("A");
        assertMixerOrderMatchesTrackOrder(project, "creating A");
        Track b = project.createAudioTrack("B");
        assertMixerOrderMatchesTrackOrder(project, "creating B");
        Track c = project.createAudioTrack("C");
        assertMixerOrderMatchesTrackOrder(project, "creating C");

        Track bCopy = project.duplicateTrack(b);
        assertMixerOrderMatchesTrackOrder(project, "duplicating the middle track B");
        history.execute(new RemoveTrackAction(project, a));
        assertMixerOrderMatchesTrackOrder(project, "removing A");
        history.undo();
        assertMixerOrderMatchesTrackOrder(project, "undoing the removal of A");

        Track d = new Track("D", TrackType.AUDIO);
        history.execute(new AddTrackAction(project, d));
        assertMixerOrderMatchesTrackOrder(project, "adding D");
        project.duplicateTrack(d);
        assertMixerOrderMatchesTrackOrder(project, "duplicating the last track D");
        history.execute(new MoveTrackAction(project, project.getTracks().indexOf(d), 0));
        assertMixerOrderMatchesTrackOrder(project, "moving D to the front");
        project.duplicateTrack(d);
        assertMixerOrderMatchesTrackOrder(project, "duplicating the first track D");
        history.execute(new RemoveTrackAction(project, bCopy));
        assertMixerOrderMatchesTrackOrder(project, "removing B's copy");
        history.undo();
        assertMixerOrderMatchesTrackOrder(project, "undoing the removal of B's copy");
        history.undo();
        assertMixerOrderMatchesTrackOrder(project, "undoing the move of D");
        history.undo();
        assertMixerOrderMatchesTrackOrder(project, "undoing the addition of D");
        history.redo();
        assertMixerOrderMatchesTrackOrder(project, "redoing the addition of D");

        project.moveTrack(0, project.getTracks().size() - 1);
        assertMixerOrderMatchesTrackOrder(project, "moving the first track to the end");
        project.removeTrack(c);
        assertMixerOrderMatchesTrackOrder(project, "removing C");
        project.duplicateTrack(project.getTracks().get(project.getTracks().size() / 2));
        assertMixerOrderMatchesTrackOrder(project, "duplicating a middle track");
    }

    /**
     * A seeded sweep of every track-order edit — the four mutators directly,
     * the add / remove / move actions through an {@link UndoManager}, and
     * undo / redo — asserting the contract after every step.
     *
     * <p>Tracks leave the project only through {@link RemoveTrackAction} and
     * the undo of {@link AddTrackAction}, and the sweep only ever adds fresh
     * tracks. That keeps the history replayable: a {@link MoveTrackAction}'s
     * indices stay in range when it is undone or redone, and no undo re-adds
     * a track that is still in the project. That last sequence is kept out on
     * purpose: {@code addTrack} of a track already in the project appends the
     * track a second time but leaves the mixer unchanged, which breaks this
     * contract in a way {@code duplicateTrack} does not cause.</p>
     */
    @Test
    void aSeededSweepOfEditsUndoAndRedoKeepsChannelOrderEqualToTrackOrder() {
        Random random = new Random(SWEEP_SEED);
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        UndoManager history = new UndoManager();
        Map<Edit, Integer> applied = new EnumMap<>(Edit.class);
        for (int i = 0; i < 3; i++) {
            project.createAudioTrack("Track " + i);
        }

        for (int step = 0; step < SWEEP_STEPS; step++) {
            Edit edit = Edit.values()[random.nextInt(Edit.values().length)];
            if (applyEdit(edit, project, history, random, step)) {
                applied.merge(edit, 1, Integer::sum);
                assertMixerOrderMatchesTrackOrder(project, "sweep step " + step + " (" + edit + ")");
            }
        }

        assertThat(applied.keySet())
                .as("the sweep applied every kind of edit at least once")
                .containsExactlyInAnyOrder(Edit.values());
    }

    @Test
    void movingTheCopyMovesTheCopysChannel() {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track a = project.createAudioTrack("A");
        Track b = project.createAudioTrack("B");
        Track c = project.createAudioTrack("C");
        Track copy = project.duplicateTrack(a);

        project.moveTrack(project.getTracks().indexOf(copy), 3);

        assertThat(project.getTracks()).containsExactly(a, b, c, copy);
        assertThat(project.getMixer().getChannels().get(3))
                .as("the channel moved to the end is the copy's")
                .isSameAs(project.getMixerChannelForTrack(copy));
        assertMixerOrderMatchesTrackOrder(project, "moving the copy to the end");
    }

    /**
     * Renders one block the way the engine does — track {@code t}'s audio in
     * buffer {@code t}, mixed by {@link Mixer#mixDown(float[][][], float[][], float[][][], int)}
     * — after duplicating A, halving A's fader and muting B's strip.
     *
     * <p>The levels are 0.1 times a power of two and A's fader is 0.5, exact
     * in binary, so the correct pairing of buffers and strips and each
     * misplacement of the copy's strip sum to different values: 1.25 is
     * reached only by A at half level + A′ + C, that is with
     * A's buffer on A's strip, the copy's 0.8 on its own unity strip and B's
     * 0.2 out of the mix. Swapped strips for A and A′ give 0.1 + 0.4 + 0.4 =
     * 0.9; the copy's strip appended at the end gives 0.05 + 0.2 + 0.4 = 0.65.
     * Mono buffers into a mono output bypass the channel pan law, and the
     * sum is exact up to float rounding of three terms (one ulp at 1.25 is
     * about 1.2e-7).</p>
     */
    @Test
    void theMixPlaysEveryTrackThroughItsOwnStripAfterADuplicate() {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track a = project.createAudioTrack("A");
        Track b = project.createAudioTrack("B");
        Track c = project.createAudioTrack("C");
        Track aCopy = project.duplicateTrack(a);
        project.getMixerChannelForTrack(a).setVolume(0.5);
        project.getMixerChannelForTrack(b).setMuted(true);
        Map<Track, Float> levels = new IdentityHashMap<>();
        levels.put(a, 0.1f);
        levels.put(aCopy, 0.8f);
        levels.put(b, 0.2f);
        levels.put(c, 0.4f);

        List<Track> tracks = project.getTracks();
        float[][][] trackBuffers = new float[tracks.size()][1][FRAMES];
        for (int t = 0; t < tracks.size(); t++) {
            Arrays.fill(trackBuffers[t][0], levels.get(tracks.get(t)));
        }
        Mixer mixer = project.getMixer();
        float[][] output = new float[1][FRAMES];
        float[][][] returnBuffers = new float[mixer.getReturnBusCount()][1][FRAMES];

        mixer.mixDown(trackBuffers, output, returnBuffers, FRAMES);

        for (int frame = 0; frame < FRAMES; frame++) {
            assertThat(output[0][frame])
                    .as("frame %d is A at half level + A' + C with muted B silent", frame)
                    .isCloseTo(0.05f + 0.8f + 0.4f, within(1e-6f));
        }
    }

    private static boolean applyEdit(Edit edit, DawProject project, UndoManager history,
                                     Random random, int step) {
        List<Track> tracks = project.getTracks();
        int count = tracks.size();
        switch (edit) {
            case CREATE_TRACK -> project.createAudioTrack("Created " + step);
            case ADD_TRACK_ACTION -> history.execute(
                    new AddTrackAction(project, new Track("Added " + step, TrackType.AUDIO)));
            case DUPLICATE_FIRST -> {
                if (count == 0) {
                    return false;
                }
                project.duplicateTrack(tracks.getFirst());
            }
            case DUPLICATE_MIDDLE -> {
                if (count < 3) {
                    return false;
                }
                project.duplicateTrack(tracks.get(1 + random.nextInt(count - 2)));
            }
            case DUPLICATE_LAST -> {
                if (count == 0) {
                    return false;
                }
                project.duplicateTrack(tracks.getLast());
            }
            case REMOVE_TRACK_ACTION -> {
                if (count == 0) {
                    return false;
                }
                history.execute(new RemoveTrackAction(project, tracks.get(random.nextInt(count))));
            }
            case MOVE_TRACK_ACTION -> {
                if (count < 2) {
                    return false;
                }
                history.execute(new MoveTrackAction(project, random.nextInt(count), random.nextInt(count)));
            }
            case MOVE_TRACK -> {
                if (count < 2) {
                    return false;
                }
                project.moveTrack(random.nextInt(count), random.nextInt(count));
            }
            case UNDO -> {
                return history.undo();
            }
            case REDO -> {
                return history.redo();
            }
        }
        return true;
    }

    /**
     * The contract: the mixer's channels are the project's tracks mapped to
     * their channels, element for element (by identity) and in order.
     */
    private static void assertMixerOrderMatchesTrackOrder(DawProject project, String afterStep) {
        List<MixerChannel> channelsInTrackOrder = project.getTracks().stream()
                .map(project::getMixerChannelForTrack)
                .toList();
        assertThat(project.getMixer().getChannels())
                .as("mixer channel order equals track order after %s", afterStep)
                .containsExactlyElementsOf(channelsInTrackOrder);
    }
}
