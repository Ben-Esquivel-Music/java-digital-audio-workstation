package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.spatial.BedBus;
import com.benesquivelmusic.daw.core.mixer.spatial.BedChannelRouting;
import com.benesquivelmusic.daw.core.mixer.spatial.BedRoutingPresets;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSerializerBedBusTest {

    @Test
    void bedBusAndRoutingsRoundTrip() throws Exception {
        DawProject project = new DawProject(
                "test", new AudioFormat(48000, 2, 24, 512));
        UUID trackId = UUID.randomUUID();

        // Configure a 5.1.4 bed bus with custom per-channel trim and an
        // LCR routing for one track. Use a bespoke gain array so we are
        // sure the round-trip preserves non-default values.
        UUID busId = UUID.randomUUID();
        double[] busGains = new double[ImmersiveFormat.FORMAT_5_1_4.channelCount()];
        busGains[0] = -1.5;
        busGains[1] = -1.5;
        project.getBedBusManager().setBedBus(
                new BedBus(busId, ImmersiveFormat.FORMAT_5_1_4, busGains));
        BedChannelRouting routing = BedRoutingPresets.lcr(trackId, ImmersiveFormat.FORMAT_5_1_4);
        project.getBedBusManager().setRouting(routing);

        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        assertThat(xml).contains("bed-bus");
        assertThat(xml).contains("FORMAT_5_1_4");
        assertThat(xml).contains(trackId.toString());

        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject loaded = deserializer.deserialize(xml);

        BedBus loadedBus = loaded.getBedBusManager().getBedBus();
        assertThat(loadedBus.id()).isEqualTo(busId);
        assertThat(loadedBus.format()).isEqualTo(ImmersiveFormat.FORMAT_5_1_4);
        assertThat(loadedBus.channelGainsDb()[0]).isEqualTo(-1.5);
        assertThat(loadedBus.channelGainsDb()[1]).isEqualTo(-1.5);

        BedChannelRouting loadedRouting =
                loaded.getBedBusManager().getRouting(trackId).orElseThrow();
        int lIdx = ImmersiveFormat.FORMAT_5_1_4.layout().indexOf(SpeakerLabel.L);
        int cIdx = ImmersiveFormat.FORMAT_5_1_4.layout().indexOf(SpeakerLabel.C);
        int rIdx = ImmersiveFormat.FORMAT_5_1_4.layout().indexOf(SpeakerLabel.R);
        assertThat(loadedRouting.gainDb(lIdx)).isEqualTo(0.0);
        assertThat(loadedRouting.gainDb(cIdx)).isEqualTo(0.0);
        assertThat(loadedRouting.gainDb(rIdx)).isEqualTo(0.0);
        // LFE was not part of LCR — must be silent (-inf).
        int lfeIdx = ImmersiveFormat.FORMAT_5_1_4.layout().indexOf(SpeakerLabel.LFE);
        assertThat(loadedRouting.gainDb(lfeIdx)).isEqualTo(BedChannelRouting.SILENT_DB);
    }

    @Test
    void legacyProjectsWithoutBedBusElementLoadWithDefaultManager() throws Exception {
        DawProject project = new DawProject(
                "legacy", new AudioFormat(48000, 2, 24, 512));
        ProjectSerializer serializer = new ProjectSerializer();
        String xml = serializer.serialize(project);

        // Strip the bed-bus block so we simulate a project saved before
        // this story was implemented.
        String stripped = xml.replaceAll("(?s)<bed-bus.*?</bed-bus>", "");
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject loaded = deserializer.deserialize(stripped);

        // The default manager has a unity-gain 7.1.4 bus and no routings.
        assertThat(loaded.getBedBusManager().getFormat())
                .isEqualTo(ImmersiveFormat.FORMAT_7_1_4);
        assertThat(loaded.getBedBusManager().getRoutings()).isEmpty();
    }
}
