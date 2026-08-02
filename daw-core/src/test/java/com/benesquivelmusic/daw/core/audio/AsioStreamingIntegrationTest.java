package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AsioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.AudioBlock;
import com.benesquivelmusic.daw.sdk.audio.AudioFormat;
import com.benesquivelmusic.daw.sdk.audio.BufferSizeRange;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 311 — end-to-end streaming smoke test against a real, installed ASIO
 * driver: open the default device, confirm at least one genuine
 * {@code bufferSwitch} reaches {@link AsioBackend#inputBlocks()}, then close
 * cleanly with no leaked driver state.
 *
 * <p>The test lives in {@code daw-core} rather than next to
 * {@code AsioBackend} in {@code daw-sdk} for two reasons, both of which also
 * put {@code NativeLibraryDetectorTest}'s asioshim assertions here:</p>
 * <ol>
 *   <li>{@link NativeLibraryDetector} is a {@code daw-core} class, and
 *       {@code daw-sdk} is the API floor — it must not depend on
 *       {@code daw-core}.</li>
 *   <li>Only {@code daw-core}'s Surefire sets
 *       {@code -Djava.library.path=${native.libs.dir}}, and only
 *       {@code daw-core} runs <em>after</em> the CMake native build that
 *       produces {@code asioshim.dll}. A {@code daw-sdk} test could never see
 *       the library.</li>
 * </ol>
 *
 * <p>Gating, in order: Windows only (the story's stated target), then the
 * {@code requireOrAssumeAsioshim()} pattern copied from
 * {@code NativeLibraryDetectorTest} (hard failure when
 * {@code DAW_REQUIRE_ASIOSHIM=1}, a clean skip otherwise), then a skip when
 * no ASIO driver is installed on the machine.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class AsioStreamingIntegrationTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 32);

    @Test
    void openStreamsRealBufferSwitchCallbacksAndClosesCleanly() throws Exception {
        requireOrAssumeAsioshim();
        AsioBackend backend = new AsioBackend();
        assumeTrue(!backend.listDevices().isEmpty(), "no ASIO driver installed");

        DeviceId device = DeviceId.defaultFor("ASIO");
        // bufferSizeRange() can only reach ASIOGetBufferSize once a driver is
        // loaded, which open() is what does — so the first attempt uses the
        // portable DEFAULT_RANGE preferred value.
        BufferSizeRange range = backend.bufferSizeRange(device);
        int bufferFrames = range.preferred();

        CountDownLatch firstBlock = new CountDownLatch(1);
        AtomicReference<AudioBlock> captured = new AtomicReference<>();
        AtomicInteger blockCount = new AtomicInteger();
        backend.inputBlocks().subscribe(new Flow.Subscriber<AudioBlock>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AudioBlock item) {
                blockCount.incrementAndGet();
                // Story 311 marshals capture off the driver's real-time thread
                // via asio-input-drain, which allocates a fresh AudioBlock per
                // callback — the block is immutable and safe to retain.
                captured.compareAndSet(null, item);
                firstBlock.countDown();
            }

            @Override public void onError(Throwable throwable) { }

            @Override public void onComplete() { }
        });

        try {
            backend.open(device, FORMAT, bufferFrames);
        } catch (AudioBackendException rejected) {
            // An exotic driver may reject the portable default size outright.
            // Skipping keeps the lane honest instead of failing on hardware
            // the story does not control.
            assumeTrue(false, "ASIO driver rejected the requested buffer size: "
                    + rejected.getMessage());
            return;
        }

        try {
            assertThat(backend.isOpen()).isTrue();
            assertThat(firstBlock.await(5, TimeUnit.SECONDS))
                    .as("at least one real ASIO bufferSwitch must reach inputBlocks() "
                            + "within 5 s of ASIOStart")
                    .isTrue();
            AudioBlock block = captured.get();
            assertThat(block).isNotNull();
            assertThat(block.channels()).isEqualTo(FORMAT.channels());
            assertThat(block.frames()).isEqualTo(bufferFrames);
            assertThat(blockCount.get()).isPositive();
        } finally {
            backend.close();
        }

        assertThat(backend.isOpen()).isFalse();
        assertThatCode(backend::close)
                .as("a second close() must be a safe no-op")
                .doesNotThrowAnyException();
    }

    /**
     * Story 224 — gates the dedicated Windows-with-shim CI lane (the
     * {@code windows-asioshim.yml} workflow exports
     * {@code DAW_REQUIRE_ASIOSHIM=1}). Copied verbatim from
     * {@code NativeLibraryDetectorTest} so both suites gate identically.
     */
    private static void requireOrAssumeAsioshim() {
        boolean available = NativeLibraryDetector.isAvailable("asioshim");
        if ("1".equals(System.getenv("DAW_REQUIRE_ASIOSHIM"))) {
            assertThat(available)
                    .as("DAW_REQUIRE_ASIOSHIM=1 — asioshim.dll must be on the "
                            + "FFM library path. Ensure the native build ran "
                            + "with -DASIO_SDK_DIR=... before mvn verify.")
                    .isTrue();
        } else {
            assumeTrue(available,
                    "asioshim.dll not on java.library.path — skip "
                            + "(build the native libs with -DASIO_SDK_DIR=...; "
                            + "set DAW_REQUIRE_ASIOSHIM=1 to fail instead of skip)");
        }
    }
}
