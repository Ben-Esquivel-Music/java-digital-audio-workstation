package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.InsertEffectRack;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.ProcessingInsertFixture;
import com.benesquivelmusic.daw.app.ui.plugin.fixtures.UndeclaredThrowFixture;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.RemovedPlugin;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.plugin.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@ExtendWith(JavaFxToolkitExtension.class)
final class PluginGraphResourceLifecycleTest {
    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 24, 512);

    @TempDir Path directory;

    @BeforeEach
    @AfterEach
    void disarm() {
        UndeclaredThrowFixture.disarm();
    }

    /**
     * Story 334 (PR #990 review, ruling R-C3.2): a plugin whose {@code activate()} throws a
     * checked exception it does not declare is disposed once — its class loader closed with it —
     * and the load fails unchecked, so the rack's and the plugin menu's workers recover.
     */
    @Test
    void anUndeclaredCheckedExceptionFromActivateDisposesThePluginOnceAndFailsTheLoadUnchecked() throws Exception {
        IOException licence = new IOException("the licence file could not be read");
        ExternalPluginEntry entry = undeclaredThrowEntry();
        UndeclaredThrowFixture.onActivate = licence;

        Throwable thrown = catchThrowable(() -> PluginSlotLoader.load(entry, FORMAT));

        assertThat(thrown).as("unchecked, named by the plugin's message, caused by its exception")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the licence file could not be read")
                .hasCauseReference(licence);
        assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
    }

    /**
     * As above, from {@code initialize()}: {@code ExternalPluginLoader.instantiate} has disposed
     * the plugin and closed its loader, and the load turns its exception unchecked all the same.
     */
    @Test
    void anUndeclaredCheckedExceptionFromInitializeDisposesThePluginOnceAndFailsTheLoadUnchecked() throws Exception {
        IOException presets = new IOException("the preset folder is missing");
        ExternalPluginEntry entry = undeclaredThrowEntry();
        UndeclaredThrowFixture.onInitialize = presets;

        Throwable thrown = catchThrowable(() -> PluginSlotLoader.load(entry, FORMAT));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("the preset folder is missing")
                .hasCauseReference(presets);
        assertThat(UndeclaredThrowFixture.disposals).as("disposed once, by instantiate").hasValue(1);
    }

    /**
     * A plugin whose {@code activate()} and {@code dispose()} throw one shared exception, as a
     * library's cached exception is: the load fails with that exception, which never has itself
     * suppressed into it — trying would replace it with the host's "Self-suppression not
     * permitted".
     */
    @Test
    void aDisposalThatThrowsTheActivationsOwnExceptionKeepsThatException() throws Exception {
        IllegalStateException shared = new IllegalStateException("the plugin library is already closed");
        ExternalPluginEntry entry = undeclaredThrowEntry();
        UndeclaredThrowFixture.onActivate = shared;
        UndeclaredThrowFixture.onDispose = shared;

        Throwable thrown = catchThrowable(() -> PluginSlotLoader.load(entry, FORMAT));

        assertThat(thrown).as("the plugin's own failure").isSameAs(shared);
        assertThat(shared.getSuppressed()).as("never suppressed into itself").isEmpty();
        assertThat(UndeclaredThrowFixture.disposals).hasValue(1);
    }

    /**
     * Story 334 (PR #990 review, ruling R-C3.2): an {@link Error} a plugin's {@code activate()}
     * throws propagates from the load as thrown, the same instance and never wrapped; the instance
     * it made is disposed once, and a disposal that throws nothing adds nothing to the error.
     */
    @Test
    void anErrorFromActivateIsThrownAsItIsAndThePluginIsDisposedOnce() throws Exception {
        LinkageError linkage = new LinkageError("the plugin's native library is missing");
        ExternalPluginEntry entry = undeclaredThrowEntry();
        UndeclaredThrowFixture.onActivate = linkage;

        Throwable thrown = catchThrowable(() -> PluginSlotLoader.load(entry, FORMAT));

        assertThat(thrown).as("the plugin's own error, never wrapped").isSameAs(linkage);
        assertThat(linkage.getSuppressed()).as("nothing suppressed into it").isEmpty();
        assertThat(UndeclaredThrowFixture.disposals).as("the instance it made is disposed once").hasValue(1);
    }

    private ExternalPluginEntry undeclaredThrowEntry() throws IOException {
        Path jar = PluginTestJars.buildJar(directory, "undeclared-throw.jar",
                List.of(PluginTestJars.manifest(UndeclaredThrowFixture.class)), List.of(UndeclaredThrowFixture.class));
        return new ExternalPluginEntry(jar, UndeclaredThrowFixture.class.getName());
    }

    @Test
    void registryRemovalAndRackRefreshNeverDisposeTheIndependentGraphInstance() throws Exception {
        Path jar = PluginTestJars.buildJar(directory, "graph-owned.jar",
                List.of(PluginTestJars.manifest(ProcessingInsertFixture.class)), List.of(ProcessingInsertFixture.class));
        var entry = new ExternalPluginEntry(jar, ProcessingInsertFixture.class.getName());
        var registry = new PluginRegistry();
        ProcessingInsertFixture prototype = (ProcessingInsertFixture) PluginTestJars.install(registry, jar,
                ProcessingInsertFixture.class).plugin();
        var slot = PluginSlotLoader.load(entry, new AudioFormat(48_000, 2, 24, 512));
        var live = (ProcessingInsertFixture) slot.getPlugin();
        var channel = new MixerChannel("Channel");
        channel.addInsert(slot);
        assertThat(live).isNotSameAs(prototype);
        assertThat(live.initialized).isTrue();
        assertThat(prototype.initialized).isFalse();
        try {
            // Story 334 (critique J9) — the registry takes the entry out and hands it back; its caller
            // disposes the catalogue instance, off the registry's monitor.
            RemovedPlugin removed = registry.unregister(entry).orElseThrow();
            assertThat(prototype.disposalCount).isZero();
            removed.disposal().run();
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
