package com.benesquivelmusic.daw.app.ui.controls;

import com.benesquivelmusic.daw.app.ui.DarkThemeHelper;
import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the story-271 themability contract (§2.5 — "themability is a
 * stylesheet swap, not a rewrite") end-to-end through the REAL application
 * {@code styles.css} cascade.
 *
 * <p>The six story-mandated MixerChannelStrip tests all attach the default
 * {@code DarkThemeHelper} theme, whose Palette&nbsp;A token values are
 * <em>identical</em> to the control's user-agent fallback hex. A circular
 * looked-up-colour drop (the exact trap caught on story 267 — a same-named
 * {@code -x: -x;} forward is flagged circular and silently falls back to
 * the UA initial value) would therefore be <strong>invisible</strong> in
 * every one of those tests. This test re-tints the {@code .root-pane}
 * role tokens to values <em>different</em> from the UA fallback so a broken
 * {@code -mcs-*} forward cannot hide. Story 277 (theme picker) and story
 * 272 (Inspector themability) reuse this cascade — it is a downstream
 * contract, so this probe is a permanent regression guard
 * (mirrors {@code LevelMeterAppThemeCascadeTest}).
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelStripAppThemeCascadeTest {

    private final List<MixerChannelStrip> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        runOnFxThread(() -> {
            for (MixerChannelStrip s : created) {
                if (s.getSkin() != null) {
                    s.setSkin(null);
                }
            }
            created.clear();
            return null;
        });
    }

    private MixerChannelStrip newStrip() {
        MixerChannelStrip s = runOnFxThread(MixerChannelStrip::new);
        created.add(s);
        return s;
    }

    @Test
    void defaultAppThemeResolvesToPaletteAValues() {
        MixerChannelStrip s = newStrip();
        Color[] c = runOnFxThread(() -> {
            StackPane root = new StackPane(s);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 88, 600);
            DarkThemeHelper.applyTo(scene);
            root.applyCss();
            root.layout();
            return new Color[] {
                    s.getSurface1(), s.getSurfaceBg(),
                    s.getAccent(), s.getTextMute(), s.getDanger()
            };
        });
        // These resolve via the distinctly-named styles.css forward
        // (.mixer-channel-strip { -mcs-x: -x; }); a circular drop would
        // instead yield the UA fallback (here equal — that's WHY the
        // re-tint tests below matter).
        assertThat(c[0]).as("-mcs-surface-1 ← -surface-1").isEqualTo(Color.web("#15161B"));
        assertThat(c[1]).as("-mcs-surface-bg ← -surface-bg (§5.4 gutter)").isEqualTo(Color.web("#0B0B0E"));
        assertThat(c[2]).as("-mcs-accent ← -accent").isEqualTo(Color.web("#7C8CFF"));
        assertThat(c[3]).as("-mcs-text-mute ← -text-mute").isEqualTo(Color.web("#7A808C"));
        assertThat(c[4]).as("-mcs-danger ← -danger").isEqualTo(Color.web("#E5484D"));
    }

    @Test
    void reTintingRootPaneTokensReachesTheStripViaTheStylesCssForward() {
        MixerChannelStrip s = newStrip();
        Color[] c = runOnFxThread(() -> {
            StackPane root = new StackPane(s);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 88, 600);
            DarkThemeHelper.applyTo(scene);
            // Simulate a story-277 theme re-tinting the role tokens on
            // .root-pane to values distinct from the UA fallback hex.
            root.setStyle("-accent: #0011FF; -text-mute: #FFAA00; "
                    + "-surface-bg: #112233; -danger: #00CC44;");
            root.applyCss();
            root.layout();
            return new Color[] {
                    s.getAccent(), s.getTextMute(),
                    s.getSurfaceBg(), s.getDanger()
            };
        });
        assertThat(c[0])
                .as("re-tinting -accent on .root-pane must reach -mcs-accent "
                        + "via the distinctly-named styles.css forward (NOT a "
                        + "circular looked-up colour that drops to the UA fallback)")
                .isEqualTo(Color.web("#0011FF"));
        assertThat(c[1])
                .as("re-tinting -text-mute must reach -mcs-text-mute")
                .isEqualTo(Color.web("#FFAA00"));
        assertThat(c[2])
                .as("re-tinting -surface-bg must reach -mcs-surface-bg (gutter)")
                .isEqualTo(Color.web("#112233"));
        assertThat(c[3])
                .as("re-tinting -danger must reach -mcs-danger")
                .isEqualTo(Color.web("#00CC44"));
    }

    @Test
    void stripColoursCanAlsoBeReTintedOnTheControlSelector() {
        // Plugin GUIs / themes that prefer to scope the override to the
        // control can set the internal -mcs-* names directly on
        // .mixer-channel-strip (the supported theming entry point — same
        // as story 267's .level-meter direct re-tint path).
        MixerChannelStrip s = newStrip();
        Color[] c = runOnFxThread(() -> {
            StackPane root = new StackPane(s);
            root.getStyleClass().add("root-pane");
            Scene scene = new Scene(root, 88, 600);
            DarkThemeHelper.applyTo(scene);
            scene.getStylesheets().add("data:text/css;base64,"
                    + java.util.Base64.getEncoder().encodeToString(
                            (".mixer-channel-strip { -mcs-accent: #0011FF; "
                                    + "-mcs-text-mute: #FFAA00; }")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            root.applyCss();
            root.layout();
            return new Color[] {s.getAccent(), s.getTextMute()};
        });
        assertThat(c[0]).isEqualTo(Color.web("#0011FF"));
        assertThat(c[1]).isEqualTo(Color.web("#FFAA00"));
    }
}
