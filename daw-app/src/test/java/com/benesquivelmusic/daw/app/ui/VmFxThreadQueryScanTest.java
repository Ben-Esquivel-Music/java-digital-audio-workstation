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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round (B2) — the source-scan sentinel for the view-model
 * layer's FX-thread test. {@code Platform.isFxApplicationThread()} (and the
 * {@code PlatformImpl} / {@code Toolkit.isFxUserThread} forms behind it) goes
 * through the {@code static synchronized Toolkit.getToolkit()}: every call
 * takes the {@code Toolkit.class} monitor, a lock the FX thread holds on every
 * pulse. The core-signal handlers under {@code ui/vm} run on whatever thread
 * mutated the model — for {@code ChannelVM} that is the audio thread, per
 * block under automation — so the whole VM layer makes that test through
 * {@code FxDispatcher.isFxThread()}, a lock-free field compare (Audio Engine
 * Wiring Design Book §6.1: the RT callback never locks).
 *
 * <p>The {@code RunLaterConsolidationTest} / {@code MixerControlTruthScanTest}
 * idiom over {@link SourceScanSupport}: comments and string literals are
 * stripped first, the scan must visit the VM files it exists for, every
 * {@link #ALLOWLIST} entry must still have a site (a stale entry fails), and
 * the four VM types that used to ask the toolkit must now ask the seam.</p>
 */
final class VmFxThreadQueryScanTest {

    /** The toolkit's own FX-thread queries, in every spelling that reaches the monitor. */
    private static final Pattern TOOLKIT_THREAD_QUERY =
            Pattern.compile("\\b(?:isFxApplicationThread|isFxUserThread)\\s*\\(");

    /** The seam every VM must use instead. */
    private static final Pattern SEAM_QUERY = Pattern.compile("\\bisFxThread\\s*\\(");

    /** Scanned roots, relative to {@code src/main/java/com/benesquivelmusic/daw/app/ui}. */
    private static final List<String> SCAN_ROOTS = List.of("vm", "marshal");

    /**
     * The one permitted site, keyed by path relative to the {@code ui} source
     * root, with the reason it is not an offender.
     */
    private static final Map<String, String> ALLOWLIST = Map.of(
            "marshal/FxDispatcher.java",
            "FxDispatcher.isFxThread() is the seam itself: it records the FX thread at start() and "
                    + "falls back to Platform.isFxApplicationThread() ONLY for a never-started "
                    + "pure-unit dispatcher, which has no audio-thread producer");

    /** The VM types that asked the toolkit before this fix round; each must use the seam now. */
    private static final Set<String> MUST_USE_SEAM = Set.of(
            "vm/ChannelVM.java", "vm/TrackVM.java", "vm/TrackChannelRegistry.java", "vm/ProjectVM.java");

    @Test
    void noViewModelAsksTheToolkitWhichThreadItIsOn() throws IOException {
        Path uiRoot = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(Files.isDirectory(uiRoot)).as("daw-app ui sources must live under %s", uiRoot).isTrue();

        List<String> offenders = new ArrayList<>();
        Set<String> allowlistHits = new LinkedHashSet<>();
        Set<String> seamUsers = new LinkedHashSet<>();
        List<String> scanned = new ArrayList<>();

        for (String rootName : SCAN_ROOTS) {
            Path root = uiRoot.resolve(rootName);
            assertThat(Files.isDirectory(root)).as("scan root %s", root).isTrue();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.getFileName().toString().endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relPath = uiRoot.relativize(file).toString().replace('\\', '/');
                    scanned.add(relPath);
                    String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(
                            Files.readString(file, StandardCharsets.UTF_8)));
                    if (SEAM_QUERY.matcher(code).find()) {
                        seamUsers.add(relPath);
                    }
                    if (TOOLKIT_THREAD_QUERY.matcher(code).find()) {
                        if (ALLOWLIST.containsKey(relPath)) {
                            allowlistHits.add(relPath);
                        } else {
                            offenders.add(relPath + "  — asks the toolkit which thread it is on "
                                    + "(Platform.isFxApplicationThread takes the Toolkit class monitor); "
                                    + "use FxDispatcher.isFxThread()");
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // Non-vacuity: the files this sentinel exists for were visited.
        assertThat(scanned).as("scan visited the VM layer").containsAll(MUST_USE_SEAM).hasSizeGreaterThan(8);
        assertThat(seamUsers)
                .as("every VM that used to ask the toolkit now asks FxDispatcher.isFxThread()")
                .containsAll(MUST_USE_SEAM);
        assertThat(ALLOWLIST.keySet())
                .as("every allow-list entry still has a site — a stale entry is an error")
                .isSubsetOf(allowlistHits);
        assertThat(offenders)
                .as("toolkit thread queries in the VM layer (story 322 fix round, B2)")
                .isEmpty();
    }
}
