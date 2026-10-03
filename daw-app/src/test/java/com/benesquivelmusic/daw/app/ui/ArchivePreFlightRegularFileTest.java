package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.archive.ArchiveOptions;
import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiver;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 323 probe, PR #978 Copilot round 2: the Archive Project pre-flight's
 * {@code namesRegularFile}.
 *
 * <p>Pins that an absolute clip reference naming something that exists but is
 * not a regular file — here a directory, as a single-file clip's source and as
 * a take's segment — is listed as missing, exactly as {@link ProjectArchiver}
 * treats it (it packs only regular files, so the size estimate leaves it
 * out): a pre-flight that asked only whether the path exists would hide it
 * from the dialog, and the archive would then skip it with no decision.</p>
 */
class ArchivePreFlightRegularFileTest {

    @TempDir
    Path tmp;

    @Test
    void anAbsoluteClipReferenceNamingADirectoryIsListedAsTheArchiverTreatsIt() throws IOException {
        Path directory = Files.createDirectories(tmp.resolve("not-a-file.wav"));
        Path present = Files.write(tmp.resolve("segment-000.wav"), new byte[]{1, 2, 3});
        String directoryReference = directory.toAbsolutePath().toString();
        DawProject project = new DawProject("Directory", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("T");
        track.addClip(new AudioClip("single", 0.0, 1.0, directoryReference));
        AudioClip take = new AudioClip("Take 1", 1.0, 8.0, present.toString());
        take.setSourceSegmentPaths(List.of(present.toString(), directoryReference));
        track.addClip(take);

        assertThat(new ProjectArchiver().previewAssetSizes(project, ArchiveOptions.defaults()))
                .as("fixture: the archiver packs only the regular file").hasSize(1);
        assertThat(ProjectLifecycleController.collectMissingAssetPaths(project))
                .containsExactly(directoryReference, directoryReference);
    }
}
