package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class MixerRetiredChannelLifetimeTest {
    @Test
    void removedReturnSurvivesUndoAndRetiresExactlyOnceAtProjectClose() throws Exception {
        var project = project();
        var mixer = project.getMixer();
        var bus = mixer.addReturnBus("External return");
        var disposals = new AtomicInteger();
        var slot = slot(disposals);
        bus.addInsert(slot);
        var removal = new RemoveReturnBusAction(mixer, bus);
        removal.execute();
        assertThat(mixer.getReturnBuses()).doesNotContain(bus);
        assertThat(disposals).hasValue(0);
        removal.undo();
        assertThat(bus.getInsertSlots()).containsExactly(slot);
        var output = new float[1][1];
        bus.getEffectsChain().process(new float[][]{{0.25f}}, output, 1);
        assertThat(output[0]).containsExactly(0.25f);
        removal.execute();
        assertThat(disposals).hasValue(0);
        project.disposeInsertsWhenQuiescent().get(3, TimeUnit.SECONDS);
        project.disposeInsertsWhenQuiescent().get(3, TimeUnit.SECONDS);
        assertThat(disposals).hasValue(1);
    }

    @Test
    void removedProjectTrackAndDirectMixerChannelBothRetireAtProjectClose() throws Exception {
        var project = project();
        var track = project.createAudioTrack("Project track");
        var trackDisposals = new AtomicInteger();
        project.getMixerChannelForTrack(track).addInsert(slot(trackDisposals));
        project.removeTrack(track);
        var direct = new MixerChannel("Direct channel");
        var directDisposals = new AtomicInteger();
        direct.addInsert(slot(directDisposals));
        project.getMixer().addChannel(direct);
        project.getMixer().removeChannel(direct);
        assertThat(trackDisposals).hasValue(0);
        assertThat(directDisposals).hasValue(0);
        project.disposeInsertsWhenQuiescent().get(3, TimeUnit.SECONDS);
        assertThat(trackDisposals).hasValue(1);
        assertThat(directDisposals).hasValue(1);
    }

    private static DawProject project() {
        return new DawProject("Retirement", new AudioFormat(48_000, 1, 24, 32));
    }

    private static InsertSlot slot(AtomicInteger disposals) {
        var slot = new InsertSlot("Owned insert", new Passthrough());
        slot.setDisposal(disposals::incrementAndGet);
        return slot;
    }

    private static final class Passthrough implements AudioProcessor {
        @Override public void process(float[][] input, float[][] output, int frames) {
            for (int lane = 0; lane < output.length; lane++) System.arraycopy(input[lane], 0, output[lane], 0, frames);
        }
        @Override public void reset() { }
        @Override public int getInputChannelCount() { return 1; }
        @Override public int getOutputChannelCount() { return 1; }
    }
}
