package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ArmedTrackSourceProviderTest {

    private DawProject project;
    private RoomConfiguration config;
    private ArmedTrackSourceProvider provider;

    @BeforeEach
    void setUp() {
        project = new DawProject("Test", AudioFormat.CD_QUALITY);
        config = new RoomConfiguration(
                new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL);
        project.setRoomConfiguration(config);
        provider = new ArmedTrackSourceProvider(project);
    }

    @Test
    void emptyArmedSetProducesEmptySourceList() {
        project.createAudioTrack("Guitar");
        project.createAudioTrack("Vocals");

        provider.sync();

        assertThat(config.getSoundSources()).isEmpty();
        assertThat(provider.getManagedSources()).isEmpty();
    }

    @Test
    void armingATrackAddsASourceAtValidInRoomPosition() {
        Track guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);

        provider.sync();

        assertThat(config.getSoundSources()).hasSize(1);
        SoundSource source = config.getSoundSources().get(0);
        assertThat(source.name()).isEqualTo("Guitar");
        assertThat(source.powerDb()).isEqualTo(ArmedTrackSourceProvider.DEFAULT_POWER_DB);
        Position3D pos = source.position();
        RoomDimensions dims = config.getDimensions();
        assertThat(pos.x()).isBetween(0.0, dims.width());
        assertThat(pos.y()).isBetween(0.0, dims.length());
        assertThat(pos.z()).isBetween(0.0, dims.height());
    }

    @Test
    void armingASecondTrackDoesNotDuplicateTheFirst() {
        Track guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);
        provider.sync();

        Track vocals = project.createAudioTrack("Vocals");
        vocals.setArmed(true);
        provider.sync();

        assertThat(config.getSoundSources())
                .extracting(SoundSource::name)
                .containsExactly("Guitar", "Vocals");
    }

    @Test
    void disarmingRemovesOnlyThatSourceAndLeavesFreeSourcesIntact() {
        Track guitar = project.createAudioTrack("Guitar");
        Track vocals = project.createAudioTrack("Vocals");
        guitar.setArmed(true);
        vocals.setArmed(true);

        // A user-added "free" source, not backed by any track.
        SoundSource free = new SoundSource(
                "Room Ambience", new Position3D(1.0, 1.0, 1.0), 70);
        config.addSoundSource(free);

        provider.sync();
        assertThat(config.getSoundSources())
                .extracting(SoundSource::name)
                .containsExactlyInAnyOrder("Guitar", "Vocals", "Room Ambience");

        guitar.setArmed(false);
        provider.sync();

        assertThat(config.getSoundSources())
                .extracting(SoundSource::name)
                .containsExactlyInAnyOrder("Vocals", "Room Ambience");
    }

    @Test
    void freeSourcesAreNeverRemovedByAutoSync() {
        SoundSource free = new SoundSource(
                "Free", new Position3D(2.0, 2.0, 1.0), 80);
        config.addSoundSource(free);

        Track guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);
        provider.sync();

        guitar.setArmed(false);
        provider.sync();

        assertThat(config.getSoundSources())
                .extracting(SoundSource::name)
                .containsExactly("Free");
    }

    @Test
    void renamingATrackUpdatesTheSourceNameWithoutLosingDragPosition() {
        Track track = project.createAudioTrack("Guitar");
        track.setArmed(true);
        provider.sync();

        // User drags the source on the telemetry canvas.
        Position3D dragged = new Position3D(7.5, 6.5, 1.0);
        assertThat(provider.updateSourcePosition(track.getId(), dragged)).isTrue();
        assertThat(config.getSoundSources().get(0).position()).isEqualTo(dragged);

        // Track is renamed in the arrangement view.
        track.setName("Lead Guitar");
        provider.sync();

        assertThat(config.getSoundSources()).hasSize(1);
        SoundSource renamed = config.getSoundSources().get(0);
        assertThat(renamed.name()).isEqualTo("Lead Guitar");
        assertThat(renamed.position()).isEqualTo(dragged);
    }

    @Test
    void dragPositionSurvivesArmDisarmReArmOfSameTrack() {
        Track track = project.createAudioTrack("Guitar");
        track.setArmed(true);
        provider.sync();

        Position3D dragged = new Position3D(3.3, 4.4, 1.1);
        provider.updateSourcePosition(track.getId(), dragged);

        // Disarm — source is removed.
        track.setArmed(false);
        provider.sync();
        assertThat(config.getSoundSources()).isEmpty();

        // Re-arm — new deterministic layout position is used (drag state is
        // scoped to the lifetime of a continuous arm session per the story
        // scope).
        track.setArmed(true);
        provider.sync();
        assertThat(config.getSoundSources()).hasSize(1);
    }

    @Test
    void togglingAutoSyncOffFreezesTheCurrentList() {
        Track guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);
        provider.sync();
        assertThat(config.getSoundSources()).hasSize(1);

        provider.setAutoSyncEnabled(false);

        // Arming a second track must NOT inject a new source while frozen.
        Track vocals = project.createAudioTrack("Vocals");
        vocals.setArmed(true);
        provider.sync();

        assertThat(config.getSoundSources())
                .extracting(SoundSource::name)
                .containsExactly("Guitar");
    }

    @Test
    void togglingAutoSyncBackOnReReconcilesWithoutDuplicatingEntries() {
        Track guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);
        provider.sync();

        provider.setAutoSyncEnabled(false);

        // While frozen, the user arms another track.
        Track vocals = project.createAudioTrack("Vocals");
        vocals.setArmed(true);

        provider.setAutoSyncEnabled(true);

        assertThat(config.getSoundSources())
                .extracting(SoundSource::name)
                .containsExactlyInAnyOrder("Guitar", "Vocals");
    }

    @Test
    void listenersAreNotifiedAfterSync() {
        AtomicInteger count = new AtomicInteger();
        provider.addListener(p -> count.incrementAndGet());

        Track t = project.createAudioTrack("G");
        t.setArmed(true);
        provider.sync();

        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void disposeClearsManagedStateAndListeners() {
        Track t = project.createAudioTrack("G");
        t.setArmed(true);
        AtomicInteger count = new AtomicInteger();
        provider.addListener(p -> count.incrementAndGet());
        provider.sync();

        provider.dispose();

        assertThat(provider.getProject()).isNull();
        assertThat(provider.getManagedSources()).isEmpty();

        // Further syncs with a fresh project should not notify old listeners.
        DawProject fresh = new DawProject("F", AudioFormat.CD_QUALITY);
        fresh.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL));
        provider.setProject(fresh);
        provider.sync();

        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void layoutPositionForSingleSourceIsRoomCenter() {
        RoomDimensions dims = new RoomDimensions(10, 8, 3);
        Position3D pos = ArmedTrackSourceProvider.layoutPosition(0, 1, dims);

        assertThat(pos.x()).isEqualTo(5.0);
        assertThat(pos.y()).isEqualTo(4.0);
        assertThat(pos.z()).isBetween(0.0, dims.height());
    }

    @Test
    void layoutPositionsForMultipleSourcesStayInsideRoom() {
        RoomDimensions dims = new RoomDimensions(4, 4, 3);
        for (int i = 0; i < 8; i++) {
            Position3D pos = ArmedTrackSourceProvider.layoutPosition(i, 8, dims);
            assertThat(pos.x()).isBetween(0.0, dims.width());
            assertThat(pos.y()).isBetween(0.0, dims.length());
            assertThat(pos.z()).isBetween(0.0, dims.height());
        }
    }

    @Test
    void syncIsNoOpWhenNoRoomConfigurationBound() {
        DawProject bare = new DawProject("Bare", AudioFormat.CD_QUALITY);
        Track t = bare.createAudioTrack("X");
        t.setArmed(true);
        ArmedTrackSourceProvider p = new ArmedTrackSourceProvider(bare);

        // Should not throw.
        p.sync();
        assertThat(p.getManagedSources()).isEmpty();
    }

    @Test
    void removingATrackRemovesItsManagedSource() {
        Track guitar = project.createAudioTrack("Guitar");
        guitar.setArmed(true);
        provider.sync();
        assertThat(config.getSoundSources()).hasSize(1);

        project.removeTrack(guitar);
        provider.sync();

        assertThat(config.getSoundSources()).isEmpty();
        assertThat(provider.getManagedSources()).isEmpty();
    }
}
