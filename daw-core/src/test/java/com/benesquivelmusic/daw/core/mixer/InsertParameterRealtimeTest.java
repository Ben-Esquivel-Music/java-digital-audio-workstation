package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.mixer.classpath.AllocatingParameterProcessor;
import com.benesquivelmusic.daw.core.mixer.classpath.InsertParameterAllocationMain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every processor of the rack applies parameter changes without allocating on
 * the audio thread's path: the UI writes and the drain, 2,000 changes after
 * 2,000 of warm-up, allocate under 1 KiB on the measuring thread. The
 * measurement runs in a JVM of its own under {@code -Xint}
 * ({@link InsertParameterAllocationMain} says why), on this JVM's JDK and
 * module path, with only that program and its fixture on the class path, this
 * JVM's environment less the launcher's option variables
 * ({@code JAVA_TOOL_OPTIONS}, {@code JDK_JAVA_OPTIONS}, {@code _JAVA_OPTIONS}),
 * and the VM's own log on standard error, so that standard
 * output holds only the program's lines; the test reads what it printed. A
 * processor whose setter allocates is measured the same way, to show the
 * method reports an allocation.
 */
class InsertParameterRealtimeTest {

    /** The bound, in bytes, of what one processor's measured window may allocate. */
    private static final long BOUND_BYTES = 1024L;
    /** The environment variables the launcher reads options from; the child gets none of them. */
    private static final List<String> LAUNCHER_OPTION_VARIABLES =
            List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

    private static Measurement measurement;

    @BeforeAll
    static void measureInAnInterpretedJvm(@TempDir Path tempDir) throws Exception {
        Path classPath = tempDir.resolve("classes");
        copyOfClass(InsertParameterAllocationMain.class, classPath);
        copyOfClass(AllocatingParameterProcessor.class, classPath);
        String modulePath = System.getProperty("jdk.module.path");
        assertThat(modulePath).as("this JVM runs daw.core on the module path").isNotBlank();
        Path stdout = tempDir.resolve("stdout.txt");
        Path stderr = tempDir.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(
                javaLauncher().toString(),
                "-Xint",
                // The VM logs to standard output by default; the strict parse reads only the program's lines.
                "-Xlog:disable", "-Xlog:all=warning:stderr",
                "--module-path", modulePath,
                "--add-modules", "daw.core",
                "-cp", classPath.toString(),
                InsertParameterAllocationMain.class.getName())
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
        assertThat(child.exitValue()).as("the program ran to its end; it printed:%n%s%nits standard error:%n%s",
                printed, failure).isZero();
        measurement = Measurement.parse(printed, failure);
    }

    @Test
    void everyRackProcessorAppliesParameterChangesWithoutAllocatingOnTheAudioThread() {
        assumeTrue(measurement.supported(), "the JVM cannot measure a thread's allocation");

        assertThat(measurement.processors().keySet()).as("the processors measured; it printed:%n%s",
                measurement.printed()).containsExactlyElementsOf(rackProcessorLabels());
        measurement.processors().forEach((label, measured) -> {
            assertThat(measured.bypassed()).as(label).isFalse();
            assertThat(measured.allocated()).as(label).isLessThan(BOUND_BYTES);
        });
    }

    @Test
    void theMeasurementReportsASetterThatAllocates() {
        assumeTrue(measurement.supported(), "the JVM cannot measure a thread's allocation");

        assertThat(measurement.fixture()).as("the fixture was measured; it printed:%n%s", measurement.printed())
                .isNotNull();
        assertThat(measurement.fixture().bypassed()).isFalse();
        assertThat(measurement.fixture().allocated()).as("an allocating setter is reported")
                .isGreaterThanOrEqualTo(BOUND_BYTES);
    }

    /** The labels the program measures, in its order: the registry's types, then the processors beside them. */
    private static List<String> rackProcessorLabels() {
        List<String> labels = new ArrayList<>();
        for (InsertEffectType type : new ProcessorRegistry().availableTypes()) {
            labels.add(type.name());
        }
        labels.addAll(List.of(InsertParameterAllocationMain.EXTRA_PROCESSORS));
        return labels;
    }

    /** Copies {@code type}'s class file under {@code directory}, as a class path root holding it. */
    private static void copyOfClass(Class<?> type, Path directory) throws IOException {
        String entry = type.getName().replace('.', '/') + ".class";
        Path target = directory.resolve(entry);
        Files.createDirectories(target.getParent());
        try (InputStream bytes = type.getResourceAsStream("/" + entry)) {
            if (bytes == null) {
                throw new IOException("the class file of " + type.getName() + " is not readable");
            }
            Files.copy(bytes, target);
        }
    }

    /** The {@code java} launcher of the JDK this JVM runs on — never one found on the PATH. */
    private static Path javaLauncher() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("java.exe");
        Path launcher = Files.isRegularFile(windows) ? windows : bin.resolve("java");
        assertThat(launcher).as("the launcher of this JVM's own JDK").isRegularFile();
        return launcher;
    }

    private record Measured(boolean bypassed, long allocated) {
    }

    /** What the program printed, read line by line; a line it does not define fails the test. */
    private record Measurement(String printed, boolean supported, Map<String, Measured> processors,
                               Measured fixture) {

        static Measurement parse(String printed, String failure) {
            List<String> lines = printed.lines().toList();
            if (lines.isEmpty() || !lines.getFirst().startsWith("supported\t")) {
                fail("the program printed no support line; it printed:%n%s%nits standard error:%n%s",
                        printed, failure);
            }
            boolean supported = Boolean.parseBoolean(lines.getFirst().substring("supported\t".length()));
            Map<String, Measured> processors = new LinkedHashMap<>();
            Measured fixture = null;
            for (String line : lines.subList(1, lines.size())) {
                String[] fields = line.split("\t");
                if (fields.length != 4) {
                    fail("an unexpected line %s; it printed:%n%s", line, printed);
                }
                Measured measured = new Measured(Boolean.parseBoolean(fields[2]), Long.parseLong(fields[3]));
                switch (fields[0]) {
                    case "processor" -> {
                        if (processors.put(fields[1], measured) != null) {
                            fail("%s measured twice; it printed:%n%s", fields[1], printed);
                        }
                    }
                    case "fixture" -> {
                        if (fixture != null) {
                            fail("the fixture measured twice; it printed:%n%s", printed);
                        }
                        fixture = measured;
                    }
                    default -> fail("an unexpected line %s; it printed:%n%s", line, printed);
                }
            }
            return new Measurement(printed, supported, processors, fixture);
        }
    }
}
