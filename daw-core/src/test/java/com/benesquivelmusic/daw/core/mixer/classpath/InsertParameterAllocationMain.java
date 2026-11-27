package com.benesquivelmusic.daw.core.mixer.classpath;

import com.benesquivelmusic.daw.core.dsp.BandwidthExtender;
import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import java.lang.management.ManagementFactory;

/**
 * The program {@code InsertParameterRealtimeTest} runs in a JVM of its own,
 * under {@code -Xint}: it measures the bytes the measuring thread allocates
 * while an insert's parameters change — the UI writes and the audio thread's
 * drain — for every processor of the rack. In the test's own JVM the
 * measuring thread is also the one whose call counts trip the JIT's
 * compile requests, and HotSpot allocates on the requesting thread (it loads
 * the classes a method's signature names and resolves its string constants),
 * so the figure there depends on when the compiler queue gets to a method.
 * An interpreted JVM makes no compile request, and no escape analysis can
 * hide an allocation either.
 *
 * <p>It prints {@code supported\tfalse} and ends when the JVM cannot measure
 * a thread's allocation. Otherwise it prints {@code supported\ttrue}, then
 * one line per processor of the rack,
 * {@code processor\t<label>\t<bypassed>\t<allocated bytes>}, the label the
 * {@link InsertEffectType} name for a registry type, and last the same line
 * for {@link AllocatingParameterProcessor}, whose setter allocates, with
 * {@code fixture} in place of {@code processor}. It exits 0; anything thrown
 * ends it with its stack trace and a non-zero exit.</p>
 *
 * <p>Its package is one daw.core does not have, so the child's class path can
 * hold it: a package of the named module would hide it.</p>
 */
public final class InsertParameterAllocationMain {

    /** The label of the linear-phase graphic EQ, measured beside the registry's own. */
    public static final String LINEAR_PHASE_GRAPHIC_EQ = "GraphicEqProcessor, linear phase";
    /** The labels of the processors measured beside the registry's types, in their order. */
    public static final String[] EXTRA_PROCESSORS = {"BandwidthExtender", "ExciterProcessor", LINEAR_PHASE_GRAPHIC_EQ};

    private static final int ITERATIONS = 2_000;

    private InsertParameterAllocationMain() {
    }

    /**
     * Runs the program.
     *
     * @param args none
     */
    public static void main(String[] args) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            System.out.println("supported\tfalse");
            System.out.flush();
            System.exit(0);
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.println("supported\ttrue");
        ProcessorRegistry registry = new ProcessorRegistry();
        for (InsertEffectType type : registry.availableTypes()) {
            measure(bean, "processor", type.name(), registry.createProcessor(type, 2, 48_000));
        }
        measure(bean, "processor", EXTRA_PROCESSORS[0], new BandwidthExtender(2, 48_000));
        measure(bean, "processor", EXTRA_PROCESSORS[1], new ExciterProcessor(2, 48_000));
        GraphicEqProcessor linearEq = new GraphicEqProcessor(2, 48_000);
        linearEq.setFilterMode(GraphicEqProcessor.FilterMode.LINEAR_PHASE);
        linearEq.setBandGain(3, 6);
        measure(bean, "processor", EXTRA_PROCESSORS[2], linearEq);
        measure(bean, "fixture", AllocatingParameterProcessor.class.getSimpleName(),
                new AllocatingParameterProcessor(2));
        System.out.flush();
        System.exit(0);
    }

    private static void measure(com.sun.management.ThreadMXBean bean, String kind, String label,
                                AudioProcessor processor) {
        InsertSlot slot = new InsertSlot(processor.getClass().getSimpleName(), processor);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                changeParameters(slot, i);
            }
            long thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread);
            for (int i = 0; i < ITERATIONS; i++) {
                changeParameters(slot, i);
            }
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            System.out.println(kind + "\t" + label + "\t" + slot.isBypassed() + "\t" + allocated);
        } finally {
            slot.disposeAfterQuiescence();
        }
    }

    private static void changeParameters(InsertSlot slot, int iteration) {
        PluginParameterStore store = slot.getParameterStore();
        for (int index = 0; index < store.parameterCount(); index++) {
            PluginParameter parameter = store.parameter(index);
            double value = parameter.defaultValue();
            if ((iteration & 1) != 0) {
                value += (parameter.maxValue() - value) * 0.1;
            }
            store.writeFromUi(index, value);
        }
        slot.drainParametersToAudio();
    }
}
