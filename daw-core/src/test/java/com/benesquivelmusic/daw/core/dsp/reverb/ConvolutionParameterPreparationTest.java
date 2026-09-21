package com.benesquivelmusic.daw.core.dsp.reverb;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConvolutionParameterPreparationTest {
    @Test
    void audioParameterSettersPublishWithoutAllocatingOrSubmittingTasks() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        try (var processor = new ConvolutionReverbProcessor(1, 48_000)) {
            processor.close();
            for (int iteration = 0; iteration < 10_000; iteration++) changeParameters(processor, iteration);
            long thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread);
            for (int iteration = 0; iteration < 10_000; iteration++) changeParameters(processor, iteration);
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            assertThat(allocated).isLessThan(1024);
        }
    }

    @Test
    void trimRecallCanCrossThePreviousTrimBoundsAndBuildsTheLatestKernel() {
        try (var processor = new ConvolutionReverbProcessor(1, 48_000)) {
            processor.setImpulseResponse(new float[][]{new float[1_000]});
            processor.setTrimStart(0.1);
            processor.setTrimEnd(0.3);
            processor.awaitIrPreparation();
            assertThat(processor.getImpulseResponseLength()).isEqualTo(200);
            processor.setTrimStart(0.5);
            processor.setTrimEnd(0.8);
            processor.setStretch(2.0);
            processor.awaitIrPreparation();
            assertThat(processor.getTrimStart()).isEqualTo(0.5);
            assertThat(processor.getTrimEnd()).isEqualTo(0.8);
            assertThat(processor.getImpulseResponseLength()).isEqualTo(600);
        }
    }

    private static void changeParameters(ConvolutionReverbProcessor processor, int iteration) {
        processor.setIrSelection(iteration % 4);
        processor.setStretch(iteration % 2 == 0 ? 1.0 : 2.0);
        processor.setTrimStart(iteration % 2 == 0 ? 0.1 : 0.5);
        processor.setTrimEnd(iteration % 2 == 0 ? 0.3 : 0.8);
    }
}
