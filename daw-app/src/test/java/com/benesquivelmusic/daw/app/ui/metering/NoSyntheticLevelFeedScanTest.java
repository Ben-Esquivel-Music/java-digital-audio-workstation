package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.app.ui.SourceScanSupport;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 318 — the "no-fiction" regression sentinel. A SOURCE-level scan
 * (sibling of {@code RunLaterConsolidationTest}) over {@code daw-app/src/main}
 * that pins two facts the story establishes and every later story must keep:
 *
 * <ol>
 *   <li><strong>The idle animator no longer touches level meters.</strong>
 *       Neither {@code IdleVisualizationAnimator} nor {@code AnimationController}
 *       may so much as name {@code LevelMeterDisplay} or {@code LevelData} —
 *       the synthetic "breathing" RMS/peak push is gone, not merely disabled
 *       behind a flag. (The spectrum arm stays; story 319 removes it.)</li>
 *   <li><strong>A level display is fed by the tap bus or not at all.</strong>
 *       Every {@code .update(...)} call whose receiver is a
 *       {@code LevelMeterDisplay} must live inside this
 *       {@code ui.metering} package — i.e. in {@code MeterSinks}, the one
 *       place that turns a tap-bus {@code MeterFrame} into a
 *       {@code LevelData} — or in the explicit allowlist below. No surface
 *       may re-invent a second, synthetic feed beside a subscribed meter.</li>
 * </ol>
 *
 * <p>Receiver typing is resolved per file from the identifiers declared as
 * {@code LevelMeterDisplay} (or {@code List<LevelMeterDisplay>}), which is
 * exactly how a reviewer reads the file; comments are stripped first so a
 * Javadoc mention never false-matches, and string literals are blanked so a
 * token inside a message cannot either. A non-empty-scan guard plus a
 * both-kinds-found guard keep the audit from passing vacuously.</p>
 */
final class NoSyntheticLevelFeedScanTest {

    /**
     * Files outside {@code ui/metering} that may still push a {@code LevelData}
     * into a {@code LevelMeterDisplay}, with the reason each is exempt.
     *
     * <p>{@code MasteringView} polls {@code MasteringChain.getStageOutputPeakDb}
     * on its own timer for the per-stage cards. Those meters have <em>no</em>
     * tap-bus subscription — the mastering chain is not yet engine-owned and
     * has no {@code MeterTapPoint} — so this is not a synthetic feed beside a
     * real one; it is the only feed those meters have. Story 321 (master
     * insert rack + engine-owned mastering chain) replaces it with real taps
     * and this entry goes away with it.</p>
     */
    private static final List<String> ALLOWLIST = List.of("MasteringView.java");

    /** Files that must not even name the level-meter types (fact 1). */
    private static final List<String> IDLE_FEED_FILES =
            List.of("IdleVisualizationAnimator.java", "AnimationController.java");

    private static final Pattern LEVEL_TOKENS =
            Pattern.compile("\\bLevelMeterDisplay\\b|\\bLevelData\\b");

    /**
     * An identifier declared as {@code LevelMeterDisplay} or as an element type
     * of a {@code …<LevelMeterDisplay>} collection — field, local, or parameter.
     * {@code new LevelMeterDisplay(} does not match (a {@code (} follows the
     * type, not an identifier).
     */
    private static final Pattern DECLARED_DISPLAY =
            Pattern.compile("\\bLevelMeterDisplay\\s*>?\\s+(\\w+)\\b");

    /** {@code receiver.update(} — a direct call on a named receiver. */
    private static final Pattern DIRECT_UPDATE =
            Pattern.compile("(\\w+)\\s*\\.update\\s*\\(");

    /** {@code collection.get(i).update(} — the MasteringView shape. */
    private static final Pattern INDEXED_UPDATE =
            Pattern.compile("(\\w+)\\s*\\.get\\s*\\([^()]*\\)\\s*\\.update\\s*\\(");

    @Test
    void noProductionPathPushesASyntheticLevelIntoATapFedDisplay() throws IOException {
        Path appSrcRoot = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app");
        assertThat(Files.isDirectory(appSrcRoot))
                .as("daw-app Java sources must live under %s", appSrcRoot)
                .isTrue();

        List<Path> scanned = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        List<String> idleOffenders = new ArrayList<>();
        Set<String> allowlistedSites = new LinkedHashSet<>();
        Set<String> feedDrivenSites = new LinkedHashSet<>();

        Files.walkFileTree(appSrcRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);

                String code = SourceScanSupport.stripStringLiterals(
                        SourceScanSupport.stripComments(
                                Files.readString(file, StandardCharsets.UTF_8)));
                String relPath = appSrcRoot.relativize(file).toString().replace('\\', '/');

                if (IDLE_FEED_FILES.contains(name) && LEVEL_TOKENS.matcher(code).find()) {
                    idleOffenders.add(relPath
                            + "  — names LevelMeterDisplay / LevelData; the synthetic "
                            + "RMS/peak push must stay removed (story 318)");
                    return FileVisitResult.CONTINUE;
                }

                Set<String> displays = declaredDisplays(code);
                if (displays.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
                boolean insideMeteringPackage = relPath.startsWith("ui/metering/");
                boolean allowlisted = ALLOWLIST.contains(name);
                for (String site : updateSites(code, displays)) {
                    String where = relPath + "  — " + site;
                    if (insideMeteringPackage) {
                        feedDrivenSites.add(where);
                    } else if (allowlisted) {
                        allowlistedSites.add(where);
                    } else {
                        offenders.add(where
                                + "  (a LevelMeterDisplay is fed by the tap bus through "
                                + "ui/metering/MeterSinks, or not at all — story 318)");
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Non-vacuity: a broken path, a renamed type, or a regex that stopped
        // matching would otherwise make every assertion below trivially true.
        assertThat(scanned)
                .as("the daw-app source scan must visit a non-trivial number of .java files")
                .hasSizeGreaterThan(50);
        assertThat(feedDrivenSites)
                .as("the tap-bus-driven LevelMeterDisplay.update(...) site in "
                        + "ui/metering/ must be found — if it is not, this scan proves nothing")
                .isNotEmpty();
        assertThat(allowlistedSites)
                .as("the allowlisted MasteringView site must be found — if it is not, "
                        + "either the allowlist is stale (delete the entry) or the scan is broken")
                .isNotEmpty();

        assertThat(idleOffenders)
                .as("Story 318 — the idle animator's synthetic level feed is gone: "
                        + "%s must not name the level-meter types", IDLE_FEED_FILES)
                .isEmpty();
        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("Story 318 — no production path may push a synthetic level into a "
                        + "LevelMeterDisplay that the tap bus feeds")
                .isEmpty();
    }

    /** Identifiers this file declares as a {@code LevelMeterDisplay} (or a collection of them). */
    private static Set<String> declaredDisplays(String code) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = DECLARED_DISPLAY.matcher(code);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** Every {@code .update(} call in {@code code} whose receiver is one of {@code displays}. */
    private static List<String> updateSites(String code, Set<String> displays) {
        List<String> sites = new ArrayList<>();
        Matcher indexed = INDEXED_UPDATE.matcher(code);
        while (indexed.find()) {
            if (displays.contains(indexed.group(1))) {
                sites.add(indexed.group(1) + ".get(...).update(...)");
            }
        }
        Matcher direct = DIRECT_UPDATE.matcher(code);
        while (direct.find()) {
            if (displays.contains(direct.group(1))) {
                sites.add(direct.group(1) + ".update(...)");
            }
        }
        return sites;
    }
}
