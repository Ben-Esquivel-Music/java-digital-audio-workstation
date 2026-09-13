package com.benesquivelmusic.daw.core.dsp.reverb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ConvolutionReverbPreparationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"synchronous", "asynchronous", "file"})
    void explicitIrLoadsRetryChangedParametersBeforePublishing(String mode) throws Exception {
        var builder = new BlockingKernelBuilder();
        Path path = writeImpulseFile();
        try (var reverb = new ConvolutionReverbProcessor(1, 48_000, builder)) {
            reverb.setImpulseResponse(impulse(1_000));
            try (var stale = builder.blockNextBuild()) {
                CompletableFuture<Void> loading = switch (mode) {
                    case "synchronous" -> CompletableFuture.runAsync(() -> reverb.setImpulseResponse(impulse(2_000)));
                    case "asynchronous" -> reverb.setImpulseResponseAsync(impulse(2_000));
                    case "file" -> reverb.loadImpulseResponseFromFileAsync(path);
                    default -> throw new AssertionError(mode);
                };
                stale.awaitReady();
                try (var latest = builder.blockNextBuild()) {
                    reverb.setStretch(2.0);
                    stale.close();
                    latest.awaitReady();
                    assertThat(reverb.getImpulseResponseLength()).isEqualTo(1_000);
                    assertThat(reverb.getImpulseResponseSourceId()).isNull();
                    latest.close();
                    loading.get(10, TimeUnit.SECONDS);
                    reverb.awaitIrPreparation();
                    assertThat(reverb.getImpulseResponseLength()).isEqualTo(4_000);
                    assertThat(reverb.getImpulseResponseSourceId())
                            .isEqualTo(mode.equals("file") ? path.toString() : null);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"asynchronous", "file"})
    void aNewBundledSelectionSupersedesAnExplicitIrStillBeingPrepared(String mode) throws Exception {
        var builder = new BlockingKernelBuilder();
        Path path = writeImpulseFile();
        try (var reverb = new ConvolutionReverbProcessor(1, 48_000, builder)) {
            reverb.setImpulseResponse(impulse(1_000));
            try (var stale = builder.blockNextBuild()) {
                CompletableFuture<Void> loading = mode.equals("file")
                        ? reverb.loadImpulseResponseFromFileAsync(path)
                        : reverb.setImpulseResponseAsync(impulse(2_000));
                stale.awaitReady();
                try (var latest = builder.blockNextBuild()) {
                    reverb.setIrSelection(1);
                    stale.close();
                    latest.awaitReady();
                    assertThat(reverb.getImpulseResponseLength()).isEqualTo(1_000);
                    assertThat(reverb.getImpulseResponseSourceId()).isNull();
                    latest.close();
                    loading.get(10, TimeUnit.SECONDS);
                    reverb.awaitIrPreparation();
                    assertThat(reverb.getImpulseResponseSourceId())
                            .isEqualTo(ImpulseResponseLibrary.ENTRIES.get(1).id());
                    assertThat(reverb.getImpulseResponseLength()).isGreaterThan(2_000);
                }
            }
        }
    }

    @Test
    void supersededTrimAndStretchKeepThePreviousKernelUntilTheLatestCandidateIsReady() throws Exception {
        var builder = new BlockingKernelBuilder();
        try (var reverb = new ConvolutionReverbProcessor(1, 48_000, builder)) {
            reverb.setImpulseResponse(impulse(1_000));
            try (var stale = builder.blockNextBuild()) {
                reverb.setStretch(2.0);
                stale.awaitReady();
                try (var latest = builder.blockNextBuild()) {
                    reverb.setTrimStart(0.25);
                    reverb.setTrimEnd(0.75);
                    stale.close();
                    latest.awaitReady();

                    assertThat(reverb.getImpulseResponseLength()).isEqualTo(1_000);
                    assertThat(reverb.getImpulseResponseSourceId()).isNull();
                    latest.close();
                    reverb.awaitIrPreparation();

                    assertThat(reverb.getImpulseResponseLength()).isEqualTo(1_000);
                    assertThat(reverb.getImpulseResponseSnapshot()[0]).containsOnly(0f);
                }
            }
        }
    }

    @Test
    void supersededBundledSelectionCannotReplaceTheCachedCustomIr() throws Exception {
        var builder = new BlockingKernelBuilder();
        try (var reverb = new ConvolutionReverbProcessor(1, 48_000, builder)) {
            reverb.setImpulseResponse(impulse(1_000));
            try (var stale = builder.blockNextBuild()) {
                reverb.setIrSelection(1);
                stale.awaitReady();
                try (var latest = builder.blockNextBuild()) {
                    reverb.setIrSelection(0);
                    reverb.setStretch(1.5);
                    stale.close();
                    latest.awaitReady();

                    assertThat(reverb.getImpulseResponseLength()).isEqualTo(1_000);
                    assertThat(reverb.getImpulseResponseSourceId()).isNull();
                    latest.close();
                    reverb.awaitIrPreparation();

                    assertThat(reverb.getImpulseResponseLength()).isEqualTo(1_500);
                    assertThat(reverb.getImpulseResponseSourceId()).isNull();
                }
            }
        }
    }

    @Test
    void closingDuringPreparationDiscardsTheUnpublishedKernel() throws Exception {
        var builder = new BlockingKernelBuilder();
        try (var reverb = new ConvolutionReverbProcessor(1, 48_000, builder)) {
            reverb.setImpulseResponse(impulse(1_000));
            try (var pending = builder.blockNextBuild()) {
                reverb.setStretch(2.0);
                pending.awaitReady();
                reverb.close();
                pending.close();
                reverb.awaitIrPreparation();
                assertThat(reverb.getImpulseResponseLength()).isEqualTo(1_000);
            }
        }
    }

    private static float[][] impulse(int length) {
        var ir = new float[1][length];
        ir[0][0] = 1;
        return ir;
    }

    private Path writeImpulseFile() throws Exception {
        Path path = directory.resolve("impulse.wav");
        var format = new AudioFormat(48_000, 16, 1, true, false);
        try (var audio = new AudioInputStream(new ByteArrayInputStream(new byte[4_000]), format, 2_000)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, path.toFile());
        }
        return path;
    }

    private static final class BlockingKernelBuilder implements ConvolutionReverbProcessor.KernelBuilder {
        private final ConcurrentLinkedQueue<BuildGate> gates = new ConcurrentLinkedQueue<>();

        BuildGate blockNextBuild() {
            var gate = new BuildGate();
            gates.add(gate);
            return gate;
        }

        @Override
        public ConvolutionReverbProcessor.Kernel build(float[][] ir, int channels, double sampleRate) {
            var kernel = ConvolutionReverbProcessor.Kernel.build(ir, channels, sampleRate);
            var gate = gates.poll();
            if (gate != null) {
                gate.ready.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release the prepared kernel");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            return kernel;
        }
    }

    private static final class BuildGate implements AutoCloseable {
        private final CountDownLatch ready = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        void awaitReady() throws InterruptedException {
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("candidate reached publication boundary").isTrue();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }
}
