package com.benesquivelmusic.daw.core.persistence.backup;

import com.benesquivelmusic.daw.core.persistence.AtomicFileWriter;
import com.benesquivelmusic.daw.sdk.persistence.BackupRetentionPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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
    private final AtomicFileWriter fileWriter;

    /** Creates a store that uses {@code ~/.daw/backup-retention.json}. */
    public BackupRetentionPolicyStore() {
        this(defaultGlobalPath());
    }

    /** Creates a store with an explicit global path (for tests). */
    public BackupRetentionPolicyStore(Path globalPath) {
        this(globalPath, new AtomicFileWriter());
    }

    /**
     * Creates a store with an explicit global path and the writer its saves
     * go through — for tests that fail or observe the writes (story 331).
     */
    BackupRetentionPolicyStore(Path globalPath, AtomicFileWriter fileWriter) {
        this.globalPath = Objects.requireNonNull(globalPath, "globalPath must not be null");
        this.fileWriter = Objects.requireNonNull(fileWriter, "fileWriter must not be null");
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
     * if the file does not exist or cannot be parsed. Never deliver its result
     * to a checkpoint writer: a file that exists but cannot be read must not
     * become a policy the user did not choose — {@link #loadGlobal()} tells the
     * two apart.
     */
    public BackupRetentionPolicy loadGlobalOrDefault() {
        try {
            return load(globalPath).orElse(BackupRetentionPolicy.DEFAULT);
        } catch (IOException | IllegalArgumentException e) {
            return BackupRetentionPolicy.DEFAULT;
        }
    }

    /**
     * Loads the global policy for delivery to the checkpoint writer, which
     * deletes checkpoints by it (story 335). A missing file is no policy: the
     * result is empty and the caller's default applies. A file that exists
     * is either a complete, valid policy or a failure — nothing falls back
     * field by field. The whole file must be one complete flat JSON object
     * ({@code CompleteObjectReader}), so a file cut anywhere before its
     * closing brace is refused — even one cut inside its last value, whose
     * digits still parse ({@code "maxBytes": 2147} would cap the checkpoints
     * at 2147 bytes); each of the six keys {@link #toJson} writes must be
     * present and parse; and {@link BackupRetentionPolicy} must accept the
     * values. A zero-byte, truncated or hand-edited file is therefore refused
     * rather than read as a policy the user did not choose. A number's digits
     * may be any script's decimal digits, for files written before story 332
     * in the user's locale.
     *
     * @return the stored policy, or empty when the file does not exist
     * @throws IOException if the file exists but cannot be read, or does not
     *                     hold a complete, valid policy
     */
    public Optional<BackupRetentionPolicy> loadGlobal() throws IOException {
        String json;
        try {
            json = Files.readString(globalPath);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        try {
            Map<String, String> values = CompleteObjectReader.read(json);
            return Optional.of(new BackupRetentionPolicy(
                    Integer.parseInt(required(values, "keepRecent")),
                    Integer.parseInt(required(values, "keepHourly")),
                    Integer.parseInt(required(values, "keepDaily")),
                    Integer.parseInt(required(values, "keepWeekly")),
                    Duration.ofSeconds(Long.parseLong(required(values, "maxAgeSeconds"))),
                    Long.parseLong(required(values, "maxBytes"))));
        } catch (IllegalArgumentException invalid) {
            // Not one complete object, a missing key, a value that does not parse, or one the policy refuses.
            throw new IOException(globalPath + " holds no valid checkpoint retention policy: "
                    + invalid.getMessage(), invalid);
        }
    }

    /** The value of {@code key}; a missing key is refused like a value that does not parse. */
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("\"" + key + "\" is missing");
        }
        return value;
    }

    /** Writes the given policy to the global path, creating parent directories. */
    public void saveGlobal(BackupRetentionPolicy policy) throws IOException {
        save(globalPath, policy, fileWriter);
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
        save(projectDirectory.resolve(FILE_NAME), policy, fileWriter);
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

    /**
     * Writes a policy to an explicit path, creating parent directories. The
     * file is replaced atomically ({@link AtomicFileWriter}, story 331): a
     * failed write leaves the previous policy file as it was.
     */
    public static void save(Path file, BackupRetentionPolicy policy) throws IOException {
        save(file, policy, new AtomicFileWriter());
    }

    private static void save(Path file, BackupRetentionPolicy policy, AtomicFileWriter fileWriter)
            throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        fileWriter.writeString(file, toJson(policy));
    }

    /** Serializes a policy to JSON. Visible for testing. */
    public static String toJson(BackupRetentionPolicy p) {
        return String.format(java.util.Locale.ROOT, """
                {
                  "keepRecent": %d,
                  "keepHourly": %d,
                  "keepDaily": %d,
                  "keepWeekly": %d,
                  "maxAgeSeconds": %d,
                  "maxBytes": %d
                }
                """,
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

    /**
     * Reads a policy file for {@link #loadGlobal()} as exactly one complete
     * flat JSON object: optional whitespace, <code>{</code>, members separated
     * by commas whose values are numbers or strings, <code>}</code>, and
     * nothing after it but whitespace. Unlike {@code parseFlat}, which keeps
     * whatever key/value pairs it finds, it refuses text that stops anywhere
     * before the closing brace — inside a key, between members, or inside a
     * value that still parses — and anything else that is not such an object.
     * A key given twice is refused; unknown keys are returned and left to the
     * caller. Values are returned as written (a string's content without its
     * quotes). A number's digits may be any script's decimal digits, for files
     * written before story 332 in the user's locale, as the parse that follows
     * reads them ({@code digitValue}).
     */
    private static final class CompleteObjectReader {

        private static final int END = -1;

        private final String text;
        private int position;

        private CompleteObjectReader(String text) {
            this.text = text;
            // A leading byte order mark is an encoding marker, not content (RFC 8259 §8.1).
            this.position = text.startsWith("\uFEFF") ? 1 : 0;
        }

        /**
         * The members of the one object {@code text} holds.
         *
         * @throws IllegalArgumentException where {@code text} stops being one complete flat object
         */
        static Map<String, String> read(String text) {
            CompleteObjectReader reader = new CompleteObjectReader(text);
            Map<String, String> members = reader.object();
            reader.skipWhitespace();
            if (reader.peek() != END) {
                throw reader.refusal("text after the closing '}'");
            }
            return members;
        }

        private Map<String, String> object() {
            skipWhitespace();
            expect('{');
            Map<String, String> members = new HashMap<>();
            skipWhitespace();
            if (accept('}')) {
                return members;
            }
            do {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = peek() == '"' ? string() : number();
                if (members.putIfAbsent(key, value) != null) {
                    throw refusal("\"" + key + "\" given twice");
                }
                skipWhitespace();
            } while (accept(','));
            expect('}');
            return members;
        }

        private String string() {
            expect('"');
            int start = position;
            while (true) {
                int c = peek();
                if (c == END) {
                    throw refusal("a string that is never closed");
                }
                if (c < 0x20) {
                    throw refusal("a control character inside a string");
                }
                position++;
                if (c == '"') {
                    return text.substring(start, position - 1);
                }
                if (c == '\\') {
                    if (peek() == END) {
                        throw refusal("a string that is never closed");
                    }
                    position++;
                }
            }
        }

        private String number() {
            int start = position;
            accept('-');
            // A leading zero stands alone, in whichever script's digits it is written.
            if (digitValue(peek()) == 0) {
                position++;
            } else {
                digits();
            }
            if (accept('.')) {
                digits();
            }
            if (accept('e') || accept('E')) {
                if (!accept('+')) {
                    accept('-');
                }
                digits();
            }
            return text.substring(start, position);
        }

        private void digits() {
            if (digitValue(peek()) < 0) {
                throw refusal(peek() == END ? "the end of the text where a value belongs"
                        : "a value that is not a number or a string");
            }
            while (digitValue(peek()) >= 0) {
                position++;
            }
        }

        /**
         * The value of {@code c} as a decimal digit of any script, or -1: the
         * digits {@code Integer.parseInt} and {@code Long.parseLong} read.
         * Before story 332, {@code toJson} wrote its numbers in the default
         * locale, so a policy saved under {@code ar-SA}, {@code fa-IR} or
         * {@code th-TH-u-nu-thai} holds Arabic-Indic, Extended Arabic-Indic or
         * Thai digits, and every earlier reader read them back.
         */
        private static int digitValue(int c) {
            return c == END ? -1 : Character.digit(c, 10);
        }

        private void skipWhitespace() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') {
                position++;
            }
        }

        private boolean accept(char expected) {
            if (peek() == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!accept(expected)) {
                throw refusal(peek() == END ? "the end of the text where '" + expected + "' belongs"
                        : "'" + (char) peek() + "' where '" + expected + "' belongs");
            }
        }

        private int peek() {
            return position < text.length() ? text.charAt(position) : END;
        }

        private IllegalArgumentException refusal(String what) {
            return new IllegalArgumentException("not one complete JSON object: " + what
                    + " (character " + position + ")");
        }
    }
}
