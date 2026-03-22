package com.benesquivelmusic.daw.core.session.dawproject;

import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionClip;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionTrack;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DawProjectXmlSerializerTest {

    private final DawProjectXmlSerializer serializer = new DawProjectXmlSerializer();
    private final DawProjectXmlParser parser = new DawProjectXmlParser();

    @Test
    void shouldSerializeMinimalSession() throws IOException {
        var session = new SessionData("Empty Session", 120.0, 4, 4, 44100.0, List.of());

        var xml = serializeToString(session);

        assertThat(xml).contains("name=\"Empty Session\"");
        assertThat(xml).contains("sampleRate=\"44100.0\"");
        assertThat(xml).contains("<Tempo");
        assertThat(xml).contains("value=\"120.0\"");
        assertThat(xml).contains("<TimeSignature");
        assertThat(xml).contains("numerator=\"4\"");
        assertThat(xml).contains("denominator=\"4\"");
        assertThat(xml).contains("<Structure");
    }

    @Test
    void shouldSerializeSessionWithTracks() throws IOException {
        var clip = new SessionClip("Kick", 0.0, 4.0, 0.5, "audio/kick.wav", -3.0);
        var track = new SessionTrack("Drums", "AUDIO", 0.8, -0.5, true, false, List.of(clip));
        var session = new SessionData("Full Song", 140.0, 3, 4, 96000.0, List.of(track));

        var xml = serializeToString(session);

        assertThat(xml).contains("name=\"Full Song\"");
        assertThat(xml).contains("value=\"140.0\"");
        assertThat(xml).contains("name=\"Drums\"");
        assertThat(xml).contains("contentType=\"audio\"");
        assertThat(xml).contains("volume=\"0.8\"");
        assertThat(xml).contains("pan=\"-0.5\"");
        assertThat(xml).contains("mute=\"true\"");
        assertThat(xml).contains("name=\"Kick\"");
        assertThat(xml).contains("time=\"0.0\"");
        assertThat(xml).contains("duration=\"4.0\"");
        assertThat(xml).contains("gain=\"-3.0\"");
        assertThat(xml).contains("offset=\"0.5\"");
        assertThat(xml).contains("path=\"audio/kick.wav\"");
    }

    @Test
    void shouldMapTrackTypesCorrectly() throws IOException {
        var tracks = List.of(
                new SessionTrack("T1", "AUDIO", 1.0, 0.0, false, false, List.of()),
                new SessionTrack("T2", "MIDI", 1.0, 0.0, false, false, List.of()),
                new SessionTrack("T3", "AUX", 1.0, 0.0, false, false, List.of()),
                new SessionTrack("T4", "MASTER", 1.0, 0.0, false, false, List.of())
        );
        var session = new SessionData("Types", 120.0, 4, 4, 44100.0, tracks);

        var xml = serializeToString(session);

        assertThat(xml).contains("contentType=\"audio\"");
        assertThat(xml).contains("contentType=\"notes\"");
        assertThat(xml).contains("contentType=\"aux\"");
        assertThat(xml).contains("contentType=\"master\"");
    }

    @Test
    void shouldNotWriteSoloAttributeWhenFalse() throws IOException {
        var track = new SessionTrack("T", "AUDIO", 1.0, 0.0, false, false, List.of());
        var session = new SessionData("Test", 120.0, 4, 4, 44100.0, List.of(track));

        var xml = serializeToString(session);

        assertThat(xml).doesNotContain("solo=");
        assertThat(xml).doesNotContain("mute=");
    }

    @Test
    void shouldOmitAudioElementForClipWithNoSource() throws IOException {
        var clip = new SessionClip("Empty Clip", 0.0, 2.0, 0.0, null, 0.0);
        var track = new SessionTrack("T", "AUDIO", 1.0, 0.0, false, false, List.of(clip));
        var session = new SessionData("Test", 120.0, 4, 4, 44100.0, List.of(track));

        var xml = serializeToString(session);

        assertThat(xml).contains("name=\"Empty Clip\"");
        assertThat(xml).doesNotContain("<Audio");
    }

    @Test
    void shouldProduceParseableOutput() throws IOException {
        var clip = new SessionClip("Clip1", 2.0, 8.0, 0.0, "audio/vocal.wav", 0.0);
        var track = new SessionTrack("Vocals", "AUDIO", 0.9, 0.1, false, true, List.of(clip));
        var session = new SessionData("Round Trip", 130.0, 6, 8, 48000.0, List.of(track));

        var baos = new ByteArrayOutputStream();
        serializer.serialize(session, baos, new ArrayList<>());

        var reparsed = parser.parse(new ByteArrayInputStream(baos.toByteArray()), new ArrayList<>());

        assertThat(reparsed.projectName()).isEqualTo("Round Trip");
        assertThat(reparsed.tempo()).isEqualTo(130.0);
        assertThat(reparsed.tracks()).hasSize(1);
        assertThat(reparsed.tracks().getFirst().name()).isEqualTo("Vocals");
    }

    private String serializeToString(SessionData session) throws IOException {
        var baos = new ByteArrayOutputStream();
        serializer.serialize(session, baos, new ArrayList<>());
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void shouldProduceXmlWithoutDoctype() throws IOException {
        var session = new SessionData("Secure Session", 120.0, 4, 4, 44100.0, List.of());

        var xml = serializeToString(session);

        // The serialized output must not contain DOCTYPE declarations
        assertThat(xml.toUpperCase()).doesNotContain("<!DOCTYPE");
        assertThat(xml.toUpperCase()).doesNotContain("ENTITY");
    }
}
