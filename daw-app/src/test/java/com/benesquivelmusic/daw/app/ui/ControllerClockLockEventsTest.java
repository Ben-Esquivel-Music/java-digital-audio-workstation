package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.sdk.audio.ClockLockEvent;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The controller's clock-lock publisher (story 338): one
 * publisher for the controller's life that re-emits the clock-lock events of
 * the backend the open stream runs on, follows the stream to another backend
 * on a reopen, and completes at shutdown; and the stream-opened callback,
 * run on every open.
 */
class ControllerClockLockEventsTest {

    @Test
    void theOpenStreamsClockEventsArriveAndFollowTheStreamToAnotherBackend() {
        AudioEngine engine = new AudioEngine(AudioFormat.CD_QUALITY);
        DefaultAudioEngineController controller = new DefaultAudioEngineController(engine, null,
                new RecordingNotifications());
        AtomicInteger opens = new AtomicInteger();
        controller.setStreamOpenedCallback(opens::incrementAndGet);
        MockAudioBackend first = new MockAudioBackend();
        MockAudioBackend second = new MockAudioBackend();
        DeviceId firstDevice = new DeviceId(first.name(), "Interface A");
        DeviceId secondDevice = new DeviceId(second.name(), "Interface B");
        List<ClockLockEvent> received = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean completed = new AtomicBoolean();
        controller.clockLockEvents().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ClockLockEvent event) { received.add(event); }
            @Override public void onError(Throwable t) { /* ignore */ }
            @Override public void onComplete() { completed.set(true); }
        });
        try {
            engine.setStreamingProvision(new StreamingProvision(first.name(),
                    List.of(new BackendStreamRung(first, firstDevice))));
            engine.startAudioOutput();
            assertThat(opens.get()).as("the stream-opened callback ran for the open").isEqualTo(1);

            first.simulateClockLock(firstDevice, 2, false);
            await(() -> received.size() == 1, "the open stream's clock event");
            assertThat(received.getFirst().device()).isEqualTo(firstDevice);
            assertThat(received.getFirst().locked()).isFalse();

            engine.stopAudioOutput();
            engine.stop();
            engine.setStreamingProvision(new StreamingProvision(second.name(),
                    List.of(new BackendStreamRung(second, secondDevice))));
            engine.startAudioOutput();
            assertThat(opens.get()).isEqualTo(2);

            first.simulateClockLock(firstDevice, 2, true);
            second.simulateClockLock(secondDevice, 3, false);
            await(() -> received.size() >= 2, "the new stream's clock event");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
            assertThat(received).extracting(ClockLockEvent::device)
                    .as("the stream's earlier backend is no longer followed")
                    .containsExactly(firstDevice, secondDevice);
        } finally {
            engine.stopAudioOutput();
            engine.stop();
            controller.shutdown();
        }
        await(completed::get, "the publisher completes at shutdown");
    }
}
