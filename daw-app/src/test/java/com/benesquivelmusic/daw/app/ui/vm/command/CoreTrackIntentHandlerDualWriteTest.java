package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.event.BusEvent;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.MixerEvent;
import com.benesquivelmusic.daw.sdk.event.TrackEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Story 322 — {@link CoreTrackIntentHandler} dual-writes every mirrored fact:
 * {@code setVolume}/{@code setPan} now update the paired {@code Track} as well
 * as the {@code MixerChannel} (Audio Engine Wiring Design Book §2.10 "the UI
 * writes the model the engine reads; any mirrored model is updated in the same
 * dual-write"), a standalone channel stays channel-only, and the new
 * channel-targeted mute/solo intents run VALIDATE → MUTATE → ANNOUNCE. No
 * JavaFX involved.
 *
 * <p>ANNOUNCE assertions: the bus delivers on a per-subscription worker, so a
 * single {@link BusEvent}-typed subscription collects the events and a marker
 * event published through the <em>same</em> subscription (FIFO) proves that
 * everything published before it has been delivered — including for the
 * "nothing announced" cases.</p>
 */
class CoreTrackIntentHandlerDualWriteTest {

    private static final UUID MARKER = UUID.randomUUID();

    private DefaultEventBus bus;
    private EventBus.Subscription subscription;
    private final List<BusEvent> announced = new CopyOnWriteArrayList<>();

    @BeforeEach
    void installBus() {
        bus = new DefaultEventBus();
        EventBusPublisher.setDefault(bus);
        subscription = bus.on(BusEvent.class, DispatchMode.ON_CALLER_THREAD, event -> {
            if (isRelevant(event) || isMarker(event)) {
                announced.add(event);
            }
        });
    }

    @AfterEach
    void clearBus() {
        subscription.close();
        EventBusPublisher.setDefault(null);
        bus.close();
    }

    private static boolean isRelevant(BusEvent event) {
        return event instanceof MixerEvent.GainChanged
                || event instanceof MixerEvent.PanChanged
                || event instanceof MixerEvent.MuteChanged
                || event instanceof MixerEvent.SoloChanged
                || event instanceof TrackEvent.Muted
                || event instanceof TrackEvent.Soloed
                || event instanceof TrackEvent.Renamed;
    }

    private static boolean isMarker(BusEvent event) {
        return event instanceof MixerEvent.GainChanged g && g.channelId().equals(MARKER);
    }

    /** Publishes a marker and waits until it (and so everything before it) has been delivered. */
    private void awaitDelivery() {
        EventBusPublisher.publish(new MixerEvent.GainChanged(MARKER, Instant.now()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (announced.stream().noneMatch(CoreTrackIntentHandlerDualWriteTest::isMarker)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("bus delivery timed out");
            }
            Thread.onSpinWait();
        }
        announced.removeIf(CoreTrackIntentHandlerDualWriteTest::isMarker);
    }

    @Test
    void renameTrackStripsTheNameWritesTheTrackAndAnnouncesRenamed() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        handler.renameTrack(track, "  Kick  ");
        awaitDelivery();

        assertThat(track.getName()).as("stripped and written to the Track").isEqualTo("Kick");
        assertThat(announced).hasSize(1).first().isInstanceOf(TrackEvent.Renamed.class);
        assertThat(((TrackEvent.Renamed) announced.getFirst()).trackId())
                .isEqualTo(UUID.fromString(track.getId()));
    }

    @Test
    void renameTrackRejectsABlankNameAndIgnoresAnUnchangedOne() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        assertThatIllegalArgumentException().isThrownBy(() -> handler.renameTrack(track, "   "));
        handler.renameTrack(track, " Drums ");
        awaitDelivery();

        assertThat(track.getName()).isEqualTo("Drums");
        assertThat(announced).as("neither a rejected nor an idempotent rename announces").isEmpty();
    }

    @Test
    void renameTrackCommandOwnsTheStripRuleAndKnowsWhenItChangesNothing() {
        // Story 322 fix round 2 (R2-2): the command's normalisation is the
        // ONE rule the handler's VALIDATE applies, so a surface (the mixer's
        // undo recording) can ask what a rename would do without repeating it.
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");

        RenameTrackCommand padded = new RenameTrackCommand(track, "  Drums ");
        assertThat(padded.normalizedName()).isEqualTo("Drums");
        assertThat(padded.changesNothing()).as("whitespace-only: VALIDATE no-ops").isTrue();

        RenameTrackCommand rename = new RenameTrackCommand(track, " Kick\t");
        assertThat(rename.normalizedName()).isEqualTo("Kick");
        assertThat(rename.changesNothing()).isFalse();

        RenameTrackCommand blank = new RenameTrackCommand(track, "   ");
        assertThat(blank.normalizedName()).isEmpty();
        assertThat(blank.changesNothing()).as("blank is refused, not a no-op").isFalse();

        assertThat(RenameTrackCommand.normalize("  Snare ")).isEqualTo("Snare");
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        rename.execute(handler);
        assertThat(track.getName()).as("the handler wrote exactly normalizedName()").isEqualTo(rename.normalizedName());
        assertThat(rename.changesNothing()).as("re-raising the same command is now a no-op").isTrue();
    }

    @Test
    void setVolumeWritesTheChannelAndItsPairedTrackAndAnnouncesOnce() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        handler.setVolume(channel, 0.42);
        awaitDelivery();

        assertThat(channel.getVolume()).as("engine").isEqualTo(0.42);
        assertThat(track.getVolume()).as("arrangement mirror — the dead Track-only write is gone").isEqualTo(0.42);
        assertThat(announced).hasSize(1);
        assertThat(announced.getFirst()).isInstanceOf(MixerEvent.GainChanged.class);
        assertThat(((MixerEvent.GainChanged) announced.getFirst()).channelId()).isEqualTo(channel.getId());
    }

    @Test
    void setPanWritesTheChannelAndItsPairedTrack() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        handler.setPan(channel, -0.6);
        awaitDelivery();

        assertThat(channel.getPan()).isEqualTo(-0.6);
        assertThat(track.getPan()).isEqualTo(-0.6);
        assertThat(announced).hasSize(1).first().isInstanceOf(MixerEvent.PanChanged.class);
    }

    @Test
    void aStandaloneChannelIsChannelOnly() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel returnBus = project.getMixer().getReturnBuses().get(0);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        handler.setVolume(returnBus, 0.35);
        handler.setPan(returnBus, 0.8);
        awaitDelivery();

        assertThat(returnBus.getVolume()).isEqualTo(0.35);
        assertThat(returnBus.getPan()).isEqualTo(0.8);
        assertThat(track.getVolume()).as("no track was touched").isEqualTo(1.0);
        assertThat(track.getPan()).isEqualTo(0.0);
        assertThat(announced).hasSize(2);
    }

    @Test
    void aDivergedTrackIsHealedByATrackOnlyWriteThatAnnouncesNoMixerEvent() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        channel.setVolume(0.5);
        track.setVolume(0.9); // legacy direct write: the pair is split
        channel.setPan(-0.25);
        track.setPan(0.75);

        handler.setVolume(channel, 0.5); // the channel already matches
        handler.setPan(channel, -0.25);
        awaitDelivery();

        assertThat(channel.getVolume()).isEqualTo(0.5);
        assertThat(track.getVolume()).as("VALIDATE per surface let the Track heal").isEqualTo(0.5);
        assertThat(channel.getPan()).isEqualTo(-0.25);
        assertThat(track.getPan()).isEqualTo(-0.25);
        assertThat(announced)
                .as("the bus fact is the CHANNEL's gain/pan, which did not move — a Track-only heal "
                        + "must not put a phantom MixerEvent on the bus (story 322 fix round, N12)")
                .isEmpty();
    }

    @Test
    void aTrueNoOpNeitherMutatesNorAnnounces() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);
        handler.setVolume(channel, 0.42);
        awaitDelivery();
        announced.clear();

        handler.setVolume(channel, 0.42);
        handler.setPan(channel, 0.0);
        handler.toggleChannelMute(channel, false);
        handler.toggleChannelSolo(channel, false);
        awaitDelivery();

        assertThat(announced).isEmpty();
    }

    @Test
    void anOutOfRangeVolumeIsRejectedBeforeTheTrackIsTouched() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        assertThatIllegalArgumentException().isThrownBy(() -> handler.setVolume(channel, 1.5));
        awaitDelivery();

        assertThat(channel.getVolume()).isEqualTo(1.0);
        assertThat(track.getVolume()).isEqualTo(1.0);
        assertThat(announced).isEmpty();
    }

    @Test
    void toggleChannelMuteOnAReturnBusMutatesAndAnnouncesTheMixerEvent() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerChannel returnBus = project.getMixer().getReturnBuses().get(0);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        new ToggleChannelMuteCommand(returnBus, true).execute(handler);
        awaitDelivery();

        assertThat(returnBus.isMuted()).isTrue();
        assertThat(announced).hasSize(1).first().isInstanceOf(MixerEvent.MuteChanged.class);
        MixerEvent.MuteChanged event = (MixerEvent.MuteChanged) announced.getFirst();
        assertThat(event.channelId()).isEqualTo(returnBus.getId());
        assertThat(event.muted()).isTrue();

        announced.clear();
        new ToggleChannelMuteCommand(returnBus, true).execute(handler); // idempotent
        awaitDelivery();
        assertThat(returnBus.isMuted()).isTrue();
        assertThat(announced).isEmpty();
    }

    @Test
    void toggleChannelSoloOnTheMasterMutatesAndAnnounces() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        MixerChannel master = project.getMixer().getMasterChannel();
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        new ToggleChannelSoloCommand(master, true).execute(handler);
        awaitDelivery();

        assertThat(master.isSolo()).isTrue();
        assertThat(announced).hasSize(1).first().isInstanceOf(MixerEvent.SoloChanged.class);
        assertThat(((MixerEvent.SoloChanged) announced.getFirst()).soloed()).isTrue();
    }

    @Test
    void toggleChannelMuteOnATrackChannelMirrorsOntoTheTrackToo() {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        Track track = project.createAudioTrack("Drums");
        MixerChannel channel = project.getMixerChannelForTrack(track);
        TrackIntentHandler handler = new CoreTrackIntentHandler(project);

        handler.toggleChannelMute(channel, true);
        handler.toggleChannelSolo(channel, true);
        awaitDelivery();

        assertThat(channel.isMuted()).isTrue();
        assertThat(track.isMuted()).as("§2.10: the mirrored model is written in the same place").isTrue();
        assertThat(channel.isSolo()).isTrue();
        assertThat(track.isSolo()).isTrue();
        assertThat(announced).hasSize(4);
        assertThat(announced).anyMatch(MixerEvent.MuteChanged.class::isInstance);
        assertThat(announced).anyMatch(TrackEvent.Muted.class::isInstance);
        assertThat(announced).anyMatch(MixerEvent.SoloChanged.class::isInstance);
        assertThat(announced).anyMatch(TrackEvent.Soloed.class::isInstance);
    }
}
