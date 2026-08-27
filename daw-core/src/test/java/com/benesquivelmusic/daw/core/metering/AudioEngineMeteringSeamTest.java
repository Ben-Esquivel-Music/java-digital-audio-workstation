package com.benesquivelmusic.daw.core.metering;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.EngineBinder;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 318 — the PRODUCTION engine seam, driven through
 * {@link AudioEngine#processBlock(float[][], float[][], int)}.
 *
 * <p>Every other metering test hand-rolls the three lines that make meters
 * work in the shipped app — hoist {@code meteringTapBus.snapshot()}, hand it
 * to {@code renderPipeline.renderBlock(...)}, then
 * {@code meteringTapBus.blockCompleted(taps)} — inside its own rig. That
 * leaves the real seam unpinned: dropping the {@code taps} argument, or the
 * {@code blockCompleted} call, would leave the whole suite green while every
 * meter in the product went dark or froze. This test drives the engine
 * itself, so it fails on either deletion:</p>
 * <ul>
 *   <li>no {@code taps} argument → nothing publishes → {@code readInto}
 *       returns {@code false} and the peak assertions fail;</li>
 *   <li>no {@code blockCompleted} → the block counter never advances, so
 *       every frame carries the same {@code blockIndex} and the
 *       "advanced between drives" assertion fails.</li>
 * </ul>
 */
class AudioEngineMeteringSeamTest {

    private static final double SAMPLE_RATE = 48_000.0;
    private static final int CHANNELS = 2;
    private static final int BLOCK = 64;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, CHANNELS, 24, BLOCK);
    private static final double TEMPO = 120.0;
    private static final double SAMPLES_PER_BEAT = SAMPLE_RATE * 60.0 / TEMPO;
    private static final int TOTAL_FRAMES = BLOCK * 64;

    @Test
    void processBlockPublishesAndStampsEveryTapOfTheBoundProject() {
        DawProject project = new DawProject("Seam", FORMAT);
        project.getTransport().setTempo(TEMPO);
        Track track = project.createAudioTrack("Lead");
        track.addClip(sineClip());
        MixerChannel channel = project.getMixerChannelForTrack(track);

        AudioEngine engine = new AudioEngine(FORMAT);
        try {
            new EngineBinder(engine).bind(project);
            project.getTransport().play();
            engine.start();

            MeteringTapBus bus = engine.meteringTapBus();
            LevelSubscription masterOut = bus.attachLevel(MeterTapPoint.MASTER_OUT);
            LevelSubscription masterChain = bus.attachLevel(MeterTapPoint.MASTER_CHAIN);
            LevelSubscription channelPost =
                    bus.attachLevel(new MeterTapPoint.ChannelPost(channel.getId()));

            drive(engine, 8);

            MeterFrame masterOutFrame = new MeterFrame();
            MeterFrame masterChainFrame = new MeterFrame();
            MeterFrame channelFrame = new MeterFrame();
            assertThat(masterOut.readInto(masterOutFrame))
                    .as("processBlock hands the snapshot to renderBlock: MASTER_OUT published")
                    .isTrue();
            assertThat(masterChain.readInto(masterChainFrame))
                    .as("MASTER_CHAIN published").isTrue();
            assertThat(channelPost.readInto(channelFrame))
                    .as("CHANNEL_POST published for the bound project's channel").isTrue();

            assertThat(masterOutFrame.maxPeak())
                    .as("the engine's own render carries audible level to MASTER_OUT")
                    .isGreaterThan(0.1f);
            assertThat(channelFrame.maxPeak())
                    .as("and to the track's CHANNEL_POST").isGreaterThan(0.1f);

            long epoch = bus.epoch();
            long stamp = masterOutFrame.blockIndex();
            assertThat(masterChainFrame.blockIndex())
                    .as("all taps of one block carry the same stamp").isEqualTo(stamp);
            assertThat(channelFrame.blockIndex()).isEqualTo(stamp);
            for (MeterFrame frame : new MeterFrame[] {
                    masterOutFrame, masterChainFrame, channelFrame }) {
                assertThat(frame.epoch()).as("stamped with the binder's epoch").isEqualTo(epoch);
            }

            drive(engine, 4);
            assertThat(masterOut.readInto(masterOutFrame)).isTrue();
            assertThat(masterOutFrame.blockIndex())
                    .as("blockCompleted advanced the counter across the second drive")
                    .isGreaterThan(stamp);
        } finally {
            engine.shutdown();
        }
    }

    private static void drive(AudioEngine engine, int blocks) {
        float[][] input = new float[CHANNELS][BLOCK];
        float[][] output = new float[CHANNELS][BLOCK];
        for (int block = 0; block < blocks; block++) {
            engine.processBlock(input, output, BLOCK);
        }
    }

    private static AudioClip sineClip() {
        AudioClip clip = new AudioClip("Clip", 0.0, TOTAL_FRAMES / SAMPLES_PER_BEAT, null);
        float[][] data = new float[CHANNELS][TOTAL_FRAMES];
        for (int i = 0; i < TOTAL_FRAMES; i++) {
            float v = (float) Math.sin(2.0 * Math.PI * 8.0 * i / BLOCK) * 0.8f;
            data[0][i] = v;
            data[1][i] = v;
        }
        clip.setAudioData(data);
        return clip;
    }
}
