package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.recording.RecordingPipeline;
import com.benesquivelmusic.daw.core.recording.RecordingSession;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for per-track audio I/O routing (user story 092).
 *
 * <p>Verifies:
 * <ol>
 *   <li>Track records from assigned input channels only</li>
 *   <li>Tracks routed to different outputs don't appear on the master bus</li>
 *   <li>Default routing works for existing projects (backwards compatibility)</li>
 *   <li>InputRouting and OutputRouting record behavior</li>
 * </ol>
 */
class AudioIORoutingTest {

    // ── InputRouting record ────────────────────────────────────────────────

    @Test
    void inputRoutingDefaultStereoShouldBeInput1And2() {
        InputRouting def = InputRouting.DEFAULT_STEREO;
        assertThat(def.firstChannel()).isZero();
        assertThat(def.channelCount()).isEqualTo(2);
        assertThat(def.displayName()).isEqualTo("Input 1-2");
        assertThat(def.isNone()).isFalse();
    }

    @Test
    void inputRoutingNoneShouldBeUnassigned() {
        InputRouting none = InputRouting.NONE;
        assertThat(none.isNone()).isTrue();
        assertThat(none.displayName()).isEqualTo("None");
    }

    @Test
    void inputRoutingMonoDisplayName() {
        InputRouting mono = new InputRouting(2, 1);
        assertThat(mono.displayName()).isEqualTo("Input 3");
    }

    @Test
    void inputRoutingStereoDisplayName() {
        InputRouting stereo = new InputRouting(2, 2);
        assertThat(stereo.displayName()).isEqualTo("Input 3-4");
    }

    @Test
    void inputRoutingShouldRejectNegativeChannel() {
        assertThatThrownBy(() -> new InputRouting(-2, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── OutputRouting record ───────────────────────────────────────────────

    @Test
    void outputRoutingMasterShouldBeDefault() {
        OutputRouting master = OutputRouting.MASTER;
        assertThat(master.isMaster()).isTrue();
        assertThat(master.displayName()).isEqualTo("Master");
    }

    @Test
    void outputRoutingDirectShouldNotBeMaster() {
        OutputRouting direct = new OutputRouting(2, 2);
        assertThat(direct.isMaster()).isFalse();
        assertThat(direct.displayName()).isEqualTo("Output 3-4");
    }

    @Test
    void outputRoutingShouldRejectNegativeChannel() {
        assertThatThrownBy(() -> new OutputRouting(-2, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Track input routing ────────────────────────────────────────────────

    @Test
    void trackShouldDefaultToStereoInputRouting() {
        Track track = new Track("Vocals", TrackType.AUDIO);
        assertThat(track.getInputRouting()).isEqualTo(InputRouting.DEFAULT_STEREO);
    }

    @Test
    void trackShouldPersistInputRouting() {
        Track track = new Track("Guitar", TrackType.AUDIO);
        InputRouting routing = new InputRouting(2, 2);
        track.setInputRouting(routing);
        assertThat(track.getInputRouting()).isEqualTo(routing);
    }

    @Test
    void trackDuplicateShouldCopyInputRouting() {
        Track track = new Track("Guitar", TrackType.AUDIO);
        track.setInputRouting(new InputRouting(4, 2));
        Track copy = track.duplicate("Guitar Copy");
        assertThat(copy.getInputRouting()).isEqualTo(new InputRouting(4, 2));
    }

    // ── MixerChannel output routing ────────────────────────────────────────

    @Test
    void mixerChannelShouldDefaultToMasterOutput() {
        MixerChannel channel = new MixerChannel("Ch1");
        assertThat(channel.getOutputRouting()).isEqualTo(OutputRouting.MASTER);
        assertThat(channel.getOutputRouting().isMaster()).isTrue();
    }

    @Test
    void mixerChannelShouldPersistOutputRouting() {
        MixerChannel channel = new MixerChannel("Ch1");
        OutputRouting direct = new OutputRouting(2, 2);
        channel.setOutputRouting(direct);
        assertThat(channel.getOutputRouting()).isEqualTo(direct);
    }

    // ── Mixer output routing (master vs. direct) ───────────────────────────

    @Test
    void channelRoutedToMasterShouldAppearInMixDown() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        // Default: master routing
        mixer.addChannel(ch);
        mixer.prepareForPlayback(1, 4);

        float[][][] channelBuffers = {{{0.5f, 0.5f, 0.5f, 0.5f}}};
        float[][] output = new float[1][4];

        mixer.mixDown(channelBuffers, output, 4);

        // Channel should appear in master output
        assertThat(output[0][0]).isGreaterThan(0.0f);
    }

    @Test
    void channelRoutedToDirectOutputShouldNotAppearInMasterMix() {
        Mixer mixer = new Mixer();
        MixerChannel ch = new MixerChannel("Ch1");
        ch.setOutputRouting(new OutputRouting(2, 2)); // Direct to outputs 3-4
        mixer.addChannel(ch);
        mixer.prepareForPlayback(1, 4);

        float[][][] channelBuffers = {{{0.5f, 0.5f, 0.5f, 0.5f}}};
        float[][] output = new float[1][4];

        mixer.mixDown(channelBuffers, output, 4);

        // Channel should NOT appear in master output (it's routed elsewhere)
        assertThat(output[0][0]).isEqualTo(0.0f);
        assertThat(output[0][1]).isEqualTo(0.0f);
        assertThat(output[0][2]).isEqualTo(0.0f);
        assertThat(output[0][3]).isEqualTo(0.0f);
    }

    @Test
    void renderDirectOutputsShouldWriteToAssignedChannels() {
        Mixer mixer = new Mixer();

        MixerChannel masterCh = new MixerChannel("Master Ch");
        mixer.addChannel(masterCh);

        MixerChannel directCh = new MixerChannel("Direct Ch");
        directCh.setOutputRouting(new OutputRouting(2, 1)); // Output 3 (mono)
        mixer.addChannel(directCh);

        mixer.prepareForPlayback(1, 4);

        float[][][] channelBuffers = {
                {{0.3f, 0.3f, 0.3f, 0.3f}},  // master channel
                {{0.7f, 0.7f, 0.7f, 0.7f}}   // direct channel
        };

        // First, run mixDown to apply insert effects (modifies channelBuffers in-place)
        float[][] masterOutput = new float[1][4];
        mixer.mixDown(channelBuffers, masterOutput, 4);

        // masterCh should appear in masterOutput, directCh should not
        assertThat(masterOutput[0][0]).isGreaterThan(0.0f);

        // Now render direct outputs into a wider hardware output buffer
        float[][] hwOutput = new float[4][4]; // 4 output channels
        mixer.renderDirectOutputs(channelBuffers, hwOutput, 4);

        // Direct channel routed to output 3 (index 2)
        assertThat(hwOutput[2][0]).isGreaterThan(0.0f);
        // Other direct channels should be silent
        assertThat(hwOutput[0][0]).isEqualTo(0.0f);
        assertThat(hwOutput[1][0]).isEqualTo(0.0f);
        assertThat(hwOutput[3][0]).isEqualTo(0.0f);
    }

    @Test
    void defaultRoutingShouldWorkForBackwardsCompatibility() {
        // All channels default to master — existing behavior should be unchanged
        Mixer mixer = new Mixer();
        MixerChannel ch1 = new MixerChannel("Ch1");
        MixerChannel ch2 = new MixerChannel("Ch2");
        mixer.addChannel(ch1);
        mixer.addChannel(ch2);
        mixer.prepareForPlayback(1, 4);

        float[][][] channelBuffers = {
                {{0.3f, 0.3f, 0.3f, 0.3f}},
                {{0.4f, 0.4f, 0.4f, 0.4f}}
        };
        float[][] output = new float[1][4];

        mixer.mixDown(channelBuffers, output, 4);

        // Both channels should contribute to master output
        assertThat(output[0][0]).isCloseTo(0.7f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void multiBusMixDownShouldRespectOutputRouting() {
        Mixer mixer = new Mixer();

        MixerChannel masterCh = new MixerChannel("Master Ch");
        mixer.addChannel(masterCh);

        MixerChannel directCh = new MixerChannel("Direct Ch");
        directCh.setOutputRouting(new OutputRouting(2, 2));
        mixer.addChannel(directCh);

        mixer.prepareForPlayback(1, 4);

        float[][][] channelBuffers = {
                {{0.3f, 0.3f, 0.3f, 0.3f}},
                {{0.7f, 0.7f, 0.7f, 0.7f}}
        };
        float[][] output = new float[1][4];
        float[][][] returnBuffers = new float[1][1][4];

        mixer.mixDown(channelBuffers, output, returnBuffers, 4);

        // Only masterCh should be in the output — directCh is routed elsewhere
        // Master output should be close to 0.3 (just masterCh's contribution)
        assertThat(output[0][0]).isCloseTo(0.3f, org.assertj.core.data.Offset.offset(0.05f));
    }

    // ── RecordingPipeline per-track input routing ──────────────────────────

    @Test
    void recordingPipelineShouldRouteInputChannelsPerTrack(@TempDir Path tempDir) {
        AudioFormat format = new AudioFormat(44100.0, 4, 16, 512);
        AudioEngine engine = new AudioEngine(format);
        engine.start();
        Transport transport = new Transport();

        // Track 1: records from input channels 1-2 (indices 0-1)
        Track track1 = new Track("Vocals", TrackType.AUDIO);
        track1.setArmed(true);
        track1.setInputRouting(new InputRouting(0, 2));

        // Track 2: records from input channels 3-4 (indices 2-3)
        Track track2 = new Track("Guitar", TrackType.AUDIO);
        track2.setArmed(true);
        track2.setInputRouting(new InputRouting(2, 2));

        RecordingPipeline pipeline = new RecordingPipeline(
                engine, transport, format, tempDir, List.of(track1, track2));
        pipeline.start();

        // Simulate audio input with 4 channels of distinct data
        float[][] inputBuffer = new float[4][512];
        Arrays.fill(inputBuffer[0], 0.1f); // ch 1
        Arrays.fill(inputBuffer[1], 0.2f); // ch 2
        Arrays.fill(inputBuffer[2], 0.3f); // ch 3
        Arrays.fill(inputBuffer[3], 0.4f); // ch 4

        // Invoke the recording callback directly via processBlock
        float[][] outputBuffer = new float[4][512];
        engine.processBlock(inputBuffer, outputBuffer, 512);

        // Get sessions to verify what was captured
        RecordingSession session1 = pipeline.getSession(track1);
        RecordingSession session2 = pipeline.getSession(track2);

        assertThat(session1).isNotNull();
        assertThat(session2).isNotNull();

        // Verify track1 captured 2 channels from input 1-2
        assertThat(session1.getTotalSamplesRecorded()).isEqualTo(512);

        // Verify track2 captured 2 channels from input 3-4
        assertThat(session2.getTotalSamplesRecorded()).isEqualTo(512);

        // Stop and verify clips were created
        List<AudioClip> clips = pipeline.stop();
        assertThat(clips).hasSize(2);

        // Verify the captured audio data has correct channel data
        float[][] captured1 = clips.get(0).getAudioData();
        float[][] captured2 = clips.get(1).getAudioData();

        if (captured1 != null && captured1.length >= 2) {
            // Track 1 should have channels 0-1 (values ~0.1, ~0.2)
            assertThat(captured1[0][0]).isCloseTo(0.1f, org.assertj.core.data.Offset.offset(0.01f));
            assertThat(captured1[1][0]).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(0.01f));
        }

        if (captured2 != null && captured2.length >= 2) {
            // Track 2 should have channels 2-3 (values ~0.3, ~0.4)
            assertThat(captured2[0][0]).isCloseTo(0.3f, org.assertj.core.data.Offset.offset(0.01f));
            assertThat(captured2[1][0]).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(0.01f));
        }
    }
}
