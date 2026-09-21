package com.benesquivelmusic.daw.sdk.editor;

import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PluginParameterStoreCoalescingTest {
    @Test
    void persistenceRetainsWritesUntilTheProcessorSetterHasCompleted() throws Exception {
        var store = new PluginParameterStore(java.util.List.of(new PluginParameter(7, "Gain", 0, 1, 1)));
        store.writeFromUi(0, 0.25);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var draining = java.util.concurrent.CompletableFuture.runAsync(() -> store.drainToAudio(index -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("setter timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.snapshotPendingUiValues()).containsEntry(7, 0.25);
        } finally {
            release.countDown();
            draining.get(5, TimeUnit.SECONDS);
        }
        assertThat(store.snapshotPendingUiValues()).isEmpty();
    }

    @Test
    void burstWritesRetainEveryFinalParameterWithoutOverflow() {
        var parameters = IntStream.range(0, 300)
                .mapToObj(id -> new PluginParameter(id, "Parameter " + id, 0, 100_000, 0)).toList();
        var store = new PluginParameterStore(parameters);
        for (int write = 0; write < 20_000; write++) store.writeFromUi(0, write);
        for (int index = 1; index < 300; index++) store.writeFromUi(index, index);
        double[] applied = new double[300];
        assertThat(store.drainToAudio(index -> applied[index] = store.value(index))).isEqualTo(300);
        assertThat(applied[0]).isEqualTo(19_999);
        for (int index = 1; index < 300; index++) assertThat(applied[index]).isEqualTo(index);
        assertThat(store.drainToAudio(_ -> { })).isZero();
    }

    @Test
    void audioEchoCoalescesWithoutCreatingAnAudioFeedbackWrite() {
        var store = new PluginParameterStore(java.util.List.of(new PluginParameter(7, "Toggle", 0, 1, 0)));
        for (int write = 0; write < 20_000; write++) store.writeFromAudio(0, write % 2);
        assertThat(store.drainToUi(index -> assertThat(store.value(index)).isOne())).isOne();
        assertThat(store.drainToAudio(_ -> { })).isZero();
    }

    @Test
    void concurrentBurstPublicationPreservesTheLastValueForEveryParameter() throws Exception {
        var store = new PluginParameterStore(IntStream.range(0, 300)
                .mapToObj(id -> new PluginParameter(id, "Parameter " + id, 0, 100_000, 0)).toList());
        var started = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var producer = Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                for (int write = 1; write <= 100_000; write++) store.writeFromUi(write % 300, write);
            } finally { finished.countDown(); }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        double[] applied = new double[300];
        PluginParameterStore.IndexConsumer sink = index -> applied[index] = store.value(index);
        while (finished.getCount() != 0) store.drainToAudio(sink);
        producer.join();
        store.drainToAudio(sink);
        for (int index = 0; index < 300; index++) {
            assertThat(applied[index]).isEqualTo(100_000 - Math.floorMod(100_000 - index, 300));
        }
        assertThat(store.drainToAudio(sink)).isZero();
    }

    @Test
    void realtimeStoreMethodsContainNoAtomicReadModifyWriteInstructions() throws Exception {
        var roots = Set.of("writeFromAudio", "drainToAudio", "drain", "clamp", "value", "publishMeters");
        var forbidden = Set.of("getAndSet", "compareAndSet", "compareAndExchange", "weakCompareAndSet",
                "incrementAndGet", "getAndIncrement", "decrementAndGet", "getAndDecrement",
                "addAndGet", "getAndAdd", "updateAndGet", "getAndUpdate", "accumulateAndGet", "getAndAccumulate");
        try (var bytes = PluginParameterStore.class.getResourceAsStream("PluginParameterStore.class")) {
            assertThat(bytes).isNotNull();
            // Class-File API (JEP 484, final since Java 24) checks the deployed RT path.
            var methods = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(method -> roots.contains(method.methodName().stringValue())).toList();
            assertThat(methods).hasSize(roots.size());
            for (var method : methods) {
                for (var instruction : method.code().orElseThrow()) {
                    if (instruction instanceof InvokeInstruction invoke) {
                        assertThat(invoke.name().stringValue()).as(method.methodName().stringValue())
                                .isNotIn(forbidden);
                    }
                }
            }
        }
    }
}
