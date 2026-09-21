package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.dsp.BandwidthExtender;
import com.benesquivelmusic.daw.core.dsp.GraphicEqProcessor;
import com.benesquivelmusic.daw.core.dsp.saturation.ExciterProcessor;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InsertParameterRealtimeTest {
    @Test
    void everyRackProcessorAppliesParameterChangesWithoutAllocatingOnTheAudioThread() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        var registry = new ProcessorRegistry();
        var processors = new ArrayList<AudioProcessor>();
        for (var type : registry.availableTypes()) {
            processors.add(registry.createProcessor(type, 2, 48_000));
        }
        processors.add(new BandwidthExtender(2, 48_000));
        processors.add(new ExciterProcessor(2, 48_000));
        var linearEq = new GraphicEqProcessor(2, 48_000);
        linearEq.setFilterMode(GraphicEqProcessor.FilterMode.LINEAR_PHASE);
        linearEq.setBandGain(3, 6);
        processors.add(linearEq);
        for (AudioProcessor processor : processors) {
            var slot = new InsertSlot(processor.getClass().getSimpleName(), processor);
            try {
                for (int i = 0; i < 2_000; i++) changeParameters(slot, i);
                long thread = Thread.currentThread().threadId();
                long before = bean.getThreadAllocatedBytes(thread);
                for (int i = 0; i < 2_000; i++) changeParameters(slot, i);
                long allocated = bean.getThreadAllocatedBytes(thread) - before;
                assertThat(slot.isBypassed()).as(processor.getClass().getSimpleName()).isFalse();
                assertThat(allocated).as(processor.getClass().getSimpleName()).isLessThan(1024);
            } finally {
                slot.disposeAfterQuiescence();
            }
        }
    }

    private static void changeParameters(InsertSlot slot, int iteration) {
        var store = slot.getParameterStore();
        for (int index = 0; index < store.parameterCount(); index++) {
            var parameter = store.parameter(index);
            double value = parameter.defaultValue();
            if ((iteration & 1) != 0) {
                value += (parameter.maxValue() - value) * 0.1;
            }
            store.writeFromUi(index, value);
        }
        slot.drainParametersToAudio();
    }
}
