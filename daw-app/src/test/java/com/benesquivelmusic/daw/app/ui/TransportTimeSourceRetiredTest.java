package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 315 — source-scan sentinel for the transport-truth migration
 * (the {@code RunLaterConsolidationTest} / {@code RemovedViewClassesGoneTest}
 * family, sharing {@link SourceScanSupport}):
 *
 * <ul>
 *   <li>The wall-clock {@code TimeTickerAnimator} is <em>deleted, not
 *       gated</em> — no file of that name and no code-level reference to the
 *       identifier may exist anywhere under {@code daw-app/src}. The time
 *       display is the beats→time projection bound by
 *       {@code TransportControlBinder.bindTimeDisplay}.</li>
 *   <li>No production code declares or calls a {@code syncLoopButtonState}
 *       method — the Loop button's lit state is a pure function of
 *       {@code TransportVM} via {@code bindLoop}; an imperative sync creeping
 *       back is the story's regression.</li>
 * </ul>
 *
 * <p>Comments and string literals are stripped before matching, so a
 * historical mention in Javadoc is fine; an import, construction, call, or
 * declaration is an offender. {@code TransportControlBinder.java} is asserted
 * PRESENT to prove the scan walks the right tree (non-vacuous guard).</p>
 */
final class TransportTimeSourceRetiredTest {

    private static final String TICKER_FILE = "TimeTickerAnimator.java";
    private static final String TICKER_IDENTIFIER = "TimeTickerAnimator";
    private static final String LOOP_SYNC_IDENTIFIER = "syncLoopButtonState";
    private static final String THIS_TEST_FILE = "TransportTimeSourceRetiredTest.java";
    private static final String SURVIVOR = "TransportControlBinder.java";

    @Test
    void theWallClockTickerIsGoneAndNoImperativeLoopSyncRemains() throws IOException {
        Path src = SourceScanSupport.locateDawAppModule().resolve("src");
        assertThat(Files.isDirectory(src))
                .as("daw-app sources must live under %s", src).isTrue();

        List<String> offenders = new ArrayList<>();
        List<Path> scanned = new ArrayList<>();
        boolean survivorSeen = false;
        try (Stream<Path> files = Files.walk(src)) {
            for (Path p : (Iterable<Path>) files
                    .filter(f -> f.getFileName().toString().endsWith(".java"))::iterator) {
                String name = p.getFileName().toString();
                scanned.add(p);
                if (name.equals(SURVIVOR)) {
                    survivorSeen = true;
                }
                if (name.equals(TICKER_FILE)) {
                    offenders.add(name + " (the deleted wall-clock ticker reappeared)");
                    continue;
                }
                if (name.equals(THIS_TEST_FILE)) {
                    continue; // this sentinel names the forbidden tokens by design
                }
                String code = SourceScanSupport.stripStringLiterals(
                        SourceScanSupport.stripComments(Files.readString(p)));
                if (code.contains(TICKER_IDENTIFIER)) {
                    offenders.add(name + " references " + TICKER_IDENTIFIER);
                }
                boolean production = p.toString().replace('\\', '/').contains("src/main/java");
                if (production && code.contains(LOOP_SYNC_IDENTIFIER)) {
                    offenders.add(name + " declares or calls " + LOOP_SYNC_IDENTIFIER);
                }
            }
        }

        assertThat(scanned)
                .as("the daw-app source scan must visit a non-trivial number of files")
                .hasSizeGreaterThan(50);
        assertThat(survivorSeen)
                .as("%s must be present — its absence would mean this scan is looking at "
                        + "the wrong tree", SURVIVOR)
                .isTrue();
        assertThat(offenders)
                .as("story 315 deleted the wall-clock time source and the imperative loop "
                        + "sync; offenders: %s", offenders)
                .isEmpty();
    }
}
