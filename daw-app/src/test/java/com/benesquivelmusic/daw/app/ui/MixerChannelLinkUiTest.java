package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.ChannelLink;
import com.benesquivelmusic.daw.core.mixer.ChannelLinkManager;
import com.benesquivelmusic.daw.core.mixer.LinkMode;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Slider;
import javafx.scene.layout.VBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headless JavaFX coverage for the Story 159 mixer channel-link UI:
 * chain-glyph link toggles between adjacent strips, fader / pan / mute /
 * solo propagation in both {@link LinkMode}s, the link-detail
 * {@link ChannelLinkPopover}, and the L/R badge under linked strip names.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class MixerChannelLinkUiTest {

    private static <T> T fxGet(Supplier<T> action) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(action.get());
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action timed out");
        }
        return ref.get();
    }

    private static void fxRun(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action timed out");
        }
    }

    private record Fixture(DawProject project, MixerView view,
                           Track left, Track right,
                           UUID leftId, UUID rightId,
                           MixerChannel leftCh, MixerChannel rightCh) { }

    private static Fixture makeFixture() throws Exception {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track left  = project.createAudioTrack("Left");
        Track right = project.createAudioTrack("Right");
        UndoManager undo = new UndoManager();
        MixerView view = fxGet(() -> new MixerView(project, undo));
        UUID leftId  = UUID.fromString(left.getId());
        UUID rightId = UUID.fromString(right.getId());
        return new Fixture(project, view, left, right, leftId, rightId,
                project.getMixerChannelForTrack(left),
                project.getMixerChannelForTrack(right));
    }

    private static Button findLinkToggleBetween(MixerView view) {
        // The link toggle is wrapped in a small VBox spliced between
        // adjacent .mixer-channel strips; the wrapper carries a
        // LinkTogglePair as user data.
        for (Node n : view.getChannelStrips().getChildren()) {
            if (n.getUserData() instanceof MixerView.LinkTogglePair) {
                VBox box = (VBox) n;
                for (Node c : box.getChildren()) {
                    if (c instanceof Button b) {
                        return b;
                    }
                }
            }
        }
        throw new AssertionError("No link toggle found between strips");
    }

    private static Slider findVolumeFader(MixerView view, int stripIndex) {
        int seen = -1;
        for (Node n : view.getChannelStrips().getChildren()) {
            if (n.getStyleClass().contains("mixer-channel")) {
                seen++;
                if (seen == stripIndex) {
                    return findFirst((VBox) n, Slider.class,
                            s -> s.getOrientation() == javafx.geometry.Orientation.VERTICAL);
                }
            }
        }
        throw new AssertionError("No strip at index " + stripIndex);
    }

    private static Slider findPanSlider(MixerView view, int stripIndex) {
        int seen = -1;
        for (Node n : view.getChannelStrips().getChildren()) {
            if (n.getStyleClass().contains("mixer-channel")) {
                seen++;
                if (seen == stripIndex) {
                    return findFirst((VBox) n, Slider.class,
                            s -> s.getOrientation() == javafx.geometry.Orientation.HORIZONTAL);
                }
            }
        }
        throw new AssertionError("No strip at index " + stripIndex);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T findFirst(javafx.scene.Parent root, Class<T> type,
                                                java.util.function.Predicate<T> pred) {
        for (Node n : root.getChildrenUnmodifiable()) {
            if (type.isInstance(n) && pred.test((T) n)) {
                return (T) n;
            }
            if (n instanceof javafx.scene.Parent p) {
                T r = findFirst(p, type, pred);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Linking via the chain glyph
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void clickingLinkToggleCreatesPairAndMirrorsVolumes() throws Exception {
        Fixture f = makeFixture();

        // Pre-condition: not linked, two distinct volumes.
        f.leftCh.setVolume(0.4);
        f.rightCh.setVolume(0.9);
        fxRun(f.view::refresh);

        Button toggle = fxGet(() -> findLinkToggleBetween(f.view));
        fxRun(toggle::fire);

        // Link is now registered.
        ChannelLink link = f.project.getChannelLinkManager().getLink(f.leftId);
        assertThat(link).isNotNull();
        assertThat(link.involves(f.rightId)).isTrue();
        assertThat(link.linkFaders()).isTrue();
        assertThat(link.linkPans()).isTrue();
        assertThat(link.linkMuteSolo()).isTrue();
        assertThat(link.linkInserts()).isFalse();
        assertThat(link.linkSends()).isFalse();
        assertThat(link.mode()).isEqualTo(LinkMode.RELATIVE);

        // Move A's fader: B should follow (RELATIVE: shifted by delta).
        Slider leftFader  = fxGet(() -> findVolumeFader(f.view, 0));
        Slider rightFader = fxGet(() -> findVolumeFader(f.view, 1));
        double oldLeft = f.leftCh.getVolume();
        double oldRight = f.rightCh.getVolume();
        fxRun(() -> leftFader.setValue(0.5));
        double delta = 0.5 - oldLeft;
        assertThat(f.rightCh.getVolume()).isEqualTo(clamp(oldRight + delta));
        assertThat(rightFader.getValue()).isEqualTo(f.rightCh.getVolume());
    }

    @Test
    void clickingLinkToggleAgainUnlinksAndPreservesValues() throws Exception {
        Fixture f = makeFixture();

        Button toggle = fxGet(() -> findLinkToggleBetween(f.view));
        fxRun(toggle::fire);                     // link
        // After link, the toggle node was rebuilt by the listener — re-find.
        Button toggle2 = fxGet(() -> findLinkToggleBetween(f.view));

        // Set distinct values, then unlink.
        Slider leftFader = fxGet(() -> findVolumeFader(f.view, 0));
        fxRun(() -> leftFader.setValue(0.6));
        double leftAfter  = f.leftCh.getVolume();
        double rightAfter = f.rightCh.getVolume();

        fxRun(toggle2::fire);                    // unlink
        assertThat(f.project.getChannelLinkManager().isLinked(f.leftId)).isFalse();
        // Both retain their last values per the manager contract.
        assertThat(f.leftCh.getVolume()).isEqualTo(leftAfter);
        assertThat(f.rightCh.getVolume()).isEqualTo(rightAfter);
    }

    @Test
    void togglingLinkFadersOffStopsVolumePropagation() throws Exception {
        Fixture f = makeFixture();
        // Link the pair.
        fxRun(() -> f.project.getChannelLinkManager().link(
                ChannelLink.ofPair(f.leftId, f.rightId)));

        // Replace with a link that has linkFaders=false.
        ChannelLink original = f.project.getChannelLinkManager().getLink(f.leftId);
        fxRun(() -> f.project.getChannelLinkManager()
                .replace(original.withLinkFaders(false)));

        Slider leftFader  = fxGet(() -> findVolumeFader(f.view, 0));
        double rightBefore = f.rightCh.getVolume();
        fxRun(() -> leftFader.setValue(0.25));
        // linkFaders is off — partner should NOT have moved.
        assertThat(f.rightCh.getVolume()).isEqualTo(rightBefore);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pan mirror — both modes mirror around centre per current core API.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void panMirrorsAroundCentreInRelativeMode() throws Exception {
        Fixture f = makeFixture();
        f.leftCh.setPan(0.0);
        f.rightCh.setPan(0.0);
        fxRun(() -> f.project.getChannelLinkManager().link(
                ChannelLink.ofPair(f.leftId, f.rightId))); // RELATIVE by default
        fxRun(f.view::refresh);

        Slider leftPan = fxGet(() -> findPanSlider(f.view, 0));
        fxRun(() -> leftPan.setValue(-0.3));
        assertThat(f.rightCh.getPan()).isEqualTo(0.3, within(1e-9));
    }

    @Test
    void panMirrorsAroundCentreInAbsoluteMode() throws Exception {
        Fixture f = makeFixture();
        f.leftCh.setPan(0.0);
        f.rightCh.setPan(0.0);
        fxRun(() -> {
            ChannelLinkManager m = f.project.getChannelLinkManager();
            m.link(new ChannelLink(f.leftId, f.rightId, LinkMode.ABSOLUTE,
                    true, true, true, false, false));
        });
        fxRun(f.view::refresh);

        Slider leftPan = fxGet(() -> findPanSlider(f.view, 0));
        fxRun(() -> leftPan.setValue(-0.3));
        assertThat(f.rightCh.getPan()).isEqualTo(0.3, within(1e-9));
    }

    // ─────────────────────────────────────────────────────────────────────
    // L/R badge rendering
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void renderingExposesLAndRBadgesUnderLinkedStrips() throws Exception {
        Fixture f = makeFixture();
        fxRun(() -> f.project.getChannelLinkManager().link(
                ChannelLink.ofPair(f.leftId, f.rightId)));
        fxRun(f.view::refresh);

        VBox leftStrip  = (VBox) firstStripsOnly(f.view).get(0);
        VBox rightStrip = (VBox) firstStripsOnly(f.view).get(1);

        assertThat(stripBadgeTexts(leftStrip)).contains("L");
        assertThat(stripBadgeTexts(rightStrip)).contains("R");
    }

    private static List<Node> firstStripsOnly(MixerView view) {
        return view.getChannelStrips().getChildren().stream()
                .filter(n -> n.getStyleClass().contains("mixer-channel"))
                .toList();
    }

    private static List<String> stripBadgeTexts(VBox strip) {
        return strip.getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof javafx.scene.control.Label)
                .map(n -> ((javafx.scene.control.Label) n).getText())
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ChannelLinkPopover
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void popoverReplacesLinkOnToggle() throws Exception {
        Fixture f = makeFixture();
        ChannelLinkManager mgr = f.project.getChannelLinkManager();
        fxRun(() -> mgr.link(ChannelLink.ofPair(f.leftId, f.rightId)));

        ChannelLinkPopover popover = fxGet(() ->
                new ChannelLinkPopover(mgr, new UndoManager(), mgr.getLink(f.leftId)));

        fxRun(() -> {
            popover.getInsertsBox().setSelected(false);
            popover.getInsertsBox().getOnAction().handle(
                    new javafx.event.ActionEvent());
        });
        ChannelLink updated = mgr.getLink(f.leftId);
        assertThat(updated.linkInserts()).isFalse();

        fxRun(() -> {
            popover.getModeCombo().getSelectionModel().select(LinkMode.ABSOLUTE);
            popover.getModeCombo().getOnAction().handle(
                    new javafx.event.ActionEvent());
        });
        assertThat(mgr.getLink(f.leftId).mode()).isEqualTo(LinkMode.ABSOLUTE);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }
}
