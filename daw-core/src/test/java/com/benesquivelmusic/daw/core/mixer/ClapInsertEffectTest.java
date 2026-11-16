package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.health.AudioFailureCategory;
import com.benesquivelmusic.daw.core.audio.health.AudioFailureSample;
import com.benesquivelmusic.daw.core.audio.health.AudioHealthRecord;
import com.benesquivelmusic.daw.core.audio.health.LaneRole;
import com.benesquivelmusic.daw.core.audio.health.RenderThread;
import com.benesquivelmusic.daw.core.plugin.clap.ClapPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.*;
import org.junit.jupiter.api.Test;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClapInsertEffectTest {

    @Test
    void shouldCreateWithPluginHost() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);

        assertThat(effect.getPluginHost()).isSameAs(host);
        assertThat(effect.isFaulted()).isFalse();
    }

    @Test
    void shouldRejectNullPluginHost() {
        assertThatThrownBy(() -> new ClapInsertEffect(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pluginHost");
    }

    @Test
    void shouldDelegateChannelCountsToHost() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"), 0, 2, 2);
        ClapInsertEffect effect = new ClapInsertEffect(host);

        assertThat(effect.getInputChannelCount()).isEqualTo(2);
        assertThat(effect.getOutputChannelCount()).isEqualTo(2);
    }

    @Test
    void shouldDelegateProcessToHost() {
        // ClapPluginHost passes through when not processing (not activated)
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);

        float[][] input = {{1.0f, 0.5f, -0.5f}};
        float[][] output = {{0.0f, 0.0f, 0.0f}};
        effect.process(input, output, 3);

        // Pass-through behavior from ClapPluginHost when not processing
        assertThat(output[0]).containsExactly(1.0f, 0.5f, -0.5f);
    }

    @Test
    void shouldFallBackToPassThroughOnProcessingError() {
        ClapInsertEffect effect = new ClapInsertEffect(new ThrowingPluginHost());

        float[][] input = {{1.0f, 0.5f}};
        float[][] output = {{0.0f, 0.0f}};
        effect.process(input, output, 2);

        // Should have fallen back to pass-through
        assertThat(output[0]).containsExactly(1.0f, 0.5f);
        assertThat(effect.isFaulted()).isTrue();
    }

    /**
     * Story 337, ruling E30: a plugin crash on the render thread is an
     * {@code audio.plugin} record with the plugin host as subject — never the
     * SEVERE log line, built by string concatenation, it used to be.
     */
    @Test
    void aCrashOnARenderThreadIsRecordedWithThePluginAsSubjectAndNeverLogged() throws Exception {
        ThrowingPluginHost host = new ThrowingPluginHost();
        ClapInsertEffect effect = new ClapInsertEffect(host);
        AudioHealthRecord record = new AudioHealthRecord();
        float[][] input = {{1.0f, 0.5f}};
        float[][] output = {{0.0f, 0.0f}};
        Logger log = Logger.getLogger(ClapInsertEffect.class.getName());
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord entry) { logged.add(entry); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        log.addHandler(handler);
        try {
            AtomicReference<Throwable> escaped = new AtomicReference<>();
            RenderThread thread = new RenderThread(record.claimLane(LaneRole.RENDER_DRIVE), () -> {
                try {
                    effect.process(input, output, 2);
                } catch (Throwable t) {
                    escaped.set(t);
                }
            }, "clap-render-thread");
            thread.start();
            thread.join(5_000L);
            assertThat(thread.isAlive()).isFalse();
            assertThat(escaped.get()).isNull();
        } finally {
            log.removeHandler(handler);
        }

        assertThat(effect.isFaulted()).isTrue();
        assertThat(output[0]).as("pass-through").containsExactly(1.0f, 0.5f);
        assertThat(record.total(AudioFailureCategory.PLUGIN)).isEqualTo(1);
        List<AudioFailureSample> samples = new ArrayList<>();
        record.read(samples::add);
        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.subject()).contains(host);
            assertThat(sample.failure()).get().isInstanceOf(RuntimeException.class);
        });
        assertThat(logged).as("no SEVERE record on the render thread")
                .noneMatch(entry -> entry.getLevel() == Level.SEVERE);
    }

    /**
     * Story 337, ruling E77: off a render thread — an offline render — the
     * crash cannot be recorded, so it is logged at SEVERE with the plugin's
     * name and the thrown exception, as before story 337; the audio passes
     * through and the latch is set together with that log record.
     */
    @Test
    void aCrashOffARenderThreadIsLoggedAtSevereWithThePluginNameAndPassesThrough() {
        ThrowingPluginHost host = new ThrowingPluginHost();
        ClapInsertEffect effect = new ClapInsertEffect(host);
        float[][] input = {{1.0f, 0.5f}};
        float[][] output = {{0.0f, 0.0f}};

        List<LogRecord> logged = loggedWhile(() -> effect.process(input, output, 2));

        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(entry.getMessage())
                    .isEqualTo("CLAP plugin processing failed, falling back to pass-through: ThrowingPlugin");
            assertThat(entry.getThrown()).isSameAs(host.crash);
        });
        assertThat(output[0]).as("pass-through").containsExactly(1.0f, 0.5f);
        assertThat(effect.isFaulted()).isTrue();
    }

    /**
     * Story 337, ruling E77: no new throw — a plugin whose descriptor cannot
     * be read is still logged, under a fallback name, and the block passes
     * through.
     */
    @Test
    void aCrashWhoseDescriptorCannotBeReadIsStillLoggedAndThrowsNothing() {
        ThrowingPluginHost host = ThrowingPluginHost.withUnreadableDescriptor();
        ClapInsertEffect effect = new ClapInsertEffect(host);
        float[][] input = {{1.0f, 0.5f}};
        float[][] output = {{0.0f, 0.0f}};
        List<LogRecord> logged = new ArrayList<>();

        assertThatCode(() -> logged.addAll(loggedWhile(() -> effect.process(input, output, 2))))
                .as("reading the plugin's name throws nothing into the render")
                .doesNotThrowAnyException();

        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.SEVERE);
            assertThat(entry.getMessage()).endsWith(": an unnamed CLAP plugin");
            assertThat(entry.getThrown()).isSameAs(host.crash);
        });
        assertThat(output[0]).as("pass-through").containsExactly(1.0f, 0.5f);
        assertThat(effect.isFaulted()).isTrue();
    }

    /** Runs {@code body} on this thread and returns what {@link ClapInsertEffect}'s logger received. */
    private static List<LogRecord> loggedWhile(Runnable body) {
        Logger log = Logger.getLogger(ClapInsertEffect.class.getName());
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord entry) { logged.add(entry); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        log.addHandler(handler);
        try {
            body.run();
        } finally {
            log.removeHandler(handler);
        }
        return List.copyOf(logged);
    }

    @Test
    void shouldStayFaultedOnSubsequentCalls() {
        ClapInsertEffect effect = new ClapInsertEffect(new ThrowingPluginHost());

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};

        // First call — triggers fault
        effect.process(input, output, 1);
        assertThat(effect.isFaulted()).isTrue();

        // Second call — should still pass through without calling the plugin
        float[][] input2 = {{0.75f}};
        float[][] output2 = {{0.0f}};
        effect.process(input2, output2, 1);
        assertThat(output2[0][0]).isEqualTo(0.75f);
    }

    @Test
    void shouldClearFaultOnReset() {
        ClapInsertEffect effect = new ClapInsertEffect(new ThrowingPluginHost());

        float[][] input = {{1.0f}};
        float[][] output = {{0.0f}};
        effect.process(input, output, 1);
        assertThat(effect.isFaulted()).isTrue();

        effect.reset();
        assertThat(effect.isFaulted()).isFalse();
    }

    @Test
    void shouldResetWithoutErrorOnNonFaultedPlugin() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"));
        ClapInsertEffect effect = new ClapInsertEffect(host);

        // Should not throw
        effect.reset();
        assertThat(effect.isFaulted()).isFalse();
    }

    @Test
    void shouldHandleStereoPassThrough() {
        ClapPluginHost host = new ClapPluginHost(Path.of("/plugins/test.clap"), 0, 2, 2);
        ClapInsertEffect effect = new ClapInsertEffect(host);

        float[][] input = {{1.0f, 0.5f}, {-1.0f, -0.5f}};
        float[][] output = {{0.0f, 0.0f}, {0.0f, 0.0f}};
        effect.process(input, output, 2);

        assertThat(output[0]).containsExactly(1.0f, 0.5f);
        assertThat(output[1]).containsExactly(-1.0f, -0.5f);
    }

    // --- Test helper: an ExternalPluginHost that throws during processing ---

    private static final class ThrowingPluginHost implements ExternalPluginHost {

        final RuntimeException crash = new RuntimeException("Simulated plugin crash");
        private final boolean descriptorReadable;

        ThrowingPluginHost() {
            this(true);
        }

        private ThrowingPluginHost(boolean descriptorReadable) {
            this.descriptorReadable = descriptorReadable;
        }

        /** A crashing host whose {@link #getDescriptor()} throws as well. */
        static ThrowingPluginHost withUnreadableDescriptor() {
            return new ThrowingPluginHost(false);
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            throw crash;
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

        @Override
        public ExternalPluginFormat getFormat() {
            return ExternalPluginFormat.CLAP;
        }

        @Override
        public List<PluginParameter> getParameters() {
            return List.of();
        }

        @Override
        public double getParameterValue(int parameterId) {
            return 0;
        }

        @Override
        public void setParameterValue(int parameterId, double value) {
        }

        @Override
        public int getLatencySamples() {
            return 0;
        }

        @Override
        public byte[] saveState() {
            return new byte[0];
        }

        @Override
        public void loadState(byte[] state) {
        }

        @Override
        public PluginDescriptor getDescriptor() {
            if (!descriptorReadable) {
                throw new IllegalStateException("descriptor unavailable");
            }
            return new PluginDescriptor("test.throwing", "ThrowingPlugin", "1.0", "Test", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
        }
    }
}
