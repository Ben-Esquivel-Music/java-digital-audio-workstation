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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 302 — pins Plugin View Design Book §9 rejection #5: <em>no
 * {@code switch} on a plugin id anywhere in {@code daw-app}</em>; the only
 * place an id may drive behaviour is the registry. A source-scan conformance
 * sentinel in the established shape (comment-strip, non-empty-scan guard;
 * this test lives under {@code src/test}, so the walk over {@code src/main}
 * never reaches it):
 *
 * <ul>
 *   <li>{@code PluginViewController.java} contains no {@code switch} at all
 *       any more, no {@code PLUGIN_ID} reference, and neither
 *       {@code openLegacyBuiltInView} nor its story-name alias
 *       {@code openBuiltInPluginView} — the dispatcher method is gone, not
 *       renamed.</li>
 *   <li>App-wide, no {@code daw-app/.../ui} source switches over a plugin id
 *       ({@code case Xxx.PLUGIN_ID ->} arm or a {@code switch} head reading a
 *       {@code PLUGIN_ID}).</li>
 * </ul>
 */
final class NoSwitchOnPluginIdTest {

    private static final String CONTROLLER_FILE = "PluginViewController.java";

    /** A case label over a plugin-id constant — the deleted dispatch shape. */
    private static final Pattern CASE_ON_PLUGIN_ID =
            Pattern.compile("\\bcase\\s+[\\w.$]*PLUGIN_ID\\b");

    /** A switch head selecting on a plugin id. */
    private static final Pattern SWITCH_ON_PLUGIN_ID =
            Pattern.compile("\\bswitch\\s*\\([^)]*PLUGIN_ID");

    @Test
    void thePluginViewControllerSwitchAndItsDispatcherAreGone() throws IOException {
        Path controller = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui")
                .resolve(CONTROLLER_FILE);
        assertThat(Files.isRegularFile(controller))
                .as("%s must exist at %s", CONTROLLER_FILE, controller).isTrue();

        String raw = Files.readString(controller, StandardCharsets.UTF_8);
        String code = SourceScanSupport.stripComments(raw);
        assertThat(code.length())
                .as("the stripped controller source must be non-trivial "
                        + "(a broken read cannot vacuously pass)")
                .isGreaterThan(1_000);

        assertThat(Pattern.compile("\\bswitch\\b").matcher(code).find())
                .as("story 302 — PluginViewController must contain no switch at all "
                        + "(§9 rejection #5; behaviour dispatches through the SDK contract)")
                .isFalse();
        assertThat(Pattern.compile("\\bPLUGIN_ID\\b").matcher(code).find())
                .as("story 302 — PluginViewController must not reference any plugin-id "
                        + "constant; ids drive behaviour only in the registry")
                .isFalse();
        assertThat(Pattern.compile("\\bopenLegacyBuiltInView\\b").matcher(code).find())
                .as("the legacy dispatcher method must be deleted, not kept")
                .isFalse();
        assertThat(Pattern.compile("\\bopenBuiltInPluginView\\b").matcher(code).find())
                .as("the dispatcher must not survive under its pre-301 name either")
                .isFalse();
    }

    @Test
    void noUiSourceSwitchesOverAPluginId() throws IOException {
        Path uiRoot = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui");
        assertThat(Files.isDirectory(uiRoot))
                .as("daw-app ui sources must live under %s", uiRoot).isTrue();

        List<String> offenders = new ArrayList<>();
        List<Path> scanned = new ArrayList<>();
        Files.walkFileTree(uiRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                scanned.add(file);
                String code = SourceScanSupport.stripComments(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (CASE_ON_PLUGIN_ID.matcher(code).find()
                        || SWITCH_ON_PLUGIN_ID.matcher(code).find()) {
                    offenders.add(name);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(scanned)
                .as("the daw-app ui source scan must visit a non-trivial number of files")
                .hasSizeGreaterThan(50);
        offenders.sort(String::compareTo);
        assertThat(offenders)
                .as("§9 rejection #5 — no daw-app ui source may switch over a plugin id. "
                        + "Offenders: %s", offenders)
                .isEmpty();
    }
}
