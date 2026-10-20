package com.benesquivelmusic.daw.app.ui.vm.command;

import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.event.BusEvent;
import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.EventBusMetrics;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;
import com.benesquivelmusic.daw.sdk.transport.PunchRegion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

/**
 * PR #984 review — the loop window and the punch region are saved with the
 * project ({@code ProjectSerializer.buildTransport}), so the cascade's PROJECT
 * phase marks the project dirty when a loop or punch intent changes them
 * (Control Synchronization Design Book §5.1, §5.2: dirty-only, no undo).
 * PROJECT runs once per changed value and before ANNOUNCE; a punch intent
 * that installs the region already installed, a punch toggle with no region,
 * the loop-record toggle and every other transport intent do not run it.
 *
 * <p>The bus here delivers on the publishing thread, inside
 * {@code EventBusPublisher.publish}, and records how many times PROJECT had
 * run at that instant — so "PROJECT before ANNOUNCE" is asserted at the
 * publish itself, not at a later delivery that would see the count either
 * way.</p>
 */
class LoopAndPunchIntentsMarkTheProjectDirtyTest {

    private static final double PROJECT_RATE = 96_000.0;
    /** 2 × 5 ms at 96 kHz. */
    private static final long MIN_FRAMES = 960L;
    /** Beat 4 at 120 BPM = 2 s = 192 000 frames at 96 kHz. */
    private static final long PLAYHEAD_FRAMES = 192_000L;

    private final AtomicInteger dirtyMarks = new AtomicInteger();
    private final List<Published> published = new CopyOnWriteArrayList<>();

    /** An event as the bus received it, with the PROJECT count at that instant. */
    private record Published(BusEvent event, int dirtyMarksAtPublish) {
    }

    @BeforeEach
    void installTheRecordingBus() {
        EventBusPublisher.setDefault(new PublishRecordingBus(
                event -> published.add(new Published(event, dirtyMarks.get()))));
    }

    @AfterEach
    void clearTheBus() {
        EventBusPublisher.setDefault(null);
    }

    @Test
    void theProjectPhaseIsAMandatoryCollaborator() {
        assertThatNullPointerException()
                .isThrownBy(() -> new CoreTransportIntentHandler(new Transport(), PROJECT_RATE, null))
                .withMessageContaining("markProjectDirty");
    }

    @Test
    void setPunchInThatCreatesARegionMarksTheProjectDirtyBeforeAnnouncingIt() {
        Transport transport = transportAt120Bpm(4.0);

        new SetPunchInCommand().execute(handlerOver(transport));

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(PLAYHEAD_FRAMES, PLAYHEAD_FRAMES + MIN_FRAMES));
        assertThat(dirtyMarks).as("PROJECT runs once for the changed region").hasValue(1);
        assertThat(published)
                .as("PunchChanged is published after PROJECT ran")
                .extracting(p -> p.event().getClass(), Published::dirtyMarksAtPublish)
                .containsExactly(tuple(TransportEvent.PunchChanged.class, 1));
    }

    @Test
    void setPunchOutThatMovesTheRegionMarksTheProjectDirtyOnce() {
        Transport transport = transportAt120Bpm(4.0);
        transport.setPunchRegion(PunchRegion.enabled(10_000L, 400_000L));

        new SetPunchOutCommand().execute(handlerOver(transport));

        assertThat(transport.getPunchRegion()).isEqualTo(PunchRegion.enabled(10_000L, PLAYHEAD_FRAMES));
        assertThat(dirtyMarks).hasValue(1);
        assertThat(published).extracting(Published::dirtyMarksAtPublish).containsExactly(1);
    }

    @Test
    void togglePunchOverAnInstalledRegionMarksTheProjectDirtyEveryTime() {
        Transport transport = transportAt120Bpm(4.0);
        transport.setPunchRegion(PunchRegion.enabled(1_000L, 50_000L));
        CoreTransportIntentHandler handler = handlerOver(transport);

        new TogglePunchCommand().execute(handler);
        assertThat(dirtyMarks).as("off: the stored enabled flag changed").hasValue(1);

        new TogglePunchCommand().execute(handler);
        assertThat(dirtyMarks).as("on again: changed again").hasValue(2);
        assertThat(published)
                .as("each PunchChanged follows its own PROJECT")
                .extracting(Published::dirtyMarksAtPublish)
                .containsExactly(1, 2);
    }

    @Test
    void togglePunchWithNoRegionDoesNotMarkTheProjectDirty() {
        Transport transport = transportAt120Bpm(4.0);

        new TogglePunchCommand().execute(handlerOver(transport));

        assertThat(transport.getPunchRegion()).isNull();
        assertThat(dirtyMarks).as("VALIDATE refused it: nothing changed").hasValue(0);
        assertThat(published).isEmpty();
    }

    @Test
    void aPunchSetThatInstallsTheRegionAlreadyInstalledDoesNotMarkTheProjectDirty() {
        Transport transport = transportAt120Bpm(4.0);
        CoreTransportIntentHandler handler = handlerOver(transport);

        new SetPunchInCommand().execute(handler);
        assertThat(dirtyMarks).as("the first Set In creates the region").hasValue(1);
        new SetPunchInCommand().execute(handler);
        assertThat(dirtyMarks).as("a second Set In at the same playhead installs the identical region").hasValue(1);

        new SetPunchOutCommand().execute(handler);
        assertThat(dirtyMarks).as("Set Out at the same playhead moves the in-point back").hasValue(2);
        new SetPunchOutCommand().execute(handler);
        assertThat(dirtyMarks).as("a second Set Out installs the identical region").hasValue(2);

        assertThat(published)
                .as("the announce still follows every install, the identical ones included")
                .hasSize(4);
    }

    @Test
    void toggleLoopMarksTheProjectDirtyBeforeAnnouncingLoopChangedEveryTime() {
        Transport transport = transportAt120Bpm(4.0);
        CoreTransportIntentHandler handler = handlerOver(transport);

        new ToggleLoopCommand().execute(handler);
        assertThat(transport.isLoopEnabled()).isTrue();
        assertThat(dirtyMarks).hasValue(1);

        new ToggleLoopCommand().execute(handler);
        assertThat(transport.isLoopEnabled()).isFalse();
        assertThat(dirtyMarks).hasValue(2);

        assertThat(published)
                .extracting(p -> p.event().getClass(), Published::dirtyMarksAtPublish)
                .containsExactly(
                        tuple(TransportEvent.LoopChanged.class, 1),
                        tuple(TransportEvent.LoopChanged.class, 2));
    }

    @Test
    void toggleLoopRecordDoesNotMarkTheProjectDirty() {
        Transport transport = transportAt120Bpm(4.0);

        new ToggleLoopRecordCommand().execute(handlerOver(transport));

        assertThat(transport.isLoopRecordEnabled()).isTrue();
        assertThat(dirtyMarks).as("loop-record is not written to the project file").hasValue(0);
    }

    @Test
    void playbackRecordSeekAndTempoIntentsDoNotMarkTheProjectDirty() {
        Transport transport = transportAt120Bpm(4.0);
        CoreTransportIntentHandler handler = handlerOver(transport);

        new StartTransportCommand().execute(handler);
        new PauseTransportCommand().execute(handler);
        new TogglePlayPauseCommand().execute(handler);
        new StopTransportCommand().execute(handler);
        new SkipForwardCommand().execute(handler);
        new SkipBackCommand().execute(handler);
        new PlayWithPreRollCommand().execute(handler);
        new StopTransportCommand().execute(handler);
        new ToggleRecordCommand().execute(handler);
        new ToggleRecordCommand().execute(handler);
        new SetTempoCommand(140.0).execute(handler);

        assertThat(published).as("guard: the intents ran and announced").isNotEmpty();
        assertThat(dirtyMarks)
                .as("no playhead move, record toggle or tempo command runs PROJECT here")
                .hasValue(0);
    }

    private CoreTransportIntentHandler handlerOver(Transport transport) {
        return new CoreTransportIntentHandler(transport, PROJECT_RATE, dirtyMarks::incrementAndGet);
    }

    private static Transport transportAt120Bpm(double positionBeats) {
        Transport transport = new Transport();
        transport.setTempo(120.0);
        transport.setPositionInBeats(positionBeats);
        return transport;
    }

    /** A bus whose publish runs the hook on the publishing thread and delivers nowhere else. */
    private static final class PublishRecordingBus implements EventBus {
        private final Consumer<BusEvent> onPublish;

        private PublishRecordingBus(Consumer<BusEvent> onPublish) {
            this.onPublish = onPublish;
        }

        @Override
        public void publish(BusEvent event) {
            onPublish.accept(event);
        }

        @Override
        public <E extends BusEvent> Flow.Publisher<E> subscribe(Class<E> type) {
            throw new UnsupportedOperationException("subscriptions are not used by this test bus");
        }

        @Override
        public <E extends BusEvent> Subscription on(Class<E> type, DispatchMode mode, Consumer<? super E> handler) {
            throw new UnsupportedOperationException("subscriptions are not used by this test bus");
        }

        @Override
        public EventBusMetrics metrics() {
            throw new UnsupportedOperationException("metrics are not used by this test bus");
        }

        @Override
        public void close() {
            // Nothing to release: publish runs synchronously on the caller.
        }
    }
}
