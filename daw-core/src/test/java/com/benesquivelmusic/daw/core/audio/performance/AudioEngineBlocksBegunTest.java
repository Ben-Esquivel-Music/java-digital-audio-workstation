package com.benesquivelmusic.daw.core.audio.performance;

import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;
import com.benesquivelmusic.daw.core.transport.Transport;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackPerformanceEvent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 338, ruling E203: the engine counts a block begun once per attempted
 * render, after the running check and before the block reads its CPU budget
 * enforcer, and its metering block index counts the block ended after the
 * block's last use of that enforcer, so while the two differ a block is in
 * flight, or a block threw after its count and before the {@code try} whose
 * {@code finally} counts it ended. The enforcer's clock, read at each
 * measurement, and a subscriber its publisher delivers to on the render
 * thread at the master cascade's marking, the block's last use of the
 * enforcer, both find the block begun and not ended; a stopped engine begins
 * no block. In this package for the enforcer's publisher seam.
 */
class AudioEngineBlocksBegunTest {

    private static final int BLOCK = 256;
    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 16, BLOCK);
    /** The descriptor of the four-argument {@code processBlock}, the one the stream pump calls. */
    private static final String PROCESS_BLOCK = "([[F[[FI[F)Z";

    @Test
    void aBlockIsInFlightAtEachUseOfTheEnforcerAndEndsOncePerAttemptedRender() {
        AudioEngine engine = new AudioEngine(FORMAT);
        bindAPlayingTrackThatTakesTime(engine);
        List<List<Long>> atEachMeasurement = new CopyOnWriteArrayList<>();
        TrackCpuBudgetEnforcer enforcer = new TrackCpuBudgetEnforcer(FORMAT.sampleRate(), BLOCK, Double.MIN_VALUE,
                () -> {
                    atEachMeasurement.add(counts(engine));
                    return 0L;
                },
                new SubmissionPublisher<>(Runnable::run, 1));
        AtTheMarking marking = new AtTheMarking(engine);
        enforcer.performanceEvents().subscribe(marking);
        engine.setCpuBudgetEnforcer(enforcer);
        List<List<Long>> afterEachBlock = new ArrayList<>();
        boolean renderedStopped;
        List<Long> afterAStoppedBlock;
        engine.start();
        try {
            for (int block = 0; block < 3; block++) {
                engine.processBlock(new float[2][BLOCK], new float[2][BLOCK], BLOCK);
                afterEachBlock.add(counts(engine));
            }
            engine.stop();
            renderedStopped = engine.processBlock(new float[2][BLOCK], new float[2][BLOCK], BLOCK);
            afterAStoppedBlock = counts(engine);
        } finally {
            engine.shutdown();
            enforcer.close();
        }

        assertThat(atEachMeasurement).as("begun and not ended at each measurement")
                .containsExactly(List.of(1L, 0L), List.of(2L, 1L), List.of(3L, 2L));
        assertThat(marking.counts).as("begun and not ended at the master cascade's marking, the block's last use"
                + " of the enforcer").containsExactly(List.of(1L, 0L));
        assertThat(afterEachBlock).as("each attempted render begins and ends one block")
                .containsExactly(List.of(1L, 1L), List.of(2L, 2L), List.of(3L, 3L));
        assertThat(renderedStopped).as("premise: the stopped engine rendered nothing").isFalse();
        assertThat(afterAStoppedBlock).as("a stopped engine begins no block").isEqualTo(List.of(3L, 3L));
    }

    /**
     * The order no measurement can see: a detach that lands between the
     * running check and the block's read of the enforcer finds the block
     * already counted, so it waits for that block.
     */
    @Test
    void aBlockCountsItselfBegunAfterTheRunningCheckAndBeforeItReadsTheEnforcer() throws IOException {
        ClassModel engine = engineClass();
        List<String> accesses = fieldAccesses(engine.methods().stream()
                .filter(method -> method.methodName().equalsString("processBlock")
                        && method.methodType().equalsString(PROCESS_BLOCK))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AudioEngine#processBlock" + PROCESS_BLOCK + " is gone")));
        int runningCheck = accesses.indexOf("GETFIELD running");
        int begun = accesses.indexOf("PUTFIELD blocksBegun");
        int enforcerRead = accesses.indexOf("GETFIELD cpuBudgetEnforcer");
        long stores = engine.methods().stream().map(AudioEngineBlocksBegunTest::fieldAccesses)
                .flatMap(List::stream).filter("PUTFIELD blocksBegun"::equals).count();

        assertThat(runningCheck).as("premise: processBlock checks that the engine runs").isNotNegative();
        assertThat(enforcerRead).as("premise: processBlock reads the enforcer").isNotNegative();
        assertThat(begun).as("the block counts itself begun after the running check")
                .isGreaterThan(runningCheck)
                .as("and before it reads the enforcer").isLessThan(enforcerRead);
        assertThat(stores).as("one writer: the render thread's processBlock").isEqualTo(1L);
    }

    private static List<Long> counts(AudioEngine engine) {
        return List.of(engine.blocksBegun(), engine.meteringTapBus().blockIndex());
    }

    private static void bindAPlayingTrackThatTakesTime(AudioEngine engine) {
        Transport transport = new Transport();
        Track track = new Track("Lead Vox", TrackType.AUDIO);
        Mixer mixer = new Mixer();
        MixerChannel channel = new MixerChannel(track.getName());
        channel.setCpuBudget(new TrackCpuBudget(1.0, new DegradationPolicy.BypassExpensive()));
        channel.addInsert(new InsertSlot("Takes time", new TakesTime()));
        mixer.addChannel(channel);
        transport.play();
        engine.setGraph(transport, mixer, List.of(track));
    }

    private static ClassModel engineClass() throws IOException {
        try (InputStream bytes = AudioEngine.class.getResourceAsStream("AudioEngine.class")) {
            assertThat(bytes).as("AudioEngine's class file").isNotNull();
            return ClassFile.of().parse(bytes.readAllBytes());
        }
    }

    /** The field instructions of {@code method}, in bytecode order, as {@code "OPCODE name"}. */
    private static List<String> fieldAccesses(MethodModel method) {
        List<String> accesses = new ArrayList<>();
        CodeAttribute code = method.findAttribute(Attributes.code()).orElse(null);
        if (code == null) {
            return accesses;
        }
        for (CodeElement element : code) {
            if (element instanceof FieldInstruction field) {
                accesses.add(field.opcode().name() + " " + field.name().stringValue());
            }
        }
        return accesses;
    }

    /** Passes the signal through and takes at least two microseconds, so every measurement is above zero. */
    private static final class TakesTime implements AudioProcessor {
        @Override
        public void process(float[][] inputBuffer, float[][] outputBuffer, int numFrames) {
            long start = System.nanoTime();
            for (int channel = 0; channel < Math.min(inputBuffer.length, outputBuffer.length); channel++) {
                System.arraycopy(inputBuffer[channel], 0, outputBuffer[channel], 0, numFrames);
            }
            while (System.nanoTime() - start < 2_000L) {
                Thread.onSpinWait();
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
    }

    /** Reads the engine's counts at each marking, which the enforcer's publisher delivers on the render thread. */
    private static final class AtTheMarking implements Flow.Subscriber<TrackPerformanceEvent> {
        private final AudioEngine engine;
        final List<List<Long>> counts = new CopyOnWriteArrayList<>();

        AtTheMarking(AudioEngine engine) {
            this.engine = engine;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(TrackPerformanceEvent event) {
            if (event instanceof TrackPerformanceEvent.TrackDegraded) {
                counts.add(counts(engine));
            }
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }
}
