package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfProfile;
import com.benesquivelmusic.daw.sdk.spatial.PersonalizedHrtfProfile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Library of HRTF profiles available for binaural monitoring — combining the
 * built-in {@link HrtfProfile} presets with personalized profiles imported
 * from SOFA files (AES69-2020).
 *
 * <p>Imported profiles are persisted under {@code ~/.daw/hrtf/} as a compact
 * binary format ({@code <name>.hrtfp}) so they survive across sessions and
 * machines. Project files reference profiles purely by name; opening a
 * project on another machine that has the same SOFA imported into its
 * library restores the personalized rendering exactly.</p>
 *
 * <p>The {@link #importSofa} action is the entry point used by the UI's
 * "Import SOFA…" command: it parses + validates the SOFA file, resamples
 * impulses to the session rate, persists the profile, and returns the
 * import result so the dialog can surface coverage warnings.</p>
 *
 * @see SofaFileReader
 * @see PersonalizedHrtfProfile
 */
public final class HrtfProfileLibrary {

    /** File extension used for persisted personalized profiles. */
    public static final String PROFILE_EXTENSION = ".hrtfp";

    /** Default relative path under {@code user.home}. */
    public static final String DEFAULT_RELATIVE_PATH = ".daw/hrtf";

    private static final int FILE_MAGIC = 0x48525446; // "HRTF"
    private static final int FILE_VERSION = 1;

    private final Path baseDir;

    /** Creates a library rooted at {@code ~/.daw/hrtf/}. */
    public HrtfProfileLibrary() {
        this(Paths.get(System.getProperty("user.home", "."), DEFAULT_RELATIVE_PATH));
    }

    /**
     * Creates a library backed by an explicit directory — useful for tests
     * that point at a {@code @TempDir}.
     *
     * @param baseDir directory where {@code .hrtfp} files are stored
     */
    public HrtfProfileLibrary(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null");
    }

    /** Returns the on-disk directory backing this library. */
    public Path baseDir() {
        return baseDir;
    }

    // ---- Built-in profiles --------------------------------------------------

    /**
     * Returns the built-in generic profiles (small/medium/large head presets).
     *
     * @return an unmodifiable list of profiles; never empty
     */
    public List<HrtfProfile> genericProfiles() {
        return List.of(HrtfProfile.values());
    }

    // ---- Imported (personalized) profiles -----------------------------------

    /**
     * Lists the names of all personalized profiles currently persisted in the
     * library. The returned list is sorted alphabetically and is safe to
     * present directly in the binaural monitoring chooser.
     *
     * @return profile names (without {@link #PROFILE_EXTENSION}); never null
     * @throws IOException if the library directory cannot be read
     */
    public List<String> listImportedProfileNames() throws IOException {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            List<String> names = new ArrayList<>();
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(PROFILE_EXTENSION))
                    .map(n -> n.substring(0, n.length() - PROFILE_EXTENSION.length()))
                    .filter(stem -> {
                        try {
                            profileFile(stem);
                            return true;
                        } catch (IllegalArgumentException ex) {
                            return false;
                        }
                    })
                    .forEach(names::add);
            Collections.sort(names);
            return names;
        }
    }

    /**
     * Returns {@code true} if a personalized profile with the given name is
     * persisted in this library.
     */
    public boolean hasImportedProfile(String name) {
        return Files.isRegularFile(profileFile(name));
    }

    /**
     * Loads a previously imported personalized profile by name.
     *
     * @param name profile name as returned by {@link #listImportedProfileNames}
     * @return the loaded profile, or empty if no such profile is persisted
     * @throws IOException if the persisted file cannot be read or is malformed
     */
    public Optional<PersonalizedHrtfProfile> loadImportedProfile(String name) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        Path file = profileFile(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            int magic = in.readInt();
            if (magic != FILE_MAGIC) {
                throw new IOException("Not a personalized HRTF profile file: " + file);
            }
            int version = in.readInt();
            if (version != FILE_VERSION) {
                throw new IOException("Unsupported personalized HRTF profile version: " + version);
            }
            String storedName = in.readUTF();
            if (!storedName.equals(name)) {
                throw new IOException("Personalized HRTF profile name mismatch for file " + file
                        + ": expected '" + name + "' but found '" + storedName + "'");
            }
            double sampleRate = in.readDouble();
            int m = in.readInt();
            int n = in.readInt();
            if (m <= 0 || m > 100_000) {
                throw new IOException("Invalid measurement count in profile file " + file + ": " + m);
            }
            if (n <= 0 || n > 1_000_000) {
                throw new IOException("Invalid impulse length in profile file " + file + ": " + n);
            }
            float[][] left = new float[m][n];
            float[][] right = new float[m][n];
            double[][] positions = new double[m][3];
            for (int i = 0; i < m; i++) {
                positions[i][0] = in.readDouble();
                positions[i][1] = in.readDouble();
                positions[i][2] = in.readDouble();
            }
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) left[i][j] = in.readFloat();
            }
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < n; j++) right[i][j] = in.readFloat();
            }
            return Optional.of(new PersonalizedHrtfProfile(
                    storedName, m, sampleRate, left, right, positions));
        }
    }

    /**
     * Imports a SOFA file into the library, persisting the resulting
     * {@link PersonalizedHrtfProfile} so it appears in the binaural monitoring
     * chooser alongside the built-in profiles. Equivalent to the UI's
     * "Import SOFA…" command.
     *
     * <p>Validation, sample-rate matching and coverage analysis are performed
     * by {@link SofaFileReader}; any non-fatal advisories surface through the
     * returned {@link SofaFileReader.ImportResult#warnings} list.</p>
     *
     * @param sofaFile          path to the SOFA file
     * @param sessionSampleRate session sample rate in Hz; impulses are
     *                          resampled to this rate
     * @return the import result (profile + warnings)
     * @throws IOException if the SOFA file cannot be read or is malformed
     */
    public SofaFileReader.ImportResult importSofa(Path sofaFile, double sessionSampleRate)
            throws IOException {
        SofaFileReader.ImportResult result = SofaFileReader.read(sofaFile, sessionSampleRate);
        save(result.profile());
        return result;
    }

    /**
     * Persists a {@link PersonalizedHrtfProfile} into the library, overwriting
     * any existing profile with the same name. Useful for testing and for
     * importing profiles obtained outside of SOFA files.
     *
     * @param profile the profile to persist
     * @throws IOException if the file cannot be written
     */
    public void save(PersonalizedHrtfProfile profile) throws IOException {
        Objects.requireNonNull(profile, "profile must not be null");
        Files.createDirectories(baseDir);
        Path file = profileFile(profile.name());
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(FILE_MAGIC);
            out.writeInt(FILE_VERSION);
            out.writeUTF(profile.name());
            out.writeDouble(profile.sampleRate());
            out.writeInt(profile.measurementCount());
            out.writeInt(profile.impulseLength());
            for (double[] pos : profile.measurementPositionsSpherical()) {
                out.writeDouble(pos[0]);
                out.writeDouble(pos[1]);
                out.writeDouble(pos[2]);
            }
            for (float[] row : profile.leftImpulses()) {
                for (float v : row) out.writeFloat(v);
            }
            for (float[] row : profile.rightImpulses()) {
                for (float v : row) out.writeFloat(v);
            }
        }
    }

    /**
     * Removes a personalized profile from the library.
     *
     * @param name profile name
     * @return {@code true} if a profile was removed, {@code false} if no such
     *         profile existed
     * @throws IOException if the file cannot be deleted
     */
    public boolean deleteImportedProfile(String name) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        return Files.deleteIfExists(profileFile(name));
    }

    /**
     * Resolves the on-disk path used to persist the profile with the given name.
     * Visible for tests.
     */
    Path profileFile(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        // Reject path-traversal characters that would let a profile name escape baseDir.
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("name contains illegal characters: " + name);
        }
        return baseDir.resolve(name + PROFILE_EXTENSION);
    }

    /**
     * Returns a unified list of profile chooser entries — built-in profiles
     * first (by display name), then imported personalized profiles
     * (alphabetically). Used by the binaural monitoring chooser.
     *
     * @return chooser entries in display order; never null
     */
    public List<ProfileEntry> chooserEntries() {
        List<ProfileEntry> out = new ArrayList<>();
        for (HrtfProfile p : HrtfProfile.values()) {
            out.add(new ProfileEntry(p.displayName(), Kind.GENERIC, p, null));
        }
        try {
            for (String name : listImportedProfileNames()) {
                out.add(new ProfileEntry(name, Kind.PERSONALIZED, null, name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Whether a chooser entry refers to a built-in or a personalized profile. */
    public enum Kind { GENERIC, PERSONALIZED }

    /**
     * A single entry in the binaural monitoring profile chooser. Either
     * {@code generic} or {@code personalizedName} is non-null depending on
     * {@link Kind}.
     */
    public record ProfileEntry(
            String displayName,
            Kind kind,
            HrtfProfile generic,
            String personalizedName) {

        public ProfileEntry {
            Objects.requireNonNull(displayName, "displayName must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            switch (kind) {
                case GENERIC -> Objects.requireNonNull(generic, "generic must not be null for GENERIC entries");
                case PERSONALIZED -> Objects.requireNonNull(personalizedName,
                        "personalizedName must not be null for PERSONALIZED entries");
            }
        }
    }
}
