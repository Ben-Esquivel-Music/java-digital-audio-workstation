package com.benesquivelmusic.daw.core.persistence.archive;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.StoreParameterJarTestPlugin;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveRestoreStructuredRollbackTest {

    @TempDir
    Path tmp;

    @Test
    void restoreFailureLeavesNoTargetDirectoryOrStagingDebrisWhenTargetDidNotExist()
            throws IOException {
        byte[] invalidProject = """
                <project>
                  <audio-format sample-rate="-1" channels="2" bit-depth="16" buffer-size="512"/>
                </project>
                """.getBytes(StandardCharsets.UTF_8);
        Path archive = tmp.resolve("malformed.dawz");
        writeArchive(archive, invalidProject);

        Path targetDir = tmp.resolve("restored-project");
        Path stagingDir = tmp.resolve(".restored-project.restoring.tmp");

        assertThat(targetDir).doesNotExist();
        assertThat(stagingDir).doesNotExist();

        assertThatThrownBy(() -> new ProjectArchiver().openArchive(
                archive, targetDir, MissingAssetResolver.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRate");

        assertThat(targetDir).doesNotExist();
        assertThat(stagingDir).doesNotExist();
    }

    /**
     * Story 334 (critique J11, ruling-3 C5): a restore that fails after it
     * read the project releases the plugins the project's inserts built, and
     * a release that fails is logged as the project reader logs one.
     */
    @Test
    void aRestoreThatFailsAfterReadingTheProjectReleasesItsPluginsAndLogsAFailedRelease() throws Exception {
        Path ghost = tmp.resolve("ghost.wav");
        Files.write(ghost, new byte[]{9, 8, 7, 6});
        DawProject project = new DawProject("Ghost", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Audio");
        track.addClip(new AudioClip("ghost", 0, 1.0, ghost.toString()));
        project.getMixerChannelForTrack(track).addInsert(
                InsertEffectFactory.createSlotFromPlugin(new StoreParameterJarTestPlugin()).orElseThrow());
        Files.delete(ghost);
        List<StoreParameterJarTestPlugin> made = new CopyOnWriteArrayList<>();
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.setPluginResolver((identity, context) -> {
            StoreParameterJarTestPlugin plugin = new StoreParameterJarTestPlugin();
            plugin.initialize(context);
            made.add(plugin);
            return new PluginResolution.Resolved(new LoadedPlugin(plugin, () -> {
                plugin.dispose();
                throw new IllegalStateException("the plugin cannot be released");
            }));
        });
        ProjectArchiver archiver = new ProjectArchiver(new ProjectSerializer(), deserializer, "test");
        Path archive = tmp.resolve("ghost.dawz");
        ArchiveTestSupport.archive(archiver, project, archive);
        List<LogRecord> fine = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.FINE) {
                    fine.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger readerLog = Logger.getLogger(ProjectDeserializer.class.getName());
        Level levelBefore = readerLog.getLevel();
        readerLog.setLevel(Level.FINE);
        readerLog.addHandler(handler);
        try {
            assertThatThrownBy(() -> archiver.openArchive(archive, tmp.resolve("ghost-restore"),
                    (stored, hints) -> {
                        throw new IllegalStateException("the locate dialog failed");
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the locate dialog failed");

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (fine.isEmpty() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
        } finally {
            readerLog.removeHandler(handler);
            readerLog.setLevel(levelBefore);
        }

        assertThat(made).singleElement().satisfies(plugin ->
                assertThat(plugin.isDisposed()).as("the plugin the read built is released").isTrue());
        assertThat(fine).singleElement().satisfies(record -> {
            assertThat(record.getMessage()).isEqualTo("Releasing the inserts of a project that was not opened failed");
            assertThat(record.getThrown()).hasRootCauseMessage("the plugin cannot be released");
        });
    }

    private static void writeArchive(Path archive, byte[] projectBytes) throws IOException {
        try (var out = Files.newOutputStream(archive);
             var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(ArchiveHeader.FILE_NAME));
            zip.write(headerFor(projectBytes).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(ArchiveHeader.PROJECT_DOC_NAME));
            zip.write(projectBytes);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(ArchiveHeader.ASSETS_DIR + "/partial.wav"));
            zip.write(new byte[]{1, 2, 3});
            zip.closeEntry();
        }
    }

    private static String headerFor(byte[] projectBytes) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("projectName", "Broken Restore");
        properties.setProperty("archiveDate", Instant.EPOCH.toString());
        properties.setProperty("assetCount", "1");
        properties.setProperty("originalRoot", "");
        properties.setProperty("dawVersion", "test");
        properties.setProperty("projectDocSha256",
                ProjectArchiver.sha256Hex(projectBytes));
        StringWriter writer = new StringWriter();
        properties.store(writer, "test archive");
        return writer.toString();
    }
}
