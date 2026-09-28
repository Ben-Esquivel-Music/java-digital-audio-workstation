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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 — the model-write conformance sentinel (Audio Engine Wiring
 * Design Book §2.10 "the UI writes the model the engine reads; any mirrored
 * model is updated in the same dual-write, in one place", §5.6 contract table,
 * Stage 9 proof: "a source-scan conformance test fails on any strip control
 * writing a model the render path does not read"). A SOURCE-level scan over
 * {@code daw-app/src/main} in the {@code NoSyntheticLevelFeedScanTest} /
 * {@code SourceScanSupport} idiom that pins the facts this story establishes:
 *
 * <ol>
 *   <li><strong>One intent path.</strong> No production file outside the
 *       {@link #ALLOWLIST} may call {@code setVolume}/{@code setPan}/
 *       {@code setMuted}/{@code setSolo}/{@code setArmed} on a receiver that
 *       resolves to a {@code Track} or a {@code MixerChannel} — directly, as an
 *       element unwrapped out of a {@code List<Track>}/{@code Optional<Track>},
 *       on the result of a model-returning getter chain or of an in-file
 *       model-returning method, through a {@code var} that was initialised
 *       from any of those, on an enhanced-for {@code var}, on a lambda
 *       parameter bound over such a collection/optional (through any
 *       {@code stream()}/{@code filter(...)}/{@code subList(...)} segments),
 *       through a {@code requireNonNull(...)} wrapper — bound to a {@code var}
 *       or used inline as the receiver; {@code Objects.}-qualified,
 *       {@code java.util.Objects.}-qualified or static-imported; with no
 *       second argument, a message or a {@code Supplier} (fix round 3) — or
 *       as a method reference — type-qualified ({@code MixerChannel::setMuted},
 *       the pre-322 VcaStrip shape), bound to a typed identifier
 *       ({@code track::setMuted}) or bound to a model getter chain / in-file
 *       model-returning method ({@code project.getMasterChannel()::setMuted},
 *       fix round 3). Known boundary (fix round 3): a two-parameter lambda
 *       over a {@code Map<…, Track>} / {@code Map<…, MixerChannel>}
 *       ({@code byId.forEach((id, t) -> t.setMuted(true))}) is not typed —
 *       the lambda binder takes a single parameter only. Every control writer (mixer strip, arrangement strip,
 *       Performance Stage tile, VCA strip, stereo link) raises a
 *       {@code TrackCommand} and only {@code CoreTrackIntentHandler}
 *       dual-writes {@code Track} + {@code MixerChannel}.</li>
 *   <li><strong>The legacy send path is gone.</strong> No
 *       {@code setSendLevel}/{@code getSendLevel} anywhere under
 *       {@code src/main}.</li>
 *   <li><strong>"Link Inserts" is gone</strong> (no plugin-clone contract
 *       exists to honour it) — no such string, identifier or checkbox seam.</li>
 *   <li><strong>The arrangement strip's placeholder-insert fiction is gone</strong>
 *       — {@code TrackStripController} names none of the Gain/Gate/Comp/HPF/
 *       Limiter icons or tooltips, nor the MIDI instrument hints.</li>
 *   <li><strong>The story-271 strip suite is live and the 3D button is
 *       gated.</strong> {@code MixerView} instantiates {@code MixerChannelStrip}
 *       in production, and its panner button is bound to
 *       {@code ChannelVM.spatialNodePresent} rather than always shown.</li>
 * </ol>
 *
 * <p>Receiver typing is resolved per file, exactly how a reviewer reads it:
 * the identifiers declared as {@code Track}/{@code MixerChannel} (or as an
 * element type of a {@code …<Track>} collection/optional) seed the set, and
 * {@code var} locals, enhanced-for variables and lambda parameters that take
 * their type from a typed receiver or a model source are added until nothing
 * new is learnt. Comments are stripped first so a Javadoc mention never
 * false-matches, and string literals are blanked for the setter scan so a
 * token inside a message cannot either. Non-vacuity guards: the scan must visit
 * a non-trivial number of files, the intent path's own dual-write sites must
 * be found, every allow-list entry must still have a site inside its scope (a
 * stale entry is an error), and
 * {@link #theReceiverResolutionCatchesEveryShapeAControlWriterCanTake} feeds
 * one fixture per receiver shape through the same helpers — the permanent
 * fault-injection fixtures (fix round 1: the first two positives are the
 * prober's verbatim injections into {@code TrackStripController}, which the
 * earlier scan let through; the third is the Optional-unwrap shape it
 * identified by construction).</p>
 */
final class MixerControlTruthScanTest {

    /**
     * Where a file may call the audible setters on a {@code Track} /
     * {@code MixerChannel}, and why that is not a control. A control writer
     * never belongs here — it belongs on the intent path.
     */
    private sealed interface Allowance permits WholeFile, MethodBody {
        String reason();
    }

    /** Every site in the file is covered by the reason. */
    private record WholeFile(String reason) implements Allowance {
    }

    /**
     * Only the sites inside the body of {@code method} are covered; a site
     * anywhere else in the file is an offender, because the reason names a
     * method, not a file.
     */
    private record MethodBody(String method, String reason) implements Allowance {
    }

    private static final String CORE_INTENT_HANDLER =
            "com/benesquivelmusic/daw/app/ui/vm/command/CoreTrackIntentHandler.java";
    private static final String SESSION_INTERCHANGE =
            "com/benesquivelmusic/daw/app/ui/SessionInterchangeController.java";

    /**
     * Keyed by the path relative to {@code src/main/java} so that a same-named
     * file elsewhere in the tree is never allow-listed by accident.
     */
    private static final Map<String, Allowance> ALLOWLIST = Map.of(
            CORE_INTENT_HANDLER, new WholeFile(
                    "the ONE intent path: VALIDATE -> MUTATE -> ANNOUNCE dual-writes Track + MixerChannel "
                            + "in one place (§2.10); every control raises a TrackCommand into it"),
            SESSION_INTERCHANGE, new MethodBody("applyMixerSettings",
                    "session import: a persistence-shaped seed of a freshly created track's Track + "
                            + "MixerChannel mirrors in one place, before any control exists — not a gesture"));

    private static final String SETTERS = "setVolume|setPan|setMuted|setSolo|setArmed";
    private static final String MODEL = "(?:Track|MixerChannel)";

    /** A balanced argument list up to two parentheses deep — enough for {@code filter(t -> !t.getName().isEmpty())}. */
    private static final String ARGS = "\\((?:[^()]|\\((?:[^()]|\\([^()]*\\))*\\))*\\)";

    /**
     * Getters that return one model (or an {@code Optional} of one):
     * {@code project.getMixerChannelForTrack(t)}, {@code getTrackForChannel(c)},
     * {@code getMasterChannel()}, {@code getAuxBus()}, {@code partnerOf(...)} …
     */
    private static final String MODEL_GETTERS =
            "getMixerChannelForTrack|getMixerChannel|getMasterChannel|getAuxBus"
                    + "|getTrackForChannel|getTrack|getChannel|partnerOf";

    /** Getters that return a collection of models. */
    private static final String COLLECTION_GETTERS = "getTracks|getChannels|getReturnBuses";

    /** One element taken out of a container: {@code .get(i)}, {@code .getFirst()}, {@code .orElseThrow()} … */
    private static final String UNWRAP =
            "\\s*\\.\\s*(?:get|getFirst|getLast|orElseThrow|orElse|orElseGet)\\s*" + ARGS;

    /** A segment that keeps the element type: {@code .stream()}, {@code .filter(...)}, {@code .sorted()}, {@code .subList(a, b)}, {@code .values()} … */
    private static final String PASS_THROUGH =
            "\\s*\\.\\s*(?:stream|parallelStream|filter|sorted|distinct|limit|skip|peek|toList|reversed|values|subList)"
                    + "\\s*" + ARGS;

    /**
     * The terminal that binds a lambda parameter to an element; its parameter
     * is the capture group. The parameter may be parenthesised —
     * {@code forEach((t) -> …)} binds {@code t} exactly as {@code forEach(t -> …)}
     * does (fix round 2) — and may be {@code var}-typed,
     * {@code forEach((var t) -> …)} / {@code forEach((final var t) -> …)}
     * (fix round 3). A two-parameter lambda is not bound (the class Javadoc's
     * known boundary).
     */
    private static final String LAMBDA_TERMINAL =
            "\\s*\\.\\s*(?:forEach|forEachOrdered|ifPresent|ifPresentOrElse|map|flatMap|filter|peek|removeIf"
                    + "|anyMatch|allMatch|noneMatch)\\s*\\(\\s*\\(?\\s*(?:(?:final\\s+)?var\\s+)?(\\w+)\\s*\\)?\\s*->";

    /**
     * A null-check wrapper that hands its argument back unchanged:
     * {@code Objects.requireNonNull(x)}, the package-qualified
     * {@code java.util.Objects.requireNonNull(x)} (fix round 3) or a
     * static-imported {@code requireNonNull(x, "msg")}. A {@code var}
     * initialised through it takes the wrapped model source's type (fix
     * round 2), and a setter called on it inline is a site (fix round 3).
     */
    private static final String NULL_CHECK_WRAPPER =
            "(?:(?:java\\s*\\.\\s*util\\s*\\.\\s*)?Objects\\s*\\.\\s*)?requireNonNull";

    /**
     * The wrapper's optional second argument: a message literal (blanked to
     * {@code ""} before the scan), an identifier, or the
     * {@code requireNonNull(T, Supplier<String>)} overload's lambda —
     * {@code () -> "x"} is ARGS-shaped, which the earlier {@code [^;()]*}
     * rejected (fix round 3).
     */
    private static final String OPTIONAL_MESSAGE = "(?:,\\s*(?:[^;()]|" + ARGS + ")*)?";

    /**
     * An identifier declared as {@code Track} / {@code MixerChannel}, or as the
     * element type of a {@code …<Track>} collection / optional — field, local,
     * parameter, enhanced-for variable or typed lambda parameter.
     * {@code TrackVM}, {@code TrackStrip}, {@code MixerChannelStrip} do not
     * match: the type must be followed by whitespace (after an optional
     * {@code >}), not by more identifier characters.
     */
    private static final Pattern DECLARED_MODEL =
            Pattern.compile("\\b" + MODEL + "\\s*>?\\s+(\\w+)\\b");

    /**
     * An in-file method whose declared return type is a model or a container
     * of models — {@code private MixerChannel resolveChannel(UUID id)},
     * {@code Optional<Track> trackFor(...)}, {@code List<Track> members()}.
     * Calling it and setting on the result is a model write without any typed
     * identifier in between.
     */
    private static final Pattern MODEL_METHOD_DECLARATION = Pattern.compile(
            "\\b(?:" + MODEL + "|(?:Optional|List|Collection|Set|Stream|Iterable|SequencedCollection)"
                    + "\\s*<\\s*" + MODEL + "\\s*>)\\s+(\\w+)\\s*\\(");

    /** {@code receiver.setX(} — a direct call on a named receiver. */
    private static final Pattern DIRECT_SET =
            Pattern.compile("(\\w+)\\s*\\.\\s*(" + SETTERS + ")\\s*\\(");

    /** {@code collection.get(i).setX(} / {@code maybe.orElseThrow().setX(} — one element of a typed container. */
    private static final Pattern UNWRAP_SET = Pattern.compile(
            "(\\w+)" + UNWRAP + "\\s*\\.\\s*(" + SETTERS + ")\\s*\\(");

    /**
     * A model-returning getter call on some receiver, optionally unwrapped:
     * {@code .getMixerChannelForTrack(t)}, {@code .getTrackForChannel(c).orElseThrow()},
     * {@code .getTracks().get(0)} … The getter name is the capture group.
     */
    private static final String MODEL_CHAIN =
            "\\.\\s*(" + MODEL_GETTERS + "|" + COLLECTION_GETTERS + ")\\s*" + ARGS + "(?:" + UNWRAP + ")?";

    /**
     * A model-returning getter chain ending in an audible setter:
     * {@code project.getMixerChannelForTrack(t).setVolume(},
     * {@code getTrackForChannel(c).orElseThrow().setMuted(},
     * {@code getTracks().get(0).setSolo(} …
     */
    private static final Pattern GETTER_CHAIN_SET = Pattern.compile(
            MODEL_CHAIN + "\\s*\\.\\s*(" + SETTERS + ")\\s*\\(");

    /**
     * {@code project.getMasterChannel()::setMuted} — a setter bound to the
     * result of a model getter chain and handed around as a method reference
     * (fix round 3). {@link #BOUND_METHOD_REF_SET} needs an identifier right
     * before the {@code ::}, so a closing parenthesis there slipped past it.
     */
    private static final Pattern GETTER_CHAIN_METHOD_REF_SET = Pattern.compile(
            MODEL_CHAIN + "\\s*::\\s*(" + SETTERS + ")\\b");

    /**
     * {@code Objects.requireNonNull(project.getMixerChannelForTrack(t)).setVolume(}
     * — the wrapper used inline as the receiver, no {@code var} in between
     * (fix round 3). The wrapper's argument must be the model chain itself: a
     * receiver prefix and balanced call arguments are stepped over, the
     * wrapper's own closing parenthesis is not.
     */
    private static final Pattern WRAPPED_GETTER_CHAIN_SET = Pattern.compile(
            "\\b" + NULL_CHECK_WRAPPER + "\\s*\\(\\s*(?:[^;()]|" + ARGS + ")*?" + MODEL_CHAIN + "\\s*"
                    + OPTIONAL_MESSAGE + "\\s*\\)\\s*\\.\\s*(" + SETTERS + ")\\s*\\(");

    /** {@code MixerChannel::setMuted} / {@code Track::setArmed} — a setter handed around as a type-qualified method reference. */
    private static final Pattern METHOD_REF_SET =
            Pattern.compile("\\b" + MODEL + "\\s*::\\s*(" + SETTERS + ")\\b");

    /**
     * {@code track::setMuted} / {@code channel::setVolume} — a setter bound to
     * a receiver identifier; a site when that identifier resolves to a model
     * (fix round 2). A type-qualified reference matches here too, but its
     * "receiver" is the type name, never a declared identifier, so it is
     * counted once, by {@link #METHOD_REF_SET}.
     */
    private static final Pattern BOUND_METHOD_REF_SET =
            Pattern.compile("\\b(\\w+)\\s*::\\s*(" + SETTERS + ")\\b");

    /** {@code var x = typedIdentifier[.get(i)][.stream()];} — the local takes the receiver's type. */
    private static final Pattern VAR_FROM_TYPED = Pattern.compile(
            "\\bvar\\s+(\\w+)\\s*=\\s*(\\w+)(?:" + UNWRAP + "|" + PASS_THROUGH + ")*\\s*;");

    private static final Pattern SEND_LEVEL = Pattern.compile("\\b[gs]etSendLevel\\b");
    private static final Pattern LINK_INSERTS =
            Pattern.compile("Link\\s+Inserts|\\blinkInserts\\b|\\bwithLinkInserts\\b|\\bgetInsertsBox\\b");

    /** The hardcoded five-icon insert fiction + the MIDI instrument hints (story 322 §5.6 last row). */
    private static final Pattern PLACEHOLDER_INSERT_FICTION = Pattern.compile(
            "\"(?:Gain|Gate|Comp|Compressor|HPF|High-Pass Filter|Limiter|Velocity)\""
                    + "|\"Instrument:|DawIcon\\.(?:GAIN|NOISE_GATE|HIGH_PASS|LIMITER)\\b"
                    + "|\\bmidiInstrumentIcon\\b|\\bplaceholderInserts?\\b");

    private static final Pattern STRIP_INSTANTIATION =
            Pattern.compile("new\\s+MixerChannelStrip\\s*\\(|MixerChannelStrip\\s*\\.\\s*create\\s*\\(");
    private static final Pattern PANNER_BUTTON = Pattern.compile("new\\s+Button\\s*\\(\\s*\"3D\"\\s*\\)");
    private static final Pattern PANNER_VISIBLE_GATE = Pattern.compile(
            "visibleProperty\\s*\\(\\s*\\)\\s*\\.\\s*bind\\s*\\(\\s*\\w+\\s*\\.\\s*spatialNodePresentProperty\\s*\\(\\s*\\)\\s*\\)");
    private static final Pattern PANNER_MANAGED_GATE = Pattern.compile(
            "managedProperty\\s*\\(\\s*\\)\\s*\\.\\s*bind\\s*\\(\\s*\\w+\\s*\\.\\s*spatialNodePresentProperty\\s*\\(\\s*\\)\\s*\\)");

    @Test
    void everyAudibleControlWritesTheModelThroughTheOneIntentPath() throws IOException {
        Path mainRoot = SourceScanSupport.locateDawAppModule().resolve("src/main");
        Path javaRoot = mainRoot.resolve("java");
        Path appSrcRoot = javaRoot.resolve("com/benesquivelmusic/daw/app");
        assertThat(Files.isDirectory(appSrcRoot))
                .as("daw-app Java sources must live under %s", appSrcRoot)
                .isTrue();

        List<Path> scannedJava = new ArrayList<>();
        List<Path> scannedOther = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        Map<String, List<String>> allowlistedSites = new LinkedHashMap<>();
        List<String> missingScopes = new ArrayList<>();
        List<String> sendLevelHits = new ArrayList<>();
        List<String> linkInsertsHits = new ArrayList<>();
        List<String> fictionHits = new ArrayList<>();
        Set<String> typedFiles = new LinkedHashSet<>();
        boolean[] stripInstantiated = {false};
        int[] pannerButtons = {0};
        boolean[] pannerGated = {false};

        Files.walkFileTree(mainRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                String relPath = mainRoot.relativize(file).toString().replace('\\', '/');
                if (!name.endsWith(".java")) {
                    if (!isTextResource(name)) {
                        return FileVisitResult.CONTINUE; // icons / fonts: nothing to read
                    }
                    // Resources (fxml / css / properties): a raw text scan for the
                    // removed seams is enough — there is no Java to type.
                    scannedOther.add(file);
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    if (SEND_LEVEL.matcher(raw).find()) {
                        sendLevelHits.add(relPath);
                    }
                    if (LINK_INSERTS.matcher(raw).find()) {
                        linkInsertsHits.add(relPath);
                    }
                    return FileVisitResult.CONTINUE;
                }
                scannedJava.add(file);

                String source = Files.readString(file, StandardCharsets.UTF_8);
                String withStrings = SourceScanSupport.stripComments(source);
                String code = SourceScanSupport.stripStringLiterals(withStrings);

                if (SEND_LEVEL.matcher(code).find()) {
                    sendLevelHits.add(relPath);
                }
                if (LINK_INSERTS.matcher(withStrings).find()) {
                    linkInsertsHits.add(relPath);
                }
                if (name.equals("TrackStripController.java")) {
                    Matcher fiction = PLACEHOLDER_INSERT_FICTION.matcher(withStrings);
                    while (fiction.find()) {
                        fictionHits.add(relPath + "  — " + fiction.group());
                    }
                }
                if (name.equals("MixerView.java")) {
                    stripInstantiated[0] = STRIP_INSTANTIATION.matcher(code).find();
                    Matcher panner = PANNER_BUTTON.matcher(withStrings);
                    while (panner.find()) {
                        pannerButtons[0]++;
                    }
                    pannerGated[0] = PANNER_VISIBLE_GATE.matcher(code).find()
                            && PANNER_MANAGED_GATE.matcher(code).find();
                }

                ModelScope scope = modelScope(code);
                List<Site> sites = modelWriteSites(code, scope);
                if (!scope.identifiers().isEmpty()) {
                    typedFiles.add(relPath);
                }
                if (sites.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
                // The allow-list key is the src/main/java-relative path; a .java
                // file anywhere else under src/main can never be allow-listed.
                String key = file.startsWith(javaRoot)
                        ? javaRoot.relativize(file).toString().replace('\\', '/')
                        : relPath;
                Range allowed = switch (ALLOWLIST.get(key)) {
                    case null -> Range.NONE;
                    case WholeFile _ -> new Range(0, code.length());
                    case MethodBody(String method, String _) -> methodBody(code, method)
                            .orElseGet(() -> {
                                missingScopes.add(key + "#" + method);
                                return Range.NONE;
                            });
                };
                for (Site site : sites) {
                    String where = relPath + "  — " + site.text();
                    if (allowed.contains(site.offset())) {
                        allowlistedSites.computeIfAbsent(key, _ -> new ArrayList<>()).add(where);
                    } else {
                        offenders.add(where
                                + (ALLOWLIST.containsKey(key)
                                        ? "  (outside the allow-listed scope of this file)" : "")
                                + "  (a control raises a TrackCommand; only the intent path "
                                + "writes Track / MixerChannel — story 322, §2.10 / §5.6)");
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Non-vacuity: a broken path, a renamed type, or a regex that stopped
        // matching would otherwise make every assertion below trivially true.
        assertThat(scannedJava)
                .as("the daw-app source scan must visit a non-trivial number of .java files")
                .hasSizeGreaterThan(50);
        assertThat(scannedOther)
                .as("the daw-app resource scan must visit the fxml / css / properties files")
                .isNotEmpty();
        assertThat(typedFiles)
                .as("Track / MixerChannel-typed identifiers must be resolvable in production files")
                .hasSizeGreaterThan(5);
        assertThat(allowlistedSites.getOrDefault(CORE_INTENT_HANDLER, List.of()))
                .as("the ONE intent path must be found dual-writing Track + MixerChannel — "
                        + "if it is not, this scan proves nothing")
                .hasSizeGreaterThanOrEqualTo(8);
        assertThat(missingScopes)
                .as("every method-scoped allow-list entry must name a method that still exists")
                .isEmpty();
        for (Map.Entry<String, Allowance> entry : ALLOWLIST.entrySet()) {
            assertThat(allowlistedSites.getOrDefault(entry.getKey(), List.of()))
                    .as("allow-list entry %s has no model-write site left in its scope — remove the "
                            + "entry (reason on file: %s)", entry.getKey(), entry.getValue().reason())
                    .isNotEmpty();
        }

        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("Story 322 §5.6 — every strip / mixer / arrangement / stage / VCA / link "
                        + "control writes Track and MixerChannel ONLY through the intent path "
                        + "(allow-list: %s)", ALLOWLIST.keySet())
                .isEmpty();
        assertThat(sendLevelHits)
                .as("Story 322 — the legacy per-channel send level is removed: no "
                        + "setSendLevel / getSendLevel anywhere under daw-app/src/main")
                .isEmpty();
        assertThat(linkInsertsHits)
                .as("Story 322 — 'Link Inserts' is removed (no plugin-clone contract exists)")
                .isEmpty();
        assertThat(fictionHits)
                .as("Story 322 — TrackStripController renders the channel's real InsertSlot list; "
                        + "the hardcoded Gain/Gate/Comp/HPF/Limiter fiction and the MIDI instrument "
                        + "hints are gone")
                .isEmpty();
        assertThat(stripInstantiated[0])
                .as("Story 322 / 271 — MixerView must instantiate MixerChannelStrip in production "
                        + "(the strip suite must not survive uninstantiated)")
                .isTrue();
        assertThat(pannerButtons[0])
                .as("MixerView builds at most one 3D panner button per strip site")
                .isLessThanOrEqualTo(1);
        if (pannerButtons[0] == 1) {
            assertThat(pannerGated[0])
                    .as("Story 322 — the 3D panner button's visible AND managed properties are bound "
                            + "to ChannelVM.spatialNodePresent (hidden until a spatial node exists), "
                            + "never shown unconditionally")
                    .isTrue();
        }
    }

    /**
     * The permanent fault-injection fixtures for the receiver resolution: one
     * positive per shape a control writer can take, and the negatives a
     * reviewer must never be woken up by. Each snippet goes through the exact
     * production pipeline (comment strip → literal blank → scope → sites), so a
     * helper that regresses fails here by name instead of silently letting the
     * file walk go green. The first two positives are the prober's verbatim
     * injections into {@code TrackStripController.addTrackToUI} (fix round 1,
     * fault F12): the earlier scan never typed a {@code var} and required the
     * lambda terminal right after the getter, so both stayed green; the third
     * is the Optional-unwrap shape it identified by construction. The "fix
     * round 2" fixtures cover the bound method reference, the
     * {@code requireNonNull} wrapper, the parenthesised lambda parameter and
     * the {@code subList} segment, each with a negative twin. The "fix round 3"
     * fixtures are the round-2 lens's three evading receiver shapes and the
     * round-2 prober's uncaught faults: the getter-chain and in-file-method
     * method references, {@code requireNonNull(...)} used inline as the
     * receiver ({@code Objects.}-qualified, static-imported with a message,
     * and around an in-file model-returning method), the package-qualified
     * {@code java.util.Objects.requireNonNull} behind a {@code var}, the
     * {@code var}-typed lambda parameter and the {@code Supplier}-message
     * overload — with a negative twin for each shape that has one. The "fix
     * round 4" pair pins the composition the round-3 prober saw the production
     * scan report but no fixture owned — the package-qualified wrapper used
     * inline as the receiver with a {@code Supplier} message — and its
     * {@code TrackVM} twin.
     */
    @Test
    void theReceiverResolutionCatchesEveryShapeAControlWriterCanTake() {
        assertCaught("var local from a model getter (prober fixture)", "setVolume", """
                void addTrackToUI(Track track) {
                    var probeCh = project.getMixerChannelForTrack(track);
                    if (probeCh != null) probeCh.setVolume(0.5);
                }
                """);
        assertCaught("stream segment before forEach (prober fixture)", "setMuted", """
                void addTrackToUI() {
                    project.getTracks().stream().forEach(pt -> pt.setMuted(true));
                }
                """);
        assertCaught("Optional local unwrapped by orElseThrow (prober fixture)", "setMuted", """
                void heal(MixerChannel channel) {
                    Optional<Track> t = project.getTrackForChannel(channel);
                    t.orElseThrow().setMuted(true);
                }
                """);

        assertCaught("method reference — the pre-322 VcaStrip shape", "setMuted", """
                void setMuted(boolean muted) {
                    applyToAllMembers(MixerChannel::isMuted, MixerChannel::setMuted, muted);
                }
                """);
        assertCaught("var from an already-typed identifier", "setArmed", """
                void arm(Track track) {
                    var t = track;
                    t.setArmed(true);
                }
                """);
        assertCaught("var from a typed collection element", "setSolo", """
                void solo(List<Track> tracks) {
                    var first = tracks.get(0);
                    first.setSolo(true);
                }
                """);
        assertCaught("var chained through another var", "setPan", """
                void centre(List<MixerChannel> channels) {
                    var chosen = channels.getFirst();
                    var again = chosen;
                    again.setPan(0.0);
                }
                """);
        assertCaught("enhanced-for var over a typed collection", "setMuted", """
                void muteAll(List<MixerChannel> channels) {
                    for (var ch : channels) {
                        ch.setMuted(true);
                    }
                }
                """);
        assertCaught("enhanced-for var over a collection getter", "setPan", """
                void centreAll() {
                    for (var t : project.getTracks()) {
                        t.setPan(0.0);
                    }
                }
                """);
        assertCaught("in-file model-returning method", "setMuted", """
                private MixerChannel resolveChannel(UUID id) {
                    return project.getMixerChannel(id);
                }
                void mute(UUID id) {
                    resolveChannel(id).setMuted(true);
                }
                """);
        assertCaught("in-file Optional-returning method, unwrapped", "setVolume", """
                private Optional<Track> trackFor(UUID id) {
                    return Optional.empty();
                }
                void set(UUID id) {
                    this.trackFor(id).orElseThrow().setVolume(0.25);
                }
                """);
        assertCaught("in-file collection-returning method feeding a lambda", "setSolo", """
                private List<Track> members() {
                    return List.of();
                }
                void soloAll() {
                    members().stream().filter(t -> !t.isSolo()).forEach(t -> t.setSolo(true));
                }
                """);
        assertCaught("filter segment before a block-lambda map", "setMuted", """
                List<Track> muteAll(List<Track> tracks) {
                    return tracks.stream().filter(t -> !t.isMuted()).map(t -> {
                        t.setMuted(true);
                        return t;
                    }).toList();
                }
                """);

        assertCaught("direct call on a declared Track", "setMuted",
                "void f(Track track) { track.setMuted(true); }");
        assertCaught("direct call on a declared MixerChannel", "setVolume",
                "void f(MixerChannel channel) { channel.setVolume(0.5); }");
        assertCaught("direct call on a declared Track (arm)", "setArmed",
                "void f(Track track) { track.setArmed(true); }");
        assertCaught("indexed element of a typed list", "setPan",
                "void f(List<MixerChannel> channels) { channels.get(1).setPan(-1.0); }");
        assertCaught("model getter chain", "setSolo",
                "void f(Track t) { project.getMixerChannelForTrack(t).setSolo(true); }");
        assertCaught("lambda over a typed optional", "setArmed",
                "void f(Optional<Track> maybe) { maybe.ifPresent(t -> t.setArmed(true)); }");

        assertCaught("bound method reference on a declared Track (fix round 2)", "setMuted", """
                void f(Track track) {
                    onToggle(track::setMuted);
                }
                """);
        assertCaught("bound method reference on a lambda parameter over a channel list (fix round 2)", "setVolume", """
                void f(List<MixerChannel> channels) {
                    channels.forEach(c -> apply(c::setVolume));
                }
                """);
        assertCaught("var through Objects.requireNonNull around a model getter (fix round 2)", "setVolume", """
                void f(Track t) {
                    var ch = Objects.requireNonNull(project.getMixerChannelForTrack(t));
                    ch.setVolume(0.5);
                }
                """);
        assertCaught("var through a static-imported requireNonNull with a message (fix round 2)", "setPan", """
                void f(Track t) {
                    var ch = requireNonNull(project.getMixerChannelForTrack(t), "channel");
                    ch.setPan(0.0);
                }
                """);
        assertCaught("parenthesised single lambda parameter (fix round 2)", "setMuted", """
                void f(List<Track> tracks) {
                    tracks.forEach((t) -> t.setMuted(true));
                }
                """);
        assertCaught("subList segment before the lambda (fix round 2)", "setPan", """
                void f(List<MixerChannel> channels) {
                    channels.subList(0, 2).forEach(c -> c.setPan(0.0));
                }
                """);

        assertCaught("getter-chain method reference (fix round 3)", "setMuted", """
                void f() {
                    onToggle(project.getMasterChannel()::setMuted);
                }
                """);
        assertCaught("in-file model-returning method as a method reference (fix round 3)", "setMuted", """
                private MixerChannel resolveChannel(UUID id) {
                    return project.getMixerChannel(id);
                }
                void f(UUID id) {
                    onToggle(resolveChannel(id)::setMuted);
                }
                """);
        assertCaught("Objects.requireNonNull used inline around a model getter (fix round 3)", "setVolume", """
                void f(Track t) {
                    Objects.requireNonNull(project.getMixerChannelForTrack(t)).setVolume(0.5);
                }
                """);
        assertCaught("static-imported requireNonNull used inline, with a message (fix round 3)", "setVolume", """
                void f(Track t) {
                    requireNonNull(project.getMixerChannelForTrack(t), "channel").setVolume(0.5);
                }
                """);
        assertCaught("requireNonNull used inline around an in-file model-returning method (fix round 3)", "setMuted", """
                private MixerChannel resolveChannel(UUID id) {
                    return project.getMixerChannel(id);
                }
                void f(UUID id) {
                    Objects.requireNonNull(resolveChannel(id)).setMuted(true);
                }
                """);
        assertCaught("var through the package-qualified java.util.Objects.requireNonNull (fix round 3)", "setVolume", """
                void f(Track track) {
                    var ch = java.util.Objects.requireNonNull(project.getMixerChannelForTrack(track));
                    ch.setVolume(0.5);
                }
                """);
        assertCaught("var-typed lambda parameter (fix round 3)", "setArmed", """
                void f() {
                    project.getTracks().forEach((var t) -> t.setArmed(true));
                }
                """);
        assertCaught("var through requireNonNull with a Supplier message (fix round 3)", "setSolo", """
                void f(Track track) {
                    var s = Objects.requireNonNull(project.getMixerChannelForTrack(track), () -> "x");
                    s.setSolo(true);
                }
                """);

        assertCaught("package-qualified requireNonNull used inline, with a Supplier message (fix round 4)", "setPan", """
                void f(Track track) {
                    java.util.Objects.requireNonNull(project.getMixerChannelForTrack(track), () -> "x").setPan(0.1);
                }
                """);

        assertClean("a TrackVM receiver", "void f(TrackVM track) { track.setMuted(true); }");
        assertClean("a TrackStrip receiver", "void f(TrackStrip strip) { strip.setVolume(0.5); }");
        assertClean("a MixerChannelStrip receiver", "void f(MixerChannelStrip strip) { strip.setPan(0.0); }");
        assertClean("a setter in a comment",
                "void f(Track track) { /* track.setMuted(true); */ } // track.setSolo(true);");
        assertClean("a setter in a string",
                "void f(Track track) { log(\"track.setMuted(true)\"); }");
        assertClean("a setter in a text block", """
                void f(Track track) {
                    String s = \"""
                        track.setMuted(true);
                        \""";
                }
                """);
        assertClean("a method reference to a model getter",
                "void f() { members(MixerChannel::isMuted); }");
        assertClean("a var from an in-file method that returns a strip, not a model", """
                private MixerChannelStrip stripFor(Track track) {
                    return strips.get(track);
                }
                void f(Track track) {
                    var strip = stripFor(track);
                    strip.setVolume(0.5);
                }
                """);
        assertClean("a var from a non-model getter",
                "void f(UUID id) { var vm = registry.channelVm(id); vm.setVolume(0.5); }");
        assertClean("a var holding a model's id, not the model", """
                void f(Track track) {
                    var id = project.getMixerChannelForTrack(track).getId();
                    id.setMuted(true);
                }
                """);
        assertClean("a VM lambda over a registry",
                "void f() { registry.trackVms().forEach(vm -> vm.setMuted(true)); }");

        assertClean("a bound method reference on a TrackVM (fix round 2)",
                "void f(TrackVM vm) { onToggle(vm::setMuted); }");
        assertClean("a var through Objects.requireNonNull around a non-model getter (fix round 2)",
                "void f(UUID id) { var vm = Objects.requireNonNull(registry.channelVm(id)); vm.setVolume(0.5); }");
        assertClean("a parenthesised lambda parameter over a VM list (fix round 2)",
                "void f(List<TrackVM> vms) { vms.forEach((vm) -> vm.setMuted(true)); }");
        assertClean("a subList of VMs (fix round 2)",
                "void f(List<TrackVM> vms) { vms.subList(0, 2).forEach(vm -> vm.setMuted(true)); }");

        assertClean("a getter-chain method reference on a non-model getter (fix round 3)",
                "void f(UUID id) { onToggle(registry.channelVm(id)::setMuted); }");
        assertClean("requireNonNull used inline around a non-model getter (fix round 3)",
                "void f(UUID id) { Objects.requireNonNull(registry.channelVm(id)).setVolume(0.5); }");
        assertClean("requireNonNull used inline around an in-file method that returns a strip (fix round 3)", """
                private MixerChannelStrip stripFor(Track track) {
                    return strips.get(track);
                }
                void f(Track track) {
                    Objects.requireNonNull(stripFor(track)).setVolume(0.5);
                }
                """);
        assertClean("a var-typed lambda parameter over a VM list (fix round 3)",
                "void f(List<TrackVM> vms) { vms.forEach((var vm) -> vm.setMuted(true)); }");

        assertClean("package-qualified requireNonNull used inline around a TrackVM getter, with a Supplier message "
                        + "(fix round 4)",
                "void f(UUID id) { java.util.Objects.requireNonNull(registry.trackVm(id), () -> \"x\").setPan(0.1); }");
    }

    private static void assertCaught(String shape, String setter, String snippet) {
        List<String> found = sites(snippet);
        assertThat(found)
                .as("fixture '%s' must resolve to exactly one model-write site", shape)
                .hasSize(1);
        assertThat(found.getFirst())
                .as("fixture '%s' must name the audible setter", shape)
                .contains(setter);
    }

    private static void assertClean(String shape, String snippet) {
        assertThat(sites(snippet))
                .as("fixture '%s' must NOT resolve to a model-write site", shape)
                .isEmpty();
    }

    /** The production pipeline over one snippet: comment strip, literal blank, scope, sites. */
    private static List<String> sites(String source) {
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(source));
        return modelWriteSites(code, modelScope(code)).stream().map(Site::text).toList();
    }

    /** Text resources worth scanning for the removed seams; icons and fonts are skipped. */
    private static boolean isTextResource(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "fxml", "css", "properties", "xml", "txt", "json", "svg", "md", "html" -> true;
            default -> false;
        };
    }

    /** One audible-setter call: where it starts in the comment-free code, and how to name it. */
    private record Site(int offset, String text) {
    }

    /** A half-open offset range of the comment-free code. */
    private record Range(int start, int end) {
        static final Range NONE = new Range(0, 0);

        boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    /**
     * What one file declares: the identifiers that resolve to a
     * {@code Track} / {@code MixerChannel} (or a container of them) and the
     * in-file methods that return one.
     */
    private record ModelScope(Set<String> identifiers, Set<String> modelMethods) {
    }

    /**
     * Every call that yields a model or a container of models in this file:
     * the project / mixer / link-manager getters plus the file's own
     * model-returning methods.
     */
    private static String modelSources(Set<String> modelMethods) {
        String sources = MODEL_GETTERS + "|" + COLLECTION_GETTERS;
        return modelMethods.isEmpty() ? sources : sources + "|" + String.join("|", modelMethods);
    }

    private static ModelScope modelScope(String code) {
        Set<String> methods = new LinkedHashSet<>();
        Matcher declaration = MODEL_METHOD_DECLARATION.matcher(code);
        while (declaration.find()) {
            methods.add(declaration.group(1));
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher declared = DECLARED_MODEL.matcher(code);
        while (declared.find()) {
            names.add(declared.group(1));
        }
        String sources = modelSources(methods);

        // var x = …source(...)[.unwrap()][.stream()]; — the local takes the source's type;
        // also var x = Objects.requireNonNull(…source(...)[, msg]); — the wrapper hands
        // the same object back (fix round 2; the package-qualified wrapper and the
        // Supplier-message overload, fix round 3).
        String sourceChain = "\\b(?:" + sources + ")\\s*" + ARGS + "(?:" + UNWRAP + "|" + PASS_THROUGH + ")*";
        Pattern varFromSource = Pattern.compile(
                "\\bvar\\s+(\\w+)\\s*=\\s*(?:[^;]*?" + sourceChain
                        + "|" + NULL_CHECK_WRAPPER + "\\s*\\(\\s*[^;]*?" + sourceChain
                        + "\\s*" + OPTIONAL_MESSAGE + "\\))\\s*;");
        Matcher fromSource = varFromSource.matcher(code);
        while (fromSource.find()) {
            names.add(fromSource.group(1));
        }
        // for (var t : tracks) / for (var t : project.getTracks()) — group 2 is the
        // typed receiver, or null for the source-call form.
        Pattern forVar = Pattern.compile(
                "\\bfor\\s*\\(\\s*(?:final\\s+)?var\\s+(\\w+)\\s*:\\s*(?:(\\w+)|[^;{)]*?\\b(?:" + sources
                        + ")\\s*" + ARGS + ")(?:" + PASS_THROUGH + ")*\\s*\\)");
        // tracks.forEach(t ->, maybeTrack.ifPresent(t ->, getTracks().stream().filter(...).map(t ->
        // — group 1 is the typed receiver, or null for the source-call form; group 2 the parameter.
        Pattern lambdaParam = Pattern.compile(
                "(?:(\\w+)|(?:\\.\\s*)?\\b(?:" + sources + ")\\s*" + ARGS + ")"
                        + "(?:" + PASS_THROUGH + "|" + UNWRAP + ")*" + LAMBDA_TERMINAL);

        // A var, an enhanced-for variable or a lambda parameter takes its type
        // from a receiver that may itself have been typed one step earlier
        // (var a = tracks.get(0); var b = a;), so resolve until nothing new is learnt.
        boolean grew = true;
        while (grew) {
            int before = names.size();
            Matcher fromTyped = VAR_FROM_TYPED.matcher(code);
            while (fromTyped.find()) {
                if (names.contains(fromTyped.group(2))) {
                    names.add(fromTyped.group(1));
                }
            }
            Matcher loop = forVar.matcher(code);
            while (loop.find()) {
                if (loop.group(2) == null || names.contains(loop.group(2))) {
                    names.add(loop.group(1));
                }
            }
            Matcher lambda = lambdaParam.matcher(code);
            while (lambda.find()) {
                if (lambda.group(1) == null || names.contains(lambda.group(1))) {
                    names.add(lambda.group(2));
                }
            }
            grew = names.size() > before;
        }
        return new ModelScope(names, methods);
    }

    /** Every audible-setter call in {@code code} whose receiver resolves to a {@code Track} / {@code MixerChannel}. */
    private static List<Site> modelWriteSites(String code, ModelScope scope) {
        List<Site> sites = new ArrayList<>();
        Matcher chain = GETTER_CHAIN_SET.matcher(code);
        while (chain.find()) {
            sites.add(new Site(chain.start(), "." + chain.group(1) + "(...)…." + chain.group(2) + "(...)"));
        }
        Matcher chainReference = GETTER_CHAIN_METHOD_REF_SET.matcher(code);
        while (chainReference.find()) {
            sites.add(new Site(chainReference.start(),
                    "." + chainReference.group(1) + "(...)::" + chainReference.group(2)));
        }
        Matcher wrappedChain = WRAPPED_GETTER_CHAIN_SET.matcher(code);
        while (wrappedChain.find()) {
            sites.add(new Site(wrappedChain.start(),
                    "requireNonNull(…." + wrappedChain.group(1) + "(...))." + wrappedChain.group(2) + "(...)"));
        }
        Matcher reference = METHOD_REF_SET.matcher(code);
        while (reference.find()) {
            sites.add(new Site(reference.start(), reference.group().replaceAll("\\s+", "")));
        }
        Matcher bound = BOUND_METHOD_REF_SET.matcher(code);
        while (bound.find()) {
            if (scope.identifiers().contains(bound.group(1))) {
                sites.add(new Site(bound.start(), bound.group(1) + "::" + bound.group(2)));
            }
        }
        if (!scope.modelMethods().isEmpty()) {
            // resolveChannel(id).setMuted( / this.trackFor(id).orElseThrow().setVolume( /
            // resolveChannel(id)::setMuted — a bare (or this.) call of an in-file
            // model-returning method; a call on another receiver is that receiver's
            // getter and is covered by GETTER_CHAIN_SET. The same call inside a
            // requireNonNull(...) used as the receiver is a site too (fix round 3).
            String localChain = "(?:\\bthis\\s*\\.\\s*|(?<![\\w.]))\\b(" + String.join("|", scope.modelMethods())
                    + ")\\s*" + ARGS + "(?:" + UNWRAP + ")?";
            Pattern localCall = Pattern.compile(
                    localChain + "\\s*(?:\\.\\s*(" + SETTERS + ")\\s*\\(|::\\s*(" + SETTERS + ")\\b)");
            Matcher local = localCall.matcher(code);
            while (local.find()) {
                sites.add(new Site(local.start(), local.group(2) != null
                        ? local.group(1) + "(...)…." + local.group(2) + "(...)"
                        : local.group(1) + "(...)::" + local.group(3)));
            }
            Pattern wrappedLocalCall = Pattern.compile(
                    "\\b" + NULL_CHECK_WRAPPER + "\\s*\\(\\s*" + localChain + "\\s*" + OPTIONAL_MESSAGE
                            + "\\s*\\)\\s*\\.\\s*(" + SETTERS + ")\\s*\\(");
            Matcher wrappedLocal = wrappedLocalCall.matcher(code);
            while (wrappedLocal.find()) {
                sites.add(new Site(wrappedLocal.start(),
                        "requireNonNull(" + wrappedLocal.group(1) + "(...))." + wrappedLocal.group(2) + "(...)"));
            }
        }
        Matcher unwrapped = UNWRAP_SET.matcher(code);
        while (unwrapped.find()) {
            if (scope.identifiers().contains(unwrapped.group(1))) {
                sites.add(new Site(unwrapped.start(),
                        unwrapped.group(1) + ".get(...)." + unwrapped.group(2) + "(...)"));
            }
        }
        Matcher direct = DIRECT_SET.matcher(code);
        while (direct.find()) {
            if (scope.identifiers().contains(direct.group(1))) {
                sites.add(new Site(direct.start(), direct.group(1) + "." + direct.group(2) + "(...)"));
            }
        }
        return sites;
    }

    /**
     * The body of the in-file method {@code method} as a range of
     * {@code code}: from its opening brace to the matching closing one.
     * Braces are structural here because comments are stripped and every
     * string / char literal is blanked before the scan. Empty when the method
     * is not declared in this file.
     */
    private static Optional<Range> methodBody(String code, String method) {
        Matcher head = Pattern.compile(
                "\\b" + Pattern.quote(method) + "\\s*" + ARGS + "\\s*(?:throws\\s+[\\w.,\\s]+)?\\{").matcher(code);
        if (!head.find()) {
            return Optional.empty();
        }
        int open = head.end() - 1;
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return Optional.of(new Range(open, i + 1));
            }
        }
        return Optional.empty();
    }
}
