package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.HrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Headless tests for the SOFA import workflow that the UI exposes (story 174).
 *
 * <p>The SOFA-on-disk parsing path exercises {@link SofaFileParser} which
 * requires a real NetCDF/HDF5 file — fixtures for that path live in
 * {@link SofaFileParserTest}. Here we exercise the rest of the workflow
 * end-to-end via {@link SofaFileReader#fromHrtfData}, which is the
 * controller's primitive after the parser has read the file.</p>
 */
class HrtfImportControllerTest {

    @TempDir
    Path tempDir;

    /** Build an AES69-shaped, dense-sphere HrtfData stand-in. */
    private static HrtfData buildAes69Data(double sampleRate, int irLen) {
        List<SphericalCoordinate> positions = new ArrayList<>();
        for (int el : new int[]{-30, 0, 30, 60}) {
            for (int az = 0; az < 360; az += 45) {
                positions.add(new SphericalCoordinate(az, el, 1.5));
            }
        }
        int m = positions.size();
        float[][][] ir = new float[m][2][irLen];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < irLen; j++) {
                ir[i][0][j] = (float) Math.sin(2.0 * Math.PI * j / irLen) * 0.1f;
                ir[i][1][j] = (float) Math.cos(2.0 * Math.PI * j / irLen) * 0.1f;
            }
        }
        return new HrtfData("aes69", sampleRate, positions, ir, new float[m][2]);
    }

    @Test
    void importedProfileAppearsInLibraryListAndChooserEntries() throws IOException {
        // Equivalent of the UI flow: validate → persist → list. We bypass the
        // file-system parser by building a stand-in HrtfData and persisting
        // through the same library save() the controller would call.
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        HrtfData data = buildAes69Data(48000.0, 64);
        SofaFileReader.ImportResult result =
                SofaFileReader.fromHrtfData(data, "subject-A12", 48000.0);
        library.save(result.profile());

        // Library now lists the personalized profile alphabetically.
        assertThat(library.listImportedProfileNames()).contains("subject-A12");

        // The controller's chooser entries include factory + personalized.
        List<HrtfProfileLibrary.ProfileEntry> entries = controller.chooserEntries();
        assertThat(entries).extracting(HrtfProfileLibrary.ProfileEntry::displayName)
                .contains("Small", "Medium", "Large", "subject-A12");
        assertThat(entries.stream()
                .filter(e -> e.kind() == HrtfProfileLibrary.Kind.PERSONALIZED)
                .map(HrtfProfileLibrary.ProfileEntry::personalizedName))
                .contains("subject-A12");
    }

    @Test
    void corruptedSofaFileIsRejectedWithDescriptiveError() throws IOException {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        // Write garbage bytes to a *.sofa file — the parser must reject it.
        Path bad = tempDir.resolve("corrupt.sofa");
        Files.write(bad, new byte[]{0x42, 0x41, 0x44, 0x21, 0x00, 0x00, 0x00, 0x00});

        assertThatThrownBy(() -> controller.importSofaFile(bad))
                .isInstanceOf(IOException.class);
    }

    @Test
    void sampleRateMismatchTriggersResampleAtLoad() throws IOException {
        // SOFA at 44.1 kHz, session at 48 kHz → impulses must be resampled at
        // load and the result must declare resampled() == true.
        HrtfData data = buildAes69Data(44100.0, 64);
        SofaFileReader.ImportResult result =
                SofaFileReader.fromHrtfData(data, "44k-profile", 48000.0);

        assertThat(result.resampled()).isTrue();
        assertThat(result.originalSampleRate()).isEqualTo(44100.0);
        assertThat(result.profile().sampleRate()).isEqualTo(48000.0);
        assertThat(result.warnings()).anyMatch(w -> w.contains("Resampled"));

        // After persistence + reload, the saved profile is still at the session rate.
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        library.save(result.profile());
        Optional<PersonalizedHrtfProfile> loaded = library.loadImportedProfile("44k-profile");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().sampleRate()).isEqualTo(48000.0);
    }

    @Test
    void resolvesFactoryProfileByDisplayName() {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        HrtfImportController.Resolution r = controller.resolve("Medium");
        assertThat(r.generic()).isEqualTo(HrtfProfile.MEDIUM);
        assertThat(r.personalized()).isNull();
        assertThat(r.hasFallbackWarning()).isFalse();
    }

    @Test
    void resolvesPersonalizedProfileWhenPresent() throws IOException {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);
        HrtfData data = buildAes69Data(48000.0, 32);
        library.save(SofaFileReader.fromHrtfData(data, "alice", 48000.0).profile());

        HrtfImportController.Resolution r = controller.resolve("alice");
        assertThat(r.personalized()).isNotNull();
        assertThat(r.personalized().name()).isEqualTo("alice");
        assertThat(r.hasFallbackWarning()).isFalse();
    }

    @Test
    void fallsBackWithWarningWhenReferencedProfileMissing() {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        HrtfImportController.Resolution r = controller.resolve("imported-on-other-machine");
        assertThat(r.generic()).isNotNull();
        assertThat(r.personalized()).isNull();
        assertThat(r.hasFallbackWarning()).isTrue();
        assertThat(r.fallbackWarning().orElseThrow())
                .contains("imported-on-other-machine")
                .contains("falling back");
    }

    @Test
    void nullReferenceResolvesToFactoryDefault() {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        HrtfImportController controller = new HrtfImportController(library, 48000.0);

        HrtfImportController.Resolution r = controller.resolve(null);
        assertThat(r.generic()).isNotNull();
        assertThat(r.hasFallbackWarning()).isFalse();
    }

    @Test
    void rejectsNonPositiveSessionSampleRate() {
        HrtfProfileLibrary library = new HrtfProfileLibrary(tempDir);
        assertThatThrownBy(() -> new HrtfImportController(library, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HrtfImportController(library, -48000.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
