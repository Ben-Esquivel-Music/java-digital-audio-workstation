package com.benesquivelmusic.daw.core.plugin.clap;

import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginFormat;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginStateRejectedException;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClapPluginHostTest {

    /**
     * Story 337, ruling E31: every guarded host callback catches whatever its
     * body throws and answers its ABI-safe value — an exception that reached
     * the plugin's native frame would terminate the JVM — and on a render
     * thread records {@code audio.plugin}. Each body is driven to a real
     * fault through the per-call state it reads.
     */
    @Test
    void everyGuardedHostCallbackAnswersItsAbiValueOnAFaultAndRecordsIt() throws Exception {
        AudioHealthRecord record = new AudioHealthRecord();
        AtomicReference<int[]> answers = new AtomicReference<>();
        AtomicReference<MemorySegment> event = new AtomicReference<>();

        onRenderThread(record, () -> {
            threadLocal("CURRENT_EVENT_COUNT").set(new int[0]);
            int size = ClapPluginHost.pendingEventsSize(MemorySegment.NULL);
            threadLocal("CURRENT_EVENT_COUNT").set(new int[] {3});
            threadLocal("CURRENT_EVENT_SEGMENTS").set(new MemorySegment[1]);
            event.set(ClapPluginHost.pendingEventsGet(MemorySegment.NULL, 2));
            threadLocal("CURRENT_WRITE_TARGET").set(new ByteArrayOutputStream());
            long written = ClapPluginHost.ostreamWrite(MemorySegment.NULL,
                    MemorySegment.ofArray(new byte[4]), 4);
            threadLocal("CURRENT_READ_SOURCE").set(new byte[8]);
            threadLocal("CURRENT_READ_POSITION").set(new int[] {0});
            long read = ClapPluginHost.istreamRead(MemorySegment.NULL,
                    MemorySegment.ofArray(new byte[2]), 8);
            answers.set(new int[] {size, (int) written, (int) read});
            for (String name : List.of("CURRENT_EVENT_COUNT", "CURRENT_EVENT_SEGMENTS",
                    "CURRENT_WRITE_TARGET", "CURRENT_READ_SOURCE", "CURRENT_READ_POSITION")) {
                threadLocal(name).remove();
            }
        });

        assertThat(answers.get()).containsExactly(0, -1, -1);
        assertThat(event.get()).isEqualTo(MemorySegment.NULL);
        assertThat(record.total(AudioFailureCategory.PLUGIN)).isEqualTo(4);
        List<AudioFailureSample> samples = new ArrayList<>();
        record.read(samples::add);
        assertThat(samples).extracting(AudioFailureSample::detail)
                .as("the events-get fault carries the index it was asked for")
                .contains(2);
    }

    /**
     * Story 337, ruling E77: off a render thread — an offline render — a
     * block the plugin failed to process cannot be recorded, so it is logged
     * at WARNING naming the plugin, rate-bounded per host (three failing
     * blocks inside the window write one record), and the input is passed
     * through. The host is marked processing with no native state, so its
     * first native write throws and the catch runs.
     */
    @Test
    void aProcessingFailureOffARenderThreadIsLoggedAndPassesTheInputThrough() throws Exception {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/broken.clap"));
        java.lang.reflect.Field processing = ClapPluginHost.class.getDeclaredField("processing");
        processing.setAccessible(true);
        processing.setBoolean(host, true);
        Logger hostLog = Logger.getLogger(ClapPluginHost.class.getName());
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord entry) { logged.add(entry); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        float[][] input = {{0.25f, -0.75f}, {0.5f, 1.0f}};
        float[][] output = new float[2][2];

        hostLog.addHandler(handler);
        try {
            for (int block = 0; block < 3; block++) {
                host.process(input, output, 2);
            }
        } finally {
            hostLog.removeHandler(handler);
        }

        assertThat(output[0]).as("pass-through").containsExactly(0.25f, -0.75f);
        assertThat(output[1]).as("pass-through").containsExactly(0.5f, 1.0f);
        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.WARNING);
            assertThat(entry.getMessage()).isEqualTo("CLAP plugin “broken.clap” failed to process a block; "
                    + "its input was passed through for this block");
            assertThat(entry.getThrown()).isInstanceOf(NullPointerException.class);
        });
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Object> threadLocal(String name) {
        try {
            java.lang.reflect.Field field = ClapPluginHost.class.getDeclaredField(name);
            field.setAccessible(true);
            return (ThreadLocal<Object>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ClapPluginHost." + name + " must exist", e);
        }
    }

    private static void onRenderThread(AudioHealthRecord record, Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        RenderThread thread = new RenderThread(record.claimLane(LaneRole.RENDER_DRIVE), () -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "clap-callback-thread");
        thread.start();
        thread.join(5_000L);
        assertThat(thread.isAlive()).as("the render thread finished within the guard budget").isFalse();
        assertThat(failure.get()).as("nothing escaped a host callback").isNull();
    }

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateWithValidArguments() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"), 0, 2, 2);

        assertThat(host.getLibraryPath()).isEqualTo(Path.of("/plugins/test.clap"));
        assertThat(host.getPluginIndex()).isEqualTo(0);
        assertThat(host.getInputChannelCount()).isEqualTo(2);
        assertThat(host.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldCreateWithDefaultStereoSettings() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));

        assertThat(host.getPluginIndex()).isEqualTo(0);
        assertThat(host.getInputChannelCount()).isEqualTo(2);
        assertThat(host.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldReportClapFormat() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        assertThat(host.getFormat()).isEqualTo(ExternalPluginFormat.CLAP);
    }

    @Test
    void shouldProvideDefaultDescriptorBeforeInit() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        PluginDescriptor descriptor = host.getDescriptor();

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.id()).contains("test.clap");
        assertThat(descriptor.type()).isEqualTo(PluginType.EFFECT);
    }

    @Test
    void shouldNotBeInitializedByDefault() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        assertThat(host.isInitialized()).isFalse();
        assertThat(host.isActivated()).isFalse();
    }

    @Test
    void shouldRejectNullLibraryPath() {
        assertThatThrownBy(() -> new ClapPluginHost(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("libraryPath");
    }

    @Test
    void shouldRejectNegativePluginIndex() {
        assertThatThrownBy(() -> new ClapPluginHost(Path.of("/test.clap"), -1, 2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pluginIndex");
    }

    @Test
    void shouldRejectZeroInputChannels() {
        assertThatThrownBy(() -> new ClapPluginHost(Path.of("/test.clap"), 0, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputChannels");
    }

    @Test
    void shouldRejectZeroOutputChannels() {
        assertThatThrownBy(() -> new ClapPluginHost(Path.of("/test.clap"), 0, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputChannels");
    }

    @Test
    void shouldThrowOnInitializeWithNullContext() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThatThrownBy(() -> host.initialize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    void shouldThrowOnInitializeWithNonExistentLibrary() {
        ClapPluginHost host = new ClapPluginHost(tempDir.resolve("nonexistent.clap"));

        assertThatThrownBy(() -> host.initialize(new TestPluginContext()))
                .isInstanceOf(ClapException.class)
                .hasMessageContaining("Failed to load");
    }

    @Test
    void shouldThrowOnActivateBeforeInitialize() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThatThrownBy(host::activate)
                .isInstanceOf(ClapException.class)
                .hasMessageContaining("not initialized");
    }

    @Test
    void shouldPassThroughWhenNotProcessing() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));

        float[][] input = {{1.0f, 0.5f, -0.5f}};
        float[][] output = {{0.0f, 0.0f, 0.0f}};
        host.process(input, output, 3);

        assertThat(output[0]).containsExactly(1.0f, 0.5f, -0.5f);
    }

    @Test
    void shouldReturnZeroLatencyWhenNotInitialized() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThat(host.getLatencySamples()).isEqualTo(0);
    }

    /**
     * A host without the state extension — here, not initialized, so it has
     * neither the extension nor a plugin — saves an empty state and never
     * throws: empty means "no state support", while a failed save throws
     * (story 334, studio ruling E10.2). The failed-save branches need a
     * native CLAP plugin (story 034).
     */
    @Test
    void saveStateWithoutStateSupportIsEmpty() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThat(host.saveState()).isEmpty();
    }

    /**
     * A non-empty state given to a host without state support is the plugin
     * rejecting it (story 334, studio ruling E4; ruling-2 G17), never a
     * silent success that would leave the plugin at its defaults.
     */
    @Test
    void aNonEmptyStateWithoutStateSupportIsRejected() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThatThrownBy(() -> host.loadState(new byte[]{1, 2, 3}))
                .isInstanceOf(PluginStateRejectedException.class)
                .hasMessage("the plugin has no state support");
    }

    @Test
    void shouldRejectNullState() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        assertThatThrownBy(() -> host.loadState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldResetWithoutError() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // reset() should not throw when not initialized
        host.reset();
    }

    @Test
    void shouldDisposeWithoutErrorWhenNotInitialized() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // dispose() should not throw on uninitialized host
        host.dispose();
        assertThat(host.isInitialized()).isFalse();
    }

    @Test
    void shouldDeactivateWithoutErrorWhenNotActivated() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // deactivate() should not throw when not activated
        host.deactivate();
    }

    // --- Parameter validation and queuing ---

    @Test
    void shouldRejectSetParameterWhenPluginDoesNotSupportParams() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // getParameters() returns empty list when not initialized — setParameterValue
        // should throw because the parameter does not exist.
        assertThatThrownBy(() -> host.setParameterValue(0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parameter not found");
    }

    @Test
    void shouldAcceptLoadStateWithEmptyArrayWithoutError() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // Empty state should be silently ignored (nothing to restore).
        host.loadState(new byte[0]);
    }

    @Test
    void shouldReturnEmptyStateBeforeInitialize() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));
        // Before initialize(), there is no state extension — should return empty array.
        assertThat(host.saveState()).isEmpty();
    }

    @Test
    void shouldPassThroughWithPendingParamChangesWhenNotProcessing() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/test.clap"));

        float[][] input = {{0.8f, -0.3f}};
        float[][] output = {{0.0f, 0.0f}};

        // process() in pass-through mode must not be affected by any internal state.
        host.process(input, output, 2);

        assertThat(output[0]).containsExactly(0.8f, -0.3f);
    }

    // --- Test helper ---

    private static class TestPluginContext implements com.benesquivelmusic.daw.sdk.plugin.PluginContext {
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
            // No-op for testing
        }
    }
}
