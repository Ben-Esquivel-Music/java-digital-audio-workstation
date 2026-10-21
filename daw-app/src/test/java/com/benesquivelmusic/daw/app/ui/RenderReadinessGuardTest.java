package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decisions D19, D28 and D31 — every entry point that renders
 * clip audio into a file asks the open project's audio first, through one
 * readiness query: while a clip of the rendered tracks still loads the
 * render is refused with
 * {@code Audio is still loading (N of M) — try again when loading completes}
 * (N of M over the whole project; a track that loaded renders while others
 * still load); when clips of the rendered tracks would be silent the user is
 * asked in the render path's words — {@code K clip(s) have missing audio and
 * will be silent. Export anyway?} for an export, the frozen-track warning
 * and {@code Freeze Anyway} for a freeze; otherwise it proceeds. With no
 * current run — the project on screen is not an open's — a clip with no
 * audio is still asked about. A freeze consults the guard before it renders
 * and renders nothing when the guard says no.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RenderReadinessGuardTest {

    private static final String EXPORT_ONE_SILENT = "1 clip has missing audio and will be silent. Export anyway?";
    private static final String FREEZE_ONE_SILENT = "1 clip has missing audio and will be silent in the frozen"
            + " audio. The frozen audio stays silent until you unfreeze or freeze again, even after relinking."
            + " Freeze anyway?";

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;
    /** Each question asked: title, message and the button that renders anyway. */
    private final List<List<String>> asked = new CopyOnWriteArrayList<>();
    private final AtomicBoolean answer = new AtomicBoolean();

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
        runOnFx(() -> fixture.lifecycle().renderGuard().setConfirmation((title, message, confirmLabel) -> {
            asked.add(List.of(title, message, confirmLabel));
            return answer.get();
        }));
    }

    @AfterEach
    void closeTheController() throws Exception {
        fixture.close();
    }

    @Test
    void aRenderIsRefusedWithTheLoadingWarningWhileClipAudioLoads() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Loading",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("a.wav"), 48_000, 0.5, 220.0),
                OpenProjectFixture.wav(workspace.resolve("library").resolve("b.wav"), 48_000, 0.5, 330.0));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        boolean mayRender = onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.EXPORT, fixture.project.get().getTracks()));

        assertThat(mayRender).isFalse();
        assertThat(asked).as("refused, not asked").isEmpty();
        assertThat(fixture.notifications().getLast()).satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.WARNING);
            assertThat(entry.message())
                    .isEqualTo("Audio is still loading (0 of 2) — try again when loading completes");
        });
    }

    @Test
    void aRenderOfTracksWithSilentClipsProceedsOnlyWhenTheUserConfirms() throws Exception {
        Path present = OpenProjectFixture.wav(workspace.resolve("library").resolve("present.wav"), 48_000, 0.5, 220.0);
        Path missing = OpenProjectFixture.wav(workspace.resolve("library").resolve("missing.wav"), 48_000, 0.5, 330.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Silent", present, missing);
        Files.delete(missing);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        fixture.releaseDecodesAndPulse();
        int shown = fixture.notifications().size();

        answer.set(false);
        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.EXPORT, fixture.project.get().getTracks())))
                .as("declined").isFalse();
        assertThat(asked).containsExactly(List.of("Missing Audio", EXPORT_ONE_SILENT, "Export Anyway"));

        answer.set(true);
        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.EXPORT, fixture.project.get().getTracks())))
                .as("confirmed").isTrue();
        assertThat(asked).hasSize(2);

        Track loadedTrack = onFx(() -> fixture.project.get().getTracks().getFirst());
        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.EXPORT, List.of(loadedTrack))))
                .as("a render of the loaded track alone has nothing silent to ask about").isTrue();
        assertThat(asked).hasSize(2);
        assertThat(fixture.notifications()).as("asked, not warned").hasSize(shown);
    }

    @Test
    void aFreezeAsksInFreezeWordsThatTheFrozenTrackStaysSilentUntilFrozenAgain() throws Exception {
        Path missing = OpenProjectFixture.wav(workspace.resolve("library").resolve("missing.wav"), 48_000, 0.5, 330.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Freeze Silent", missing);
        Files.delete(missing);
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        fixture.releaseDecodesAndPulse();

        answer.set(true);
        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.FREEZE, fixture.project.get().getTracks())))
                .as("confirmed").isTrue();

        assertThat(asked).containsExactly(List.of("Missing Audio", FREEZE_ONE_SILENT, "Freeze Anyway"));
    }

    @Test
    void freezingALoadedTrackProceedsWhileAnotherTrackStillLoads() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Still Loading",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("a.wav"), 48_000, 0.5, 220.0));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        // A track recorded in this session: its clip holds its audio, nothing loads it.
        Track recorded = onFx(() -> {
            Track track = fixture.project.get().createAudioTrack("Recorded");
            AudioClip take = new AudioClip("Take", 0.0, 1.0, workspace.resolve("take.wav").toString());
            take.setAudioData(new float[2][480]);
            track.addClip(take);
            return track;
        });
        Track loading = onFx(() -> fixture.project.get().getTracks().getFirst());
        assertThat(onFx(() -> fixture.audio().stateOf(loading.getClips().getFirst())))
                .as("fixture: the opened track still loads").isEqualTo(ClipAudioState.LOADING);

        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.FREEZE, List.of(recorded))))
                .as("nothing on the frozen track loads").isTrue();
        assertThat(asked).isEmpty();

        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.FREEZE, List.of(recorded, loading))))
                .as("a render that reads the loading track is still refused").isFalse();
        assertThat(fixture.notifications().getLast().message())
                .as("its counts are the whole project's")
                .isEqualTo("Audio is still loading (0 of 1) — try again when loading completes");
    }

    @Test
    void withNoCurrentOpenAClipWithoutAudioIsStillAskedAboutByExportAndFreeze() throws Exception {
        // A project no open began a run for — a DAWproject import builds one:
        // its clip reads from a file and has no audio.
        Track imported = onFx(() -> {
            DawProject project = new DawProject("Imported", OpenProjectFixture.PROJECT_FORMAT);
            Track track = project.createAudioTrack("Imported");
            track.addClip(new AudioClip("Verse", 0.0, 4.0, workspace.resolve("verse.wav").toString()));
            fixture.project.set(project);
            return track;
        });
        assertThat(onFx(() -> fixture.audio().currentGeneration())).as("fixture: no run is current").isEmpty();

        answer.set(false);
        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.EXPORT, List.of(imported)))).isFalse();
        assertThat(onFx(() -> fixture.lifecycle().renderGuard()
                .mayRender(RenderReadinessGuard.RenderPath.FREEZE, List.of(imported)))).isFalse();

        assertThat(asked).containsExactly(List.of("Missing Audio", EXPORT_ONE_SILENT, "Export Anyway"),
                List.of("Missing Audio", FREEZE_ONE_SILENT, "Freeze Anyway"));
    }

    @Test
    void aFreezeConsultsTheGuardBeforeItRendersAndRendersNothingWhenRefused() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Freeze",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("a.wav"), 48_000, 0.5, 220.0),
                OpenProjectFixture.wav(workspace.resolve("library").resolve("b.wav"), 48_000, 0.5, 330.0));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        List<List<Track>> consulted = new CopyOnWriteArrayList<>();
        List<String> statuses = new CopyOnWriteArrayList<>();

        runOnFx(() -> {
            TrackFreezeController freeze = new TrackFreezeController(fixture.project::get,
                    fixture.undoManager::get, null, _ -> { }, () -> { }, (message, _) -> statuses.add(message),
                    fixture.dispatcher);
            freeze.setRenderGuard(tracks -> {
                consulted.add(List.copyOf(tracks));
                // The open project's guard: its audio is still loading.
                return fixture.lifecycle().renderGuard().mayRender(RenderReadinessGuard.RenderPath.FREEZE, tracks);
            });
            List<Track> tracks = fixture.project.get().getTracks();
            freeze.freezeTrack(tracks.getFirst());
            freeze.freezeTracks(tracks);
        });

        List<Track> tracks = liveTracks();
        assertThat(consulted).as("each freeze asked the guard for the tracks it would render")
                .containsExactly(List.of(tracks.getFirst()), tracks);
        for (Track track : tracks) {
            assertThat(track.isFrozen()).as("refused: nothing rendered").isFalse();
        }
        assertThat(onFx(() -> fixture.undoManager.get().canUndo())).isFalse();
        assertThat(statuses).as("no freeze began").isEmpty();
        assertThat(fixture.notifications().getLast().message())
                .isEqualTo("Audio is still loading (0 of 2) — try again when loading completes");
    }

    private List<Track> liveTracks() throws Exception {
        return onFx(() -> List.copyOf(fixture.project.get().getTracks()));
    }
}
