package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mastering.MasteringChain;
import com.benesquivelmusic.daw.core.mixer.CueBusManager;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.Metronome;
import com.benesquivelmusic.daw.core.recording.MetronomeSideOutputRouter;
import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.mastering.MasteringStageType;
import com.benesquivelmusic.daw.sdk.transport.ClickOutput;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class MetronomeMasterLatencyTest {
    private static final int BLOCK = 64;
    private static final int TOTAL_FRAMES = 100_000;
    private static final AudioFormat FORMAT = new AudioFormat(32_768, 2, 24, BLOCK);

    @ParameterizedTest
    @CsvSource({
            "0, 0, false, 0",
            "37, 0, false, 0",
            "0, 97, false, 0",
            "97, 159, false, 0",
            "0, 97, true, 0",
            "97, 159, false, 40960",
            "97, 159, false, 40959",
            "97, 159, false, 40705"
    })
    void masterAndHardwareClicksAlignAcrossBlocksAndLoopWraps(int insertLatency, int masteringLatency,
                                                             boolean bypassMastering, int loopEndNumerator) {
        assertAligned(FORMAT, insertLatency, masteringLatency, bypassMastering,
                loopEndNumerator / 32_768.0, 0.25, 0);
    }

    @ParameterizedTest
    @CsvSource({"48000, 512, 1, 0.16", "48000, 64, 1, 0.328125", "48000, 64, 256, 0.328125",
            "44100, 256, 37, 0.333"})
    void shiftedLoopEndRoundingDoesNotDropAlternateLapClicks(double sampleRate, int block, int latency, double loopEnd) {
        assertAligned(new AudioFormat(sampleRate, 2, 24, block), latency, 0, false, loopEnd, 0, 1_024);
    }

    private static void assertAligned(AudioFormat format, int insertLatency, int masteringLatency,
                                      boolean bypassMastering, double loopEnd, double startBeat, int compareFrom) {
        int block = format.bufferSize();
        var project = new DawProject("Click timing", format);
        project.createAudioTrack("Silent programme");
        var mixer = project.getMixer();
        // Parallel track PDC must not advance the main click, which enters after it.
        mixer.getChannels().getFirst().addInsert(new InsertSlot("Track delay", new SampleDelay(613)));
        if (insertLatency > 0) mixer.getMasterChannel().addInsert(new InsertSlot("Master delay", new SampleDelay(insertLatency)));
        var mastering = new MasteringChain();
        if (masteringLatency > 0) mastering.addStage(MasteringStageType.LIMITING, "Mastering delay", new SampleDelay(masteringLatency));
        mastering.setChainBypassed(bypassMastering);
        mastering.allocateIntermediateBuffers(2, block);
        mixer.setMasteringChain(mastering);
        mixer.prepareForPlayback(2, block);
        try {
            var transport = project.getTransport();
            transport.setTempo(120);
            // Start before the first audible beat with enough lead-in to fill all delays.
            transport.setPositionInBeats(startBeat);
            if (loopEnd > 0) {
                transport.setLoopRegion(0, loopEnd);
                transport.setLoopEnabled(true);
            }
            transport.play();
            var metronome = new Metronome(format.sampleRate(), 2);
            metronome.setClickOutput(new ClickOutput(7, 1, true, true));
            var router = new MetronomeSideOutputRouter();
            var cues = new CueBusManager();
            router.setCueBusLevel(cues.createCueBus("Drummer", 2).id(), 1);
            var timeline = new ClickTimeline();
            var backend = mock(AudioBackend.class);
            doAnswer(invocation -> {
                timeline.write(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(backend).writeToChannel(anyInt(), any(float[].class));
            var pipeline = new RenderPipeline(format, 1, block);
            var output = new float[2][block];
            for (timeline.position = 0; timeline.position < TOTAL_FRAMES; timeline.position += block) {
                int frames = Math.min(block, TOTAL_FRAMES - timeline.position);
                pipeline.renderBlock(null, output, frames, transport, mixer, project.getTracks(), null,
                        mastering, null, null, null, metronome, router, cues, backend);
                System.arraycopy(output[0], 0, timeline.main, timeline.position, frames);
            }

            float peak = 0;
            for (float sample : timeline.side) peak = Math.max(peak, Math.abs(sample));
            assertThat(peak).isGreaterThan(0.1f);
            for (var hardware : new float[][] {timeline.side, timeline.cueLeft, timeline.cueRight}) {
                assertThat(Arrays.mismatch(timeline.main, compareFrom, TOTAL_FRAMES, hardware, compareFrom, TOTAL_FRAMES))
                        .as("first frame where main/hardware clicks differ after warmup").isEqualTo(-1);
            }
        } finally {
            mixer.getDelayCompensation().close();
        }
    }

    private static final class ClickTimeline {
        private final float[] main = new float[TOTAL_FRAMES];
        private final float[] side = new float[TOTAL_FRAMES];
        private final float[] cueLeft = new float[TOTAL_FRAMES];
        private final float[] cueRight = new float[TOTAL_FRAMES];
        private int position;

        private void write(int channel, float[] samples) {
            float[] destination = switch (channel) {
                case 7 -> side;
                case 4 -> cueLeft;
                case 5 -> cueRight;
                default -> throw new AssertionError("Unexpected output " + channel);
            };
            for (int i = 0; i < Math.min(samples.length, TOTAL_FRAMES - position); i++) {
                destination[position + i] += samples[i];
            }
        }
    }

    private static final class SampleDelay implements AudioProcessor {
        private final float[][] history;
        private int position;

        private SampleDelay(int latency) { history = new float[2][latency]; }
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < 2; channel++) {
                    float sample = input[channel][frame];
                    output[channel][frame] = history[channel][position];
                    history[channel][position] = sample;
                }
                position = (position + 1) % history[0].length;
            }
        }
        @Override public void reset() { for (var lane : history) Arrays.fill(lane, 0f); position = 0; }
        @Override public int getInputChannelCount() { return 2; }
        @Override public int getOutputChannelCount() { return 2; }
        @Override public int getLatencySamples() { return history[0].length; }
    }
}
