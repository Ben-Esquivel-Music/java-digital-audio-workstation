package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.InsertEffectRack;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.ProcessingInsertFixture;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
final class PluginGraphResourceLifecycleTest {
    @TempDir Path directory;

    @Test
    void registryRemovalAndRackRefreshNeverDisposeTheIndependentGraphInstance() throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "graph-owned.jar",
                List.of(PluginTestJars.manifest(ProcessingInsertFixture.class)), List.of(ProcessingInsertFixture.class));
        var entry = new ExternalPluginEntry(jar, ProcessingInsertFixture.class.getName());
        var registry = new PluginRegistry();
        var prototype = (ProcessingInsertFixture) registry.register(entry);
        var slot = PluginSlotLoader.load(entry, new AudioFormat(48_000, 2, 24, 512));
        var live = (ProcessingInsertFixture) slot.getPlugin();
        var channel = new MixerChannel("Channel");
        channel.addInsert(slot);
        assertThat(live).isNotSameAs(prototype);
        assertThat(live.initialized).isTrue();
        assertThat(prototype.initialized).isFalse();
        try {
            registry.unregister(entry);
            assertThat(prototype.disposalCount).isOne();
            runOnFxThread(() -> {
                var rack = new InsertEffectRack(channel, 2, 48_000, 512, null, null);
                rack.rebuildSlots();
                rack.dispose();
                return null;
            });
            assertThat(live.disposalCount).isZero();
            float[][] output = {new float[4], new float[4]};
            channel.getEffectsChain().process(new float[][]{{0.5f, 0, 0, 0}, {0.25f, 0, 0, 0}}, output, 4);
            assertThat(output[0][0]).isEqualTo(0.5f);
            channel.removeInsert(0);
            assertThat(live.disposalCount).isZero();
            channel.addInsert(slot);
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
            assertThat(live.disposalCount).isOne();
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
            assertThat(live.disposalCount).isOne();
        } finally {
            registry.disposeAll();
            channel.disposeInsertsWhenQuiescent().get(5, TimeUnit.SECONDS);
        }
    }

}
