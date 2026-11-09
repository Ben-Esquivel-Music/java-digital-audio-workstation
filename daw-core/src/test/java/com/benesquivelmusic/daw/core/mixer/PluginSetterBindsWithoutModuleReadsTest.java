package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.mixer.classpath.PluginParameterSetterMain;
import com.benesquivelmusic.daw.core.persistence.AnnotatedJarTestPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginTestJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A JAR plugin's {@code @ProcessorParam} setter binds where daw.core does not
 * read the plugin's module (story 334, finding FX2-1): the application's
 * launchers — the jlink image's {@code daw} script and the installer — run
 * daw.core as a named module with no {@code --add-reads}, and a plugin's
 * classes live in the unnamed module of the class loader the registry gives
 * it, which daw.core does not read. This test's own JVM cannot show it:
 * surefire runs daw.core with {@code --add-reads daw.core=ALL-UNNAMED}. So
 * it runs {@link PluginParameterSetterMain} in a JVM of its own, on this
 * JVM's JDK and module path, with only that program on the class path and a
 * clean environment, and reads what the program printed.
 */
class PluginSetterBindsWithoutModuleReadsTest {

    /** The environment variables the launcher reads options from; the child gets none of them. */
    private static final List<String> LAUNCHER_OPTION_VARIABLES =
            List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    @TempDir
    Path tempDir;

    @Test
    void anAnnotatedJarPluginsSetterBindsWhereDawCoreDoesNotReadItsModule() throws Exception {
        Path jar = PluginTestJars.buildJar(tempDir.resolve("plugins"), "annotated-gain.jar", List.of(),
                List.of(AnnotatedJarTestPlugin.class));
        Path classPath = copyOfClass(PluginParameterSetterMain.class, tempDir.resolve("classes"));
        String modulePath = System.getProperty("jdk.module.path");
        assertThat(modulePath).as("this JVM runs daw.core on the module path").isNotBlank();
        Path stdout = tempDir.resolve("stdout.txt");
        Path stderr = tempDir.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(
                javaLauncher().toString(),
                "--module-path", modulePath,
                "--add-modules", "daw.core",
                "-cp", classPath.toString(),
                PluginParameterSetterMain.class.getName(),
                jar.toString(),
                AnnotatedJarTestPlugin.class.getName())
                .directory(tempDir.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        Map<String, String> environment = builder.environment();
        LAUNCHER_OPTION_VARIABLES.forEach(environment::remove);

        Process child = builder.start();
        boolean exited = child.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }

        String printed = Files.readString(stdout);
        String failure = Files.readString(stderr);
        assertThat(exited).as("the program ends; it printed:%n%s%n%s", printed, failure).isTrue();
        assertThat(child.exitValue()).as("the program ran to its end; its standard error:%n%s", failure).isZero();
        assertThat(printed.lines().toList()).containsExactly(
                "daw.core is a named module: true",
                "the plugin's class loader: true",
                "daw.core reads the plugin's module: false",
                "parameter 0 after the setter: 0.5",
                "insert: " + AnnotatedJarTestPlugin.NAME);
    }

    /** Copies {@code type}'s class file under {@code directory}, as a class path root holding it. */
    private static Path copyOfClass(Class<?> type, Path directory) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        Path target = directory.resolve(entry);
        Files.createDirectories(target.getParent());
        try (InputStream bytes = type.getResourceAsStream("/" + entry)) {
            if (bytes == null) {
                throw new IOException("the class file of " + type.getName() + " is not readable");
            }
            Files.copy(bytes, target);
        }
        return directory;
    }

    /** The {@code java} launcher of the JDK this JVM runs on — never one found on the PATH. */
    private static Path javaLauncher() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("java.exe");
        Path launcher = Files.isRegularFile(windows) ? windows : bin.resolve("java");
        assertThat(launcher).as("the launcher of this JVM's own JDK").isRegularFile();
        return launcher;
    }
}
