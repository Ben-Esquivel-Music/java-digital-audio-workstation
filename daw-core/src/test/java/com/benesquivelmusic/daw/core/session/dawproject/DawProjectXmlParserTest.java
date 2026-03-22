package com.benesquivelmusic.daw.core.session.dawproject;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionClip;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionTrack;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DawProjectXmlParserTest {

    private final DawProjectXmlParser parser = new DawProjectXmlParser();

    @Test
    void shouldParseMinimalProject() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="Minimal Song" sampleRate="48000.0">
                    <Transport>
                        <Tempo value="140.0"/>
                        <TimeSignature numerator="3" denominator="4"/>
                    </Transport>
                    <Structure/>
                </Project>
                """;

        ArrayList<String> warnings = new ArrayList<String>();
        SessionData result = parser.parse(toStream(xml), warnings);

        assertThat(result.projectName()).isEqualTo("Minimal Song");
        assertThat(result.tempo()).isEqualTo(140.0);
        assertThat(result.timeSignatureNumerator()).isEqualTo(3);
        assertThat(result.timeSignatureDenominator()).isEqualTo(4);
        assertThat(result.sampleRate()).isEqualTo(48000.0);
        assertThat(result.tracks()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldParseProjectWithTracks() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="Full Song" sampleRate="44100.0">
                    <Transport>
                        <Tempo value="120.0"/>
                        <TimeSignature numerator="4" denominator="4"/>
                    </Transport>
                    <Structure>
                        <Track name="Drums" contentType="audio">
                            <Channel volume="0.8" pan="-0.5" mute="false" solo="true"/>
                            <Lanes>
                                <Clips>
                                    <Clip name="Kick" time="0.0" duration="4.0">
                                        <Audio gain="-3.0" offset="0.5">
                                            <File path="audio/kick.wav"/>
                                        </Audio>
                                    </Clip>
                                    <Clip name="Snare" time="4.0" duration="4.0">
                                        <Audio>
                                            <File path="audio/snare.wav"/>
                                        </Audio>
                                    </Clip>
                                </Clips>
                            </Lanes>
                        </Track>
                        <Track name="Bass" contentType="midi">
                            <Channel volume="0.6" pan="0.2"/>
                        </Track>
                    </Structure>
                </Project>
                """;

        ArrayList<String> warnings = new ArrayList<String>();
        SessionData result = parser.parse(toStream(xml), warnings);

        assertThat(result.projectName()).isEqualTo("Full Song");
        assertThat(result.tracks()).hasSize(2);

        SessionData.SessionTrack drums = result.tracks().get(0);
        assertThat(drums.name()).isEqualTo("Drums");
        assertThat(drums.type()).isEqualTo("AUDIO");
        assertThat(drums.volume()).isEqualTo(0.8);
        assertThat(drums.pan()).isEqualTo(-0.5);
        assertThat(drums.muted()).isFalse();
        assertThat(drums.solo()).isTrue();
        assertThat(drums.clips()).hasSize(2);

        SessionData.SessionClip kick = drums.clips().get(0);
        assertThat(kick.name()).isEqualTo("Kick");
        assertThat(kick.startBeat()).isEqualTo(0.0);
        assertThat(kick.durationBeats()).isEqualTo(4.0);
        assertThat(kick.gainDb()).isEqualTo(-3.0);
        assertThat(kick.sourceOffsetBeats()).isEqualTo(0.5);
        assertThat(kick.sourceFilePath()).isEqualTo("audio/kick.wav");

        SessionData.SessionClip snare = drums.clips().get(1);
        assertThat(snare.name()).isEqualTo("Snare");
        assertThat(snare.sourceFilePath()).isEqualTo("audio/snare.wav");
        assertThat(snare.gainDb()).isEqualTo(0.0);

        SessionData.SessionTrack bass = result.tracks().get(1);
        assertThat(bass.name()).isEqualTo("Bass");
        assertThat(bass.type()).isEqualTo("MIDI");
        assertThat(bass.volume()).isEqualTo(0.6);
        assertThat(bass.pan()).isEqualTo(0.2);
        assertThat(bass.clips()).isEmpty();

        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldDefaultMissingProjectName() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0">
                    <Structure/>
                </Project>
                """;

        ArrayList<String> warnings = new ArrayList<String>();
        SessionData result = parser.parse(toStream(xml), warnings);

        assertThat(result.projectName()).isEqualTo("Untitled");
    }

    @Test
    void shouldDefaultMissingTransport() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="NoTransport">
                    <Structure/>
                </Project>
                """;

        ArrayList<String> warnings = new ArrayList<String>();
        SessionData result = parser.parse(toStream(xml), warnings);

        assertThat(result.tempo()).isEqualTo(120.0);
        assertThat(result.timeSignatureNumerator()).isEqualTo(4);
        assertThat(result.timeSignatureDenominator()).isEqualTo(4);
    }

    @Test
    void shouldWarnAboutUnsupportedDeviceChains() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="WithPlugins">
                    <Structure>
                        <Track name="Guitar" contentType="audio">
                            <Devices>
                                <Device name="Reverb"/>
                            </Devices>
                        </Track>
                    </Structure>
                </Project>
                """;

        ArrayList<String> warnings = new ArrayList<String>();
        parser.parse(toStream(xml), warnings);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("Guitar").contains("Device/plugin chains");
    }

    @Test
    void shouldWarnAboutUnsupportedAutomation() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="WithAutomation">
                    <Structure>
                        <Track name="Synth" contentType="audio">
                            <Lanes>
                                <Automation>
                                    <Point time="0" value="0.5"/>
                                </Automation>
                            </Lanes>
                        </Track>
                    </Structure>
                </Project>
                """;

        ArrayList<String> warnings = new ArrayList<String>();
        parser.parse(toStream(xml), warnings);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("Synth").contains("Automation");
    }

    @Test
    void shouldMapContentTypes() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="Types">
                    <Structure>
                        <Track name="T1" contentType="audio"/>
                        <Track name="T2" contentType="midi"/>
                        <Track name="T3" contentType="notes"/>
                        <Track name="T4" contentType="aux"/>
                        <Track name="T5" contentType="bus"/>
                        <Track name="T6" contentType="master"/>
                        <Track name="T7" contentType="unknown"/>
                        <Track name="T8"/>
                    </Structure>
                </Project>
                """;

        ArrayList<String> warnings = new ArrayList<String>();
        SessionData result = parser.parse(toStream(xml), warnings);

        assertThat(result.tracks().get(0).type()).isEqualTo("AUDIO");
        assertThat(result.tracks().get(1).type()).isEqualTo("MIDI");
        assertThat(result.tracks().get(2).type()).isEqualTo("MIDI");
        assertThat(result.tracks().get(3).type()).isEqualTo("AUX");
        assertThat(result.tracks().get(4).type()).isEqualTo("AUX");
        assertThat(result.tracks().get(5).type()).isEqualTo("MASTER");
        assertThat(result.tracks().get(6).type()).isEqualTo("AUDIO");
        assertThat(result.tracks().get(7).type()).isEqualTo("AUDIO");
    }

    @Test
    void shouldRejectInvalidXml() {
        String xml = "this is not xml";

        assertThatThrownBy(() -> parser.parse(toStream(xml), new ArrayList<>()))
                .isInstanceOf(IOException.class);
    }

    private static ByteArrayInputStream toStream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
