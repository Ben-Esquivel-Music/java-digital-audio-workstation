package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, "Goals — Tests", no-FX-stall: opening a many-clip project
 * performs no decode on the FX thread, the window is interactive before
 * rehydration completes, and per-clip completions arrive through the
 * dispatcher seam, coalesced (decision D12: one completion queue and one
 * keyed drain per open, so a pulse repaints at most once however many clips
 * landed).
 *
 * <p>Forty clips, each with its own source, open while every decode is held
 * ({@link OpenProjectFixture.HeldDecodeThreads}): the load returns, the UI
 * was rebuilt and the open announced, the FX thread answers, and no clip has
 * audio — nothing decoded synchronously; and a source rewritten while every
 * decode is held is what its clip later holds, so its decode ran after the
 * open returned. Released, the decodes run on their
 * own threads and every result waits in the queue: no clip has audio and
 * nothing repainted until the dispatcher pulses, and that one pulse lands
 * all forty with one repaint.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ProjectOpenDoesNotDecodeOnTheFxThreadTest {

    private static final int CLIPS = 40;

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
    }

    @AfterEach
    void closeTheController() throws Exception {
        fixture.close();
    }

    @Test
    void fortyHeldDecodesNeitherHoldTheOpenNorRunOnTheFxThreadAndLandInOnePulseWithOneRepaint()
            throws Exception {
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < CLIPS; i++) {
            sources.add(OpenProjectFixture.wav(workspace.resolve("library").resolve("clip-" + i + ".wav"),
                    48_000, 0.25, 110.0 + i));
        }
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Forty", sources.toArray(Path[]::new));

        // Bounded at the fixture's guard: a load that waited for a held decode never returns.
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        assertThat(fixture.rebuilds).as("the UI was rebuilt before any decode ran").hasValue(1);
        assertThat(fixture.notifications()).as("and the open announced").singleElement()
                .satisfies(entry -> assertThat(entry.message())
                        .isEqualTo("Opened project: " + saved.directory().getFileName()));
        runOnFx(() -> { }); // the FX thread is free: an FX no-op round-trips within the guard
        assertThat(fixture.decodes.handedOver()).as("one decode job per source").isEqualTo(CLIPS);
        assertThat(fixture.decodes.ranOnFx).as("every decode is still held").isEmpty();
        List<AudioClip> clips = fixture.liveClips();
        assertThat(clips).hasSize(CLIPS);
        for (AudioClip clip : clips) {
            assertThat(clip.getAudioData()).as("no decode ran on the FX thread during the open").isNull();
            assertThat(onFx(() -> fixture.audio().stateOf(clip))).isEqualTo(ClipAudioState.LOADING);
        }
        assertThat(fixture.operationNames()).containsExactly("Loading audio — 0 of " + CLIPS);
        assertThat(onFx(() -> fixture.audio().readiness().loading())).isTrue();

        // The open has returned and every decode is held: a source rewritten now
        // with another tone of the same length and format is what its decode
        // reads — so no decode of it ran during the open, on any thread.
        int rewritten = CLIPS / 2;
        OpenProjectFixture.wav(sources.get(rewritten), 48_000, 0.25, 1_000.0);
        float[][] newTone = importedAudio(sources.get(rewritten));
        assertThat(Arrays.deepEquals(newTone, saved.clips().get(rewritten).getAudioData()))
                .as("fixture: the rewritten source holds another tone").isFalse();

        fixture.decodes.release();
        fixture.decodes.awaitEveryTaskEnded();

        assertThat(fixture.decodes.ranOnFx).as("every decode ran, on its own thread")
                .hasSize(CLIPS).containsOnly(false);
        runOnFx(() -> { });
        for (AudioClip clip : clips) {
            assertThat(clip.getAudioData()).as("completions are applied only inside a pulse").isNull();
        }
        assertThat(fixture.repaints).as("nothing repainted outside a pulse").hasValue(0);

        fixture.pulse();

        for (AudioClip clip : clips) {
            assertThat(clip.getAudioData()).as("one pulse lands every queued completion").isNotNull();
            assertThat(onFx(() -> fixture.audio().stateOf(clip))).isEqualTo(ClipAudioState.LOADED);
        }
        assertThat(Arrays.deepEquals(clips.get(rewritten).getAudioData(), newTone))
                .as("the held decode read the source after the open returned: the new tone").isTrue();
        assertThat(fixture.repaints).as("one repaint for the forty clips of the pulse").hasValue(1);
        assertThat(fixture.repaintsOutsidePulse).hasValue(0);
        assertThat(fixture.operationNames()).as("the run ended: the strip operation is gone").isEmpty();
        assertThat(onFx(() -> fixture.audio().readiness().loading())).isFalse();

        fixture.pulse();
        assertThat(fixture.repaints).as("an empty pulse repaints nothing").hasValue(1);
        assertThat(onFx(() -> fixture.project.get().isDirty())).as("loading is not an edit").isFalse();
    }

    /** The audio an import of {@code source} into a project of the fixture's format holds. */
    private static float[][] importedAudio(Path source) throws Exception {
        DawProject scratch = new DawProject("Scratch", OpenProjectFixture.PROJECT_FORMAT);
        new AudioFileImporter(scratch).importFile(source, 0.0);
        return scratch.getTracks().getFirst().getClips().getFirst().getAudioData();
    }
}
