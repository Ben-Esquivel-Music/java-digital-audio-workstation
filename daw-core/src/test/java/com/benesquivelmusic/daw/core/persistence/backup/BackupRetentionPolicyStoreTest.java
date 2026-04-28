package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRetentionPolicyStoreTest {

    @Test
    void roundTripsThroughJson() {
        BackupRetentionPolicy original = new BackupRetentionPolicy(
                7, 12, 30, 4, Duration.ofDays(60), 5L * 1024L * 1024L);
        String json = BackupRetentionPolicyStore.toJson(original);
        BackupRetentionPolicy parsed = BackupRetentionPolicyStore.parse(json);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void parseFillsMissingFieldsWithDefaults() {
        BackupRetentionPolicy parsed = BackupRetentionPolicyStore.parse("{ \"keepRecent\": 3 }");
        assertThat(parsed.keepRecent()).isEqualTo(3);
        assertThat(parsed.keepHourly()).isEqualTo(BackupRetentionPolicy.DEFAULT.keepHourly());
        assertThat(parsed.keepDaily()).isEqualTo(BackupRetentionPolicy.DEFAULT.keepDaily());
    }

    @Test
    void globalPathDefaultsToHomeDawDir() {
        Path expected = Path.of(System.getProperty("user.home", "."), ".daw", "backup-retention.json");
        assertThat(BackupRetentionPolicyStore.defaultGlobalPath()).isEqualTo(expected);
    }

    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path tempDir) throws IOException {
        assertThat(BackupRetentionPolicyStore.load(tempDir.resolve("missing.json"))).isEmpty();
    }

    @Test
    void saveAndLoadRoundTripsToDisk(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("nested/backup-retention.json");
        BackupRetentionPolicy p = BackupRetentionPolicy.DEFAULT.withKeepDaily(7);
        BackupRetentionPolicyStore.save(file, p);
        assertThat(file).exists();
        BackupRetentionPolicy loaded = BackupRetentionPolicyStore.load(file).orElseThrow();
        assertThat(loaded).isEqualTo(p);
    }

    @Test
    void resolveForProjectPrefersOverrideOverGlobal(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve(".daw").resolve("backup-retention.json");
        BackupRetentionPolicy globalPolicy = BackupRetentionPolicy.DEFAULT.withKeepRecent(2);
        BackupRetentionPolicyStore.save(globalFile, globalPolicy);

        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);
        BackupRetentionPolicy projectPolicy = BackupRetentionPolicy.DEFAULT.withKeepRecent(99);
        BackupRetentionPolicyStore.save(projectDir.resolve(BackupRetentionPolicyStore.FILE_NAME),
                projectPolicy);

        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.resolveForProject(projectDir).keepRecent()).isEqualTo(99);
    }

    @Test
    void resolveForProjectFallsBackToGlobalWhenNoOverride(@TempDir Path tempDir) throws IOException {
        Path globalFile = tempDir.resolve(".daw").resolve("backup-retention.json");
        BackupRetentionPolicy globalPolicy = BackupRetentionPolicy.DEFAULT.withKeepRecent(2);
        BackupRetentionPolicyStore.save(globalFile, globalPolicy);

        Path projectDir = tempDir.resolve("proj");
        Files.createDirectories(projectDir);

        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.resolveForProject(projectDir).keepRecent()).isEqualTo(2);
    }

    @Test
    void loadGlobalOrDefaultWhenMissingReturnsDefault(@TempDir Path tempDir) {
        Path globalFile = tempDir.resolve("absent.json");
        BackupRetentionPolicyStore store = new BackupRetentionPolicyStore(globalFile);
        assertThat(store.loadGlobalOrDefault()).isEqualTo(BackupRetentionPolicy.DEFAULT);
    }
}
