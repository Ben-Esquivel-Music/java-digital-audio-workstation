package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.app.ui.SourceScanSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure spine changes no render-path code (story 336 Non-Goals;
 * Failure Surfacing book §2.3; research-daw "### 3. Real-Time Audio
 * Processing": no locks, no allocation, no logging on the audio thread).
 * The spine lives in {@code daw.app}, which the engine cannot reference
 * ("### 1. Modular Architecture (SDK + Core + App)"), so a render thread
 * runs spine code only through what {@code daw.app} installs in the JDK:
 * the process's default handler, for a throwable that reaches it from the
 * thread's top, and the root logger's handlers, which the log sink sets
 * up, for a record the thread logs. When such a record is the session's
 * first write to the log file to fail, the sink hands that failure to the
 * funnel on the same thread ({@code FailureFunnel.onSinkWriteFailure});
 * story 336's Known limits record that under the render path's log-once
 * sites, which story 337 moves off the render thread. This scan pins the
 * handler half: no module below {@code daw.app} looks a handler up or sets
 * one — on a thread with {@code setUncaughtExceptionHandler}, or on a
 * {@code Thread.Builder} with {@code uncaughtExceptionHandler} — except
 * {@code EngineStreamPump}'s own per-thread handler, which pre-empts the
 * default handler, so the {@code engine-render-pump} thread's death is
 * logged, not shown. It complements {@code RealTimeSafeContractTest}
 * (daw-core), whose invariant 7 keeps {@code java.util.logging} out of the
 * bytecode it walks from the render-path roots, but for an exact allow-list
 * of pre-existing sites, and whose bytecode walk would not see a call into
 * the default handler.
 */
class FailureSpineStaysOffTheRenderPathTest {

    private static final List<String> MODULES_BELOW_THE_APPLICATION =
            List.of("daw-core", "daw-sdk", "daw-fx", "daw-acoustics");

    @Test
    void theEngineModuleDoesNotRequireTheApplication() {
        ModuleDescriptor core = ModuleLayer.boot().findModule("daw.core").orElseThrow().getDescriptor();

        assertThat(core.requires()).extracting(ModuleDescriptor.Requires::name)
                .as("non-vacuity: the descriptor is daw.core's").contains("daw.sdk")
                .doesNotContain("daw.app");
    }

    @Test
    void noModuleBelowTheApplicationLooksUpOrSetsAnUncaughtExceptionHandlerButTheRenderPump() throws IOException {
        Path repository = SourceScanSupport.locateDawAppModule().getParent();
        List<String> lookups = new ArrayList<>();
        List<String> setters = new ArrayList<>();
        int scanned = 0;
        for (String module : MODULES_BELOW_THE_APPLICATION) {
            Path sources = repository.resolve(module).resolve("src/main/java");
            assertThat(sources).as("the sources of %s", module).isDirectory();
            try (Stream<Path> files = Files.walk(sources)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                            Files.readString(file, StandardCharsets.UTF_8)));
                    String name = repository.relativize(file).toString().replace('\\', '/');
                    if (code.contains("getDefaultUncaughtExceptionHandler")
                            || code.contains("getUncaughtExceptionHandler()")
                            || code.contains("setDefaultUncaughtExceptionHandler")) {
                        lookups.add(name);
                    }
                    // Both ways to set a per-thread handler: on the thread, and on a Thread.Builder.
                    if (code.contains("setUncaughtExceptionHandler(") || code.contains(".uncaughtExceptionHandler(")) {
                        setters.add(name);
                    }
                }
            }
        }

        assertThat(scanned).as("non-vacuity: the modules' sources were scanned").isGreaterThan(500);
        assertThat(lookups).isEmpty();
        assertThat(setters).containsExactly(
                "daw-core/src/main/java/com/benesquivelmusic/daw/core/audio/EngineStreamPump.java");
    }
}
