package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SofaFileReaderTest {

    @TempDir
    Path tempDir;

    /** Build an AES69-shaped, dense-sphere HrtfData stand-in usable by fromHrtfData(). */
    private static HrtfData buildAes69ConformantData(double sampleRate, int irLen) {
        List<SphericalCoordinate> positions = new ArrayList<>();
        // 4 elevation rings × 8 azimuths = 32 measurements covering both hemispheres.
        for (int el : new int[]{-30, 0, 30, 60}) {
            for (int az = 0; az < 360; az += 45) {
                positions.add(new SphericalCoordinate(az, el, 1.5));
            }
        }
        int m = positions.size();
        float[][][] ir = new float[m][2][irLen];
        float[][] delays = new float[m][2];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < irLen; j++) {
                ir[i][0][j] = (float) Math.sin(2.0 * Math.PI * j / irLen) * 0.1f;
                ir[i][1][j] = (float) Math.sin(2.0 * Math.PI * j / irLen) * 0.1f;
            }
        }
        return new HrtfData("aes69-conformant", sampleRate, positions, ir, delays);
    }

    @Test
    void importsAes69ConformantDataWithoutError() throws IOException {
        HrtfData data = buildAes69ConformantData(48000.0, 64);
        SofaFileReader.ImportResult result = SofaFileReader.fromHrtfData(data, "aes69", 48000.0);

        assertThat(result.profile().measurementCount()).isEqualTo(32);
        assertThat(result.profile().sampleRate()).isEqualTo(48000.0);
        assertThat(result.profile().impulseLength()).isEqualTo(64);
        assertThat(result.resampled()).isFalse();
        // Dense and dual-hemisphere coverage → no warnings.
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void azimuthZeroElevationZeroProducesSymmetricLeftRight() throws IOException {
        // Build a profile where the front-and-center IR (az=0, el=0) is identical L/R.
        List<SphericalCoordinate> positions = List.of(new SphericalCoordinate(0.0, 0.0, 1.5));
        float[] frontIr = {0.9f, 0.5f, 0.2f, 0.05f};
        float[][][] ir = new float[1][2][];
        ir[0][0] = frontIr.clone();
        ir[0][1] = frontIr.clone();
        HrtfData data = new HrtfData("symmetric-front", 48000.0, positions, ir, new float[1][2]);

        SofaFileReader.ImportResult result = SofaFileReader.fromHrtfData(data, "symmetric", 48000.0);
        PersonalizedHrtfProfile p = result.profile();

        assertThat(p.leftImpulses()[0]).containsExactly(p.rightImpulses()[0]);
    }

    @Test
    void sampleRateMismatchTriggersResamplingAtLoad() throws IOException {
        // SOFA at 96k, session at 48k → impulses should be downsampled (~half length).
        HrtfData data = buildAes69ConformantData(96000.0, 128);
        SofaFileReader.ImportResult result = SofaFileReader.fromHrtfData(data, "rs", 48000.0);

        assertThat(result.resampled()).isTrue();
        assertThat(result.originalSampleRate()).isEqualTo(96000.0);
        assertThat(result.profile().sampleRate()).isEqualTo(48000.0);
        assertThat(result.profile().impulseLength()).isLessThan(128);
        assertThat(result.profile().impulseLength()).isGreaterThan(32);
        assertThat(result.warnings())
                .anyMatch(w -> w.contains("Resampled"));
    }

    @Test
    void rejectsMonoReceiverConfiguration() {
        // 1-receiver "HRTF" is not a binaural file.
        List<SphericalCoordinate> positions = List.of(new SphericalCoordinate(0, 0, 1));
        float[][][] ir = new float[1][1][4];
        HrtfData data = new HrtfData("mono", 48000.0, positions, ir, new float[1][1]);

        assertThatThrownBy(() -> SofaFileReader.fromHrtfData(data, "mono", 48000.0))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2 receivers");
    }

    @Test
    void rejectsMissingFile() {
        Path missing = tempDir.resolve("nope.sofa");
        assertThatThrownBy(() -> SofaFileReader.read(missing, 48000.0))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsNonPositiveSessionSampleRate() throws IOException {
        Path empty = tempDir.resolve("empty.sofa");
        Files.write(empty, new byte[]{1});
        assertThatThrownBy(() -> SofaFileReader.read(empty, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionSampleRate");
    }

    @Test
    void emitsSparseCoverageWarning() throws IOException {
        // Only 4 directions → below the sparseness threshold.
        List<SphericalCoordinate> positions = List.of(
                new SphericalCoordinate(0, 0, 1),
                new SphericalCoordinate(90, 0, 1),
                new SphericalCoordinate(180, 0, 1),
                new SphericalCoordinate(270, 0, 1));
        float[][][] ir = new float[4][2][16];
        HrtfData data = new HrtfData("sparse", 48000.0, positions, ir, new float[4][2]);

        SofaFileReader.ImportResult result = SofaFileReader.fromHrtfData(data, "sparse", 48000.0);

        assertThat(result.warnings()).anyMatch(w -> w.contains("Sparse"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("upper-hemisphere"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("lower-hemisphere"));
    }
}
