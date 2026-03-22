package com.benesquivelmusic.daw.core.plugin.clap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClapPluginScannerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnDefaultSearchPaths() {
        ClapPluginScanner scanner = new ClapPluginScanner();
        assertThat(scanner.getSearchPaths()).isNotEmpty();
    }

    @Test
    void shouldAcceptCustomSearchPaths() {
        List<Path> paths = List.of(tempDir.resolve("plugins"));
        ClapPluginScanner scanner = new ClapPluginScanner(paths);
        assertThat(scanner.getSearchPaths()).isEqualTo(paths);
    }

    @Test
    void shouldReturnEmptyForNonExistentDirectories() {
        ClapPluginScanner scanner = new ClapPluginScanner(List.of(
                tempDir.resolve("nonexistent1"),
                tempDir.resolve("nonexistent2")));
        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void shouldDiscoverClapFiles() throws IOException {
        Path pluginDir = tempDir.resolve("clap-plugins");
        Files.createDirectory(pluginDir);
        Files.createFile(pluginDir.resolve("reverb.clap"));
        Files.createFile(pluginDir.resolve("delay.clap"));
        Files.createFile(pluginDir.resolve("readme.txt"));

        ClapPluginScanner scanner = new ClapPluginScanner(List.of(pluginDir));
        List<Path> results = scanner.scan();

        assertThat(results).hasSize(2);
        assertThat(results).extracting(Path::getFileName)
                .extracting(Path::toString)
                .containsExactlyInAnyOrder("reverb.clap", "delay.clap");
    }

    @Test
    void shouldNotIncludeNonClapFiles() throws IOException {
        Path pluginDir = tempDir.resolve("plugins");
        Files.createDirectory(pluginDir);
        Files.createFile(pluginDir.resolve("plugin.vst3"));
        Files.createFile(pluginDir.resolve("plugin.dll"));
        Files.createFile(pluginDir.resolve("plugin.so"));

        ClapPluginScanner scanner = new ClapPluginScanner(List.of(pluginDir));
        assertThat(scanner.scan()).isEmpty();
    }

    @Test
    void shouldScanMultipleDirectories() throws IOException {
        Path dir1 = tempDir.resolve("dir1");
        Path dir2 = tempDir.resolve("dir2");
        Files.createDirectory(dir1);
        Files.createDirectory(dir2);
        Files.createFile(dir1.resolve("a.clap"));
        Files.createFile(dir2.resolve("b.clap"));

        ClapPluginScanner scanner = new ClapPluginScanner(List.of(dir1, dir2));
        assertThat(scanner.scan()).hasSize(2);
    }

    @Test
    void shouldScanSingleDirectory() throws IOException {
        Path pluginDir = tempDir.resolve("single");
        Files.createDirectory(pluginDir);
        Files.createFile(pluginDir.resolve("synth.clap"));

        ClapPluginScanner scanner = new ClapPluginScanner();
        List<Path> results = scanner.scanDirectory(pluginDir);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getFileName().toString()).isEqualTo("synth.clap");
    }

    @Test
    void shouldReturnEmptyWhenScanningNonDirectory() {
        ClapPluginScanner scanner = new ClapPluginScanner();
        assertThat(scanner.scanDirectory(tempDir.resolve("nonexistent"))).isEmpty();
    }

    @Test
    void shouldRejectNullSearchPaths() {
        assertThatThrownBy(() -> new ClapPluginScanner(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullDirectory() {
        ClapPluginScanner scanner = new ClapPluginScanner();
        assertThatThrownBy(() -> scanner.scanDirectory(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReturnUnmodifiableSearchPaths() {
        ClapPluginScanner scanner = new ClapPluginScanner(List.of(tempDir));
        assertThatThrownBy(() -> scanner.getSearchPaths().add(Path.of("/extra")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnUnmodifiableScanResults() throws IOException {
        Path pluginDir = tempDir.resolve("unmod");
        Files.createDirectory(pluginDir);
        Files.createFile(pluginDir.resolve("test.clap"));

        ClapPluginScanner scanner = new ClapPluginScanner(List.of(pluginDir));
        List<Path> results = scanner.scan();

        assertThatThrownBy(() -> results.add(Path.of("/fake.clap")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldProvideDefaultSearchPathsStaticMethod() {
        List<Path> defaults = ClapPluginScanner.defaultSearchPaths();
        assertThat(defaults).isNotNull();
        assertThat(defaults).isNotEmpty();
    }
}
