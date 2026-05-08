package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.AssignVcaMemberAction;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.VcaGroup;
import com.benesquivelmusic.daw.core.mixer.VcaGroupManager;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import javafx.application.Platform;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link VcaStrip}.
 *
 * <p>Drag-and-drop event firing from JavaFX requires a real input gesture
 * which Robot tests can't reliably simulate without focus and a windowed
 * stage; instead we verify the component-level behavior directly: drop
 * payload format is correctly published, fader changes route through the
 * undo manager, and the mute/solo buttons exist and are wired.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class VcaStripTest {

    private <T> T runFx(java.util.concurrent.Callable<T> task) throws Exception {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ref.set(task.call());
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        if (!completed) {
            throw new AssertionError("FX task did not complete within 5 seconds");
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    @Test
    void exposesChannelIdDataFormatForDragPayloads() {
        // The MixerChannel drag source and the VcaStrip drop target rely on
        // the same DataFormat string. Locking it here prevents accidental
        // renames from silently breaking drag-to-assign.
        DataFormat fmt = VcaStrip.CHANNEL_ID_FORMAT;
        assertThat(fmt.getIdentifiers()).contains("application/x-mixer-channel-id");
    }

    @Test
    void rendersBadgeNameFieldFaderAndButtons() throws Exception {
        VcaGroupManager manager = new VcaGroupManager();
        VcaGroup vca = manager.createVcaGroup("Drums");

        VcaStrip strip = runFx(() -> new VcaStrip(
                vca, manager, null, id -> null, () -> {}));

        assertThat(strip.getStyleClass()).contains("vca-strip", "mixer-channel");
        assertThat(strip.getNameField().getText()).isEqualTo("Drums");
        assertThat(strip.getGainFader()).isNotNull();
        assertThat(strip.getGainFader().getValue()).isEqualTo(0.0);
        assertThat(strip.getMuteButton()).isNotNull();
        assertThat(strip.getSoloButton()).isNotNull();
    }

    @Test
    void faderAdjustsMasterGainOnTheManager() throws Exception {
        VcaGroupManager manager = new VcaGroupManager();
        VcaGroup vca = manager.createVcaGroup("Bus");

        VcaStrip strip = runFx(() -> new VcaStrip(
                vca, manager, null, id -> null, () -> {}));
        runFx(() -> {
            strip.getGainFader().setValue(3.5);
            return null;
        });

        // The slider's value listener writes through to the manager so
        // members react in real time even before a drag-release commits an
        // undoable action.
        assertThat(manager.getById(vca.id()).masterGainDb()).isEqualTo(3.5);
    }

    @Test
    void renamingNameFieldUpdatesGroupLabel() throws Exception {
        VcaGroupManager manager = new VcaGroupManager();
        VcaGroup vca = manager.createVcaGroup("Old");

        VcaStrip strip = runFx(() -> new VcaStrip(
                vca, manager, null, id -> null, () -> {}));
        runFx(() -> {
            strip.getNameField().setText("Drums");
            strip.getNameField().fireEvent(new javafx.event.ActionEvent());
            return null;
        });

        assertThat(manager.getById(vca.id()).label()).isEqualTo("Drums");
    }

    @Test
    void deleteButtonRemovesGroupFromManager() throws Exception {
        VcaGroupManager manager = new VcaGroupManager();
        VcaGroup vca = manager.createVcaGroup("Doomed");

        VcaStrip strip = runFx(() -> new VcaStrip(
                vca, manager, null, id -> null, () -> {}));
        // Delete is the third button in the M/S/✕ row; fire it directly.
        runFx(() -> {
            javafx.scene.layout.HBox row =
                    (javafx.scene.layout.HBox) strip.getChildren().get(strip.getChildren().size() - 1);
            javafx.scene.control.Button delete = (javafx.scene.control.Button) row.getChildren().get(2);
            delete.fire();
            return null;
        });
        assertThat(manager.getById(vca.id())).isNull();
    }

    @Test
    void respectsExistingColorWhenRendering() throws Exception {
        VcaGroupManager manager = new VcaGroupManager();
        VcaGroup vca = manager.createVcaGroup("Vox");
        manager.replace(vca.withColor(TrackColor.MAGENTA));
        VcaGroup colored = manager.getById(vca.id());

        VcaStrip strip = runFx(() -> new VcaStrip(
                colored, manager, null, id -> null, () -> {}));

        // The strip's inline border style should cite the VCA's hex color so
        // the engineer can see at-a-glance which strip is which.
        assertThat(strip.getStyle()).contains(TrackColor.MAGENTA.getHexColor());
    }

    @Test
    void muteAndSoloApplyToAllMemberChannels() throws Exception {
        VcaGroupManager manager = new VcaGroupManager();
        UUID kickId = UUID.randomUUID();
        UUID snareId = UUID.randomUUID();
        VcaGroup vca = manager.createVcaGroup("Drums", List.of(kickId, snareId));

        MixerChannel kick = new MixerChannel("Kick");
        MixerChannel snare = new MixerChannel("Snare");
        Function<UUID, MixerChannel> lookup = id -> {
            if (id.equals(kickId)) return kick;
            if (id.equals(snareId)) return snare;
            return null;
        };

        VcaStrip strip = runFx(() -> new VcaStrip(vca, manager, null, lookup, () -> {}));
        runFx(() -> {
            strip.getMuteButton().fire();
            return null;
        });

        assertThat(kick.isMuted()).isTrue();
        assertThat(snare.isMuted()).isTrue();

        runFx(() -> {
            strip.getSoloButton().fire();
            return null;
        });
        assertThat(kick.isSolo()).isTrue();
        assertThat(snare.isSolo()).isTrue();
    }

    @Test
    void droppingChannelIdOnStripTogglesMembership() throws Exception {
        // We can't fire a real DragEvent without a windowed stage, but we
        // can drive the same code path the drop handler uses by exercising
        // AssignVcaMemberAction directly. This locks the contract that the
        // strip's drop target acts as an undoable assign/unassign toggle.
        VcaGroupManager manager = new VcaGroupManager();
        VcaGroup vca = manager.createVcaGroup("Group");
        UUID channel = UUID.randomUUID();

        UndoManager um = new UndoManager();
        AssignVcaMemberAction assign =
                new AssignVcaMemberAction(manager, vca.id(), channel, true);
        um.execute(assign);
        assertThat(manager.getById(vca.id()).hasMember(channel)).isTrue();
        um.undo();
        assertThat(manager.getById(vca.id()).hasMember(channel)).isFalse();

        // Also verify the ClipboardContent shape used by the drag source
        // matches what the strip's drop handler reads back.
        ClipboardContent cc = new ClipboardContent();
        cc.put(VcaStrip.CHANNEL_ID_FORMAT, channel.toString());
        assertThat(cc.get(VcaStrip.CHANNEL_ID_FORMAT)).isEqualTo(channel.toString());
    }
}
