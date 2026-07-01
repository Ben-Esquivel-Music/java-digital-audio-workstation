package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link PluginInvocationSupervisor#reportUiFault(String, Throwable)} —
 * story 301's editor fault harness (Plugin View Design Book §2.7, §6.6).
 * Editor-side faults flow to the fault log and the publisher like audio-side
 * faults, but never increment the session fault count and can never
 * quarantine the audio slot: only the editor is disabled, the audio processor
 * keeps running.
 */
class PluginInvocationSupervisorUiFaultTest {

    @TempDir
    Path tempDir;

    private PluginInvocationSupervisor supervisor;
    private Path faultLog;

    @BeforeEach
    void setUp() {
        faultLog = tempDir.resolve("plugin-faults.log");
        supervisor = new PluginInvocationSupervisor(faultLog);
    }

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    @Test
    void reportUiFaultShouldPublishAndLogFaultWithoutQuarantine() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        supervisor.reportUiFault("WavyVisualizer", new IllegalStateException("editor kaboom"));

        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        PluginFault fault = subscriber.faults.getFirst();
        assertThat(fault.pluginId()).isEqualTo("WavyVisualizer");
        assertThat(fault.exceptionClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(fault.message()).isEqualTo("editor kaboom");
        assertThat(fault.stackTrace()).contains("IllegalStateException");
        assertThat(fault.quarantined()).isFalse();
        assertThat(fault.faultCountThisSession()).isZero();

        // Drain thread writes the log file BEFORE publishing to subscribers,
        // so the latch firing is a sufficient happens-before for the file.
        assertThat(faultLog).exists();
        String contents = Files.readString(faultLog);
        assertThat(contents).contains("\"pluginId\":\"WavyVisualizer\"");
    }

    @Test
    void uiFaultShouldNotIncrementSessionFaultCount() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        supervisor.reportUiFault("CountFree", new RuntimeException("boom"));

        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(supervisor.getFaultCount("CountFree")).isZero();
    }

    @Test
    void repeatedUiFaultsShouldNeverQuarantine() throws Exception {
        int reports = PluginInvocationSupervisor.QUARANTINE_THRESHOLD + 2;
        CollectingSubscriber subscriber = new CollectingSubscriber(reports);
        supervisor.publisher().subscribe(subscriber);

        for (int i = 0; i < reports; i++) {
            supervisor.reportUiFault("StubbornEditor", new RuntimeException("editor fault #" + i));
        }

        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(supervisor.isQuarantined("StubbornEditor")).isFalse();
        assertThat(supervisor.getFaultCount("StubbornEditor")).isZero();
        assertThat(subscriber.faults)
                .hasSize(reports)
                .allSatisfy(fault -> assertThat(fault.quarantined()).isFalse());
    }

    @Test
    void uiFaultShouldReportCurrentAudioFaultCountWithoutIncrementing() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(2);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot slot = new InsertSlot("MixedSource",
                new ThrowingProcessor(new IllegalStateException("audio boom")));
        AudioProcessor supervised = supervisor.supervise(slot, slot.getProcessor());

        // Audio-sourced fault first: increments the session count to 1.
        supervised.process(new float[][]{{0f}}, new float[][]{{0f}}, 1);
        // Editor-sourced fault second: reads the count, never increments it.
        supervisor.reportUiFault("MixedSource", new RuntimeException("editor boom"));

        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(supervisor.getFaultCount("MixedSource")).isEqualTo(1);

        PluginFault audioFault = subscriber.faults.get(0);
        assertThat(audioFault.faultCountThisSession()).isEqualTo(1);

        PluginFault uiFault = subscriber.faults.get(1);
        assertThat(uiFault.exceptionClass()).isEqualTo(RuntimeException.class.getName());
        assertThat(uiFault.faultCountThisSession()).isEqualTo(1);
        assertThat(uiFault.quarantined()).isFalse();
    }

    // --- helpers ---

    private static final class ThrowingProcessor implements AudioProcessor {
        private final RuntimeException error;

        ThrowingProcessor(RuntimeException error) {
            this.error = error;
        }

        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            throw error;
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
    }

    private static final class CollectingSubscriber implements Flow.Subscriber<PluginFault> {
        final List<PluginFault> faults = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;
        private Flow.Subscription subscription;

        CollectingSubscriber(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PluginFault item) {
            faults.add(item);
            latch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            throwable.printStackTrace();
        }

        @Override
        public void onComplete() {
        }
    }
}
