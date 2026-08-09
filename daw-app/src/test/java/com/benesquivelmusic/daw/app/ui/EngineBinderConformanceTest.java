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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 314 — the EngineBinder conformance sentinel (the source-scan pattern
 * of {@code RunLaterConsolidationTest}): no production source in
 * {@code daw-app} or {@code daw-core} outside {@code EngineBinder.java} may
 * call the audio engine's {@code setTransport} / {@code setMixer} /
 * {@code setTracks} — the binder is the one binding point (Audio Engine
 * Wiring Design Book §4.1).
 *
 * <p>The scan is <em>receiver-aware</em>: {@code daw-app} carries unrelated
 * same-named methods ({@code ArrangementCanvas.setTracks} called as
 * {@code arrangementCanvas.setTracks(...)} in {@code MainController};
 * {@code InsertEffectRack.setMixer} called as {@code insertRack.setMixer(...)}
 * in {@code MixerView}), so the pattern captures the receiver identifier and
 * checks it against an explicit allowlist of non-engine receivers. The
 * allowlist itself is asserted exactly, so drift in either direction — a new
 * engine call like {@code audioEngine.setTracks(...)} or a stale allowlist
 * entry — fails the test. {@code setTransport} has no non-engine receivers
 * today; any hit outside {@code EngineBinder} is an offense.</p>
 *
 * <p>A literal single-file assertion (the {@code MainControllerShrinkTest}
 * idiom) closes the "MainController is never FXML-loaded in tests" gap for
 * the bind call itself: {@code rebuildViewModels()} must contain
 * {@code engineBinder.bind(}.</p>
 */
final class EngineBinderConformanceTest {

    /** The one file allowed to call the engine setters. */
    private static final String BINDER_FILE = "EngineBinder.java";

    /** Non-engine receivers of {@code .setTracks(} pinned exactly (sorted). */
    private static final List<String> ALLOWED_SET_TRACKS_RECEIVERS = List.of("arrangementCanvas");

    /** Non-engine receivers of {@code .setMixer(} pinned exactly (sorted). */
    private static final List<String> ALLOWED_SET_MIXER_RECEIVERS = List.of("insertRack");

    /** {@code .setTransport(} has no legitimate non-binder receiver at all. */
    private static final List<String> ALLOWED_SET_TRANSPORT_RECEIVERS = List.of();

    /**
     * The three patterns one setter is scanned with. {@code receiverAware}
     * captures the receiver token (identifier, or {@code )} for a chained call
     * such as {@code getEngine().setTracks(...)}; a chained-call receiver
     * cannot be allowlisted by name, so it is always an offense outside the
     * binder). {@code anyCall} counts EVERY {@code .setter(} occurrence for
     * the parity check: a receiver the receiver-aware pattern cannot parse
     * (e.g. the array-indexed {@code engines[0].setTracks(}) must fail loudly
     * instead of vanishing. {@code methodRef} catches {@code ::setter}
     * references, which bypass the call-syntax scan entirely (the
     * {@code RunLaterConsolidationTest} {@code Platform::runLater} precedent).
     */
    private record SetterScan(String setter, Pattern receiverAware, Pattern anyCall,
                              Pattern methodRef, List<String> allowedReceivers) {
        static SetterScan of(String setter, List<String> allowedReceivers) {
            return new SetterScan(setter,
                    Pattern.compile("([\\w$]+|\\))\\s*\\.\\s*" + setter + "\\s*\\("),
                    Pattern.compile("\\.\\s*" + setter + "\\s*\\("),
                    Pattern.compile("::\\s*" + setter + "\\b"),
                    allowedReceivers);
        }
    }

    private static final List<SetterScan> SETTER_SCANS = List.of(
            SetterScan.of("setTracks", ALLOWED_SET_TRACKS_RECEIVERS),
            SetterScan.of("setMixer", ALLOWED_SET_MIXER_RECEIVERS),
            SetterScan.of("setTransport", ALLOWED_SET_TRANSPORT_RECEIVERS));

    @Test
    void onlyEngineBinderCallsTheEngineSettersAcrossDawAppAndDawCore() throws IOException {
        Path dawAppRoot = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app");
        Path dawCoreRoot = locateDawCoreSourceRoot();
        assertThat(Files.isDirectory(dawAppRoot))
                .as("daw-app Java sources must live under %s", dawAppRoot).isTrue();
        assertThat(Files.isDirectory(dawCoreRoot))
                .as("daw-core Java sources must live under %s", dawCoreRoot).isTrue();

        Map<String, Set<String>> receiversBySetter = new TreeMap<>();
        List<String> offenders = new ArrayList<>();

        List<Path> scannedApp = scanTree(dawAppRoot, receiversBySetter, offenders);
        List<Path> scannedCore = scanTree(dawCoreRoot, receiversBySetter, offenders);

        // Non-empty-scan guards for BOTH module scans — a broken path would
        // make this audit vacuously pass.
        assertThat(scannedApp)
                .as("the daw-app source scan must visit a non-trivial number of .java files")
                .hasSizeGreaterThan(50);
        assertThat(scannedCore)
                .as("the daw-core source scan must visit a non-trivial number of .java files")
                .hasSizeGreaterThan(50);

        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("Story 314 — the EngineBinder is the ONE binding point: no production "
                        + "source outside EngineBinder.java may call the engine's "
                        + "setTransport/setMixer/setTracks (a non-allowlisted receiver "
                        + "means an engine call, or a new same-named method that must be "
                        + "reviewed and pinned here). Offending call sites:%n  %s",
                        String.join("\n  ", offenders))
                .isEmpty();

        // Pin the allowlists exactly: the observed non-binder receiver sets must
        // equal the allowlists, so a vanished call site (stale allowlist entry)
        // is caught just like a new one.
        assertThat(receiversBySetter.getOrDefault("setTracks", Set.of()))
                .as("observed non-binder .setTracks( receivers must equal the pinned allowlist")
                .containsExactlyElementsOf(ALLOWED_SET_TRACKS_RECEIVERS);
        assertThat(receiversBySetter.getOrDefault("setMixer", Set.of()))
                .as("observed non-binder .setMixer( receivers must equal the pinned allowlist")
                .containsExactlyElementsOf(ALLOWED_SET_MIXER_RECEIVERS);
        assertThat(receiversBySetter.getOrDefault("setTransport", Set.of()))
                .as("observed non-binder .setTransport( receivers must equal the pinned "
                        + "(empty) allowlist")
                .containsExactlyElementsOf(ALLOWED_SET_TRANSPORT_RECEIVERS);
    }

    @Test
    void mainControllersRebuildViewModelsBindsTheProjectThroughTheEngineBinder()
            throws IOException {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java");
        assertThat(Files.isRegularFile(file))
                .as("MainController source must exist at %s", file).isTrue();

        String code = SourceScanSupport.stripStringLiterals(
                SourceScanSupport.stripComments(
                        Files.readString(file, StandardCharsets.UTF_8)));
        String body = methodBody(code, "private void rebuildViewModels()");
        assertThat(body)
                .as("Story 314 — rebuildViewModels() must bind the live project through "
                        + "the EngineBinder (every lifecycle path funnels through it)")
                .contains("engineBinder.bind(");

        // Pin the dispose-before-bind-before-VMs order (book §6.2): the bind
        // sits between epoch-N disposal and the first VM construction, so
        // moving it to the end of the method must fail here.
        int disposeEpoch = body.indexOf("vmDisposers.clear()");
        int bindCall = body.indexOf("engineBinder.bind(");
        int firstVm = body.indexOf("new ProjectVM(");
        assertThat(disposeEpoch)
                .as("rebuildViewModels() must dispose the previous epoch (vmDisposers.clear())")
                .isNotNegative();
        assertThat(firstVm)
                .as("rebuildViewModels() must construct ProjectVM (the first epoch-N+1 VM)")
                .isNotNegative();
        assertThat(disposeEpoch)
                .as("epoch-N disposal must precede the engine bind").isLessThan(bindCall);
        assertThat(bindCall)
                .as("the engine bind must precede the first VM construction (§6.2)")
                .isLessThan(firstVm);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Walks one module tree collecting engine-setter call receivers. Allowed
     * receivers are recorded for the exact-allowlist assertion; everything
     * else (including chained-call {@code )} receivers) is an offense.
     */
    private static List<Path> scanTree(Path root,
                                       Map<String, Set<String>> receiversBySetter,
                                       List<String> offenders) throws IOException {
        List<Path> scanned = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java") || name.equals(BINDER_FILE)) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);

                String code = SourceScanSupport.stripStringLiterals(
                        SourceScanSupport.stripComments(
                                Files.readString(file, StandardCharsets.UTF_8)));
                String relPath = root.relativize(file).toString().replace('\\', '/');

                for (SetterScan scan : SETTER_SCANS) {
                    collect(code, relPath, scan, receiversBySetter, offenders);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return scanned;
    }

    private static void collect(String code, String relPath, SetterScan scan,
                                Map<String, Set<String>> receiversBySetter,
                                List<String> offenders) {
        String setter = scan.setter();
        int receiverMatches = 0;
        Matcher matcher = scan.receiverAware().matcher(code);
        while (matcher.find()) {
            receiverMatches++;
            String receiver = matcher.group(1);
            if (scan.allowedReceivers().contains(receiver)) {
                receiversBySetter.computeIfAbsent(setter, _ -> new TreeSet<>()).add(receiver);
            } else {
                offenders.add(relPath + "  — `" + receiver + "." + setter
                        + "(` is not an allowlisted non-engine receiver; only "
                        + "EngineBinder may address the audio engine's graph setters");
            }
        }

        // Parity check: every `.setter(` occurrence must have been receiver-
        // parsed above, or an unparseable receiver (e.g. an array-indexed
        // `engines[0].setTracks(`) would silently vanish from the scan.
        int allCalls = countMatches(scan.anyCall(), code);
        if (allCalls != receiverMatches) {
            offenders.add(relPath + "  — " + (allCalls - receiverMatches) + " `." + setter
                    + "(` call(s) whose receiver the receiver-aware pattern could not "
                    + "parse; the scan cannot vouch for them, so they fail loudly");
        }

        // Method references bypass call syntax entirely — any `::setter`
        // outside the binder is an unconditional offense.
        Matcher ref = scan.methodRef().matcher(code);
        while (ref.find()) {
            offenders.add(relPath + "  — `" + ref.group() + "` method reference: only "
                    + "EngineBinder may address the audio engine's graph setters "
                    + "(method-reference form)");
        }
    }

    private static int countMatches(Pattern pattern, String code) {
        Matcher matcher = pattern.matcher(code);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Returns the brace-balanced body following {@code signature} in
     * comment/string-stripped code (stripping first makes brace counting
     * reliable).
     */
    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        assertThat(start)
                .as("MainController must declare `%s`", signature).isNotNegative();
        int open = code.indexOf('{', start);
        assertThat(open).as("`%s` must have a body", signature).isNotNegative();
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("Unbalanced braces after " + signature);
    }

    /**
     * Locates {@code daw-core}'s production source root as the sibling of the
     * located {@code daw-app} module (the {@code ProjectContextNoThreadLocalTest}
     * cwd/parent-hop shape).
     */
    private static Path locateDawCoreSourceRoot() {
        Path dawApp = SourceScanSupport.locateDawAppModule();
        Path repoRoot = dawApp.getParent();
        assertThat(repoRoot)
                .as("the daw-app module root %s must have a parent (the reactor root)", dawApp)
                .isNotNull();
        return repoRoot.resolve("daw-core")
                .resolve("src/main/java/com/benesquivelmusic/daw/core");
    }
}
