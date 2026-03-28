package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginScanner;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClapPluginManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateWithDefaultScanner() {
        ClapPluginManager manager = new ClapPluginManager();
        assertThat(manager.getScanner()).isNotNull();
        assertThat(manager.getAvailablePlugins()).isEmpty();
    }

    @Test
    void shouldCreateWithCustomScanner() {
        ClapPluginScanner scanner = new ClapPluginScanner(List.of(tempDir));
        ClapPluginManager manager = new ClapPluginManager(scanner);
        assertThat(manager.getScanner()).isSameAs(scanner);
    }

    @Test
    void shouldRejectNullScanner() {
        assertThatThrownBy(() -> new ClapPluginManager(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scanner");
    }

    @Test
    void shouldScanForPlugins() throws IOException {
        Files.createFile(tempDir.resolve("effect.clap"));
        Files.createFile(tempDir.resolve("synth.clap"));
        Files.createFile(tempDir.resolve("not-a-plugin.txt"));

        ClapPluginScanner scanner = new ClapPluginScanner(List.of(tempDir));
        ClapPluginManager manager = new ClapPluginManager(scanner);

        int count = manager.scanForPlugins();

        assertThat(count).isEqualTo(2);
        assertThat(manager.getAvailablePlugins()).hasSize(2);
        assertThat(manager.getAvailablePlugins())
                .extracting(Path::getFileName)
                .extracting(Path::toString)
                .containsExactlyInAnyOrder("effect.clap", "synth.clap");
    }

    @Test
    void shouldReturnZeroWhenNoPluginsFound() {
        ClapPluginScanner scanner = new ClapPluginScanner(List.of(tempDir));
        ClapPluginManager manager = new ClapPluginManager(scanner);

        int count = manager.scanForPlugins();

        assertThat(count).isEqualTo(0);
        assertThat(manager.getAvailablePlugins()).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiablePluginList() throws IOException {
        Files.createFile(tempDir.resolve("test.clap"));
        ClapPluginScanner scanner = new ClapPluginScanner(List.of(tempDir));
        ClapPluginManager manager = new ClapPluginManager(scanner);
        manager.scanForPlugins();

        assertThatThrownBy(() -> manager.getAvailablePlugins().add(Path.of("/fake.clap")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectNullLibraryPathOnLoad() {
        ClapPluginManager manager = new ClapPluginManager();

        assertThatThrownBy(() -> manager.loadPlugin(null, new TestPluginContext()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("libraryPath");
    }

    @Test
    void shouldRejectNullContextOnLoad() {
        ClapPluginManager manager = new ClapPluginManager();

        assertThatThrownBy(() -> manager.loadPlugin(Path.of("/test.clap"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    void shouldThrowOnLoadNonExistentPlugin() {
        ClapPluginManager manager = new ClapPluginManager();

        assertThatThrownBy(() -> manager.loadPlugin(
                tempDir.resolve("nonexistent.clap"), new TestPluginContext()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldIdentifyClapInsert() {
        ClapInsertEffect effect = new ClapInsertEffect(
                new com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost(Path.of("/test.clap")));
        InsertSlot clapSlot = new InsertSlot("CLAP Plugin", effect);

        assertThat(ClapPluginManager.isClapInsert(clapSlot)).isTrue();
    }

    @Test
    void shouldNotIdentifyBuiltInAsClap() {
        InsertSlot builtInSlot = new InsertSlot("EQ", new StubProcessor());

        assertThat(ClapPluginManager.isClapInsert(builtInSlot)).isFalse();
    }

    @Test
    void shouldGetClapHostFromClapSlot() {
        com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost host =
                new com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost(Path.of("/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);
        InsertSlot slot = new InsertSlot("CLAP Plugin", effect);

        assertThat(ClapPluginManager.getClapHost(slot)).isSameAs(host);
    }

    @Test
    void shouldReturnNullClapHostForBuiltInSlot() {
        InsertSlot builtInSlot = new InsertSlot("EQ", new StubProcessor());

        assertThat(ClapPluginManager.getClapHost(builtInSlot)).isNull();
    }

    @Test
    void shouldRejectNullSlotOnIsClapInsert() {
        assertThatThrownBy(() -> ClapPluginManager.isClapInsert(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSlotOnGetClapHost() {
        assertThatThrownBy(() -> ClapPluginManager.getClapHost(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSlotOnDisposePlugin() {
        ClapPluginManager manager = new ClapPluginManager();

        assertThatThrownBy(() -> manager.disposePlugin(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldDisposeNonClapSlotWithoutError() {
        ClapPluginManager manager = new ClapPluginManager();
        InsertSlot builtInSlot = new InsertSlot("EQ", new StubProcessor());

        // Should be a no-op
        manager.disposePlugin(builtInSlot);
    }

    @Test
    void shouldDisposeClapSlotWithoutError() {
        ClapPluginManager manager = new ClapPluginManager();
        com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost host =
                new com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost(Path.of("/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);
        InsertSlot slot = new InsertSlot("CLAP Plugin", effect);

        // Should not throw (host is not initialized, dispose is safe)
        manager.disposePlugin(slot);
    }

    @Test
    void shouldSaveStateFromNonClapSlot() {
        ClapPluginManager manager = new ClapPluginManager();
        InsertSlot builtInSlot = new InsertSlot("EQ", new StubProcessor());

        assertThat(manager.savePluginState(builtInSlot)).isEmpty();
    }

    @Test
    void shouldSaveStateFromClapSlot() {
        ClapPluginManager manager = new ClapPluginManager();
        com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost host =
                new com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost(Path.of("/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);
        InsertSlot slot = new InsertSlot("CLAP Plugin", effect);

        // Not initialized, so state will be empty
        assertThat(manager.savePluginState(slot)).isEmpty();
    }

    @Test
    void shouldLoadStateToNonClapSlotWithoutError() {
        ClapPluginManager manager = new ClapPluginManager();
        InsertSlot builtInSlot = new InsertSlot("EQ", new StubProcessor());

        // No-op for non-CLAP slots
        manager.loadPluginState(builtInSlot, new byte[]{1, 2, 3});
    }

    @Test
    void shouldLoadStateToClapSlot() {
        ClapPluginManager manager = new ClapPluginManager();
        com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost host =
                new com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost(Path.of("/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);
        InsertSlot slot = new InsertSlot("CLAP Plugin", effect);

        // Not initialized, so load is silently skipped
        manager.loadPluginState(slot, new byte[]{1, 2, 3});
    }

    @Test
    void shouldRejectNullSlotOnSaveState() {
        ClapPluginManager manager = new ClapPluginManager();
        assertThatThrownBy(() -> manager.savePluginState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSlotOnLoadState() {
        ClapPluginManager manager = new ClapPluginManager();
        assertThatThrownBy(() -> manager.loadPluginState(null, new byte[0]))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullStateOnLoadState() {
        ClapPluginManager manager = new ClapPluginManager();
        InsertSlot slot = new InsertSlot("EQ", new StubProcessor());
        assertThatThrownBy(() -> manager.loadPluginState(slot, null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- Stub processor ---

    private static final class StubProcessor implements com.benesquivelmusic.daw.sdk.audio.AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            for (int ch = 0; ch < inputBuffer.length; ch++) {
                System.arraycopy(inputBuffer[ch], 0, outputBuffer[ch], 0, numFrames);
            }
        }

        @Override
        public void reset() {
        }

        @Override
        public int getInputChannelCount() {
            return 2;
        }

        @Override
        public int getOutputChannelCount() {
            return 2;
        }
    }

    private static final class TestPluginContext implements PluginContext {
        @Override
        public double getSampleRate() {
            return 44100.0;
        }

        @Override
        public int getBufferSize() {
            return 512;
        }

        @Override
        public void log(String message) {
        }
    }
}
