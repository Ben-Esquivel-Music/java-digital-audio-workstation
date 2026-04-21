package com.benesquivelmusic.daw.app;

import javafx.application.Application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Entry point for the DAW application.
 *
 * <p>This separate launcher class exists because JavaFX requires the
 * {@link Application} subclass to <em>not</em> be the main class when
 * running from a classpath-based (non-module) configuration.</p>
 *
 * <p>On startup the launcher also materializes the bundled
 * {@code zgc.conf} JVM options file into the user's settings directory
 * (see {@link #installZgcConfig(Path, String)}). The shipped launcher
 * script references that file via {@code java @<settings>/zgc.conf ...}
 * to run the DAW under the real-time ZGC tuning profile documented in
 * {@code docs/java26-setup.md}.</p>
 */
public final class DawLauncher {

    /** Classpath resource containing the ZGC JVM options template. */
    static final String ZGC_CONF_RESOURCE = "/zgc.conf";

    /** File name written into the user's settings directory. */
    static final String ZGC_CONF_FILENAME = "zgc.conf";

    /** Default heap size used when {@code sessionMem} is not provided. */
    static final String DEFAULT_SESSION_MEM = "4G";

    /**
     * Conservative validation for {@code sessionMem}: a positive integer
     * followed by an optional {@code k/K/m/M/g/G} unit suffix. This is
     * the same syntax accepted by {@code -Xms} / {@code -Xmx}, and
     * prevents arbitrary whitespace / newlines / extra JVM args from
     * being injected into the options file via substitution.
     */
    static final Pattern SESSION_MEM_PATTERN = Pattern.compile("\\d+[kKmMgG]?");

    private DawLauncher() {
        // utility class
    }

    public static void main(String[] args) {
        try {
            installZgcConfig(userSettingsDirectory(), resolveSessionMem());
        } catch (IOException e) {
            // Non-fatal: the DAW still runs, just without the tuned GC profile.
            System.err.println("Warning: could not install zgc.conf: " + e.getMessage());
        }
        Application.launch(DawApplication.class, args);
    }

    /**
     * Writes the bundled {@code zgc.conf} template into the given settings
     * directory, substituting {@code ${sessionMem}} with the supplied value.
     * The directory is created if it does not already exist.
     *
     * @param settingsDir directory where {@code zgc.conf} will be written;
     *                    must not be {@code null}
     * @param sessionMem  JVM memory size to substitute for
     *                    {@code ${sessionMem}} (for example {@code "4G"});
     *                    must not be {@code null} or blank
     * @return the absolute path to the written config file
     * @throws IOException if the file cannot be written
     */
    public static Path installZgcConfig(Path settingsDir, String sessionMem) throws IOException {
        Objects.requireNonNull(settingsDir, "settingsDir must not be null");
        Objects.requireNonNull(sessionMem, "sessionMem must not be null");
        String trimmed = sessionMem.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("sessionMem must not be blank");
        }
        if (!SESSION_MEM_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "sessionMem must match " + SESSION_MEM_PATTERN.pattern()
                            + " (e.g. \"4G\"): " + sessionMem);
        }

        Files.createDirectories(settingsDir);
        Path target = settingsDir.resolve(ZGC_CONF_FILENAME).toAbsolutePath();

        String template = readResource(ZGC_CONF_RESOURCE);
        String rendered = template.replace("${sessionMem}", trimmed);

        Path tmp = Files.createTempFile(settingsDir, "zgc", ".conf.tmp");
        Files.writeString(tmp, rendered, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems (notably certain Windows setups) don't
            // support atomic rename. Fall back to a plain replace; the
            // temp-file write still avoids truncation on writer crash.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Resolves the user's settings directory for the DAW.
     *
     * <p>Honors {@code $XDG_CONFIG_HOME} on Linux, defaults to
     * {@code %APPDATA%} on Windows, and
     * {@code ~/Library/Application Support} on macOS. Falls back to
     * {@code ~/.config} on unknown platforms.</p>
     */
    static Path userSettingsDirectory() {
        String appName = "java-daw";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank())
                    ? Path.of(appData)
                    : Path.of(home, "AppData", "Roaming");
            return base.resolve(appName);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Path.of(home, "Library", "Application Support", appName);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".config");
        return base.resolve(appName);
    }

    /**
     * Resolves the session memory size used to pin {@code -Xms} / {@code -Xmx}.
     * The system property {@code daw.sessionMem} or the environment variable
     * {@code DAW_SESSION_MEM} may override the default.
     */
    static String resolveSessionMem() {
        String prop = System.getProperty("daw.sessionMem");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String env = System.getenv("DAW_SESSION_MEM");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_SESSION_MEM;
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = DawLauncher.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + resource);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        }
    }
}
