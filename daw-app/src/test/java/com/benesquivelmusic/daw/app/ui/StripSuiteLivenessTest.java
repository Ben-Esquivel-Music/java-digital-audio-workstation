package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.InsertSlotModel;
import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.controls.skin.MixerChannelStripSkin;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorDrawer;
import com.benesquivelmusic.daw.app.ui.inspector.InspectorSelection;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.TrackChannelRegistry;
import com.benesquivelmusic.daw.app.ui.vm.TrackControlWiring;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.LinkedTrackCommandDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.ToggleMuteCommand;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Story 322 — "Strip-suite liveness: {@code MixerChannelStrip} is
 * instantiated in production and the Inspector's selection-event listeners
 * fire on strip selection — no dead suite remains" (Audio Engine Wiring
 * Design Book §1.8 / §5.6; the story-271 migration completed as a pure skin
 * swap).
 *
 * <p>A production-shaped {@link MixerView} — the three-argument constructor,
 * a live {@link TrackControlWiring} supplier injected right after
 * construction, exactly as {@code MainController.handleProjectRebuild} and
 * {@code ViewNavigationController} do — is mounted under the root pane an
 * {@link InspectorDrawer#installSourceEventForwarding(Node)} filter is
 * installed on (what {@code MainController} does with its {@code rootPane}).
 * The test then proves, on one strip inside that view:</p>
 * <ul>
 *   <li>the view contains exactly one {@link MixerChannelStrip} per track,
 *       and no other {@code MixerChannelStrip} anywhere in its scene graph;</li>
 *   <li>a primary click on the strip's first insert row (the skin's real row
 *       node) bubbles an {@code INSERT_SELECTED} event up to the ancestor
 *       filter, which sets an {@link InspectorSelection.InsertSelection}
 *       carrying the strip's channel id;</li>
 *   <li>the strip is bound: a model volume / pan move reaches the strip's
 *       fader / pan, and a click on the skin's M toggle raises a
 *       {@link ToggleMuteCommand} through the wiring's sink, which dual-writes
 *       Track + MixerChannel;</li>
 *   <li>the solo-safe affordance survived the swap: the right-click "Solo
 *       safe" menu is installed on the skin's S toggle.</li>
 * </ul>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StripSuiteLivenessTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);
    private static final double EPS = 1e-9;

    @Test
    void productionMixerInstantiatesOneStripPerTrackAndTheInspectorHearsAStripInsertClick() throws Exception {
        DawProject project = new DawProject("Liveness", FORMAT);
        Track kick = project.createAudioTrack("Kick");
        Track snare = project.createAudioTrack("Snare");
        MixerChannel kickCh = project.getMixerChannelForTrack(kick);
        UUID kickId = UUID.fromString(kick.getId());
        // A real insert so the strip's first insert row is a clickable slot,
        // not an empty placeholder.
        kickCh.addInsert(new InsertSlot("Comp", passthrough()));

        ArrangementStripFixture.onFx(() -> {
            FxDispatcher dispatcher = new FxDispatcher();
            // MainController.rebuildTrackControlWiring(): a registry + the
            // production CoreTrackIntentHandler + LinkedTrackCommandDispatcher;
            // the sink is wrapped here only to record what the strip raises.
            TrackChannelRegistry registry = new TrackChannelRegistry(project, dispatcher);
            Consumer<TrackCommand> production =
                    new LinkedTrackCommandDispatcher(project, new CoreTrackIntentHandler(project));
            List<TrackCommand> raised = new ArrayList<>();
            TrackControlWiring wiring = new TrackControlWiring(registry, command -> {
                raised.add(command);
                production.accept(command);
            });
            UndoManager undo = new UndoManager();
            MixerView view = new MixerView(project, undo, dispatcher);
            view.setTrackControlWiring(() -> wiring);
            BorderPane rootPane = new BorderPane(view);
            InspectorDrawer drawer = new InspectorDrawer();
            drawer.installSourceEventForwarding(rootPane);
            Scene scene = new Scene(rootPane, 900, 600);
            try {
                rootPane.applyCss();
                rootPane.layout();

                // One MixerChannelStrip per track, and nothing else in the
                // view is one (return / master strips are not migrated).
                List<MixerChannelStrip> strips = new ArrayList<>();
                collectStrips(view, strips);
                assertThat(strips).as("one MixerChannelStrip per track").hasSize(project.getTracks().size());
                assertThat(view.getTrackStrips().stream().map(MixerView.TrackStripHandles::strip).toList())
                        .as("the view's own seam sees the same instances")
                        .containsExactlyElementsOf(strips);
                MixerChannelStrip kickStrip = strips.get(0);
                assertThat(kickStrip.getChannelId()).isEqualTo(kickId);
                assertThat(kickStrip.getChannelName()).isEqualTo("Kick");
                assertThat(kickStrip.insertsProperty()).extracting(InsertSlotModel::name).containsExactly("Comp");
                assertThat(scene.getRoot()).isSameAs(rootPane);

                // The skin exists (CSS pass) — the insert row is the real node.
                assertThat(kickStrip.getSkin()).isInstanceOf(MixerChannelStripSkin.class);
                MixerChannelStripSkin skin = (MixerChannelStripSkin) kickStrip.getSkin();
                Node insertRow = skin.insertRowNode(0);
                assertThat(insertRow).isNotNull();

                // A primary click on the row fires INSERT_SELECTED from the
                // strip; it bubbles to the ancestor filter the Inspector
                // installed on the root pane.
                assertThat(drawer.getSelectionModel().getSelection())
                        .as("nothing selected before the click (the model's empty sentinel)")
                        .isNotInstanceOf(InspectorSelection.InsertSelection.class);
                Event.fireEvent(insertRow, primaryClick());
                assertThat(drawer.getSelectionModel().getSelection())
                        .as("the Inspector's INSERT_SELECTED filter fired for the strip's channel")
                        .isEqualTo(new InspectorSelection.InsertSelection(kickId, 0));

                // Bound: model → strip (the VM applies an FX-thread signal inline).
                kickCh.setVolume(0.5);
                assertThat(kickStrip.getFaderDb()).as("model volume reaches the strip fader (dB)")
                        .isCloseTo(20.0 * Math.log10(0.5), within(EPS));
                kickCh.setPan(-0.25);
                assertThat(kickStrip.getPan()).as("model pan reaches the strip").isEqualTo(-0.25);

                // Bound: strip M click → ToggleMuteCommand through the wiring.
                assertThat(kick.isMuted()).isFalse();
                skin.muteButton().fire();
                assertThat(raised).as("the strip's M toggle raised the intent")
                        .containsExactly(new ToggleMuteCommand(kick, true));
                assertThat(kick.isMuted()).as("Track dual-written").isTrue();
                assertThat(kickCh.isMuted()).as("MixerChannel dual-written").isTrue();
                assertThat(kickStrip.isMuted()).as("strip mirrors the VM").isTrue();
                assertThat(strips.get(1).isMuted()).as("the other strip is untouched").isFalse();

                // The solo-safe menu lives on the skin's S toggle.
                assertThat(skin.soloButton().getContextMenu()).as("Solo safe menu on the S toggle").isNotNull();
                assertThat(skin.soloButton().getContextMenu().getItems())
                        .anySatisfy(item -> {
                            assertThat(item).isInstanceOf(CheckMenuItem.class);
                            assertThat(item.getText()).isEqualTo("Solo safe");
                        });
            } finally {
                view.dispose();
                wiring.dispose();
                dispatcher.dispose();
            }
        });
    }

    private static void collectStrips(Parent parent, List<MixerChannelStrip> into) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof MixerChannelStrip strip) {
                into.add(strip);
            } else if (n instanceof Parent p) {
                collectStrips(p, into);
            }
        }
    }

    private static MouseEvent primaryClick() {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
    }

    /** A stereo pass-through insert. */
    private static AudioProcessor passthrough() {
        return new AudioProcessor() {
            @Override
            public void process(float[][] in, float[][] out, int numFrames) {
                for (int ch = 0; ch < Math.min(in.length, out.length); ch++) {
                    System.arraycopy(in[ch], 0, out[ch], 0, numFrames);
                }
            }

            @Override
            public void reset() {
            }

            @Override
            public int getInputChannelCount() {
                return 2;
            }

            @Override
            public int getOutputChannelCount() {
                return 2;
            }
        };
    }
}
