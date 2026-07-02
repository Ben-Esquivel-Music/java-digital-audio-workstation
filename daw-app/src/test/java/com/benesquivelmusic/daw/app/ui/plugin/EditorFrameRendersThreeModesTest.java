package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.editor.CanvasSurface;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.RenderTick;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 301 acceptance — each of the three plugin-authored editor modes
 * (Plugin View Design Book §2.1, §6.3) mounts inside the standard
 * {@link EditorFrame} chrome when opened through
 * {@link PluginEditorSession#open}:
 *
 * <ul>
 *   <li>{@link PluginEditorFactory.Declarative} — a host-generated
 *       {@link ParameterGridPanel} body (§6.2);</li>
 *   <li>{@link PluginEditorFactory.Panel} — the plugin's own {@link Region}
 *       embedded in the clipped body host (§6.3);</li>
 *   <li>{@link PluginEditorFactory.Canvas} — a host-owned canvas surface,
 *       attached and then given exactly one synchronous initial render
 *       (§5.D, §6.3), detached again on {@link PluginEditorSession#dispose()}.</li>
 * </ul>
 *
 * <p>All FX work runs through the capture-and-rethrow
 * {@code FxSnapshotTest.runOnFxThread} helper; assertions target style
 * classes, observable properties and instance identity only — never
 * rasterized pixels (headless test convention).</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
@DisplayName("EditorFrame renders all three editor modes (story 301, §2.1/§6.3)")
class EditorFrameRendersThreeModesTest {

    /** Two continuous parameters (non-integral defaults — knob cells, §6.2). */
    private static final PluginParameter GAIN =
            new PluginParameter(1, "Gain", 0.0, 1.0, 0.5);
    private static final PluginParameter MIX =
            new PluginParameter(2, "Mix", 0.0, 1.0, 0.25);

    /** Headless deps — every nullable service degraded (session tolerates it). */
    private static final PluginEditorSession.Deps DEPS =
            new PluginEditorSession.Deps(() -> 48_000.0, () -> null, null, null);

    private PluginEditorSession session;

    @AfterEach
    void disposeSession() {
        if (session != null) {
            PluginEditorSession doomed = session;
            session = null;
            runOnFxThread(() -> {
                doomed.dispose();
                return null;
            });
        }
    }

    // ── Declarative (§6.2) ────────────────────────────────────────────────

    @Test
    @DisplayName("Declarative factory mounts a host-generated parameter grid inside breadcrumb + footer chrome")
    void declarativeFactoryMountsParameterGridInsideChrome() {
        FakeDawPlugin plugin = new FakeDawPlugin("declarative",
                () -> new PluginEditorFactory.Declarative(
                        List.of(GAIN, MIX), EditorHints.compact()));

        session = openSession(plugin);
        EditorFrame frame = session.frame();
        realize(frame);

        assertThat(frame.lookup(".editor-frame-header")).isNotNull();
        assertThat(frame.lookup(".editor-frame-footer")).isNotNull();
        assertThat(frame.lookup(".editor-frame-breadcrumb")).isNotNull();

        assertThat(frame.getBody()).isInstanceOf(ParameterGridPanel.class);
        ParameterGridPanel grid = (ParameterGridPanel) frame.getBody();
        assertThat(grid.getChildrenUnmodifiable())
                .as("one grid cell per declared parameter")
                .hasSize(2);
    }

    // ── Panel (§6.3) ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Panel factory's region becomes the body and the body host is clipped")
    void panelFactoryRegionIsMountedAsBodyAndClippedByHost() {
        List<EditorContext> contexts = new ArrayList<>();
        AtomicReference<Region> stubRef = new AtomicReference<>();
        PluginEditorFactory.Panel factory = new PluginEditorFactory.Panel() {
            @Override
            public Region createPanel(EditorContext context) {
                contexts.add(context);
                Region stub = new Region();
                stubRef.set(stub);
                return stub;
            }
        };

        session = openSession(new FakeDawPlugin("panel", () -> factory));
        EditorFrame frame = session.frame();
        realize(frame);

        // createPanel ran exactly once, on the host's context (never null).
        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0)).isNotNull();

        // The plugin's own region is the mounted body, framed by the chrome.
        assertThat(frame.getBody()).isSameAs(stubRef.get());
        assertThat(frame.lookup(".editor-frame-header")).isNotNull();
        assertThat(frame.lookup(".editor-frame-footer")).isNotNull();

        // §6.3 — the body HOST is clipped so a misbehaving plugin cannot
        // paint over the breadcrumb or footer.
        Node bodyHost = frame.lookup(".editor-frame-body");
        assertThat(bodyHost).isNotNull();
        assertThat(bodyHost.getClip()).isNotNull();
        // Same node the skin exposes through its package-private seam.
        EditorFrameSkin skin = (EditorFrameSkin) frame.getSkin();
        assertThat(skin.bodyHost()).isSameAs(bodyHost);
        assertThat(skin.bodyHost().getClip()).isNotNull();
    }

    // ── Canvas (§5.D, §6.3) ───────────────────────────────────────────────

    @Test
    @DisplayName("Canvas factory receives attach then exactly one initial render, and detach on dispose")
    void canvasFactoryAttachesThenRendersAndDetachesOnDispose() {
        List<String> events = new ArrayList<>();
        AtomicReference<CanvasSurface> surfaceRef = new AtomicReference<>();
        PluginEditorFactory.Canvas factory = new PluginEditorFactory.Canvas() {
            @Override
            public void attach(CanvasSurface surface) {
                events.add("attach");
                surfaceRef.set(surface);
            }

            @Override
            public void render(RenderTick tick) {
                events.add("render");
            }

            @Override
            public void detach() {
                events.add("detach");
            }
        };

        session = openSession(new FakeDawPlugin("canvas", () -> factory));

        // open() synchronously attaches and performs one initial render
        // (§8.2 pipeline contract). Later pulses may only APPEND further
        // renders, so the prefix — not the whole list — is the invariant.
        assertThat(events).startsWith("attach", "render");

        // The captured host-owned surface is live and drawable.
        assertThat(surfaceRef.get()).isNotNull();
        GraphicsContext gc = runOnFxThread(() -> surfaceRef.get().graphicsContext());
        assertThat(gc).isNotNull();

        EditorFrame frame = session.frame();
        realize(frame);
        assertThat(frame.lookup(".editor-frame-header")).isNotNull();

        // dispose() best-effort-detaches the attached canvas factory.
        PluginEditorSession doomed = session;
        session = null;
        runOnFxThread(() -> {
            doomed.dispose();
            return null;
        });
        assertThat(events).contains("detach");
        assertThat(events.getLast())
                .as("detach is the final lifecycle event — no render after teardown")
                .isEqualTo("detach");
    }

    // ── Shared scaffolding ────────────────────────────────────────────────

    private static PluginEditorSession openSession(DawPlugin plugin) {
        return runOnFxThread(
                () -> PluginEditorSession.open(plugin, "Drum Bus / Insert 2", DEPS));
    }

    /**
     * Puts the frame in a {@link Scene} and forces CSS + layout so the skin
     * exists and {@code lookup(...)} works (content is not reachable until
     * the skin has run — known headless pitfall).
     */
    private static void realize(EditorFrame frame) {
        runOnFxThread(() -> {
            Scene scene = new Scene(new StackPane(frame), 900, 700);
            frame.applyCss();
            frame.layout();
            return scene;
        });
    }

    /**
     * Minimal story-300 contract plugin: per-instance descriptor, two
     * continuous parameters, lifecycle no-ops, the injected editor factory,
     * and the default {@code asAudioProcessor()} ({@code Optional.empty()}).
     */
    private static final class FakeDawPlugin implements DawPlugin {

        private final PluginDescriptor descriptor;
        private final Supplier<PluginEditorFactory> factory;

        FakeDawPlugin(String mode, Supplier<PluginEditorFactory> factory) {
            this.descriptor = new PluginDescriptor(
                    "com.test.story301.mode." + mode,
                    "Mode Test", "1.0.0", "Test Vendor", PluginType.EFFECT);
            this.factory = factory;
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void initialize(PluginContext context) {
            // lifecycle no-op
        }

        @Override
        public void activate() {
            // lifecycle no-op
        }

        @Override
        public void deactivate() {
            // lifecycle no-op
        }

        @Override
        public void dispose() {
            // lifecycle no-op
        }

        @Override
        public List<PluginParameter> getParameters() {
            return List.of(GAIN, MIX);
        }

        @Override
        public PluginEditorFactory editorFactory() {
            return factory.get();
        }
    }
}
