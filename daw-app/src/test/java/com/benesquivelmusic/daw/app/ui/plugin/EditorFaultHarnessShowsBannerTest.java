package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story 301 acceptance — the §2.7 fault harness (Plugin View Design Book
 * §2.7, §6.6): a {@link PluginEditorFactory.Panel} whose {@code createPanel}
 * throws never propagates out of {@link PluginEditorSession#open}. The frame
 * shows the inline fault banner, the session swaps in the
 * {@link PluginEditorFactory.Faulted}-style placeholder body, and the user
 * always sees an editor — never a void. The banner's {@code [ⓘ]} action
 * routes to the host fault log, and {@code [Reload]} re-runs the whole
 * factory pipeline, recovering a plugin that succeeds on a later attempt.
 *
 * <p>The tests assert via the banner's style class, visibility and
 * accessible text — the plugin's exception is never caught by the test
 * itself. All FX work runs through the capture-and-rethrow
 * {@code FxSnapshotTest.runOnFxThread} helper.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
@DisplayName("Editor fault harness shows the banner, never a void (story 301, §2.7/§6.6)")
class EditorFaultHarnessShowsBannerTest {

    /** Two continuous parameters (non-integral defaults — knob cells, §6.2). */
    private static final PluginParameter GAIN =
            new PluginParameter(1, "Gain", 0.0, 1.0, 0.5);
    private static final PluginParameter MIX =
            new PluginParameter(2, "Mix", 0.0, 1.0, 0.25);

    /**
     * Headless deps. The fault supervisor supplier deliberately yields
     * {@code null}: the harness must tolerate a missing supervisor while
     * reporting a fault (asserted implicitly by every open below completing).
     */
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

    // ── createPanel throws → banner + placeholder, never a void (§6.6) ────

    @Test
    @DisplayName("a throwing createPanel does not propagate; the frame shows the fault banner and a placeholder body")
    void throwingCreatePanelShowsBannerAndPlaceholderWithoutPropagating() {
        DawPlugin plugin = new FakeDawPlugin("fault.panel", () -> new ThrowingPanel());

        // The plugin's exception must be swallowed by the host harness — the
        // test never catches it itself (a null faultSupervisor supplier
        // result must not throw either; see DEPS).
        assertThatCode(() -> session = openSession(plugin))
                .doesNotThrowAnyException();

        EditorFrame frame = session.frame();
        realize(frame);

        // Banner text names the failing phase and the exception type.
        assertThat(frame.getFaultMessage())
                .isNotBlank()
                .contains("createPanel")
                .contains(IllegalStateException.class.getSimpleName());

        // The banner node itself: present, visible, and carrying the fault
        // message as its accessible text (§6.6 test-visible seam).
        Node banner = frame.lookup(".editor-fault-banner");
        assertThat(banner).isNotNull();
        assertThat(banner.isVisible()).isTrue();
        assertThat(banner.getAccessibleText()).isEqualTo(frame.getFaultMessage());

        // Never a void: the body was substituted with the §6.6 placeholder.
        assertThat(frame.getBody()).isNotNull();
        assertThat(frame.lookup(".editor-fault-placeholder")).isNotNull();
    }

    // ── Banner [ⓘ] routes to the host fault log (§6.6) ────────────────────

    @Test
    @DisplayName("the banner's fault-details action routes to the deps' openPluginFaultLog")
    void faultDetailsCallbackRoutesToOpenPluginFaultLog() {
        AtomicInteger routed = new AtomicInteger();
        PluginEditorSession.Deps deps = new PluginEditorSession.Deps(
                () -> 48_000.0, () -> null, routed::incrementAndGet, null);

        session = runOnFxThread(() -> PluginEditorSession.open(
                new FakeDawPlugin("fault.details", () -> new ThrowingPanel()),
                "Drum Bus / Insert 2", deps));
        EditorFrame frame = session.frame();

        assertThat(frame.getOnFaultDetailsRequested()).isNotNull();
        runOnFxThread(() -> {
            frame.getOnFaultDetailsRequested().run();
            return null;
        });

        assertThat(routed).hasValue(1);
    }

    // ── [Reload] recovery (§6.6) ──────────────────────────────────────────

    @Test
    @DisplayName("Reload re-runs the factory pipeline: a first-attempt fault recovers to the real panel")
    void reloadRecoversAPanelThatSucceedsOnTheSecondAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Region> stubRef = new AtomicReference<>();
        PluginEditorFactory.Panel flaky = new PluginEditorFactory.Panel() {
            @Override
            public Region createPanel(EditorContext context) {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("boom in panel");
                }
                Region stub = new Region();
                stubRef.set(stub);
                return stub;
            }
        };

        session = openSession(new FakeDawPlugin("fault.reload", () -> flaky));
        EditorFrame frame = session.frame();
        realize(frame);

        // First attempt faulted: banner shown, placeholder body.
        assertThat(frame.getFaultMessage()).isNotBlank();
        Node banner = frame.lookup(".editor-fault-banner");
        assertThat(banner).isNotNull();
        assertThat(banner.isVisible()).isTrue();
        assertThat(frame.lookup(".editor-fault-placeholder")).isNotNull();

        // §6.6 [Reload] — re-runs the whole pipeline on the FX thread.
        runOnFxThread(() -> {
            frame.getOnReloadRequested().run();
            return null;
        });

        assertThat(attempts).hasValue(2);
        assertThat(frame.getFaultMessage()).isBlank();
        assertThat(banner.isVisible()).isFalse();
        assertThat(frame.getBody())
                .as("the recovered body is the plugin's real region")
                .isSameAs(stubRef.get());
        assertThat(frame.lookup(".editor-fault-placeholder")).isNull();
    }

    // ── Shared scaffolding ────────────────────────────────────────────────

    /** The §2.7 misbehaving plugin editor: {@code createPanel} always throws. */
    private static final class ThrowingPanel implements PluginEditorFactory.Panel {
        @Override
        public Region createPanel(EditorContext context) {
            throw new IllegalStateException("boom in panel");
        }
    }

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
