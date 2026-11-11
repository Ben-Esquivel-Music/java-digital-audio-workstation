package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.mixer.OfflineInsert;
import com.benesquivelmusic.daw.core.mixer.SavedInsert;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.Border;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Shape;
import javafx.stage.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334, studio-engineer ruling E22 (javafx-application-design skill §8
 * "CSS styling and theming"; UI Design Book §3.1 "tokens by role, never by
 * hex"): an offline insert's state mark is on the warn role and its name on
 * text-mute, through style classes and role tokens only. Each surface is
 * resolved in a real scene under the theme, re-tinted to values no fallback
 * holds ({@code MixerChannelStripInsertDotPaintTest}'s idiom), so a broken
 * forward or a wrong class cannot hide behind an equal hex; sibling probes
 * styled with the tokens give the expected colours
 * ({@code LockStatusIndicatorStyleTest}'s idiom). The rack's warn glyph, an
 * SVG with no CSS hook of its own, is tinted from its mark's resolved text
 * fill — when CSS first resolves it and on a rebuild inside a scene.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class OfflineInsertStyleTest {

    /** Re-tints the two roles to values neither Palette A nor a component fallback holds. */
    private static final String RETINT = "-warn: #0011FF; -text-mute: #FFAA00;";

    /** Re-tints the focus ring to a value neither Palette A nor the Inspector's fallback holds. */
    private static final String RING_RETINT = " -focus-ring: #00CC66;";

    /** The paints the rack resolved, read on the FX thread. */
    record RackPaints(Paint warn, Paint mute, Paint mark, List<Paint> glyph, Paint name, String nameStyle,
                      List<Paint> rebuiltGlyph) {
        RackPaints {
            Objects.requireNonNull(warn, "warn must not be null");
            Objects.requireNonNull(mute, "mute must not be null");
            Objects.requireNonNull(mark, "mark must not be null");
            glyph = List.copyOf(glyph);
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(nameStyle, "nameStyle must not be null");
            rebuiltGlyph = List.copyOf(rebuiltGlyph);
        }
    }

    /** The paints the strip, the badge and the Inspector row resolved, read on the FX thread. */
    record SurfacePaints(Paint warn, Paint mute, Paint stripDot, Paint badge, double badgeOpacity,
                         Paint inspectorDot, Paint inspectorWord) {
        SurfacePaints {
            Objects.requireNonNull(warn, "warn must not be null");
            Objects.requireNonNull(mute, "mute must not be null");
            Objects.requireNonNull(stripDot, "stripDot must not be null");
            Objects.requireNonNull(badge, "badge must not be null");
            Objects.requireNonNull(inspectorDot, "inspectorDot must not be null");
            Objects.requireNonNull(inspectorWord, "inspectorWord must not be null");
        }
    }

    @Test
    void theMarkUsesWarnAndTheNameTextMute() {
        RackPaints paints = runOnFxThread(() -> {
            MixerChannel channel = new MixerChannel("Vox");
            channel.addInsert(offlineSlot());
            InsertEffectRack rack = new InsertEffectRack(channel, 2, 48_000.0, 256, null, null, () -> true);
            try {
                Region warnProbe = probe("-warn");
                Region muteProbe = probe("-text-mute");
                resolve(new HBox(rack, warnProbe, muteProbe));
                Labeled mark = (Labeled) rack.lookup(".insert-slot-offline-mark");
                Label name = (Label) rack.lookup(".insert-slot-offline-name");
                List<Paint> glyph = glyphPaints(mark.getGraphic());

                rack.rebuildSlots();
                Labeled rebuiltMark = (Labeled) rack.lookup(".insert-slot-offline-mark");
                assertThat(rebuiltMark).as("the rebuild made a new mark").isNotSameAs(mark);
                return new RackPaints(fill(warnProbe), fill(muteProbe), mark.getTextFill(), glyph,
                        name.getTextFill(), Objects.requireNonNullElse(name.getStyle(), ""),
                        glyphPaints(rebuiltMark.getGraphic()));
            } finally {
                rack.dispose();
            }
        });

        assertThat(paints.warn()).as("the probe resolved the re-tinted -warn").isEqualTo(Color.web("#0011FF"));
        assertThat(paints.mark()).as("the mark's text fill is the warn role").isEqualTo(paints.warn());
        assertThat(paints.glyph()).as("the glyph is tinted to the mark's resolved fill, never the SVG's colour")
                .isNotEmpty().containsOnly(paints.warn());
        assertThat(paints.rebuiltGlyph()).as("and so is a glyph a rebuild in the scene made")
                .isNotEmpty().containsOnly(paints.warn());
        assertThat(paints.mute()).as("the probe resolved the re-tinted -text-mute").isEqualTo(Color.web("#FFAA00"));
        assertThat(paints.name()).as("the name is muted: the insert does not process").isEqualTo(paints.mute());
        assertThat(paints.nameStyle()).as("no inline colour on the new node")
                .doesNotContain("-fx-text-fill").doesNotContainPattern("#[0-9a-fA-F]{3,8}");
    }

    @Test
    void theStripDotTheBadgeAndTheInspectorRowUseTheirRoleTokens() {
        SurfacePaints paints = runOnFxThread(() -> {
            MixerChannelStrip strip = new MixerChannelStrip();
            strip.insertsProperty().setAll(offlineModel());
            HBox badges = new HBox();
            TrackStripController.renderInsertIndicators(badges, List.of(offlineModel()));
            MixerChannel channel = new MixerChannel("Vox");
            channel.addInsert(offlineSlot());
            FxDispatcher dispatcher = new FxDispatcher();
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            drawer.bindInserts(() -> channel, (host, slot) -> { }, host -> { }, dispatcher);
            try {
                Region warnProbe = probe("-warn");
                Region muteProbe = probe("-text-mute");
                resolve(new HBox(strip, badges, drawer, warnProbe, muteProbe));
                Circle dot = descendants(((MixerChannelStripSkin) strip.getSkin()).insertRowNode(0))
                        .filter(Circle.class::isInstance).map(Circle.class::cast).findFirst().orElseThrow();
                Label badge = (Label) badges.getChildren().getFirst();
                HBox row = (HBox) drawer.getInsertsSection().getRowsContainer().getChildren().getFirst();
                Label inspectorDot = (Label) row.getChildren().getFirst();
                Labeled word = (Labeled) row.getChildren().getLast();
                return new SurfacePaints(fill(warnProbe), fill(muteProbe), dot.getFill(), badge.getTextFill(),
                        badge.getOpacity(), inspectorDot.getTextFill(), word.getTextFill());
            } finally {
                drawer.disposeInsertBinding();
                dispatcher.dispose();
            }
        });

        assertThat(paints.warn()).isEqualTo(Color.web("#0011FF"));
        assertThat(paints.mute()).isEqualTo(Color.web("#FFAA00"));
        assertThat(paints.stripDot()).as("the strip's dot: -mcs-warn, forwarded from -warn").isEqualTo(paints.warn());
        assertThat(paints.badge()).as("the track-header badge: -warn").isEqualTo(paints.warn());
        assertThat(paints.badgeOpacity()).as("offline wins over the stored bypass: not dimmed as bypassed")
                .isEqualTo(1.0);
        assertThat(paints.inspectorDot()).as("the Inspector's dot: -insp-warn, forwarded from -warn")
                .isEqualTo(paints.warn());
        assertThat(paints.inspectorWord()).as("the Inspector's Offline word: -insp-text-mute")
                .isEqualTo(paints.mute());
    }

    /**
     * Ruling R-N1: the rack's warn mark and the Inspector's word are their
     * rows' keyboard stops, buttons that keep their surface's role and size —
     * never the application's {@code .button}, which would repaint and resize
     * them — and show the house focus ring only when focused, moving nothing.
     * Mounted in a shown stage, where a node can hold the focus; the ring is
     * re-tinted too, so the Inspector's forwarded ring cannot hide behind its
     * fallback's equal hex.
     */
    @Test
    void theOfflineMarksKeepTheirRolesAndShowTheFocusRingOnlyWhenFocused() {
        runOnFxThread(() -> {
            MixerChannel channel = new MixerChannel("Vox");
            channel.addInsert(offlineSlot());
            InsertEffectRack rack = new InsertEffectRack(channel, 2, 48_000.0, 256, null, null, () -> true);
            FxDispatcher dispatcher = new FxDispatcher();
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.setAnimated(false);
            drawer.bindInserts(() -> channel, (host, slot) -> { }, host -> { }, dispatcher);
            Stage stage = new Stage();
            try {
                Region warnProbe = probe("-warn");
                Region muteProbe = probe("-text-mute");
                Region ringProbe = new Region();
                ringProbe.setStyle("-fx-border-color: -focus-ring; -fx-border-width: 1;");
                HBox root = new HBox(rack, drawer, warnProbe, muteProbe, ringProbe);
                resolve(root);
                root.setStyle(RETINT + RING_RETINT);
                stage.setScene(root.getScene());
                stage.show();
                rack.rebuildSlots();
                root.applyCss();
                root.layout();

                Paint warn = fill(warnProbe);
                Paint mute = fill(muteProbe);
                assertThat(warn).as("the probe resolved the re-tinted -warn").isEqualTo(Color.web("#0011FF"));
                assertThat(mute).as("the probe resolved the re-tinted -text-mute").isEqualTo(Color.web("#FFAA00"));
                assertThat(topStroke(ringProbe)).as("the probe resolved the re-tinted -focus-ring")
                        .contains(Color.web("#00CC66"));

                Labeled mark = (Labeled) rack.lookup(".insert-slot-offline-mark");
                assertThat(mark.getTextFill()).as("the rack mark's fill is the warn role").isEqualTo(warn);
                assertThat(glyphPaints(mark.getGraphic())).as("its glyph, made by a rebuild in the scene, is tinted to it")
                        .isNotEmpty().containsOnly(warn);
                assertThat(mark.getWidth()).as("the width of the bypass toggle it stands for").isEqualTo(12.0);
                assertThat(mark.getHeight()).as("and its height").isEqualTo(14.0);
                assertThat(mark.getStyleClass()).doesNotContain("button");

                HBox row = (HBox) drawer.getInsertsSection().getRowsContainer().getChildren().getFirst();
                Labeled word = (Labeled) row.getChildren().getLast();
                Labeled name = (Labeled) row.getChildren().get(1);
                assertThat(word.getTextFill()).as("the Inspector's word stays on -insp-text-mute").isEqualTo(mute);
                assertThat(word.getFont()).as("in its row's type").isEqualTo(name.getFont());
                assertThat(word.getStyleClass()).doesNotContain("button");

                drawer.requestFocus();
                root.applyCss();
                for (Labeled stop : List.of(mark, word)) {
                    assertThat(stop.isFocused()).as("fixture: %s is at rest", stop).isFalse();
                    Insets resting = stop.getInsets();
                    assertThat(topStroke(stop)).as("at rest the border is transparent").contains(Color.TRANSPARENT);
                    stop.requestFocus();
                    root.applyCss();
                    assertThat(stop.isFocused()).as("fixture: %s holds the focus in the shown stage", stop).isTrue();
                    assertThat(topStroke(stop)).as("focused, the house focus ring")
                            .isEqualTo(topStroke(ringProbe));
                    assertThat(stop.getInsets()).as("the ring moves nothing").isEqualTo(resting);
                }
            } finally {
                stage.hide();
                drawer.disposeInsertBinding();
                dispatcher.dispose();
                rack.dispose();
            }
            return null;
        });
    }

    /** An insert an open kept offline because the build has no effect type {@code NOT_A_TYPE}, saved bypassed. */
    private static InsertSlot offlineSlot() {
        SequencedMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("bypassed", "true");
        attributes.put("effect-type", "NOT_A_TYPE");
        attributes.put("name", "Old FX");
        InsertSlot slot = InsertSlot.ofOffline("Old FX", 2, new OfflineInsert(
                new OfflineCause.UnknownEffectType("NOT_A_TYPE"), new SavedInsert(attributes, List.of()), false));
        slot.setBypassed(true);
        return slot;
    }

    /** What the channel's view-model maps {@link #offlineSlot()} to. */
    private static InsertSlotModel offlineModel() {
        return new InsertSlotModel("Old FX", false, true, true, "Old FX, offline");
    }

    private static Region probe(String token) {
        Region probe = new Region();
        probe.setStyle("-fx-background-color: " + token + ";");
        return probe;
    }

    /** Mounts {@code root} as the themed, re-tinted root of a scene and resolves its CSS. FX thread. */
    private static void resolve(HBox root) {
        root.getStyleClass().add("root-pane");
        Scene scene = new Scene(root, 900, 600);
        ThemeManager.getDefault().applyTo(scene);
        root.setStyle(RETINT);
        root.applyCss();
        root.layout();
        // A second pass reaches content a first pass's skins added (the Inspector's scroll pane).
        root.applyCss();
        root.layout();
    }

    private static Paint fill(Region probe) {
        return probe.getBackground().getFills().getFirst().getFill();
    }

    /** The paint of {@code region}'s top border stroke, or empty when it has no border. */
    private static Optional<Paint> topStroke(Region region) {
        Border border = region.getBorder();
        return border == null || border.getStrokes().isEmpty()
                ? Optional.empty() : Optional.of(border.getStrokes().getFirst().getTopStroke());
    }

    /** Every paint the glyph's shapes show: each stroke, and each fill that is not transparent. */
    private static List<Paint> glyphPaints(Node glyph) {
        List<Paint> paints = new ArrayList<>();
        descendants(glyph).filter(Shape.class::isInstance).map(Shape.class::cast).forEach(shape -> {
            if (shape.getStroke() != null) {
                paints.add(shape.getStroke());
            }
            if (shape.getFill() != null && !Color.TRANSPARENT.equals(shape.getFill())) {
                paints.add(shape.getFill());
            }
        });
        return paints;
    }

    private static Stream<Node> descendants(Node node) {
        if (node instanceof Parent parent) {
            return Stream.concat(Stream.of(node),
                    parent.getChildrenUnmodifiable().stream().flatMap(OfflineInsertStyleTest::descendants));
        }
        return Stream.of(node);
    }
}
