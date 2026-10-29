package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.event.DefaultEventBus;
import com.benesquivelmusic.daw.core.event.EventBusPublisher;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.core.transport.TransportState;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.TransportEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.SaveFlowFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 333, {@code TransportController.stopForClose()} (design §3.8; the
 * studio engineer's B2 and rulings R1, R2): the close's Stop never leaves the
 * transport in post-roll — a post-roll is not entered, one already running
 * ends now — and publishes {@code TransportEvent.Stopped} on its own FX turn,
 * so the close's capture is of a stopped transport and no record follows it;
 * over a transport already stopped it does nothing, never the double-stop
 * rewind. It answers whether the transport is stopped.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class StopForCloseTest {

    private RecordingInFlightFixture recording;
    private Transport transport;
    private EventBus previous;
    private DefaultEventBus bus;
    private final List<TransportEvent> published = new CopyOnWriteArrayList<>();

    @BeforeEach
    void aControllerWithPostRoll() throws Exception {
        DawProject project = new DawProject("Song", AudioFormat.CD_QUALITY);
        recording = RecordingInFlightFixture.audio(project);
        transport = project.getTransport();
        previous = EventBusPublisher.getDefault();
        bus = new DefaultEventBus();
        bus.onPublication(TransportEvent.class, published::add);
        EventBusPublisher.setDefault(bus);
        runOnFx(() -> transport.setPreRollPostRoll(new PreRollPostRoll(0, 2, true)));
    }

    @AfterEach
    void close() throws Exception {
        EventBusPublisher.setDefault(previous);
        bus.close();
        recording.close();
    }

    @Test
    void aRunningPostRollEndsNowWithItsStoppedPublishedOnThisTurn() throws Exception {
        runOnFx(() -> {
            transport.play();
            recording.controller().stop();
        });
        assertThat(onFx(transport::isInPostRoll)).as("fixture: the user's Stop entered the post-roll").isTrue();
        published.clear();

        boolean stopped = onFx(() -> {
            boolean answer = recording.controller().stopForClose();
            assertThat(published).as("Stopped is published on the close's own turn")
                    .hasAtLeastOneElementOfType(TransportEvent.Stopped.class);
            return answer;
        });

        assertThat(stopped).isTrue();
        assertThat(onFx(transport::getState)).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(transport::isInPostRoll)).isFalse();
    }

    @Test
    void aPlayingTransportStopsWithoutEnteringThePostRoll() throws Exception {
        runOnFx(transport::play);

        boolean stopped = onFx(() -> recording.controller().stopForClose());

        assertThat(stopped).isTrue();
        assertThat(onFx(transport::getState)).isEqualTo(TransportState.STOPPED);
        assertThat(onFx(transport::isInPostRoll)).as("no post-roll is entered for a close").isFalse();
        assertThat(published).hasAtLeastOneElementOfType(TransportEvent.Stopped.class);
    }

    @Test
    void aStoppedTransportIsLeftAsItIsNeverTheDoubleStopRewind() throws Exception {
        runOnFx(() -> transport.setPositionInBeats(8.0));
        assertThat(onFx(transport::isReturnToStartOnStop)).as("fixture: a Stop would rewind").isTrue();

        boolean stopped = onFx(() -> recording.controller().stopForClose());

        assertThat(stopped).isTrue();
        assertThat(onFx(transport::getPositionInBeats)).isEqualTo(8.0);
        assertThat(published).as("nothing transitioned: nothing announced").isEmpty();
    }
}
