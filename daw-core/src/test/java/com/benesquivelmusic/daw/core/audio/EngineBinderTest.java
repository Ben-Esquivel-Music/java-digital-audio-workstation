package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.core.performance.PerformanceMonitor;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 314 — verifies the {@link EngineBinder} binding facts: bind publishes
 * the project's live transport/mixer plus an immutable tracks snapshot,
 * structural changes refresh the snapshot, rebind detaches the previous
 * project's listener and increments the epoch, unbind nulls everything
 * idempotently, and the performance monitor is wired once at bind.
 */
class EngineBinderTest {

    private static final AudioFormat FORMAT = new AudioFormat(44_100.0, 2, 16, 8);

    private AudioEngine engine;
    private EngineBinder binder;
    private DawProject project;

    @BeforeEach
    void setUp() {
        engine = new AudioEngine(FORMAT);
        binder = new EngineBinder(engine);
        project = new DawProject("Bound", FORMAT);
    }

    @Test
    void bindPublishesTheLiveTransportAndMixerAndAnImmutableTracksSnapshot() {
        Track drums = project.createAudioTrack("Drums");

        binder.bind(project);

        assertThat(engine.getTransport())
                .as("the engine holds the project's live transport").isSameAs(project.getTransport());
        assertThat(engine.getMixer())
                .as("the engine holds the project's live mixer").isSameAs(project.getMixer());
        assertThat(engine.getTracks())
                .as("the engine's tracks equal the project's").containsExactly(drums);
        assertThat(engine.getTracks())
                .as("the engine holds a snapshot copy, not the project's live view")
                .isNotSameAs(project.getTracks());
        assertThatThrownBy(() -> engine.getTracks().add(new Track("Rogue", TrackType.AUDIO)))
                .as("the snapshot is immutable")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(binder.boundProject()).contains(project);
    }

    @Test
    void structuralChangesRefreshTheSnapshotWithoutMutatingThePreviousOne() {
        Track drums = project.createAudioTrack("Drums");
        binder.bind(project);
        List<Track> firstSnapshot = engine.getTracks();

        Track bass = project.createAudioTrack("Bass");
        assertThat(engine.getTracks())
                .as("a track add swaps in a new snapshot instance").isNotSameAs(firstSnapshot);
        assertThat(engine.getTracks()).containsExactly(drums, bass);
        assertThat(firstSnapshot)
                .as("the previous snapshot is unchanged (immutability proof)")
                .containsExactly(drums);

        project.moveTrack(0, 1);
        assertThat(engine.getTracks())
                .as("a track move refreshes the snapshot order").containsExactly(bass, drums);

        project.removeTrack(bass);
        assertThat(engine.getTracks())
                .as("a track remove refreshes the snapshot").containsExactly(drums);
    }

    @Test
    void rebindIncrementsTheEpochAndDetachesThePreviousProjectsListener() {
        DawProject projectB = new DawProject("Other", FORMAT);
        Track lead = projectB.createAudioTrack("Lead");

        binder.bind(project);
        long epochA = binder.epoch();

        binder.bind(projectB);
        assertThat(binder.epoch()).as("rebind increments the epoch").isGreaterThan(epochA);
        assertThat(engine.getTransport()).isSameAs(projectB.getTransport());
        assertThat(engine.getMixer()).isSameAs(projectB.getMixer());
        assertThat(engine.getTracks()).containsExactly(lead);
        assertThat(binder.boundProject()).contains(projectB);

        // Mutating the OLD project must not touch the engine — the binder
        // detached the remembered listener token on rebind.
        List<Track> snapshotAfterRebind = engine.getTracks();
        project.createAudioTrack("Stale");
        assertThat(engine.getTracks())
                .as("the old project's listener is detached; its mutations are invisible")
                .isSameAs(snapshotAfterRebind);
    }

    @Test
    void unbindNullsAllThreeReferencesIdempotentlyWithoutAdvancingTheEpoch() {
        project.createAudioTrack("Drums");
        binder.bind(project);
        long epochAfterBind = binder.epoch();

        binder.unbind();
        assertThat(engine.getTransport()).as("transport nulled (playbackActive gate off)").isNull();
        assertThat(engine.getMixer()).isNull();
        assertThat(engine.getTracks()).isNull();
        assertThat(binder.boundProject()).isEmpty();
        assertThat(binder.epoch()).as("unbind does not advance the epoch").isEqualTo(epochAfterBind);

        // Structural changes on the unbound project must not resurrect anything.
        project.createAudioTrack("Ghost");
        assertThat(engine.getTracks())
                .as("the unbound project's mutations do not reach the engine").isNull();

        binder.unbind();
        assertThat(engine.getTransport()).as("unbind is idempotent").isNull();
        assertThat(binder.epoch()).isEqualTo(epochAfterBind);
    }

    @Test
    void bindWiresAPerformanceMonitorAndASameFormatRebindKeepsTheInstance() {
        assertThat(engine.getPerformanceMonitor()).isNull();

        binder.bind(project);
        PerformanceMonitor monitor = engine.getPerformanceMonitor();
        assertThat(monitor).as("bind wires a performance monitor when absent").isNotNull();
        assertThat(monitor.getFormat())
                .as("the monitor is built from the engine's format").isSameAs(FORMAT);

        binder.bind(new DawProject("Second", FORMAT));
        assertThat(engine.getPerformanceMonitor())
                .as("a same-format rebind keeps the existing monitor instance").isSameAs(monitor);
    }

    @Test
    void aFormatChangeReplacesTheMonitorWithOneBuiltFromTheNewFormat() {
        binder.bind(project);
        PerformanceMonitor original = engine.getPerformanceMonitor();

        AudioFormat reconfigured = new AudioFormat(48_000.0, 2, 16, 16);
        engine.setFormat(reconfigured);
        binder.refreshPerformanceMonitor();

        PerformanceMonitor replaced = engine.getPerformanceMonitor();
        assertThat(replaced)
                .as("a format change followed by refresh replaces the stale monitor")
                .isNotSameAs(original);
        assertThat(replaced.getFormat())
                .as("the replacement is built from the new engine format")
                .isEqualTo(reconfigured);

        binder.refreshPerformanceMonitor();
        assertThat(engine.getPerformanceMonitor())
                .as("a same-format refresh keeps the instance stable").isSameAs(replaced);
    }
}
