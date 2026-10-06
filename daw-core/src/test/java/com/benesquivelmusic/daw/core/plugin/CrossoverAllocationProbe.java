package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** Strict allocation contract, launched with the parent's JDK in interpreted mode. */
public final class CrossoverAllocationProbe {

    private static volatile Object allocationProbe;
    private static boolean allocateInWorkload;

    private CrossoverAllocationProbe() { }

    public static void main(String[] arguments) {
        String vmInfo = System.getProperty("java.vm.info", "");
        if (!vmInfo.contains("interpreted mode")) {
            throw new AssertionError("allocation probe requires interpreted mode: " + vmInfo);
        }
        System.out.println("allocation probe JDK " + System.getProperty("java.runtime.version") + ": " + vmInfo);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported() || Thread.currentThread().isVirtual()) {
            throw new AssertionError("platform-thread allocation counter is unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        var plugin = new MultibandCompressorPlugin();
        plugin.initialize(new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) { }
        });
        plugin.setBandCount(5);
        try {
            var slot = InsertEffectFactory.createSlotFromPlugin(plugin).orElseThrow();
            updateCrossovers(slot, 50000);
            // Warm the exact scalar counter call before the fixed measurement windows.
            measureCrossoverAllocations(slot, bean);
            allocateInWorkload = arguments.length == 1 && arguments[0].equals("--allocate-in-workload");
            for (int batch = 0; batch < 5; batch++) {
                long allocated = measureCrossoverAllocations(slot, bean);
                if (allocated != 0) {
                    throw new AssertionError("measurement batch " + batch + " allocated " + allocated + " bytes");
                }
                System.out.println("measurement batch " + batch + " allocated " + allocated + " bytes");
            }
            long thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread);
            allocationProbe = new byte[1024];
            long controlBytes = bean.getThreadAllocatedBytes(thread) - before;
            if (controlBytes < 1024) {
                throw new AssertionError("escaped allocation counter control was not detected: " + controlBytes);
            }
            if (slot.isBypassed()) {
                throw new AssertionError("parameter drain faulted and bypassed the slot");
            }
            if (!Arrays.equals(plugin.getProcessor().getCrossoverFrequencies(),
                    new double[] {4099, 6099, 8099, 10099})) {
                throw new AssertionError("parameter drain did not apply the measured crossover writes");
            }
        } finally {
            allocationProbe = null;
            plugin.dispose();
        }
    }

    private static long measureCrossoverAllocations(InsertSlot slot, com.sun.management.ThreadMXBean bean) {
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        if (before < 0) {
            throw new AssertionError("platform-thread allocation counter is unavailable");
        }
        updateCrossovers(slot, 10000);
        return bean.getThreadAllocatedBytes(thread) - before;
    }

    private static void updateCrossovers(InsertSlot slot, int iterations) {
        var store = slot.getParameterStore();
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int index = 2; index <= 5; index++) {
                store.writeFromUi(index, index * 2000.0 + iteration % 100);
            }
            slot.drainParametersToAudio();
        }
        if (allocateInWorkload) {
            allocationProbe = new byte[1024];
        }
    }
}
