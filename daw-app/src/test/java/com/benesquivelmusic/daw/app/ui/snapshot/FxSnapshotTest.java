package com.benesquivelmusic.daw.app.ui.snapshot;

import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.theme.Theme;
import com.benesquivelmusic.daw.app.ui.theme.ThemeJson;
import com.benesquivelmusic.daw.app.ui.theme.ThemeRegistry;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base class for JavaFX visual-regression / snapshot tests.
 *
 * <p>Subclasses render a {@link Node} (typically a view under test),
 * optionally apply one of the bundled themes, then call
 * {@link #assertMatchesSnapshot(Node, String)} which:
 *
 * <ol>
 *   <li>Forces layout, takes a {@link Scene#snapshot(WritableImage)}
 *       on the FX thread.</li>
 *   <li>Loads the golden PNG from
 *       {@code daw-app/src/test/resources/snapshots/&lt;test-class&gt;/&lt;name&gt;.png}.</li>
 *   <li>If the golden is missing, writes the rendered image as the
 *       baseline (auto-baseline) and lets the test pass — the next run
 *       will compare against this baseline. To disable auto-baseline
 *       (e.g. on CI), set {@code -Dsnapshots.autoBaseline=false}.</li>
 *   <li>Otherwise compares with {@link ImageDiff}; on diff, writes the
 *       expected/actual/diff PNGs into {@code target/snapshot-failures/}
 *       and fails with a rebaseline command hint.</li>
 * </ol>
 *
 * <h2>Rebaselining</h2>
 *
 * <p>To intentionally update goldens after a UI change, run the build
 * with {@code -Dsnapshots.update=true} (or delete the relevant PNG):
 *
 * <pre>
 *   mvn -pl daw-app test -Dsnapshots.update=true
 *   git add daw-app/src/test/resources/snapshots
 * </pre>
 *
 * <h2>Stability</h2>
 *
 * <p>The {@code daw-app} surefire configuration sets
 * {@code -Dprism.order=sw} and {@code -Dprism.lcdtext=false} so JavaFX
 * uses the software rasterizer with grayscale text antialiasing — this
 * keeps goldens reproducible across machines (per the issue, Linux-CI
 * is the reference platform). On top of that, {@link ImageDiff} compares
 * each pixel against a {@code (2r+1)×(2r+1)} bidirectional window in the
 * other image (default {@code r=3}); this absorbs the multi-pixel font-
 * metric drift JavaFX exhibits across operating systems (which pick
 * different default fonts and line metrics) while still surfacing
 * layout regressions larger than ~3 pixels. The fraction tolerance
 * (default 0.5%) catches anything that survives the windowed match.</p>
 *
 * <h2>Headless</h2>
 *
 * <p>Like every other JavaFX test in {@code daw-app}, snapshot tests
 * require an active display — on CI this is provided by {@code xvfb-run}
 * (see {@code .github/workflows/ci.yml}). Without a display the JavaFX
 * toolkit will fail to start (and the tests will fail-fast rather than
 * hang) thanks to {@link JavaFxToolkitExtension}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
public abstract class FxSnapshotTest {

    /** Default snapshot scene size — matches the issue's reference views. */
    protected static final int DEFAULT_WIDTH = 800;
    /** Default snapshot scene size — matches the issue's reference views. */
    protected static final int DEFAULT_HEIGHT = 500;

    /** Resource root (under {@code src/test/resources}) where goldens live. */
    public static final String GOLDENS_RESOURCE_ROOT = "snapshots";

    /** System property: when {@code true}, overwrite any existing golden. */
    public static final String SNAPSHOTS_UPDATE_PROP = "snapshots.update";

    /** System property: when {@code false}, fail instead of auto-baselining. */
    public static final String SNAPSHOTS_AUTO_BASELINE_PROP = "snapshots.autoBaseline";

    /**
     * Directory where actual + expected + diff PNGs are written when a
     * comparison fails. Resolved under {@code target/} of the running
     * Maven build.
     */
    public static final Path FAILURE_DIR =
            Paths.get("target", "snapshot-failures");

    /**
     * Source directory where goldens are written when auto-baselining
     * or when {@link #SNAPSHOTS_UPDATE_PROP} is set. Resolved relative
     * to the current working directory (i.e. {@code daw-app/}).
     */
    public static final Path GOLDENS_SOURCE_DIR =
            Paths.get("src", "test", "resources", GOLDENS_RESOURCE_ROOT);

    /**
     * Renders {@code node} into an off-screen {@link Scene} sized
     * {@link #DEFAULT_WIDTH}×{@link #DEFAULT_HEIGHT}, applies the named
     * theme as inline CSS, takes a snapshot, and asserts it matches the
     * golden under {@code snapshots/&lt;simpleClassName&gt;/&lt;name&gt;.png}.
     */
    protected final void assertMatchesSnapshot(Node node, String name) {
        assertMatchesSnapshot(node, name, DEFAULT_WIDTH, DEFAULT_HEIGHT, null);
    }

    /**
     * As {@link #assertMatchesSnapshot(Node, String)} but also applies
     * the named bundled theme (one of {@link ThemeRegistry#BUNDLED_IDS}).
     * The theme name is appended to the golden filename.
     */
    protected final void assertMatchesSnapshot(Node node, String name, String themeId) {
        assertMatchesSnapshot(node, name, DEFAULT_WIDTH, DEFAULT_HEIGHT, themeId);
    }

    /** Full overload — caller specifies size and (optional) theme. */
    protected final void assertMatchesSnapshot(
            Node node, String name, int width, int height, String themeId) {
        BufferedImage actual = render(node, width, height, themeId);
        String fileName = themeId == null ? name + ".png" : name + "." + themeId + ".png";
        String resourcePath = GOLDENS_RESOURCE_ROOT + "/" + getClass().getSimpleName() + "/" + fileName;
        compareOrBaseline(actual, resourcePath, fileName);
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    /**
     * Renders {@code node} into a {@link BufferedImage}, on the FX
     * thread. Wraps {@code node} in a {@link StackPane} with a theme-
     * derived background colour so the surrounding pixels are
     * deterministic.
     *
     * <p>Two consecutive snapshots are taken — the first acts as a
     * warm-up that lets deferred work (icon/image resource loading,
     * font fallback resolution, first-pulse layout) settle; the second
     * is returned. This keeps repeated runs of the same test stable
     * across JVM invocations.</p>
     */
    public final BufferedImage render(Node node, int width, int height, String themeId) {
        Theme theme = themeId == null ? null : loadBundledTheme(themeId);
        WritableImage fxImage = runOnFxThread(() -> {
            Region root = new StackPane(node);
            root.setPrefSize(width, height);
            root.setMinSize(width, height);
            root.setMaxSize(width, height);
            applyThemeStyle(root, theme);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, width, height,
                    theme == null
                            ? Color.web("#1a1a2e")
                            : Color.web(backgroundHex(theme)));
            // Attach the real application stylesheet so snapshots
            // exercise the same CSS selectors users see at runtime.
            ThemeManager.getDefault().applyTo(scene);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            // Warm-up snapshot: triggers css/layout, lets image
            // resources resolve, primes font caches. Discarded.
            scene.snapshot(new WritableImage(width, height));
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            WritableImage img = new WritableImage(width, height);
            scene.snapshot(img);
            return img;
        });
        return toBufferedImage(fxImage);
    }

    /**
     * Loads a bundled theme JSON from {@code daw-app}'s resources.
     * Used by both render() and tests directly.
     */
    public static Theme loadBundledTheme(String id) {
        String resource = "/themes/" + id + ".json";
        try (InputStream in = FxSnapshotTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Bundled theme not found: " + resource);
            }
            return ThemeJson.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bundled theme: " + id, e);
        }
    }

    private static void applyThemeStyle(Region root, Theme theme) {
        if (theme == null) {
            // Fallback inline style — matches the dark dialog look used
            // by ThemeManager when no theme is supplied.
            root.setStyle("-fx-background-color: #1a1a2e;");
            return;
        }
        String bg = backgroundHex(theme);
        String fg = foregroundHex(theme);
        // Inline style: a minimal but theme-driven background + base
        // text colour, expressed via JavaFX looked-up colours so any
        // descendant `.label` etc. inherits the theme's foreground.
        root.setStyle(String.format(
                Locale.ROOT,
                "-fx-base: %s; -fx-background: %s; -fx-control-inner-background: %s;"
                        + " -fx-text-fill: %s; -fx-text-base-color: %s;"
                        + " -fx-background-color: %s;",
                bg, bg, bg, fg, fg, bg));
    }

    private static String backgroundHex(Theme theme) {
        return findColorByRole(theme, "background", theme.dark() ? "#1a1a2e" : "#fafafa");
    }

    private static String foregroundHex(Theme theme) {
        return findColorByRole(theme, "foreground", theme.dark() ? "#e0e0e0" : "#202020");
    }

    private static String findColorByRole(Theme theme, String role, String fallback) {
        // Theme.colors() is built with Map.copyOf(...) whose iteration
        // order is intentionally unspecified — relying on it here would
        // make snapshots flaky across JVM invocations. We therefore
        // (1) prefer the entry whose key equals the role name (the
        // canonical "background"/"foreground" entry shipped by every
        // bundled theme), then (2) fall back to the alphabetically-
        // first entry with that role.
        var match = theme.colors().get(role);
        if (match != null && role.equals(match.role())) {
            return match.value();
        }
        return theme.colors().entrySet().stream()
                .filter(e -> role.equals(e.getValue().role()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> e.getValue().value())
                .findFirst()
                .orElse(fallback);
    }

    /**
     * Converts a JavaFX {@link WritableImage} to a Java2D
     * {@link BufferedImage} without going through {@code SwingFXUtils}
     * (avoiding a {@code javafx-swing} dependency).
     */
    public static BufferedImage toBufferedImage(WritableImage src) {
        int w = (int) Math.round(src.getWidth());
        int h = (int) Math.round(src.getHeight());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = src.getPixelReader();
        if (reader == null) {
            throw new IllegalStateException("WritableImage has no pixel reader");
        }
        int[] row = new int[w];
        // INT_ARGB (non-premultiplied) matches BufferedImage.TYPE_INT_ARGB.
        var fmt = javafx.scene.image.PixelFormat.getIntArgbInstance();
        for (int y = 0; y < h; y++) {
            reader.getPixels(0, y, w, 1, fmt, row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    // ── Comparison + baselining ────────────────────────────────────────────

    private void compareOrBaseline(BufferedImage actual, String resourcePath, String fileName) {
        boolean update = Boolean.parseBoolean(System.getProperty(SNAPSHOTS_UPDATE_PROP, "false"));
        boolean autoBaseline = Boolean.parseBoolean(
                System.getProperty(SNAPSHOTS_AUTO_BASELINE_PROP, "true"));

        InputStream goldenStream = update
                ? null
                : getClass().getClassLoader().getResourceAsStream(resourcePath);

        if (goldenStream == null) {
            // No golden on the classpath — either we are explicitly
            // updating, or this is a first-time baseline.
            if (update || autoBaseline) {
                Path target = GOLDENS_SOURCE_DIR
                        .resolve(getClass().getSimpleName())
                        .resolve(fileName);
                writePng(actual, target);
                if (update) {
                    System.out.println("[snapshot] Updated golden: " + target.toAbsolutePath());
                } else {
                    System.out.println("[snapshot] Auto-baselined missing golden: "
                            + target.toAbsolutePath()
                            + " — review and commit, or rerun with -D"
                            + SNAPSHOTS_AUTO_BASELINE_PROP + "=false to fail instead.");
                }
                return;
            }
            failMissing(resourcePath, fileName);
        }

        BufferedImage expected;
        try (InputStream in = goldenStream) {
            expected = ImageIO.read(in);
            if (expected == null) {
                throw new IOException("ImageIO.read returned null for " + resourcePath);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load golden " + resourcePath, e);
        }

        ImageDiff diff = new ImageDiff();
        ImageDiff.Result result = diff.compare(expected, actual);
        if (!result.passed()) {
            Path failureDir = FAILURE_DIR.resolve(getClass().getSimpleName());
            Path expectedPath = failureDir.resolve(fileName.replace(".png", ".expected.png"));
            Path actualPath   = failureDir.resolve(fileName.replace(".png", ".actual.png"));
            Path diffPath     = failureDir.resolve(fileName.replace(".png", ".diff.png"));
            writePng(expected, expectedPath);
            writePng(actual, actualPath);
            writePng(diff.renderDiff(expected, actual), diffPath);
            fail(String.format(Locale.ROOT,
                    "Snapshot mismatch: %s%n"
                            + "  %s%n"
                            + "  expected: %s%n"
                            + "  actual:   %s%n"
                            + "  diff:     %s%n"
                            + "  To rebaseline (after reviewing the diff), run:%n"
                            + "    mvn -pl daw-app test -Dtest=%s#%s -D%s=true%n"
                            + "  Or remove the golden and rerun the test:%n"
                            + "    rm %s/%s/%s",
                    resourcePath,
                    result.describe(),
                    expectedPath.toAbsolutePath(),
                    actualPath.toAbsolutePath(),
                    diffPath.toAbsolutePath(),
                    getClass().getSimpleName(),
                    deriveTestMethodName(fileName),
                    SNAPSHOTS_UPDATE_PROP,
                    GOLDENS_SOURCE_DIR, getClass().getSimpleName(), fileName));
        }
    }

    private void failMissing(String resourcePath, String fileName) {
        Path target = GOLDENS_SOURCE_DIR
                .resolve(getClass().getSimpleName())
                .resolve(fileName);
        fail(String.format(Locale.ROOT,
                "Missing snapshot golden: %s%n"
                        + "  Auto-baseline is disabled (%s=false). To create the baseline run:%n"
                        + "    mvn -pl daw-app test -Dtest=%s -D%s=true%n"
                        + "  The image will be written to: %s",
                resourcePath,
                SNAPSHOTS_AUTO_BASELINE_PROP,
                getClass().getSimpleName(),
                SNAPSHOTS_UPDATE_PROP,
                target.toAbsolutePath()));
    }

    /** Best-effort extraction — used purely for command-line hint text. */
    private static String deriveTestMethodName(String fileName) {
        int dot = fileName.indexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static void writePng(BufferedImage img, Path target) {
        try {
            Files.createDirectories(target.getParent());
            ImageIO.write(img, "png", target.toFile());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write PNG: " + target, e);
        }
    }

    // ── FX-thread plumbing ─────────────────────────────────────────────────

    /**
     * Runs {@code supplier} on the FX thread and returns its value.
     * Any exception thrown is rethrown on the calling thread.
     */
    public static <T> T runOnFxThread(Supplier<T> supplier) {
        if (Platform.isFxApplicationThread()) {
            return supplier.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FX-thread task timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        Throwable t = error.get();
        if (t != null) {
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException(t);
        }
        return result.get();
    }
}
