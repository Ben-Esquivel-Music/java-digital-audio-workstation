package com.benesquivelmusic.daw.core.session.dawproject;

import com.benesquivelmusic.daw.sdk.session.SessionData;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionClip;
import com.benesquivelmusic.daw.sdk.session.SessionData.SessionTrack;
import com.benesquivelmusic.daw.sdk.session.SessionExportResult;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests round-trip fidelity: export a session, import it back, and verify
 * that all data is preserved through the DAWproject format.
 */
class DawProjectRoundTripTest {

    private final DawProjectSessionExporter exporter = new DawProjectSessionExporter();
    private final DawProjectSessionImporter importer = new DawProjectSessionImporter();

    @Test
    void shouldRoundTripMinimalSession(@TempDir Path tempDir) throws IOException {
        SessionData session = new SessionData("Round Trip Minimal", 135.0, 7, 8, 96000.0, List.of());

        SessionExportResult exportResult = exporter.exportSession(session, tempDir, "minimal");
        assertThat(exportResult.outputPath()).exists();

        SessionImportResult importResult = importer.importSession(exportResult.outputPath());
        SessionData imported = importResult.sessionData();

        assertThat(imported.projectName()).isEqualTo("Round Trip Minimal");
        assertThat(imported.tempo()).isEqualTo(135.0);
        assertThat(imported.timeSignatureNumerator()).isEqualTo(7);
        assertThat(imported.timeSignatureDenominator()).isEqualTo(8);
        assertThat(imported.sampleRate()).isEqualTo(96000.0);
        assertThat(imported.tracks()).isEmpty();
    }

    @Test
    void shouldRoundTripSessionWithTracksAndClips(@TempDir Path tempDir) throws IOException {
        SessionClip clip1 = new SessionClip("Kick", 0.0, 4.0, 0.5, "audio/kick.wav", -3.0);
        SessionClip clip2 = new SessionClip("Snare", 4.0, 4.0, 0.0, "audio/snare.wav", 0.0);
        SessionTrack audioTrack = new SessionTrack("Drums", "AUDIO", 0.8, -0.5, true, false, List.of(clip1, clip2));
        SessionTrack midiTrack = new SessionTrack("Synth", "MIDI", 0.6, 0.3, false, true, List.of());
        SessionData session = new SessionData("Full Session", 128.0, 4, 4, 48000.0,
                List.of(audioTrack, midiTrack));

        SessionExportResult exportResult = exporter.exportSession(session, tempDir, "full");
        SessionImportResult importResult = importer.importSession(exportResult.outputPath());
        SessionData imported = importResult.sessionData();

        assertThat(imported.projectName()).isEqualTo("Full Session");
        assertThat(imported.tempo()).isEqualTo(128.0);
        assertThat(imported.tracks()).hasSize(2);

        SessionData.SessionTrack importedDrums = imported.tracks().get(0);
        assertThat(importedDrums.name()).isEqualTo("Drums");
        assertThat(importedDrums.type()).isEqualTo("AUDIO");
        assertThat(importedDrums.volume()).isCloseTo(0.8, within(0.001));
        assertThat(importedDrums.pan()).isCloseTo(-0.5, within(0.001));
        assertThat(importedDrums.muted()).isTrue();
        assertThat(importedDrums.solo()).isFalse();
        assertThat(importedDrums.clips()).hasSize(2);

        SessionData.SessionClip importedKick = importedDrums.clips().get(0);
        assertThat(importedKick.name()).isEqualTo("Kick");
        assertThat(importedKick.startBeat()).isEqualTo(0.0);
        assertThat(importedKick.durationBeats()).isEqualTo(4.0);
        assertThat(importedKick.sourceOffsetBeats()).isEqualTo(0.5);
        assertThat(importedKick.sourceFilePath()).isEqualTo("audio/kick.wav");
        assertThat(importedKick.gainDb()).isEqualTo(-3.0);

        SessionData.SessionTrack importedSynth = imported.tracks().get(1);
        assertThat(importedSynth.name()).isEqualTo("Synth");
        assertThat(importedSynth.type()).isEqualTo("MIDI");
        assertThat(importedSynth.volume()).isCloseTo(0.6, within(0.001));
        assertThat(importedSynth.pan()).isCloseTo(0.3, within(0.001));
        assertThat(importedSynth.muted()).isFalse();
        assertThat(importedSynth.solo()).isTrue();
    }

    @Test
    void shouldImportPlainXmlFile(@TempDir Path tempDir) throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="Plain XML" sampleRate="44100.0">
                    <Transport>
                        <Tempo value="100.0"/>
                        <TimeSignature numerator="6" denominator="8"/>
                    </Transport>
                    <Structure>
                        <Track name="Piano" contentType="audio">
                            <Channel volume="0.9" pan="0.0"/>
                        </Track>
                    </Structure>
                </Project>
                """;

        Path xmlFile = tempDir.resolve("test.xml");
        Files.writeString(xmlFile, xml);

        SessionImportResult result = importer.importSession(xmlFile);

        assertThat(result.sessionData().projectName()).isEqualTo("Plain XML");
        assertThat(result.sessionData().tempo()).isEqualTo(100.0);
        assertThat(result.sessionData().tracks()).hasSize(1);
        assertThat(result.sessionData().tracks().getFirst().name()).isEqualTo("Piano");
    }

    @Test
    void shouldRejectNonExistentFile() {
        Path path = Path.of("/tmp/does-not-exist.dawproject");

        assertThatThrownBy(() -> importer.importSession(path))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void shouldRejectNullFile() {
        assertThatThrownBy(() -> importer.importSession(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullSession(@TempDir Path tempDir) {
        assertThatThrownBy(() -> exporter.exportSession(null, tempDir, "test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankBaseName(@TempDir Path tempDir) {
        SessionData session = new SessionData("Test", 120.0, 4, 4, 44100.0, List.of());
        assertThatThrownBy(() -> exporter.exportSession(session, tempDir, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnCorrectFormatMetadata() {
        assertThat(importer.formatName()).isEqualTo("DAWproject");
        assertThat(importer.fileExtension()).isEqualTo("dawproject");
        assertThat(exporter.formatName()).isEqualTo("DAWproject");
        assertThat(exporter.fileExtension()).isEqualTo("dawproject");
    }
}
