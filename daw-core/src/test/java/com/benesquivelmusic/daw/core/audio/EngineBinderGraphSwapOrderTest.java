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
 * Story 314 review — the <em>gate-off → swap → gate-on</em> ordering proof.
 *
 * <p>{@link AudioEngine} is final, so the engine-setter call order inside
 * {@link EngineBinder} cannot be intercepted behaviourally; this pins it at
 * the source level instead (the {@code ProjectContextNoThreadLocalTest}
 * source-scan idiom). The transport is the RT callback's
 * {@code playbackActive} gate: {@code bind(...)} must null it before the
 * tracks/mixer swap and re-set it last, and {@code unbind()} must null it
 * before the mixer — any other order lets a rebind during playback render a
 * half-swapped (new tracks, old mixer/transport) graph.</p>
 */
class EngineBinderGraphSwapOrderTest {

    /** One Java comment or one string / text-block / char literal. */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|'(?:\\\\.|[^'\\\\])'"       // char literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    @Test
    void bindGatesTheTransportOffBeforeTheSwapAndBackOnLast() throws IOException {
        String body = methodBody(binderCode(), "public void bind(DawProject project)");

        int gateOff = body.indexOf("setTransport(null)");
        int tracksSwap = body.indexOf("setTracks(");
        int mixerSwap = body.indexOf("setMixer(");
        int gateOn = body.lastIndexOf("setTransport(");

        assertThat(gateOff)
                .as("bind() must gate the transport off (setTransport(null)) first")
                .isNotNegative();
        assertThat(tracksSwap).as("bind() must publish the tracks snapshot").isNotNegative();
        assertThat(mixerSwap).as("bind() must publish the mixer").isNotNegative();
        assertThat(gateOff)
                .as("gate-off must precede the tracks swap").isLessThan(tracksSwap);
        assertThat(tracksSwap)
                .as("the tracks swap must precede the gate-on").isLessThan(gateOn);
        assertThat(mixerSwap)
                .as("the mixer swap must precede the gate-on").isLessThan(gateOn);
        assertThat(gateOn)
                .as("bind() must re-set the transport LAST (gate-on) — a distinct call "
                        + "after the gate-off")
                .isGreaterThan(gateOff);
        assertThat(body.substring(gateOn))
                .as("the final setTransport call must be the gate-ON (the live transport, "
                        + "not another null store)")
                .doesNotStartWith("setTransport(null)");
    }

    @Test
    void unbindNullsTheTransportBeforeTheMixer() throws IOException {
        String body = methodBody(binderCode(), "public void unbind()");

        int gateOff = body.indexOf("setTransport(null)");
        int mixerNull = body.indexOf("setMixer(null)");

        assertThat(gateOff)
                .as("unbind() must null the transport (playbackActive gate) first")
                .isNotNegative();
        assertThat(mixerNull).as("unbind() must null the mixer").isNotNegative();
        assertThat(gateOff)
                .as("the transport-first rule: gate-off precedes the mixer null")
                .isLessThan(mixerNull);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
