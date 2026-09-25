package com.benesquivelmusic.daw.core.mastering;

import com.benesquivelmusic.daw.core.dsp.LimiterProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringChainPreset;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MasteringChainLatencyTest {
    static Stream<MasteringChainPreset> presets() {
        return MasteringChainPresets.allDefaults().stream();
    }

    @ParameterizedTest
    @MethodSource("presets")
    void bundledPresetsIncludeTheirRealLimiterLookahead(MasteringChainPreset preset) {
        var chain = new MasteringChain();
        for (var stage : preset.stages()) {
            chain.addStage(stage.stageType(), stage.name(),
                    MasteringProcessorFactory.createProcessor(stage, 2, 48_000));
        }

        assertThat(chain.getLatencySamples()).isEqualTo(240);
        chain.setChainBypassed(true);
        assertThat(chain.getLatencySamples()).isZero();
    }

    @Test
    void activeLatencyFollowsBypassSoloReplacementAndRemoval() {
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.LIMITING, "First", new LimiterProcessor(2, 48_000));
        chain.addStage(MasteringStageType.LIMITING, "Second", new LimiterProcessor(2, 48_000));
        var first = chain.getStages().getFirst();
        var second = chain.getStages().getLast();
        assertThat(chain.getLatencySamples()).isEqualTo(480);

        first.setBypassed(true);
        assertThat(chain.getLatencySamples()).isEqualTo(240);
        second.setBypassed(true);
        assertThat(chain.getLatencySamples()).isZero();
        first.setSolo(true);
        assertThat(chain.getLatencySamples()).isEqualTo(240);
        second.setSolo(true);
        assertThat(chain.getLatencySamples()).isEqualTo(480);
        chain.setChainBypassed(true);
        assertThat(chain.getLatencySamples()).isZero();
        chain.setChainBypassed(false);

        var replacement = new LimiterProcessor(2, 48_000);
        replacement.setLookAheadMs(1);
        first.setProcessor(replacement);
        assertThat(chain.getLatencySamples()).isEqualTo(288);
        replacement.setLookAheadMs(2);
        assertThat(chain.getLatencySamples()).isEqualTo(336);
        chain.removeStage(1);
        assertThat(chain.getLatencySamples()).isEqualTo(96);
        chain.replaceStages(List.of(new MasteringChain.Stage(MasteringStageType.LIMITING,
                "Preset limiter", new LimiterProcessor(2, 48_000))));
        assertThat(chain.getLatencySamples()).isEqualTo(240);
        chain.replaceStages(List.of());
        assertThat(chain.getLatencySamples()).isZero();
    }

    @Test
    void staticLatencyIsCapturedOnConfigurationAndNeverPolledFromAnotherThread() throws Exception {
        var reads = new AtomicInteger();
        Thread configuringThread = Thread.currentThread();
        AudioProcessor processor = new AudioProcessor() {
            @Override public int getLatencySamples() {
                assertThat(Thread.currentThread()).isSameAs(configuringThread);
                reads.incrementAndGet();
                return 64;
            }
            @Override public void process(float[][] input, float[][] output, int frames) { }
            @Override public void reset() { }
            @Override public int getInputChannelCount() { return 2; }
            @Override public int getOutputChannelCount() { return 2; }
        };
        var chain = new MasteringChain();
        chain.addStage(MasteringStageType.EQ_TONAL, "Native EQ", processor);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(chain::getLatencySamples).get()).isEqualTo(64);
        }
        assertThat(reads).hasValue(1);
    }
}
