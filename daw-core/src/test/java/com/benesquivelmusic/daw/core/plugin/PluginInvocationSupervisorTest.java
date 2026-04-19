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

class PluginInvocationSupervisorTest {

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
    void shouldSwallowThrowableZeroOutputAndBypassSlot() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot slot = new InsertSlot("BuggyEffect", new ThrowingProcessor(
                new IllegalStateException("kaboom")));
        AudioProcessor supervised = supervisor.supervise(slot, slot.getProcessor());

        float[][] in = new float[][]{{0.5f, -0.5f}, {0.25f, -0.25f}};
        float[][] out = new float[][]{{9f, 9f}, {9f, 9f}};

        supervised.process(in, out, 2);

        assertThat(out[0]).containsExactly(0f, 0f);
        assertThat(out[1]).containsExactly(0f, 0f);
        assertThat(slot.isBypassed()).isTrue();

        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        PluginFault fault = subscriber.faults.getFirst();
        assertThat(fault.pluginId()).isEqualTo("BuggyEffect");
        assertThat(fault.exceptionClass()).isEqualTo(IllegalStateException.class.getName());
        assertThat(fault.message()).isEqualTo("kaboom");
        assertThat(fault.stackTrace()).contains("IllegalStateException");
        assertThat(fault.quarantined()).isFalse();
    }

    @Test
    void shouldZeroDoubleOutputOnProcessDoubleFault() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot slot = new InsertSlot("BadDoubler", new ThrowingProcessor(new ArithmeticException("/0")));
        AudioProcessor supervised = supervisor.supervise(slot, slot.getProcessor());

        double[][] in = new double[][]{{0.5, -0.5}, {0.25, -0.25}};
        double[][] out = new double[][]{{9.0, 9.0}, {9.0, 9.0}};

        supervised.processDouble(in, out, 2);

        assertThat(out[0]).containsExactly(0.0, 0.0);
        assertThat(out[1]).containsExactly(0.0, 0.0);
        assertThat(slot.isBypassed()).isTrue();
        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldQuarantinePluginAfterThresholdFaults() throws Exception {
        int expectedFaults = PluginInvocationSupervisor.QUARANTINE_THRESHOLD + 1;
        CollectingSubscriber subscriber = new CollectingSubscriber(expectedFaults);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot slot = new InsertSlot("Flaky", new ThrowingProcessor(new RuntimeException("bad")));
        AudioProcessor supervised = supervisor.supervise(slot, slot.getProcessor());

        float[][] in = new float[][]{{0f}};
        float[][] out = new float[][]{{0f}};

        for (int i = 0; i < expectedFaults; i++) {
            // Un-bypass between iterations so we simulate the user re-arming
            // the slot (or a different code path reusing the wrapper).
            slot.setBypassed(false);
            supervised.process(in, out, 1);
        }

        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(supervisor.getFaultCount("Flaky")).isEqualTo(expectedFaults);
        assertThat(supervisor.isQuarantined("Flaky")).isTrue();

        PluginFault last = subscriber.faults.get(expectedFaults - 1);
        assertThat(last.quarantined()).isTrue();

        supervisor.clearQuarantine("Flaky");
        assertThat(supervisor.isQuarantined("Flaky")).isFalse();
        assertThat(supervisor.getFaultCount("Flaky")).isZero();
    }

    @Test
    void shouldAppendFaultsToLogFile() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot slot = new InsertSlot("Logger", new ThrowingProcessor(new NullPointerException("npe")));
        AudioProcessor supervised = supervisor.supervise(slot, slot.getProcessor());

        supervised.process(new float[][]{{0f}}, new float[][]{{0f}}, 1);

        // Drain thread writes the log file BEFORE publishing to subscribers,
        // so the latch firing is a sufficient happens-before for the file.
        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(faultLog).exists();
        String contents = Files.readString(faultLog);
        assertThat(contents).contains("\"pluginId\":\"Logger\"");
        assertThat(contents).contains("NullPointerException");
    }

    @Test
    void reenableShouldClearBypass() {
        InsertSlot slot = new InsertSlot("X", new ThrowingProcessor(new RuntimeException()));
        slot.setBypassed(true);

        supervisor.reenable(slot);

        assertThat(slot.isBypassed()).isFalse();
    }

    @Test
    void reenableByPluginIdShouldClearQuarantineOnly() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot slot = new InsertSlot("Reenabled", new ThrowingProcessor(new RuntimeException("boom")));
        AudioProcessor supervised = supervisor.supervise(slot, slot.getProcessor());
        supervised.process(new float[][]{{0f}}, new float[][]{{0f}}, 1);

        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(slot.isBypassed()).isTrue();

        // reenable(String) only clears quarantine — it does NOT un-bypass
        // because the pluginId may identify a type shared by multiple slots.
        boolean reenabled = supervisor.reenable("Reenabled");

        assertThat(reenabled).isFalse();
        // Bypass state unchanged — caller must use reenable(InsertSlot) for that
        assertThat(slot.isBypassed()).isTrue();
        assertThat(supervisor.isQuarantined("Reenabled")).isFalse();
        assertThat(supervisor.getFaultCount("Reenabled")).isZero();
    }

    @Test
    void reenableByUnknownPluginIdReturnsFalse() {
        assertThat(supervisor.reenable("never-registered")).isFalse();
    }

    @Test
    void shouldCatchErrorAndStillBypassSlot() throws Exception {
        CollectingSubscriber subscriber = new CollectingSubscriber(1);
        supervisor.publisher().subscribe(subscriber);

        InsertSlot slot = new InsertSlot("BadError",
                new ThrowingErrorProcessor(new StackOverflowError("deep")));
        AudioProcessor supervised = supervisor.supervise(slot, slot.getProcessor());

        float[][] out = new float[][]{{9f}};
        supervised.process(new float[][]{{0f}}, out, 1);

        assertThat(out[0]).containsExactly(0f);
        assertThat(slot.isBypassed()).isTrue();
        assertThat(subscriber.latch.await(2, TimeUnit.SECONDS)).isTrue();
        PluginFault fault = subscriber.faults.getFirst();
        assertThat(fault.exceptionClass()).isEqualTo(StackOverflowError.class.getName());
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
        public void processDouble(double[][] inputBuffer, double[][] outputBuffer, int numFrames) {
            throw error;
        }

        @Override
        public boolean supportsDouble() {
            return true;
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

    private static final class ThrowingErrorProcessor implements AudioProcessor {
        private final Error error;

        ThrowingErrorProcessor(Error error) {
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
