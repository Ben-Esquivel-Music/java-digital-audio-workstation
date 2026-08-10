package com.benesquivelmusic.daw.core.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 314 review — the <em>atomic graph publish</em> proof.
 *
 * <p>{@link AudioEngine} is final, so the engine-call shape inside
 * {@link EngineBinder} cannot be intercepted behaviourally; this pins it at
 * the source level instead (the {@code ProjectContextNoThreadLocalTest}
 * source-scan idiom). The invariant this test guards <em>supersedes</em> the
 * old gate-off → swap → gate-on ordering pin: sequencing individual
 * {@code setTransport}/{@code setMixer}/{@code setTracks} stores could never
 * close the race, because the RT callback snapshotted the three references
 * with three separate volatile loads — it could read the OLD (still playing)
 * transport immediately before the gate-off null store and then the NEW
 * tracks/mixer after the following stores, rendering a hybrid graph with
 * {@code playbackActive} true. The fix is atomicity, not ordering: the whole
 * graph lands in ONE {@link AudioEngine#setGraph} call — one volatile store
 * of one immutable record — so there is no half-swapped state at all.</p>
 *
 * <p>Pinned facts:</p>
 * <ul>
 *   <li>{@code bind(...)} publishes via exactly one {@code setGraph(} call
 *       and never touches the removed individual setters;</li>
 *   <li>the only {@code setTracks(} occurrence in {@code bind(...)} is the
 *       structural-change refresh listener, registered AFTER the atomic
 *       publish;</li>
 *   <li>{@code unbind()} clears via exactly one
 *       {@code setGraph(null, null, null)} call and no individual
 *       setters.</li>
 * </ul>
 */
class EngineBinderAtomicGraphPublishTest {

    /** One Java comment or one string / text-block / char literal. */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|'(?:\\\\.|[^'\\\\])'"       // char literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    private static final Pattern SET_GRAPH = Pattern.compile("\\bsetGraph\\s*\\(");
    private static final Pattern SET_GRAPH_ALL_NULL = Pattern.compile(
            "\\bsetGraph\\s*\\(\\s*null\\s*,\\s*null\\s*,\\s*null\\s*\\)");
    private static final Pattern SET_TRANSPORT = Pattern.compile("\\bsetTransport\\s*\\(");
    private static final Pattern SET_MIXER = Pattern.compile("\\bsetMixer\\s*\\(");
    private static final Pattern SET_TRACKS = Pattern.compile("\\bsetTracks\\s*\\(");

    @Test
    void bindPublishesTheWholeGraphInOneAtomicSetGraphCall() throws IOException {
        String body = methodBody(binderCode(), "public void bind(DawProject project)");

        assertThat(count(SET_GRAPH, body))
                .as("bind() must publish transport+mixer+tracks in EXACTLY ONE atomic "
                        + "setGraph call — splitting it back into sequential stores "
                        + "reopens the hybrid-graph race")
                .isEqualTo(1);
        assertThat(count(SET_TRANSPORT, body))
                .as("bind() must not call the removed individual setTransport setter")
                .isZero();
        assertThat(count(SET_MIXER, body))
                .as("bind() must not call the removed individual setMixer setter")
                .isZero();
    }

    @Test
    void bindsOnlySetTracksUseIsTheRefreshListenerRegisteredAfterThePublish()
            throws IOException {
        String body = methodBody(binderCode(), "public void bind(DawProject project)");

        assertThat(count(SET_TRACKS, body))
                .as("bind() may reference setTracks exactly once — the structural-change "
                        + "refresh listener; a second use would be a sequential graph "
                        + "store bypassing the atomic publish")
                .isEqualTo(1);

        int publish = indexOf(SET_GRAPH, body);
        int tracksRefresh = indexOf(SET_TRACKS, body);
        int listenerRegistration = body.indexOf("addChangeListener(");
        assertThat(listenerRegistration)
                .as("bind() must register the structural-change listener").isNotNegative();
        assertThat(tracksRefresh)
                .as("the setTracks refresh must live inside the listener, which is "
                        + "registered AFTER the atomic setGraph publish")
                .isGreaterThan(listenerRegistration);
        assertThat(listenerRegistration)
                .as("the listener registration (and its setTracks refresh) must follow "
                        + "the atomic setGraph publish")
                .isGreaterThan(publish);
    }

    @Test
    void unbindClearsTheWholeGraphInOneAtomicAllNullSetGraphCall() throws IOException {
        String body = methodBody(binderCode(), "public void unbind()");

        assertThat(count(SET_GRAPH, body))
                .as("unbind() must clear the graph in EXACTLY ONE setGraph call")
                .isEqualTo(1);
        assertThat(count(SET_GRAPH_ALL_NULL, body))
                .as("unbind()'s single setGraph call must null ALL THREE components "
                        + "(setGraph(null, null, null)) — anything else leaves part of "
                        + "the old graph live")
                .isEqualTo(1);
        assertThat(count(SET_TRANSPORT, body))
                .as("unbind() must not call the removed individual setTransport setter")
                .isZero();
        assertThat(count(SET_MIXER, body))
                .as("unbind() must not call the removed individual setMixer setter")
                .isZero();
        assertThat(count(SET_TRACKS, body))
                .as("unbind() must not null tracks via an individual setTracks store")
                .isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static int count(Pattern pattern, String code) {
        Matcher matcher = pattern.matcher(code);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /** Returns the offset of the first match, or fails the test when absent. */
    private static int indexOf(Pattern pattern, String code) {
        Matcher matcher = pattern.matcher(code);
        assertThat(matcher.find())
                .as("expected a match for %s", pattern.pattern()).isTrue();
        return matcher.start();
    }

    private static String binderCode() throws IOException {
        Path file = locateEngineBinderSource();
        assertThat(Files.isRegularFile(file))
                .as("EngineBinder source must exist at %s", file).isTrue();
        return stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Replaces comments and string/char literals with spaces, keeping code offsets stable. */
    private static String stripCommentsAndStrings(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            for (int i = m.start(); i < m.end(); i++) {
                out.append(source.charAt(i) == '\n' ? '\n' : ' ');
            }
            last = m.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /** Returns the brace-balanced body following {@code signature} in stripped code. */
    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        assertThat(start).as("EngineBinder must declare `%s`", signature).isNotNegative();
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
     * Locates {@code EngineBinder.java} the {@code ProjectContextNoThreadLocalTest}
     * cwd/parent-hop way: Surefire sets the working directory to the module for
     * a reactor run; the fallbacks cover {@code mvn -pl daw-core} and repo-root
     * invocations.
     */
    private static Path locateEngineBinderSource() {
        String rel = "src/main/java/com/benesquivelmusic/daw/core/audio/EngineBinder.java";
        Path cwd = Paths.get("").toAbsolutePath();
        Path direct = cwd.resolve(rel);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path viaModule = cwd.resolve("daw-core").resolve(rel);
        if (Files.isRegularFile(viaModule)) {
            return viaModule;
        }
        Path candidate = cwd.getParent();
        for (int i = 0; i < 5 && candidate != null; i++) {
            Path c = candidate.resolve("daw-core").resolve(rel);
            if (Files.isRegularFile(c)) {
                return c;
            }
            candidate = candidate.getParent();
        }
        return direct;
    }
}
