package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.audio.AudioFormat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project's edit revision (story 331, ruling F1): every edit advances
 * it, so a save can clear the unsaved-changes flag only up to the revision
 * it captured.
 */
class DawProjectEditRevisionTest {

    @Test
    void everyProjectTakesARevisionNoOtherProjectHas() {
        DawProject first = new DawProject("First", AudioFormat.CD_QUALITY);
        DawProject second = new DawProject("Second", AudioFormat.CD_QUALITY);

        first.markDirty();

        assertThat(second.getEditRevision()).isNotEqualTo(first.getEditRevision());
        assertThat(new DawProject("Third", AudioFormat.CD_QUALITY).getEditRevision())
                .isNotIn(first.getEditRevision(), second.getEditRevision());
    }

    @Test
    void everyMarkDirtyAdvancesTheRevisionAlsoWhileTheProjectIsAlreadyDirty() {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        long created = project.getEditRevision();

        project.markDirty();
        long firstEdit = project.getEditRevision();
        project.markDirty();
        long secondEdit = project.getEditRevision();

        assertThat(firstEdit).isGreaterThan(created);
        assertThat(secondEdit).as("already dirty, still a new revision").isGreaterThan(firstEdit);
    }

    @Test
    void markCleanLeavesTheRevisionAsItIs() {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        project.markDirty();
        long edited = project.getEditRevision();

        project.markClean();

        assertThat(project.getEditRevision()).isEqualTo(edited);
    }

    @Test
    void theConditionalCleanClearsAnUnchangedProjectAndSignalsTheTransitionOnce() {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        List<DawProject.ChangeKind> changes = new ArrayList<>();
        project.addChangeListener(changes::add);
        project.markDirty();
        long captured = project.getEditRevision();

        assertThat(project.markCleanIfUnchanged(captured)).isTrue();
        assertThat(project.isDirty()).isFalse();
        assertThat(project.markCleanIfUnchanged(captured)).as("still at the captured revision").isTrue();

        assertThat(changes).containsExactly(DawProject.ChangeKind.DIRTY, DawProject.ChangeKind.DIRTY);
    }

    @Test
    void anEditBetweenTheCaptureAndTheCleanKeepsTheProjectDirty() throws Exception {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        List<DawProject.ChangeKind> changes = new ArrayList<>();
        project.markDirty();
        long captured = project.getEditRevision();
        project.addChangeListener(changes::add);

        Thread editor = Thread.ofVirtual().start(project::markDirty);
        editor.join();

        assertThat(project.markCleanIfUnchanged(captured)).isFalse();
        assertThat(project.isDirty()).isTrue();
        assertThat(changes).as("no transition: dirty throughout").isEmpty();
    }
}
