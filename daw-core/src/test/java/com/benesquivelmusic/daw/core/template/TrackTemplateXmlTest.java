package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.mixer.SendMode;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackTemplateXmlTest {

    @Test
    void shouldRoundTripTrackTemplate() throws IOException {
        TrackTemplate original = new TrackTemplate(
                "My Vocal",
                TrackType.AUDIO,
                "Vocal",
                List.of(
                        InsertEffectSpec.of(InsertEffectType.COMPRESSOR, Map.of(0, -18.0, 1, 3.0)),
                        InsertEffectSpec.ofDefaults(InsertEffectType.PARAMETRIC_EQ)),
                List.of(new SendSpec("Reverb Return", 0.25, SendMode.POST_FADER)),
                0.85,
                0.1,
                TrackColor.PINK,
                InputRouting.DEFAULT_STEREO,
                OutputRouting.MASTER);

        String xml = TrackTemplateXml.serializeTemplate(original);
        assertThat(xml).contains("<trackTemplate").contains("name=\"My Vocal\"");

        TrackTemplate parsed = TrackTemplateXml.deserializeTemplate(xml);

        assertThat(parsed.templateName()).isEqualTo(original.templateName());
        assertThat(parsed.trackType()).isEqualTo(original.trackType());
        assertThat(parsed.nameHint()).isEqualTo(original.nameHint());
        assertThat(parsed.volume()).isEqualTo(original.volume());
        assertThat(parsed.pan()).isEqualTo(original.pan());
        assertThat(parsed.color().getHexColor()).isEqualTo(original.color().getHexColor());
        assertThat(parsed.inputRouting()).isEqualTo(original.inputRouting());
        assertThat(parsed.outputRouting()).isEqualTo(original.outputRouting());

        assertThat(parsed.inserts()).hasSize(2);
        assertThat(parsed.inserts().get(0).type()).isEqualTo(InsertEffectType.COMPRESSOR);
        assertThat(parsed.inserts().get(0).parameters()).containsEntry(0, -18.0).containsEntry(1, 3.0);
        assertThat(parsed.inserts().get(1).type()).isEqualTo(InsertEffectType.PARAMETRIC_EQ);

        assertThat(parsed.sends()).hasSize(1);
        assertThat(parsed.sends().getFirst().targetName()).isEqualTo("Reverb Return");
        assertThat(parsed.sends().getFirst().level()).isEqualTo(0.25);
        assertThat(parsed.sends().getFirst().mode()).isEqualTo(SendMode.POST_FADER);
    }

    @Test
    void shouldRoundTripChannelStripPreset() throws IOException {
        ChannelStripPreset original = new ChannelStripPreset(
                "Bus Glue",
                List.of(InsertEffectSpec.of(InsertEffectType.LIMITER, Map.of(0, -0.8))),
                List.of(),
                0.7,
                0.0);

        String xml = TrackTemplateXml.serializePreset(original);
        ChannelStripPreset parsed = TrackTemplateXml.deserializePreset(xml);

        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void shouldRejectMismatchedRootTag() throws IOException {
        String presetXml = TrackTemplateXml.serializePreset(
                new ChannelStripPreset("P", List.of(), List.of(), 1.0, 0.0));
        assertThatThrownBy(() -> TrackTemplateXml.deserializeTemplate(presetXml))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a track template");
    }

    @Test
    void shouldRejectDoctypeDeclarations() {
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE trackTemplate [<!ENTITY x "y">]>
                <trackTemplate name="X"/>
                """;
        assertThatThrownBy(() -> TrackTemplateXml.deserializeTemplate(malicious))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldRoundTripAllFactoryTemplates() throws IOException {
        for (TrackTemplate template : TrackTemplateFactory.factoryTemplates()) {
            String xml = TrackTemplateXml.serializeTemplate(template);
            TrackTemplate parsed = TrackTemplateXml.deserializeTemplate(xml);
            assertThat(parsed.templateName()).isEqualTo(template.templateName());
            assertThat(parsed.inserts()).hasSameSizeAs(template.inserts());
            assertThat(parsed.sends()).hasSameSizeAs(template.sends());
        }
    }
}
