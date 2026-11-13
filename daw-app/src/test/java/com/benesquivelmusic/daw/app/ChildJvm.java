package com.benesquivelmusic.daw.app;

import com.benesquivelmusic.daw.app.ui.SourceScanSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a test main of {@code daw.app} in a JVM of its own, set up as the
 * packaged launcher runs the application (story 336): this JVM's own JDK,
 * {@code daw.app} on the module path launched as {@code -m daw.app/<main>}
 * with the main's class files patched into the module, and none of
 * surefire's {@code --add-reads} or {@code --add-opens} (dawg-annotations-reflection
 * "### Adding a new reflection consumer": module readability shows only in a
 * JVM of its own). The environment has none of the variables the launcher
 * reads options from ({@code JAVA_TOOL_OPTIONS}, {@code JDK_JAVA_OPTIONS},
 * {@code _JAVA_OPTIONS}); the user settings directory resolves into the
 * test's own folder on every platform ({@code APPDATA}, {@code XDG_CONFIG_HOME}
 * and {@code user.home}). Standard output and error go to files, not pipes
 * (on Linux {@code destroyForcibly} closes the parent's end of a pipe); the
 * wait is bounded below the 60 s per-test timeout, and a child still running
 * then is destroyed.
 *
 * <p>{@link #logManagerOption()} reads the {@code -Djava.util.logging.manager}
 * value from {@code daw-app/pom.xml}'s jpackage {@code --java-options}, so the
 * child launches with exactly the option the installer ships.</p>
 */
final class ChildJvm {

    /** The longest a child may run before it is destroyed; under the 60 s per-test timeout. */
    static final Duration BOUND = Duration.ofSeconds(45);

    /** The environment variables the launcher reads options from; the child gets none of them. */
    private static final List<String> LAUNCHER_OPTION_VARIABLES =
            List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern ARGUMENT = Pattern.compile("<argument>(.*?)</argument>");
    private static final Pattern OPTION = Pattern.compile("<option>(.*?)</option>");

    private ChildJvm() {
    }

    /**
     * What a child did.
     *
     * @param exited    whether it ended within {@link #BOUND}
     * @param exitValue its exit value, or -1 when it was destroyed
     * @param stdout    its standard output
     * @param stderr    its standard error
     * @param elapsed   how long it ran
     * @param work      the folder it ran in
     */
    record Result(boolean exited, int exitValue, String stdout, String stderr, Duration elapsed, Path work) {

        Result {
            Objects.requireNonNull(stdout, "stdout must not be null");
            Objects.requireNonNull(stderr, "stderr must not be null");
            Objects.requireNonNull(elapsed, "elapsed must not be null");
            Objects.requireNonNull(work, "work must not be null");
        }

        /** The settings directory the production resolution gives the child, worked out independently here. */
        Path settingsDirectory() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return work.resolve("appdata").resolve("java-daw");
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return work.resolve("home").resolve("Library").resolve("Application Support").resolve("java-daw");
            }
            return work.resolve("xdg").resolve("java-daw");
        }

        /** The log file the child wrote. */
        Path logFile() {
            return settingsDirectory().resolve("logs").resolve("daw-0.log");
        }

        /** The log file's text. */
        String log() throws IOException {
            return Files.readString(logFile(), StandardCharsets.UTF_8);
        }

        /** Everything the child printed, for assertion messages. */
        String printed() {
            return "standard output:%n%s%nstandard error:%n%s".formatted(stdout, stderr);
        }
    }

    /**
     * Runs {@code main} — a test class of package {@code com.benesquivelmusic.daw.app} —
     * as {@code -m daw.app/<main>}.
     *
     * @param work       an empty folder of the test's own
     * @param main       the main class
     * @param jvmOptions options before the module path, for example {@link #logManagerOption()}
     * @param arguments  the main's arguments
     * @return what it did
     * @throws IOException          if the child cannot be set up
     * @throws InterruptedException if the wait is interrupted
     */
    static Result run(Path work, Class<?> main, List<String> jvmOptions, List<String> arguments)
            throws IOException, InterruptedException {
        Path patch = copyOfClassFiles(main, work.resolve("patch"));
        Path home = Files.createDirectories(work.resolve("home"));
        Path appData = Files.createDirectories(work.resolve("appdata"));
        Path xdg = Files.createDirectories(work.resolve("xdg"));
        String modulePath = System.getProperty("jdk.module.path");
        if (modulePath == null || modulePath.isBlank()) {
            throw new IllegalStateException("this JVM does not run daw.app on the module path");
        }
        List<String> command = new ArrayList<>();
        command.add(javaLauncher().toString());
        command.add("-Duser.home=" + home);
        command.addAll(jvmOptions);
        command.addAll(List.of("--module-path", modulePath, "--patch-module", "daw.app=" + patch,
                "-m", "daw.app/" + main.getName()));
        command.addAll(arguments);
        Path stdout = work.resolve("stdout.txt");
        Path stderr = work.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(work.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Map<String, String> environment = builder.environment();
        LAUNCHER_OPTION_VARIABLES.forEach(environment::remove);
        environment.put("APPDATA", appData.toString());
        environment.put("XDG_CONFIG_HOME", xdg.toString());

        long start = System.nanoTime();
        Process child = builder.start();
        boolean exited = child.waitFor(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        return new Result(exited, exited ? child.exitValue() : -1,
                Files.readString(stdout, StandardCharsets.UTF_8), Files.readString(stderr, StandardCharsets.UTF_8),
                elapsed, work);
    }

    /**
     * The {@code -Djava.util.logging.manager=…} option the jpackage launcher
     * carries: the argument right after a {@code --java-options} argument of
     * {@code daw-app/pom.xml}'s {@code jpackage-app-image} execution.
     *
     * @return the option, verbatim
     * @throws IOException if the POM cannot be read
     */
    static String logManagerOption() throws IOException {
        List<String> arguments = jpackageArguments();
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (arguments.get(i).equals("--java-options")
                    && arguments.get(i + 1).startsWith("-Djava.util.logging.manager=")) {
                return arguments.get(i + 1);
            }
        }
        throw new IllegalStateException("the jpackage arguments name no log manager: " + arguments);
    }

    /** The {@code <argument>}s of the POM's {@code jpackage-app-image} execution, in order. */
    static List<String> jpackageArguments() throws IOException {
        String pom = pomWithoutComments();
        int execution = pom.indexOf("<id>jpackage-app-image</id>");
        if (execution < 0) {
            throw new IllegalStateException("the POM has no jpackage-app-image execution");
        }
        int start = pom.indexOf("<arguments>", execution);
        int end = pom.indexOf("</arguments>", start);
        return matches(ARGUMENT, pom.substring(start, end));
    }

    /** The {@code <option>}s of the POM's javafx-maven-plugin configuration, in order. */
    static List<String> jlinkLauncherOptions() throws IOException {
        String configuration = javafxPluginConfiguration();
        int start = configuration.indexOf("<options>");
        if (start < 0) {
            return List.of();
        }
        return matches(OPTION, configuration.substring(start, configuration.indexOf("</options>", start)));
    }

    /**
     * The POM's javafx-maven-plugin block, comments removed: from its
     * {@code <artifactId>} up to its {@code </plugin>}, which holds the jlink
     * image's configuration and the launcher {@code <options>}.
     *
     * @return the block's text
     * @throws IOException if the POM cannot be read
     */
    static String javafxPluginConfiguration() throws IOException {
        String pom = pomWithoutComments();
        int plugin = pom.indexOf("<artifactId>javafx-maven-plugin</artifactId>");
        if (plugin < 0) {
            throw new IllegalStateException("the POM has no javafx-maven-plugin");
        }
        int end = pom.indexOf("</plugin>", plugin);
        return pom.substring(plugin, end);
    }

    /** {@code daw-app/pom.xml}. */
    static Path pom() {
        return SourceScanSupport.locateDawAppModule().resolve("pom.xml");
    }

    private static String pomWithoutComments() throws IOException {
        return COMMENT.matcher(Files.readString(pom(), StandardCharsets.UTF_8)).replaceAll("");
    }

    private static List<String> matches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1).strip());
        }
        return found;
    }

    /** Copies the class files of {@code main} and its nest members into {@code root}, as a patch of daw.app. */
    private static Path copyOfClassFiles(Class<?> main, Path root) throws IOException {
        for (Class<?> member : main.getNestMembers()) {
            String entry = member.getName().replace('.', '/') + ".class";
            Path target = root.resolve(entry);
            Files.createDirectories(target.getParent());
            try (InputStream bytes = member.getResourceAsStream("/" + entry)) {
                if (bytes == null) {
                    throw new IOException("the class file of " + member.getName() + " is not readable");
                }
                Files.copy(bytes, target);
            }
        }
        return root;
    }

    /** The {@code java} launcher of the JDK this JVM runs on — never one found on the PATH. */
    private static Path javaLauncher() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("java.exe");
        return Files.isRegularFile(windows) ? windows : bin.resolve("java");
    }
}
