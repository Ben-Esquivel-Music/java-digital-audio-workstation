package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.concurrent.DawScope;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 298 — Test 5. Proves the package carries per-recovery context via the
 * explicit {@link ProjectContext} record rather than a {@link ThreadLocal}:
 *
 * <ol>
 *   <li><b>Source scan</b> — no {@code .java} file in the journal package
 *       mentions {@code ThreadLocal} (mirrors daw-app's
 *       {@code LegacyHardcodedColorAuditTest} file-walk).</li>
 *   <li><b>Behavioural</b> — several {@code DawScope} forks each read the same
 *       explicitly-captured {@code ProjectContext} and observe identical
 *       values, proving propagation without thread affinity.</li>
 * </ol>
 */
class ProjectContextNoThreadLocalTest {

    private static final String FORBIDDEN_TOKEN = "ThreadLocal";

    /**
     * Matches a comment OR a string/text-block literal, so they can be
     * stripped before the scan — a {@code ThreadLocal} mention in Javadoc
     * (e.g. "the explicit alternative to a ThreadLocal") must not false-match;
     * only a real {@code ThreadLocal} <em>identifier</em> in code counts.
     * Mirrors {@code LegacyHardcodedColorAuditTest}'s comment/string stripper.
     */
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""        // text block
            + "|\"(?:\\\\.|[^\"\\\\])*\""   // string literal
            + "|//[^\\n]*"                  // line comment
            + "|/\\*[\\s\\S]*?\\*/");       // block comment

    @Test
    void noJournalSourceFileUsesThreadLocal() throws IOException {
        Path packageSrc = locateJournalPackageSource();
        assertThat(Files.isDirectory(packageSrc))
                .as("journal package sources must live under %s", packageSrc)
                .isTrue();

        List<String> offenders = new ArrayList<>();
        List<Path> scanned = new ArrayList<>();
        Files.walkFileTree(packageSrc, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.getFileName().toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);
                // Strip comments + string literals so a ThreadLocal mention in
                // Javadoc does not false-match — only real code usage counts.
                String code = stripCommentsAndStrings(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains(FORBIDDEN_TOKEN)) {
                    offenders.add(packageSrc.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Guard against a silently-empty scan masking the audit.
        assertThat(scanned)
                .as("the journal source scan must visit the package's .java files")
                .hasSizeGreaterThan(8);
        assertThat(offenders)
                .as("Story 298 — the journal package must thread per-recovery "
                        + "context via the explicit ProjectContext record, never "
                        + "a ThreadLocal. Offending files: %s", offenders)
                .isEmpty();
    }

    @Test
    void forksObserveTheSameExplicitlyCapturedContext() throws Exception {
        Path projectDir = Paths.get(System.getProperty("java.io.tmpdir"), "ctx-" + UUID.randomUUID());
        ProjectContext ctx = ProjectContext.forProject(projectDir);

        Set<UUID> observedRequestIds = ConcurrentHashMap.newKeySet();
        Set<Path> observedDirs = ConcurrentHashMap.newKeySet();

        try (DawScope scope = DawScope.openShutdownOnFailure("ctx-propagation")) {
            List<DawScope.Subtask<Boolean>> forks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                // Each lambda closes over the SAME ctx record — the no-ThreadLocal seam.
                forks.add(scope.fork("reader-" + i, () -> {
                    observedRequestIds.add(ctx.requestId());
                    observedDirs.add(ctx.projectDirectory());
                    // Derived paths must also be consistent across threads.
                    return ctx.journalDirectory().equals(projectDir.resolve("journal"))
                            && ctx.checkpointDirectory().equals(projectDir.resolve("checkpoints"));
                }));
            }
            scope.joinAll();
            for (DawScope.Subtask<Boolean> fork : forks) {
                assertThat(fork.resultNow())
                        .as("every fork must resolve derived paths from the captured context")
                        .isTrue();
            }
        }

        assertThat(observedRequestIds)
                .as("all forks must observe the one requestId — context propagated, not thread-bound")
                .containsExactly(ctx.requestId());
        assertThat(observedDirs).containsExactly(projectDir);
    }

    /**
     * Replaces every comment and string/text-block literal with whitespace of
     * equal newline count, leaving only executable code for the token scan.
     */
    private static String stripCommentsAndStrings(String source) {
        Matcher m = COMMENT_OR_STRING.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        while (m.find()) {
            out.append(source, last, m.start());
            // Preserve newlines so any line-based reasoning stays aligned.
            for (int i = m.start(); i < m.end(); i++) {
                char c = source.charAt(i);
                out.append(c == '\n' ? '\n' : ' ');
            }
            last = m.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /**
     * Locates this package's {@code src/main/java} directory. Surefire sets
     * the working directory to the module ({@code daw-core}) for a reactor
     * run; the fallbacks cover {@code mvn -pl daw-core} and repo-root
     * invocations. Mirrors {@code LegacyHardcodedColorAuditTest#locateDawAppModule}.
     */
    private static Path locateJournalPackageSource() {
        String rel = "src/main/java/com/benesquivelmusic/daw/core/persistence/journal";
        Path cwd = Paths.get("").toAbsolutePath();
        Path direct = cwd.resolve(rel);
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path viaModule = cwd.resolve("daw-core").resolve(rel);
        if (Files.isDirectory(viaModule)) {
            return viaModule;
        }
        Path candidate = cwd.getParent();
        for (int i = 0; i < 5 && candidate != null; i++) {
            Path c = candidate.resolve("daw-core").resolve(rel);
            if (Files.isDirectory(c)) {
                return c;
            }
            candidate = candidate.getParent();
        }
        return direct;
    }
}
