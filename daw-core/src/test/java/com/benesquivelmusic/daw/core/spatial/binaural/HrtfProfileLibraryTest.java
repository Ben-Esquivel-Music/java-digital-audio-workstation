package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrtfProfileLibraryTest {

    @TempDir
    Path tempDir;

    private static PersonalizedHrtfProfile sample(String name) {
        int m = 4;
        int n = 8;
        float[][] left = new float[m][n];
        float[][] right = new float[m][n];
        double[][] positions = new double[m][3];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                left[i][j] = i + 0.01f * j;
                right[i][j] = -i - 0.01f * j;
            }
            positions[i] = new double[]{i * 90.0, 0.0, 1.5};
        }
        return new PersonalizedHrtfProfile(name, m, 48000.0, left, right, positions);
    }

    @Test
    void exposesGenericProfiles() {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir);
        assertThat(lib.genericProfiles()).containsExactly(HrtfProfile.values());
    }

    @Test
    void listsImportedProfilesAlphabetically() throws IOException {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir);
        lib.save(sample("zeta"));
        lib.save(sample("alpha"));
        lib.save(sample("mu"));

        assertThat(lib.listImportedProfileNames()).containsExactly("alpha", "mu", "zeta");
    }

    @Test
    void roundTripsProfileThroughDisk() throws IOException {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir);
        PersonalizedHrtfProfile original = sample("aural-id-12345");
        lib.save(original);

        Optional<PersonalizedHrtfProfile> loaded = lib.loadImportedProfile("aural-id-12345");
        assertThat(loaded).isPresent();
        PersonalizedHrtfProfile back = loaded.get();
        assertThat(back.name()).isEqualTo(original.name());
        assertThat(back.measurementCount()).isEqualTo(original.measurementCount());
        assertThat(back.sampleRate()).isEqualTo(original.sampleRate());
        assertThat(back.impulseLength()).isEqualTo(original.impulseLength());
        assertThat(back.leftImpulses()[2]).containsExactly(original.leftImpulses()[2]);
        assertThat(back.rightImpulses()[2]).containsExactly(original.rightImpulses()[2]);
        assertThat(back.measurementPositionsSpherical()[3])
                .containsExactly(original.measurementPositionsSpherical()[3]);
    }

    @Test
    void deletesPersistedProfile() throws IOException {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir);
        lib.save(sample("temp"));
        assertThat(lib.hasImportedProfile("temp")).isTrue();
        assertThat(lib.deleteImportedProfile("temp")).isTrue();
        assertThat(lib.hasImportedProfile("temp")).isFalse();
        assertThat(lib.deleteImportedProfile("temp")).isFalse();
    }

    @Test
    void chooserCombinesGenericAndPersonalizedEntries() throws IOException {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir);
        lib.save(sample("user-a"));

        List<HrtfProfileLibrary.ProfileEntry> entries = lib.chooserEntries();

        assertThat(entries).hasSize(HrtfProfile.values().length + 1);
        assertThat(entries.get(0).kind()).isEqualTo(HrtfProfileLibrary.Kind.GENERIC);
        HrtfProfileLibrary.ProfileEntry last = entries.get(entries.size() - 1);
        assertThat(last.kind()).isEqualTo(HrtfProfileLibrary.Kind.PERSONALIZED);
        assertThat(last.personalizedName()).isEqualTo("user-a");
        assertThat(last.displayName()).isEqualTo("user-a");
    }

    @Test
    void rejectsPathTraversalNames() {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir);
        assertThatThrownBy(() -> lib.profileFile("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lib.profileFile("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lib.profileFile(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loadingMissingProfileReturnsEmpty() throws IOException {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir);
        assertThat(lib.loadImportedProfile("does-not-exist")).isEmpty();
    }

    @Test
    void listingOnNonExistentDirReturnsEmpty() throws IOException {
        HrtfProfileLibrary lib = new HrtfProfileLibrary(tempDir.resolve("never-created"));
        assertThat(lib.listImportedProfileNames()).isEmpty();
    }
}
