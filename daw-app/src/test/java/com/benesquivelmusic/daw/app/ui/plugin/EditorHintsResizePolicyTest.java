package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.sdk.editor.EditorContext;
import com.benesquivelmusic.daw.sdk.editor.EditorHints;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.editor.ResizePolicy;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 301 acceptance — {@link EditorHints#resize()} enforcement (Plugin
 * View Design Book §6.3): a {@link ResizePolicy#FIXED} hint yields a
 * non-resizable plugin body, {@link ResizePolicy#BOTH} a resizable one, and
 * the HOST enforces the policy — the plugin cannot override it.
 *
 * <p>Two layers are pinned: the pure, statically-testable
 * {@link EditorFrame#applyResizePolicy} min/pref/max mapping for all four
 * policies, and the end-to-end path through
 * {@link PluginEditorSession#open} where the hints a {@code Panel} factory
 * declares are applied to the plugin's own region by the frame skin.
 * Assertions target only the min/pref/max size-constraint properties —
 * never rendered geometry. All FX work runs through the capture-and-rethrow
 * {@code FxSnapshotTest.runOnFxThread} helper.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
@DisplayName("EditorHints resize policy is host-enforced (story 301, §6.3)")
class EditorHintsResizePolicyTest {

    private static final double PREF_W = 400.0;
    private static final double PREF_H = 300.0;

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

    // ── applyResizePolicy — the pure min/pref/max mapping ─────────────────

    @Test
    @DisplayName("FIXED pins min == pref == max on both axes")
    void fixedPinsMinPrefMaxOnBothAxes() {
        Region body = applyToFreshRegion(ResizePolicy.FIXED);

        assertThat(body.getMinWidth()).isEqualTo(PREF_W);
        assertThat(body.getPrefWidth()).isEqualTo(PREF_W);
        assertThat(body.getMaxWidth()).isEqualTo(PREF_W);
        assertThat(body.getMinHeight()).isEqualTo(PREF_H);
        assertThat(body.getPrefHeight()).isEqualTo(PREF_H);
        assertThat(body.getMaxHeight()).isEqualTo(PREF_H);
    }

    @Test
    @DisplayName("HORIZONTAL pins the height and frees the width")
    void horizontalPinsHeightAndFreesWidth() {
        Region body = applyToFreshRegion(ResizePolicy.HORIZONTAL);

        assertThat(body.getMinHeight()).isEqualTo(PREF_H);
        assertThat(body.getPrefHeight()).isEqualTo(PREF_H);
        assertThat(body.getMaxHeight()).isEqualTo(PREF_H);
        assertThat(body.getMinWidth()).isEqualTo(Region.USE_COMPUTED_SIZE);
        assertThat(body.getPrefWidth()).isEqualTo(PREF_W);
        assertThat(body.getMaxWidth()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("VERTICAL pins the width and frees the height (mirror of HORIZONTAL)")
    void verticalPinsWidthAndFreesHeight() {
        Region body = applyToFreshRegion(ResizePolicy.VERTICAL);

        assertThat(body.getMinWidth()).isEqualTo(PREF_W);
        assertThat(body.getPrefWidth()).isEqualTo(PREF_W);
        assertThat(body.getMaxWidth()).isEqualTo(PREF_W);
        assertThat(body.getMinHeight()).isEqualTo(Region.USE_COMPUTED_SIZE);
        assertThat(body.getPrefHeight()).isEqualTo(PREF_H);
        assertThat(body.getMaxHeight()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("BOTH sets the preferred size and frees max on both axes")
    void bothSetsPreferredSizeAndFreesMaxOnBothAxes() {
        Region body = applyToFreshRegion(ResizePolicy.BOTH);

        assertThat(body.getPrefWidth()).isEqualTo(PREF_W);
        assertThat(body.getPrefHeight()).isEqualTo(PREF_H);
        assertThat(body.getMaxWidth()).isEqualTo(Double.MAX_VALUE);
        assertThat(body.getMaxHeight()).isEqualTo(Double.MAX_VALUE);
        assertThat(body.getMinWidth()).isEqualTo(Region.USE_COMPUTED_SIZE);
        assertThat(body.getMinHeight()).isEqualTo(Region.USE_COMPUTED_SIZE);
    }

    // ── Through the session — the host applies the plugin's own hints ─────

    @Test
    @DisplayName("a FIXED hint through the session pins the plugin's own region non-resizable")
    void fixedHintThroughSessionPinsThePluginRegion() {
        AtomicReference<Region> stubRef = new AtomicReference<>();
        session = openSession(hintedPanel(ResizePolicy.FIXED, stubRef));
        EditorFrame frame = session.frame();
        realize(frame);

        // The hints landed on the frame…
        assertThat(frame.getResizePolicy()).isEqualTo(ResizePolicy.FIXED);
        assertThat(frame.getPreferredBodyWidth()).isEqualTo(PREF_W);
        assertThat(frame.getPreferredBodyHeight()).isEqualTo(PREF_H);

        // …and the host enforced them on the plugin's OWN region.
        Region stub = stubRef.get();
        assertThat(frame.getBody()).isSameAs(stub);
        assertThat(stub.getMinWidth()).isEqualTo(PREF_W);
        assertThat(stub.getPrefWidth()).isEqualTo(PREF_W);
        assertThat(stub.getMaxWidth()).isEqualTo(PREF_W);
        assertThat(stub.getMinHeight()).isEqualTo(PREF_H);
        assertThat(stub.getPrefHeight()).isEqualTo(PREF_H);
        assertThat(stub.getMaxHeight()).isEqualTo(PREF_H);
    }

    @Test
    @DisplayName("a BOTH hint through the session leaves the plugin region freely resizable")
    void bothHintThroughSessionLeavesThePluginRegionResizable() {
        AtomicReference<Region> stubRef = new AtomicReference<>();
        session = openSession(hintedPanel(ResizePolicy.BOTH, stubRef));
        EditorFrame frame = session.frame();
        realize(frame);

        assertThat(frame.getResizePolicy()).isEqualTo(ResizePolicy.BOTH);
        Region stub = stubRef.get();
        assertThat(frame.getBody()).isSameAs(stub);
        assertThat(stub.getPrefWidth()).isEqualTo(PREF_W);
        assertThat(stub.getPrefHeight()).isEqualTo(PREF_H);
        assertThat(stub.getMaxWidth()).isEqualTo(Double.MAX_VALUE);
        assertThat(stub.getMaxHeight()).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("the plugin cannot override enforcement: the skin re-pins the region on every policy change")
    void pluginCannotOverrideHostEnforcement() {
        AtomicReference<Region> stubRef = new AtomicReference<>();
        session = openSession(hintedPanel(ResizePolicy.FIXED, stubRef));
        EditorFrame frame = session.frame();
        realize(frame);
        Region stub = stubRef.get();

        runOnFxThread(() -> {
            // The plugin tries to fight the host…
            stub.setMaxWidth(123.0);
            // …and the skin's resize-policy listener re-applies the mapping
            // synchronously on every policy change (it also re-applies on
            // preferred-size and body changes).
            frame.setResizePolicy(ResizePolicy.VERTICAL);
            frame.setResizePolicy(ResizePolicy.FIXED);
            return null;
        });

        assertThat(stub.getMaxWidth())
                .as("host re-pinned the max width the plugin tried to override")
                .isEqualTo(PREF_W);
        assertThat(stub.getMinWidth()).isEqualTo(PREF_W);
        assertThat(stub.getPrefWidth()).isEqualTo(PREF_W);
        assertThat(stub.getMinHeight()).isEqualTo(PREF_H);
        assertThat(stub.getPrefHeight()).isEqualTo(PREF_H);
        assertThat(stub.getMaxHeight()).isEqualTo(PREF_H);
    }

    // ── Shared scaffolding ────────────────────────────────────────────────

    /** Applies {@code policy} at 400 × 300 to a fresh plain region, on the FX thread. */
    private static Region applyToFreshRegion(ResizePolicy policy) {
        return runOnFxThread(() -> {
            Region body = new Region();
            EditorFrame.applyResizePolicy(body, policy, PREF_W, PREF_H);
            return body;
        });
    }

    /**
     * A {@code Panel}-mode plugin whose factory declares
     * {@code standard().withResize(policy).withSize(400, 300)} and returns a
     * stub region, captured through {@code stubRef}.
     */
    private static DawPlugin hintedPanel(ResizePolicy policy,
            AtomicReference<Region> stubRef) {
        PluginEditorFactory.Panel factory = new PluginEditorFactory.Panel() {
            @Override
            public Region createPanel(EditorContext context) {
                Region stub = new Region();
                stubRef.set(stub);
                return stub;
            }

            @Override
            public EditorHints hints() {
                return EditorHints.standard()
                        .withResize(policy)
                        .withSize((int) PREF_W, (int) PREF_H);
            }
        };
        return new FakeDawPlugin("resize." + policy.name().toLowerCase(java.util.Locale.ROOT),
                () -> factory);
    }

    private static PluginEditorSession openSession(DawPlugin plugin) {
        return runOnFxThread(
                () -> PluginEditorSession.open(plugin, "Drum Bus / Insert 2", DEPS));
    }

    /**
     * Puts the frame in a {@link Scene} and forces CSS + layout so the skin
     * exists and enforcement (which lives in the skin) has run — content is
     * not reachable until the skin has run (known headless pitfall).
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
