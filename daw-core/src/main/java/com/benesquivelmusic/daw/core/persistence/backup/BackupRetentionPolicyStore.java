package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes {@link BackupRetentionPolicy} JSON documents.
 *
 * <p>The store handles two locations:</p>
 * <ul>
 *   <li><b>Global default</b> — {@code ~/.daw/backup-retention.json}.
 *       When this file is missing, the store falls back to
 *       {@link BackupRetentionPolicy#DEFAULT} in memory until a global policy
 *       is explicitly written via {@link #saveGlobal(BackupRetentionPolicy)}.</li>
 *   <li><b>Per-project override</b> — {@code <project-dir>/backup-retention.json}.
 *       When present this file overrides the global default for that
 *       project.</li>
 * </ul>
 *
 * <p>The format is a small flat JSON object with a fixed key set, hand-rolled
 * to avoid pulling in a JSON library (consistent with
 * {@code ReflectivePresetSerializer} elsewhere in the codebase).</p>
 *
 * <pre>{@code
 * {
 *   "keepRecent": 10,
 *   "keepHourly": 24,
 *   "keepDaily": 14,
 *   "keepWeekly": 8,
 *   "maxAgeSeconds": 2592000,
 *   "maxBytes": 2147483648
 * }
 * }</pre>
 */
public final class BackupRetentionPolicyStore {

    /** File name used for both the global and per-project policy file. */
    public static final String FILE_NAME = "backup-retention.json";

    private final Path globalPath;

    /** Creates a store that uses {@code ~/.daw/backup-retention.json}. */
    public BackupRetentionPolicyStore() {
        this(defaultGlobalPath());
    }

    /** Creates a store with an explicit global path (for tests). */
    public BackupRetentionPolicyStore(Path globalPath) {
        this.globalPath = Objects.requireNonNull(globalPath, "globalPath must not be null");
    }

    /** Returns {@code <user.home>/.daw/backup-retention.json}. */
    public static Path defaultGlobalPath() {
        return Paths.get(System.getProperty("user.home", "."), ".daw", FILE_NAME);
    }

    /** Returns the configured global policy path. */
    public Path globalPath() {
        return globalPath;
    }

    /**
     * Loads the global policy, falling back to {@link BackupRetentionPolicy#DEFAULT}
     * if the file does not exist or cannot be parsed.
     */
    public BackupRetentionPolicy loadGlobalOrDefault() {
        try {
            return load(globalPath).orElse(BackupRetentionPolicy.DEFAULT);
        } catch (IOException | IllegalArgumentException e) {
            return BackupRetentionPolicy.DEFAULT;
        }
    }

    /** Writes the given policy to the global path, creating parent directories. */
    public void saveGlobal(BackupRetentionPolicy policy) throws IOException {
        save(globalPath, policy);
    }

    /**
     * Resolves the effective policy for a project: per-project override if
     * {@code <projectDirectory>/backup-retention.json} exists, otherwise the
     * global default.
     */
    public BackupRetentionPolicy resolveForProject(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        Path override = projectDirectory.resolve(FILE_NAME);
        try {
            Optional<BackupRetentionPolicy> projectPolicy = load(override);
            if (projectPolicy.isPresent()) {
                return projectPolicy.get();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // malformed or unreadable override — fall through to global default
        }
        return loadGlobalOrDefault();
    }

    /** Writes a per-project override file. */
    public void saveForProject(Path projectDirectory, BackupRetentionPolicy policy) throws IOException {
        Objects.requireNonNull(projectDirectory, "projectDirectory must not be null");
        save(projectDirectory.resolve(FILE_NAME), policy);
    }

    /** Loads a policy from an explicit path; empty if the file does not exist. */
    public static Optional<BackupRetentionPolicy> load(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String json = Files.readString(file);
        return Optional.of(parse(json));
    }

    /** Writes a policy to an explicit path, creating parent directories. */
    public static void save(Path file, BackupRetentionPolicy policy) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, toJson(policy));
    }

    /** Serializes a policy to JSON. Visible for testing. */
    public static String toJson(BackupRetentionPolicy p) {
        return """
                {
                  "keepRecent": %d,
                  "keepHourly": %d,
                  "keepDaily": %d,
                  "keepWeekly": %d,
                  "maxAgeSeconds": %d,
                  "maxBytes": %d
                }
                """.formatted(
                p.keepRecent(),
                p.keepHourly(),
                p.keepDaily(),
                p.keepWeekly(),
                p.maxAge() == null ? 0L : p.maxAge().getSeconds(),
                p.maxBytes());
    }

    /** Parses a flat JSON document into a policy. Visible for testing. */
    public static BackupRetentionPolicy parse(String json) {
        Objects.requireNonNull(json, "json must not be null");
        Map<String, String> values = parseFlat(json);
        BackupRetentionPolicy d = BackupRetentionPolicy.DEFAULT;
        int keepRecent = intValue(values, "keepRecent", d.keepRecent());
        int keepHourly = intValue(values, "keepHourly", d.keepHourly());
        int keepDaily = intValue(values, "keepDaily", d.keepDaily());
        int keepWeekly = intValue(values, "keepWeekly", d.keepWeekly());
        long maxAgeSeconds = longValue(values, "maxAgeSeconds",
                d.maxAge() == null ? 0L : d.maxAge().getSeconds());
        long maxBytes = longValue(values, "maxBytes", d.maxBytes());
        return new BackupRetentionPolicy(
                keepRecent, keepHourly, keepDaily, keepWeekly,
                Duration.ofSeconds(Math.max(0L, maxAgeSeconds)),
                Math.max(0L, maxBytes));
    }

    private static int intValue(Map<String, String> values, String key, int fallback) {
        String v = values.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }

    private static long longValue(Map<String, String> values, String key, long fallback) {
        String v = values.get(key);
        if (v == null) return fallback;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return fallback; }
    }

    /** Tiny flat-JSON parser: extracts numeric/string values for a known key set. */
    private static Map<String, String> parseFlat(String json) {
        Map<String, String> result = new HashMap<>();
        int i = 0, n = json.length();
        while (i < n) {
            // find next quoted key
            while (i < n && json.charAt(i) != '"') i++;
            if (i >= n) break;
            int keyStart = ++i;
            while (i < n && json.charAt(i) != '"') i++;
            if (i >= n) break;
            String key = json.substring(keyStart, i);
            i++;
            // skip whitespace and ':'
            while (i < n && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= n || json.charAt(i) != ':') continue;
            i++;
            while (i < n && Character.isWhitespace(json.charAt(i))) i++;
            // read value until ',' '}' or whitespace newline
            int valStart = i;
            if (i < n && json.charAt(i) == '"') {
                int sStart = ++i;
                while (i < n && json.charAt(i) != '"') i++;
                result.put(key, json.substring(sStart, i));
                if (i < n) i++;
            } else {
                while (i < n && json.charAt(i) != ',' && json.charAt(i) != '}'
                        && json.charAt(i) != '\n' && json.charAt(i) != '\r') i++;
                result.put(key, json.substring(valStart, i).trim());
            }
        }
        return result;
    }
}
