package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 289 — guards the §4.5 "one marshalling seam" contract (Control
 * Synchronization Design Book §2.6, §4.5): after this story, {@code
 * Platform.runLater} and {@code AnimationTimer} appear only inside
 * {@link com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher}. Every other
 * off-thread hop onto the JavaFX Application Thread goes through the dispatcher,
 * and every legitimate per-frame control timer is explicitly sanctioned with a
 * {@link com.benesquivelmusic.daw.app.ui.marshal.FxAnimationTimerAllowed}
 * sentinel carrying a non-blank reason.
 *
 * <p>This is the direct sibling of {@code LegacyHardcodedColorAuditTest}
 * (story 277) and the {@code @LegacyDialog}/{@code @HardcodedColorAllowed}
 * conformance-sentinel pattern: a green gate that scans the source tree and
 * prevents drift — a new control cannot reintroduce an ad-hoc {@code
 * Platform.runLater} or spin up an unsanctioned {@code AnimationTimer}.</p>
 *
 * <h3>Scope</h3>
 *
 * <p>The scan roots at {@code com.benesquivelmusic.daw.app} (not just {@code
 * …app.ui}) because the bus-wiring site {@code DawApplication} lives directly in
 * {@code …app}, and the contract is that <em>no</em> {@code daw-app} source
 * outside {@code FxDispatcher} references either construct. To stay faithful to
 * a SOURCE-level scan (both sentinels are {@link
 * java.lang.annotation.RetentionPolicy#SOURCE}) it strips {@code //} / {@code /*
 * *\/} comments before matching (so a {@code Platform.runLater} mention in
 * Javadoc does not false-match), blanks string / text-block literals before the
 * {@code Platform.runLater} scan, and captures the {@code @FxAnimationTimerAllowed}
 * {@code value()} literal to assert it is <em>non-blank</em> — the
 * preprocessing provided by the shared {@link SourceScanSupport} harness. {@code
 * FxDispatcher.java} (which owns the one legitimate seam timer and the two
 * permitted {@code Platform.runLater} call sites) and the {@code
 * FxAnimationTimerAllowed.java} annotation type's own source are excluded by
 * name, exactly as the colour audit skips its annotation type.</p>
 */
final class RunLaterConsolidationTest {

    /**
     * {@code Platform.runLater(} or {@code Platform::runLater} — the ad-hoc
     * cross-thread hop this story retires, matched both as a call expression and
     * as a method reference (the fully qualified {@code
     * javafx.application.Platform} form is covered too, since {@code \bPlatform}
     * matches after the package dots).
     */
    private static final Pattern PLATFORM_RUN_LATER =
            Pattern.compile(
                    "\\bPlatform\\s*(?:\\.\\s*runLater\\s*\\(|::\\s*runLater\\b)");

    /** {@code new AnimationTimer} / {@code extends AnimationTimer} — a per-frame loop. */
    private static final Pattern ANIMATION_TIMER =
            Pattern.compile("\\b(?:new|extends)\\s+(?:javafx\\s*\\.\\s*animation\\s*\\.\\s*)?AnimationTimer\\b");

    /**
     * The class-level sentinel <em>with</em> its mandatory {@code value()}
     * string literal captured (group 1). The audit asserts the captured reason
     * is non-blank — the SOURCE-scan equivalent of a reflective
     * {@code ann.value() != null && !ann.value().isBlank()} check. The literal
     * may be a single string or a {@code "a" + "b"} concatenation; group 1
     * captures the first segment, which is always non-blank for a real reason.
     */
    private static final Pattern ANIMATION_TIMER_SENTINEL = Pattern.compile(
            "@FxAnimationTimerAllowed\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /** The seam itself — owns the one timer and the only {@code Platform.runLater} calls. */
    private static final String DISPATCHER_FILE = "FxDispatcher.java";

    /** The sentinel annotation type, whose own Javadoc necessarily names the
     *  constructs it guards; excluded exactly as the colour audit skips
     *  {@code @HardcodedColorAllowed}'s own type. */
    private static final String ANNOTATION_TYPE_FILE = "FxAnimationTimerAllowed.java";

    @Test
    void noDawAppSourceOutsideFxDispatcherHopsThreadsOrOwnsAnUnsanctionedTimer()
            throws IOException {
        Path appSrcRoot = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app");
        assertThat(Files.isDirectory(appSrcRoot))
                .as("daw-app Java sources must live under %s", appSrcRoot)
                .isTrue();

        List<String> runLaterOffenders = new ArrayList<>();
        List<String> timerOffenders = new ArrayList<>();
        List<Path> scanned = new ArrayList<>();

        Files.walkFileTree(appSrcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")
                        || name.equals(DISPATCHER_FILE)
                        || name.equals(ANNOTATION_TYPE_FILE)) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);

                // Strip comments (so a mention in Javadoc does not false-match)
                // but keep string literals so the real @FxAnimationTimerAllowed("…")
                // survives for the non-blank check; then blank strings for the
                // Platform.runLater scan so a mention inside a string is not a
                // false match.
                String code = SourceScanSupport.stripComments(
                        Files.readString(file, StandardCharsets.UTF_8));
                String relPath = appSrcRoot.relativize(file).toString()
                        .replace('\\', '/');

                if (PLATFORM_RUN_LATER.matcher(
                        SourceScanSupport.stripStringLiterals(code)).find()) {
                    runLaterOffenders.add(relPath
                            + "  — references Platform.runLater (route it through "
                            + "FxDispatcher instead)");
                }

                if (ANIMATION_TIMER.matcher(
                        SourceScanSupport.stripStringLiterals(code)).find()) {
                    Matcher sentinel = ANIMATION_TIMER_SENTINEL.matcher(code);
                    if (!sentinel.find()) {
                        timerOffenders.add(relPath
                                + "  — constructs an AnimationTimer without "
                                + "@FxAnimationTimerAllowed");
                    } else if (sentinel.group(1).isBlank()) {
                        timerOffenders.add(relPath
                                + "  — @FxAnimationTimerAllowed reason is blank "
                                + "(the rationale is mandatory)");
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Guard against a silently-empty scan (a broken path would make the
        // audit vacuously pass).
        assertThat(scanned)
                .as("the daw-app source scan must visit a non-trivial number of "
                        + ".java files — an empty scan would make this audit "
                        + "vacuously pass")
                .hasSizeGreaterThan(50);

        runLaterOffenders.sort(String::compareTo);
        assertThat(runLaterOffenders)
                .as("Story 289 — after the FxDispatcher seam lands, no daw-app "
                        + "source outside FxDispatcher may reference "
                        + "Platform.runLater; route every off-thread hop through "
                        + "fxDispatcher.onFx(...) / FxDispatcher.runOnFx(...). "
                        + "Offending files:%n  %s",
                        String.join("\n  ", runLaterOffenders))
                .isEmpty();

        timerOffenders.sort(String::compareTo);
        assertThat(timerOffenders)
                .as("Story 289 — every daw-app source outside FxDispatcher that "
                        + "constructs an AnimationTimer must carry "
                        + "@FxAnimationTimerAllowed(\"<reason>\") to record that "
                        + "its per-frame timer is a legitimate control-owned loop, "
                        + "not a cross-thread marshalling seam (sibling of story "
                        + "277's @HardcodedColorAllowed). Offending files:%n  %s",
                        String.join("\n  ", timerOffenders))
                .isEmpty();
    }
}
