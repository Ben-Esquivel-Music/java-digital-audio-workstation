package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decision D31 — an open that fails leaves the audio of the
 * project still on screen alone: the open retires the previous run only once
 * the new project parsed. Project A opens with its decodes held; an open of a
 * folder whose project file does not parse fails; A's clips still load, A's
 * report and its relink are still there, and A's loading operation stays in
 * the strip until A's run ends.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class FailedOpenKeepsTheOnScreenAudioTest {

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
    }

    @AfterEach
    void closeTheController() throws Exception {
        try {
            fixture.close();
        } finally {
            // The failed open took the corrupt folder's lock and kept it (the
            // project manager releases a lock only for a project it opened);
            // let it go, and its heartbeat with it, before the folder is deleted.
            fixture.projectManager.getLockManager().release();
        }
    }

    @Test
    void aFailedOpenLeavesTheOnScreenProjectLoadingReportedAndRelinkable() throws Exception {
        Path present = OpenProjectFixture.wav(workspace.resolve("library").resolve("present.wav"), 48_000, 0.5, 220.0);
        Path missing = OpenProjectFixture.wav(workspace.resolve("library").resolve("missing.wav"), 48_000, 0.5, 330.0);
        OpenProjectFixture.SavedProject songA = fixture.projectOnDisk("Song A", present, missing);
        Files.delete(missing);
        Path corrupt = Files.createDirectories(workspace.resolve("Corrupt"));
        Files.writeString(corrupt.resolve("project.daw"), "<daw-project><tracks>");
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(songA.directory()))).isTrue();
        DawProject projectA = onFx(fixture.project::get);
        OpenProjectAudio.Generation generationA = onFx(() -> fixture.audio().currentGeneration().orElseThrow());

        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(corrupt))).as("the open fails").isFalse();

        assertThat(fixture.notifications().getLast()).satisfies(entry -> {
            assertThat(entry.level()).isEqualTo(NotificationLevel.ERROR);
            assertThat(entry.message()).startsWith("Open failed: ");
        });
        assertThat(onFx(fixture.project::get)).as("fixture: A is still on screen").isSameAs(projectA);
        assertThat(onFx(() -> fixture.audio().isCurrent(generationA))).as("A's run was not retired").isTrue();
        assertThat(onFx(() -> fixture.audio().report().assetCount())).as("A's report is still there").isEqualTo(1);
        assertThat(onFx(() -> fixture.lifecycle().hasUnresolvedAssets())).as("and relink still offered").isTrue();
        assertThat(fixture.operationNames()).as("A is still loading").containsExactly("Loading audio — 0 of 1");
        assertThat(fixture.presenter.presentations).singleElement()
                .satisfies(presentation -> assertThat(presentation.view().dismissals).hasValue(0));

        fixture.releaseDecodesAndPulse();

        List<AudioClip> clips = fixture.liveClips();
        assertThat(clips.get(0).getAudioData()).as("A's present clip still landed").isNotNull();
        assertThat(clips.get(1).getAudioData()).isNull();
        assertThat(onFx(() -> fixture.audio().stateOf(clips.get(0)))).isEqualTo(ClipAudioState.LOADED);
        assertThat(fixture.operationNames()).as("A's run ended").isEmpty();

        runOnFx(() -> fixture.lifecycle().onRelinkMissingAssets());
        assertThat(fixture.presenter.presentations.getFirst().view().presents)
                .as("Relink Missing Assets… presents A's report again").hasValue(1);
    }
}
