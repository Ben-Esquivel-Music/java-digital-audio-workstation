package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SofaFileParserTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectFileTooSmall() {
        Path small = tempDir.resolve("tiny.sofa");
        assertThatThrownBy(() -> {
            Files.write(small, new byte[]{1, 2, 3});
            SofaFileParser.parse(small);
        }).isInstanceOf(IOException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void shouldRejectInvalidSignature() throws IOException {
        Path invalid = tempDir.resolve("invalid.sofa");
        Files.write(invalid, new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        assertThatThrownBy(() -> SofaFileParser.parse(invalid))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void shouldRejectUnsupportedSuperblockVersion() throws IOException {
        // Write valid HDF5 signature but with superblock version 99
        byte[] data = new byte[64];
        data[0] = (byte) 0x89;
        data[1] = 'H';
        data[2] = 'D';
        data[3] = 'F';
        data[4] = '\r';
        data[5] = '\n';
        data[6] = 0x1A;
        data[7] = '\n';
        data[8] = 99; // unsupported version

        Path unsupported = tempDir.resolve("unsupported.sofa");
        Files.write(unsupported, data);
        assertThatThrownBy(() -> SofaFileParser.parse(unsupported))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("superblock version");
    }

    @Test
    void shouldBuildFromRawData() {
        double[][] sourcePositions = {
                {0.0, 0.0, 1.2},
                {90.0, 0.0, 1.2},
                {180.0, 0.0, 1.2},
                {270.0, 0.0, 1.2}
        };

        int n = 64;
        double[][][] impulseResponses = new double[4][2][n];
        for (int m = 0; m < 4; m++) {
            for (int r = 0; r < 2; r++) {
                for (int s = 0; s < n; s++) {
                    impulseResponses[m][r][s] = Math.sin(2.0 * Math.PI * s / n) * 0.1;
                }
            }
        }

        double[][] delays = {
                {0.0, 5.0}, {5.0, 0.0}, {0.0, 5.0}, {5.0, 0.0}
        };

        HrtfData data = SofaFileParser.fromRawData("TestProfile", 44100.0,
                sourcePositions, impulseResponses, delays);

        assertThat(data.profileName()).isEqualTo("TestProfile");
        assertThat(data.sampleRate()).isEqualTo(44100.0);
        assertThat(data.measurementCount()).isEqualTo(4);
        assertThat(data.receiverCount()).isEqualTo(2);
        assertThat(data.irLength()).isEqualTo(n);
        assertThat(data.sourcePositions().get(0).azimuthDegrees()).isEqualTo(0.0);
        assertThat(data.sourcePositions().get(1).azimuthDegrees()).isEqualTo(90.0);
    }

    @Test
    void shouldRejectInvalidSourcePositionDimensions() {
        double[][] badPositions = {{0.0, 0.0}}; // only 2 components
        assertThatThrownBy(() -> SofaFileParser.fromRawData("X", 44100.0,
                badPositions, new double[1][2][4], new double[1][2]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 components");
    }

    @Test
    void shouldPreserveDelayValues() {
        double[][] sourcePositions = {{0.0, 0.0, 1.0}};
        double[][][] ir = {{{0.5, 0.3}, {0.4, 0.2}}};
        double[][] delays = {{3.5, 7.2}};

        HrtfData data = SofaFileParser.fromRawData("DelayTest", 48000.0,
                sourcePositions, ir, delays);

        assertThat(data.delays()[0][0]).isEqualTo(3.5f);
        assertThat(data.delays()[0][1]).isEqualTo(7.2f);
    }
}
