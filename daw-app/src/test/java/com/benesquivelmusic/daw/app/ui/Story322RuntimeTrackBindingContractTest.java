package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.controls.MixerChannelStrip;
import com.benesquivelmusic.daw.app.ui.vm.TrackVM;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.scene.layout.HBox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.flushFx;
import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.isActive;
import static com.benesquivelmusic.daw.app.ui.Story322ContractRig.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 contract (probe-derived, kept as a permanent guard for live registry reconciliation) — a track created AFTER the registry exists (on the FX
 * thread, and off it) gets live, bound controls in both surfaces; a lane
 * removed and re-added repeatedly re-binds against fresh VMs and one click
 * still flips exactly once.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class Story322RuntimeTrackBindingContractTest {

    private static final class World implements AutoCloseable {
        final DawProject project = new DawProject("Probe322", Story322ContractRig.FORMAT);
        final Track first = project.createAudioTrack("First");
        final Track second = project.createAudioTrack("Second");
        final Story322ContractRig rig = new Story322ContractRig(project);

        World() {
            rig.lane(first);
            rig.lane(second);
        }

        @Override
        public void close() {
            rig.close();
        }
    }

    @Test
    void aTrackCreatedOnTheFxThreadAfterTheRegistryExistsIsBoundInBothSurfaces() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                Track late = w.project.createAudioTrack("Late");
                MixerChannel chLate = w.project.getMixerChannelForTrack(late);
                chLate.setVolume(0.37);
                late.setVolume(0.37);

                HBox lane = w.rig.lane(late);
                var controls = w.rig.laneControls(late);
                assertThat(w.rig.controller.isBound(lane)).isTrue();
                assertThat(controls.muteBtn().isDisabled()).isFalse();
                assertThat(controls.volumeSlider().isDisabled()).isFalse();
                assertThat(controls.volumeSlider().getValue()).isEqualTo(0.37);

                w.rig.mixerView.refresh();
                assertThat(w.rig.mixerView.getTrackStrips()).hasSize(3);
                MixerChannelStrip strip = w.rig.strip(2);
                assertThat(strip.isDisabled()).isFalse();
                assertThat(strip.getFaderDb()).isCloseTo(20.0 * Math.log10(0.37), org.assertj.core.data.Offset.offset(1e-9));

                controls.muteBtn().fire();
                assertThat(late.isMuted()).isTrue();
                assertThat(chLate.isMuted()).isTrue();
                assertThat(strip.isMuted()).isTrue();

                strip.setPan(-0.62);
                assertThat(chLate.getPan()).isEqualTo(-0.62);
                assertThat(late.getPan()).isEqualTo(-0.62);
                assertThat(controls.panSlider().getValue()).isEqualTo(-0.62);
            }
        });
    }

    @Test
    void aTrackCreatedOffTheFxThreadBindsThroughTheReconcileFallbackAndKeepsItsVmAfterTheQueuedPass() throws Exception {
        AtomicReference<World> world = new AtomicReference<>();
        AtomicReference<Track> late = new AtomicReference<>();
        onFx(() -> world.set(new World()));
        try {
            Thread producer = new Thread(() -> late.set(world.get().project.createAudioTrack("Background")));
            producer.start();
            producer.join();
            UUID id = UUID.fromString(late.get().getId());

            AtomicReference<TrackVM> boundVm = new AtomicReference<>();
            onFx(() -> {
                World w = world.get();
                HBox lane = w.rig.lane(late.get());
                assertThat(w.rig.controller.isBound(lane)).as("bound via the reconcile fallback").isTrue();
                boundVm.set(w.rig.wiring.registry().trackVm(id));
                assertThat(boundVm.get()).isNotNull();
            });
            flushFx(); // the TRACKS signal's queued onFx reconcile runs now
            onFx(() -> {
                World w = world.get();
                assertThat(w.rig.wiring.registry().trackVm(id))
                        .as("the queued pass keeps the VM the lane bound to")
                        .isSameAs(boundVm.get());
                assertThat(w.rig.controller.isBound(w.rig.lane(late.get()))).isTrue();
                w.rig.laneControls(late.get()).soloBtn().fire();
                assertThat(late.get().isSolo()).isTrue();
                assertThat(w.project.getMixerChannelForTrack(late.get()).isSolo()).isTrue();
                assertThat(isActive(w.rig.laneControls(late.get()).soloBtn())).isTrue();
            });
        } finally {
            onFx(() -> world.get().close());
        }
    }

    @Test
    void aLaneRemovedAndReAddedFiveTimesRebindsAgainstFreshVmsAndOneClickFlipsExactlyOnce() throws Exception {
        onFx(() -> {
            try (World w = new World()) {
                Track late = w.project.createAudioTrack("Late");
                HBox lane = w.rig.lane(late);
                UUID id = UUID.fromString(late.getId());
                TrackVM original = w.rig.wiring.registry().trackVm(id);

                for (int round = 1; round <= 5; round++) {
                    assertThat(w.project.removeTrack(late)).isTrue();
                    w.rig.trackListPanel.getChildren().remove(lane);
                    assertThat(w.rig.controller.isBound(lane)).as("round %d: unbound on leaving", round).isFalse();
                    assertThat(w.rig.wiring.registry().trackVm(id)).as("round %d: VM disposed with the track", round).isNull();
                    w.project.addTrack(late);
                    w.rig.trackListPanel.getChildren().add(lane);
                    assertThat(w.rig.controller.isBound(lane)).as("round %d: re-bound", round).isTrue();
                    assertThat(w.rig.wiring.registry().trackVm(id)).isNotNull().isNotSameAs(original);
                }

                boolean was = late.isMuted();
                w.rig.laneControls(late).muteBtn().fire();
                assertThat(late.isMuted()).as("exactly one flip from one click").isEqualTo(!was);
                MixerChannel chLate = w.project.getMixerChannelForTrack(late);
                assertThat(chLate.isMuted()).isEqualTo(!was);
                assertThat(isActive(w.rig.laneControls(late).muteBtn())).isEqualTo(!was);
                w.rig.mixerView.refresh();
                assertThat(w.rig.strip(2).isMuted()).isEqualTo(!was);
                assertThat(w.rig.strip(2).isDisabled()).isFalse();
            }
        });
    }
}
