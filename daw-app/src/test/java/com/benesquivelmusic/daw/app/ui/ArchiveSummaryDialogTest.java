package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.persistence.archive.ProjectArchiveSummary;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ArchiveSummaryDialog}'s pure formatting helpers.
 *
 * <p>The dialog's actual {@code show()} entry points open a JavaFX
 * {@link javafx.scene.control.Alert} and therefore require a screen and
 * a started platform; here we only exercise the package-private
 * formatting methods that have no JavaFX dependency, mirroring the
 * approach used by {@link MenuEnablementPolicyTest}.</p>
 */
class ArchiveSummaryDialogTest {

    @Test
    void humanReadableBytesUsesBinaryUnits() {
        assertThat(ArchiveSummaryDialog.humanReadableBytes(0)).isEqualTo("0 B");
        assertThat(ArchiveSummaryDialog.humanReadableBytes(1023)).isEqualTo("1023 B");
        assertThat(ArchiveSummaryDialog.humanReadableBytes(1024)).isEqualTo("1.0 KiB");
        assertThat(ArchiveSummaryDialog.humanReadableBytes(1536)).isEqualTo("1.5 KiB");
        assertThat(ArchiveSummaryDialog.humanReadableBytes(1024L * 1024)).isEqualTo("1.0 MiB");
        // 1.2 GiB ≈ 1_288_490_188 bytes
        assertThat(ArchiveSummaryDialog.humanReadableBytes(1_288_490_188L)).isEqualTo("1.2 GiB");
    }

    @Test
    void formatHeadlineMatchesIssueExample() {
        ProjectArchiveSummary summary = new ProjectArchiveSummary(
                Path.of("/tmp/a.dawz"), 42, 1_288_490_188L, 1024L);
        assertThat(ArchiveSummaryDialog.formatHeadline(summary))
                .isEqualTo("Archive saved: 42 assets, 1.2 GiB");
    }

    @Test
    void formatHeadlineSingularAsset() {
        ProjectArchiveSummary summary = new ProjectArchiveSummary(
                Path.of("/tmp/a.dawz"), 1, 2048L, 1024L);
        assertThat(ArchiveSummaryDialog.formatHeadline(summary))
                .isEqualTo("Archive saved: 1 asset, 2.0 KiB");
    }

    @Test
    void archivePathDisplayShowsFileName() {
        assertThat(ArchiveSummaryDialog.archivePathDisplay(Path.of("/some/dir/song.dawz")))
                .isEqualTo("song.dawz");
        assertThat(ArchiveSummaryDialog.archivePathDisplay(null)).isEmpty();
    }
}
