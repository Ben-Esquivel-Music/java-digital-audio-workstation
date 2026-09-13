package com.benesquivelmusic.daw.core.plugin.clap;

import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the real CLAP event ABI with an in-process FFM fixture, without an installed plugin. */
class ClapParameterGraphTest {
    private static final int SETTER_ITERATIONS = 20_000;
    private static final int WARMUP_BATCHES = 20;

    @Test
    void liveSlotWriteBecomesOneNativeParameterEventInTheNextBlock() throws Exception {
        try (var fixture = new Fixture()) {
            var slot = new InsertSlot("CLAP fixture", fixture.host, null, fixture.host);
            var input = new float[1][32];
            var output = new float[1][32];
            slot.getParameterStore().writeFromUi(0, 0.25);
            slot.getParameterStore().writeFromUi(0, 0.75);
            slot.drainParametersToAudio();
            fixture.host.process(input, output, 32);
            assertThat(fixture.eventCount).isEqualTo(1);
            assertThat(fixture.parameterId).isEqualTo(7);
            assertThat(fixture.eventType).isEqualTo(ClapBindings.CLAP_EVENT_PARAM_VALUE);
            assertThat(output[0]).containsOnly(0.75f);

            fixture.host.process(input, output, 32);
            assertThat(fixture.eventCount).isZero();
            assertThat(output[0]).containsOnly(0.75f);
        }
    }

    @Test
    void primitiveAudioSetterAllocatesNothingAfterPreparation() throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        try (var fixture = new Fixture()) {
            var input = new float[1][32];
            var output = new float[1][32];
            // Warm the exact measured loop and counter calls, not a separate cold measurement loop.
            for (int batch = 0; batch < WARMUP_BATCHES; batch++) {
                measureParameterSetterAllocations(fixture.host, bean);
            }
            fixture.host.process(input, output, 32);
            long allocated = measureParameterSetterAllocations(fixture.host, bean);
            assertThat(allocated).isZero();

            fixture.host.process(input, output, 32);
            assertThat(fixture.eventCount).isOne();
            assertThat(fixture.parameterId).isEqualTo(7);
            assertThat(output[0]).containsOnly(0.75f);
        }
    }

    private static long measureParameterSetterAllocations(ClapPluginHost host,
            com.sun.management.ThreadMXBean bean) {
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int iteration = 0; iteration < SETTER_ITERATIONS; iteration++) {
            host.setAutomatableParameter(7, (iteration & 1) == 0 ? 0.25 : 0.75);
        }
        return bean.getThreadAllocatedBytes(thread) - before;
    }

    private static final class Fixture implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final ClapPluginHost host = new ClapPluginHost(Path.of("fixture.clap"), 0, 1, 1);
        private final MemorySegment events;
        private final MemorySegment output;
        private final MethodHandle eventSize;
        private final MethodHandle eventGet;
        private int eventCount;
        private int parameterId;
        private int eventType;
        private float gain;

        private Fixture() throws Exception {
            set("arena", arena);
            set("bufferSize", 32);
            set("audioParameterIds", new int[]{7});
            set("audioParameterValues", new double[1]);
            set("audioParameterDirty", new boolean[1]);
            set("cachedParameters", List.of(new PluginParameter(7, "Gain", 0, 1, 0)));
            var allocate = ClapPluginHost.class.getDeclaredMethod("allocateProcessBuffers");
            allocate.setAccessible(true);
            allocate.invoke(host);
            events = (MemorySegment) get("inputEventsStruct");
            output = ((MemorySegment[]) get("outputChannelData"))[0];
            long sizeOffset = ClapBindings.CLAP_INPUT_EVENTS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("size"));
            long getOffset = ClapBindings.CLAP_INPUT_EVENTS_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("get"));
            eventSize = ClapBindings.downcallHandle(events.get(ValueLayout.ADDRESS, sizeOffset), ClapBindings.EVENTS_SIZE_DESC);
            eventGet = ClapBindings.downcallHandle(events.get(ValueLayout.ADDRESS, getOffset), ClapBindings.EVENTS_GET_DESC);
            set("pluginSegment", MemorySegment.NULL);
            set("pluginProcess", MethodHandles.lookup().findVirtual(Fixture.class, "process",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)).bindTo(this));
            set("paramsGetValue", MethodHandles.lookup().findVirtual(Fixture.class, "getParameter",
                    MethodType.methodType(boolean.class, MemorySegment.class, int.class, MemorySegment.class)).bindTo(this));
            set("processing", true);
        }

        @SuppressWarnings("unused")
        private boolean getParameter(MemorySegment plugin, int id, MemorySegment value) {
            value.set(ValueLayout.JAVA_DOUBLE, 0, (double) gain);
            return id == 7;
        }

        @SuppressWarnings("unused")
        private int process(MemorySegment plugin, MemorySegment process) throws Throwable {
            eventCount = (int) eventSize.invokeExact(events);
            for (int index = 0; index < eventCount; index++) {
                MemorySegment event = ((MemorySegment) eventGet.invokeExact(events, index))
                        .reinterpret(ClapBindings.CLAP_EVENT_PARAM_VALUE_LAYOUT.byteSize());
                parameterId = (int) ClapBindings.PARAM_EVENT_PARAM_ID.get(event, 0L);
                eventType = (short) ClapBindings.PARAM_EVENT_TYPE.get(event, 0L);
                gain = (float) (double) ClapBindings.PARAM_EVENT_VALUE.get(event, 0L);
            }
            for (int frame = 0; frame < 32; frame++) output.setAtIndex(ValueLayout.JAVA_FLOAT, frame, gain);
            return ClapBindings.CLAP_PROCESS_CONTINUE;
        }

        private void set(String name, Object value) throws Exception {
            var field = ClapPluginHost.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(host, value);
        }

        private Object get(String name) throws Exception {
            var field = ClapPluginHost.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(host);
        }

        @Override public void close() { arena.close(); }
    }
}
