package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.dsp.CompressorProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.template.TrackTemplateService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingInsertPersistenceTest {
    @Test
    void stoppedSaveAndReopenPreservesPendingEditorChangesWithoutCallingTheProcessor() throws Exception {
        var project = new DawProject("Pending", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocal");
        var channel = project.getMixer().getChannels().getFirst();
        var processor = new CompressorProcessor(2, 44_100);
        var slot = new InsertSlot("Compressor", processor, InsertEffectType.COMPRESSOR);
        channel.addInsert(slot);
        slot.getParameterStore().writeFromUiById(0, -35);

        String xml = new ProjectSerializer().serialize(project);
        var reopened = new ProjectDeserializer().deserialize(xml);
        var restored = (CompressorProcessor) reopened.getMixer().getChannels().getFirst()
                .getInsertSlot(0).getProcessor();
        assertThat(restored.getThresholdDb()).isEqualTo(-35);
        assertThat(xml).contains("name=\"Threshold\"");
        assertThat(processor.getThresholdDb()).isEqualTo(-20);
        assertThat(slot.getParameterStore().snapshotPendingUiValues()).containsEntry(0, -35.0);
        assertThat(MixerSnapshot.capture(project.getMixer(), "Pending").channels().getFirst()
                .inserts().getFirst().parameters()).containsEntry(0, -35.0);
        assertThat(TrackTemplateService.captureChannelStrip("Pending", channel).inserts().getFirst()
                .parameters()).containsEntry(0, -35.0);
    }

    @Test
    void appliedEditorChangesDoNotMaskLaterDirectProcessorOrAutomationChanges() throws Exception {
        var project = new DawProject("Applied", AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocal");
        var processor = new CompressorProcessor(2, 44_100);
        var slot = new InsertSlot("Compressor", processor, InsertEffectType.COMPRESSOR);
        project.getMixer().getChannels().getFirst().addInsert(slot);
        slot.getParameterStore().writeFromUiById(0, -35);
        slot.drainParametersToAudio();
        processor.setThresholdDb(-27);

        var reopened = new ProjectDeserializer().deserialize(new ProjectSerializer().serialize(project));
        var restored = (CompressorProcessor) reopened.getMixer().getChannels().getFirst()
                .getInsertSlot(0).getProcessor();
        assertThat(restored.getThresholdDb()).isEqualTo(-27);
        assertThat(slot.snapshotParameterValues()).containsEntry(0, -27.0);
    }
}
