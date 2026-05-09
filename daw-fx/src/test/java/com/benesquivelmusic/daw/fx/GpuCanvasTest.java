package com.benesquivelmusic.daw.fx;

import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@ExtendWith(JavaFxToolkitExtension.class)
class GpuCanvasTest {

    @Test
    void defaultsAreSensible() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas();

            assertThat(canvas.getRenderer()).isSameAs(GpuRenderer.NOOP);
            assertThat(canvas.isAnimated()).isFalse();
            assertThat(canvas.getClearColor()).isNull();
            assertThat(canvas.getFrameCount()).isZero();
            assertThat(canvas.isResizable()).isTrue();
            assertThat(canvas.getStyleClass()).contains("gpu-canvas");
        });
    }

    @Test
    void prefAndMinSizesAreFinite() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas();

            assertThat(canvas.prefWidth(-1)).isEqualTo(250.0);
            assertThat(canvas.prefHeight(-1)).isEqualTo(250.0);
            assertThat(canvas.minWidth(-1)).isEqualTo(50.0);
            assertThat(canvas.minHeight(-1)).isEqualTo(50.0);
            assertThat(canvas.maxWidth(-1)).isEqualTo(Double.MAX_VALUE);
            assertThat(canvas.maxHeight(-1)).isEqualTo(Double.MAX_VALUE);
        });
    }

    @Test
    void requestRenderInvokesRendererWithSurfaceMetadata() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            int[] observed = new int[3]; // width, height, stride
            long[] observedByteSize = { -1L };
            long[] observedFrame = { -1L };
            GpuCanvas canvas = GpuCanvas.create()
                    .renderer(ctx -> {
                        if (calls.getAndIncrement() == 0) {
                            observed[0] = ctx.width();
                            observed[1] = ctx.height();
                            observed[2] = ctx.stride();
                            observedByteSize[0] = ctx.pixels().byteSize();
                            observedFrame[0] = ctx.frameNumber();
                        }
                    })
                    .clearColor(Color.BLACK)
                    .build();

            // Size-change renders are deferred (coalesced); requestRender is
            // the synchronous trigger we observe here.
            canvas.resize(320, 200);
            canvas.requestRender();

            assertThat(calls.get()).isGreaterThanOrEqualTo(1);
            assertThat(observed[0]).isEqualTo(320);
            assertThat(observed[1]).isEqualTo(200);
            assertThat(observed[2]).isEqualTo(320 * 4);
            assertThat(observedByteSize[0]).isEqualTo(200L * 320 * 4);
            assertThat(observedFrame[0]).isZero();
        });
    }

    @Test
    void firstFrameIsZeroIndexed() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            long[] firstFrame = { -1L };
            GpuCanvas canvas = new GpuCanvas(ctx -> {
                if (firstFrame[0] == -1L) {
                    firstFrame[0] = ctx.frameNumber();
                }
            });
            canvas.resize(64, 32);
            canvas.requestRender();

            assertThat(firstFrame[0]).isZero();
            assertThat(canvas.getFrameCount()).isGreaterThanOrEqualTo(1L);
        });
    }

    @Test
    void deltaSecondsIsZeroForOneOffRenders() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            double[] observedDelta = { -1.0 };
            GpuCanvas canvas = new GpuCanvas(ctx -> observedDelta[0] = ctx.deltaSeconds());
            canvas.resize(100, 100);

            canvas.requestRender();
            assertThat(observedDelta[0]).isZero();

            canvas.requestRender();
            assertThat(observedDelta[0]).isZero();
        });
    }

    @Test
    void requestRenderIsNoopForZeroSize() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            GpuCanvas canvas = new GpuCanvas(ctx -> calls.incrementAndGet());

            canvas.requestRender();

            assertThat(calls.get()).isZero();
            assertThat(canvas.getFrameCount()).isZero();
        });
    }

    @Test
    void changingRendererTriggersRender() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            GpuCanvas canvas = new GpuCanvas();
            canvas.resize(100, 100);

            canvas.setRenderer(ctx -> calls.incrementAndGet());

            assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void setRendererRejectsNull() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas();

            assertThatNullPointerException().isThrownBy(() -> canvas.setRenderer(null));
        });
    }

    @Test
    void disposeIsIdempotentAndStopsFurtherRenders() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            GpuCanvas canvas = new GpuCanvas(ctx -> calls.incrementAndGet());
            canvas.resize(50, 50);
            canvas.requestRender();
            int countBeforeDispose = calls.get();

            canvas.dispose();
            canvas.dispose(); // second call must not throw
            canvas.requestRender();

            assertThat(calls.get()).isEqualTo(countBeforeDispose);
        });
    }

    @Test
    void disposeReleasesTheArena() throws InterruptedException {
        AtomicReference<MemorySegment> captured = new AtomicReference<>();
        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas(ctx -> captured.set(ctx.pixels()));
            canvas.resize(8, 8);
            canvas.requestRender();
            MemorySegment segment = captured.get();
            assertThat(segment).isNotNull();
            // While alive, the segment is readable.
            assertThat(segment.get(ValueLayout.JAVA_BYTE, 0L)).isEqualTo((byte) 0);

            canvas.dispose();
        });

        // The arena close is deferred via Platform.runLater; drain the FX
        // event queue so the close executes before we assert.
        JavaFxToolkitExtension.runAndWait(() -> {
            MemorySegment segment = captured.get();
            // After the owning arena closes, any access throws ISE.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> segment.get(ValueLayout.JAVA_BYTE, 0L));
        });
    }

    @Test
    void disposeBeforeFirstRenderIsHarmless() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas();

            canvas.dispose();
            canvas.requestRender();
            canvas.dispose();

            assertThat(canvas.getFrameCount()).isZero();
        });
    }

    @Test
    void postDisposePropertyMutationsDoNotRender() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            GpuCanvas canvas = new GpuCanvas(GpuRenderer.NOOP);
            canvas.resize(20, 20);
            canvas.requestRender();
            canvas.dispose();

            canvas.setRenderer(ctx -> calls.incrementAndGet());
            canvas.setClearColor(Color.RED);
            canvas.requestRender();

            assertThat(calls.get()).isZero();
        });
    }

    @Test
    void requestRenderFromWrongThreadThrows() throws Exception {
        GpuCanvas[] holder = new GpuCanvas[1];
        JavaFxToolkitExtension.runAndWait(() -> {
            holder[0] = new GpuCanvas();
            holder[0].resize(10, 10);
        });
        GpuCanvas canvas = holder[0];

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(canvas::requestRender)
                .withMessageContaining("JavaFX Application Thread");

        JavaFxToolkitExtension.runAndWait(canvas::dispose);
    }

    @Test
    void disposeFromWrongThreadThrows() throws Exception {
        GpuCanvas[] holder = new GpuCanvas[1];
        JavaFxToolkitExtension.runAndWait(() -> holder[0] = new GpuCanvas());
        GpuCanvas canvas = holder[0];

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(canvas::dispose)
                .withMessageContaining("JavaFX Application Thread");

        JavaFxToolkitExtension.runAndWait(canvas::dispose);
    }

    @Test
    void rendererWritesAreVisibleInTheSegment() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicReference<MemorySegment> captured = new AtomicReference<>();
            // Write opaque red at pixel (0,0): BGRA = 00 00 FF FF.
            GpuCanvas canvas = new GpuCanvas(ctx -> {
                MemorySegment p = ctx.pixels();
                p.set(ValueLayout.JAVA_BYTE, 0L, (byte) 0x00); // B
                p.set(ValueLayout.JAVA_BYTE, 1L, (byte) 0x00); // G
                p.set(ValueLayout.JAVA_BYTE, 2L, (byte) 0xFF); // R
                p.set(ValueLayout.JAVA_BYTE, 3L, (byte) 0xFF); // A
                captured.set(p);
            });
            canvas.resize(4, 4);
            canvas.requestRender();

            MemorySegment p = captured.get();
            assertThat(p).isNotNull();
            assertThat(p.get(ValueLayout.JAVA_BYTE, 0L)).isEqualTo((byte) 0x00);
            assertThat(p.get(ValueLayout.JAVA_BYTE, 1L)).isEqualTo((byte) 0x00);
            assertThat(p.get(ValueLayout.JAVA_BYTE, 2L)).isEqualTo((byte) 0xFF);
            assertThat(p.get(ValueLayout.JAVA_BYTE, 3L)).isEqualTo((byte) 0xFF);
        });
    }

    @Test
    void writableImageReflectsRenderedPixels() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            // Opaque red at every pixel: BGRA-pre = 00 00 FF FF.
            GpuCanvas canvas = new GpuCanvas(ctx -> {
                MemorySegment p = ctx.pixels();
                for (long off = 0; off < p.byteSize(); off += 4) {
                    p.set(ValueLayout.JAVA_BYTE, off + 0L, (byte) 0x00);
                    p.set(ValueLayout.JAVA_BYTE, off + 1L, (byte) 0x00);
                    p.set(ValueLayout.JAVA_BYTE, off + 2L, (byte) 0xFF);
                    p.set(ValueLayout.JAVA_BYTE, off + 3L, (byte) 0xFF);
                }
            });
            canvas.resize(2, 2);
            canvas.requestRender();

            // PixelReader on the WritableImage reads through the PixelBuffer's
            // ByteBuffer, which aliases the FFM segment. This proves the
            // pathway is wired end-to-end (segment → ByteBuffer → PixelBuffer
            // → WritableImage), not just that we can read back our own writes.
            ImageView iv = (ImageView) canvas.getChildrenUnmodifiable().get(0);
            WritableImage img = (WritableImage) iv.getImage();
            assertThat(img).isNotNull();
            Color colour = img.getPixelReader().getColor(0, 0);
            assertThat(colour.getRed()).isEqualTo(1.0);
            assertThat(colour.getGreen()).isEqualTo(0.0);
            assertThat(colour.getBlue()).isEqualTo(0.0);
            assertThat(colour.getOpacity()).isEqualTo(1.0);

            canvas.dispose();
        });
    }

    @Test
    void clearColorBlackFillsBgraPreOpaqueZeros() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicReference<MemorySegment> captured = new AtomicReference<>();
            GpuCanvas canvas = GpuCanvas.create()
                    .renderer(ctx -> captured.set(ctx.pixels()))
                    .clearColor(Color.BLACK)
                    .build();
            canvas.resize(2, 2);
            canvas.requestRender();

            MemorySegment p = captured.get();
            assertThat(p).isNotNull();
            // Opaque black, BGRA pre = 00 00 00 FF for every pixel.
            for (int i = 0; i < 4; i++) {
                long base = i * 4L;
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 0L)).isEqualTo((byte) 0x00);
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 1L)).isEqualTo((byte) 0x00);
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 2L)).isEqualTo((byte) 0x00);
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 3L)).isEqualTo((byte) 0xFF);
            }
        });
    }

    @Test
    void nullClearColorFillsTransparentZeros() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicReference<MemorySegment> captured = new AtomicReference<>();
            GpuCanvas canvas = new GpuCanvas(ctx -> captured.set(ctx.pixels()));
            // clearColor defaults to null
            canvas.resize(2, 2);
            canvas.requestRender();

            MemorySegment p = captured.get();
            assertThat(p).isNotNull();
            // All bytes zero — fully transparent.
            for (long i = 0; i < p.byteSize(); i++) {
                assertThat(p.get(ValueLayout.JAVA_BYTE, i))
                        .as("byte at offset %d", i)
                        .isEqualTo((byte) 0x00);
            }
        });
    }

    @Test
    void clearColorPremultipliedWhiteFillsAllOnes() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicReference<MemorySegment> captured = new AtomicReference<>();
            GpuCanvas canvas = GpuCanvas.create()
                    .renderer(ctx -> captured.set(ctx.pixels()))
                    .clearColor(Color.WHITE)
                    .build();
            canvas.resize(3, 3);
            canvas.requestRender();

            MemorySegment p = captured.get();
            assertThat(p).isNotNull();
            // Opaque white, BGRA pre = FF FF FF FF for every byte.
            for (long i = 0; i < p.byteSize(); i++) {
                assertThat(p.get(ValueLayout.JAVA_BYTE, i))
                        .as("byte at offset %d", i)
                        .isEqualTo((byte) 0xFF);
            }
        });
    }

    @Test
    void clearColorWithDistinctChannelsTilesCorrectly() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicReference<MemorySegment> captured = new AtomicReference<>();
            // Opaque RGB(16,32,48) → premultiplied BGRA = [0x30, 0x20, 0x10, 0xFF]
            // per pixel. All four bytes distinct, so the doubling tile-fill
            // path is exercised (no uniform-byte short-circuit).
            GpuCanvas canvas = GpuCanvas.create()
                    .renderer(ctx -> captured.set(ctx.pixels()))
                    .clearColor(Color.rgb(16, 32, 48))
                    .build();
            // 3 × 5 = 15 pixels = 60 bytes. Doubling fill steps:
            // 4 → 8 → 16 → 32 → +28 (tail copy via Math.min).
            canvas.resize(3, 5);
            canvas.requestRender();

            MemorySegment p = captured.get();
            assertThat(p).isNotNull();
            assertThat(p.byteSize()).isEqualTo(60L);
            for (long pixel = 0; pixel < 15; pixel++) {
                long base = pixel * 4L;
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 0L))
                        .as("B at pixel %d", pixel).isEqualTo((byte) 0x30);
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 1L))
                        .as("G at pixel %d", pixel).isEqualTo((byte) 0x20);
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 2L))
                        .as("R at pixel %d", pixel).isEqualTo((byte) 0x10);
                assertThat(p.get(ValueLayout.JAVA_BYTE, base + 3L))
                        .as("A at pixel %d", pixel).isEqualTo((byte) 0xFF);
            }

            canvas.dispose();
        });
    }

    @Test
    void resizeReplacesTheUnderlyingSegment() throws InterruptedException {
        AtomicReference<MemorySegment> captured = new AtomicReference<>();
        AtomicReference<MemorySegment> firstRef = new AtomicReference<>();
        GpuCanvas[] holder = new GpuCanvas[1];

        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas(ctx -> captured.set(ctx.pixels()));
            holder[0] = canvas;

            canvas.resize(10, 10);
            canvas.requestRender();
            MemorySegment first = captured.get();
            assertThat(first).isNotNull();
            assertThat(first.byteSize()).isEqualTo(10L * 10 * 4);
            firstRef.set(first);

            canvas.resize(20, 15);
            canvas.requestRender();
            MemorySegment second = captured.get();
            assertThat(second).isNotNull();
            assertThat(second.byteSize()).isEqualTo(20L * 15 * 4);
            assertThat(second).isNotSameAs(first);
        });

        // The old arena's close was deferred via Platform.runLater; drain
        // the FX event queue so the close executes before we assert.
        JavaFxToolkitExtension.runAndWait(() -> {
            MemorySegment first = firstRef.get();
            // The first segment's arena was closed on the previous pulse —
            // accessing it must now throw.
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> first.get(ValueLayout.JAVA_BYTE, 0L));

            holder[0].dispose();
        });
    }

    @Test
    void resizeToZeroReleasesArenaAndReallocatesOnRegrow() throws InterruptedException {
        AtomicReference<MemorySegment> captured = new AtomicReference<>();
        AtomicReference<MemorySegment> aliveRef = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        GpuCanvas[] holder = new GpuCanvas[1];

        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas(ctx -> {
                captured.set(ctx.pixels());
                calls.incrementAndGet();
            });
            holder[0] = canvas;

            canvas.resize(50, 50);
            canvas.requestRender();
            aliveRef.set(captured.get());
            assertThat(aliveRef.get()).isNotNull();
        });

        // Drain any pending coalesced size-change renders posted by the
        // initial resize(50, 50) before sampling the baseline call count.
        // Without this, a deferred renderOneFrame from the size-change
        // listener could fire between runAndWait blocks and inflate
        // calls.get() relative to callsAfterFirst.
        AtomicInteger callsAfterFirstHolder = new AtomicInteger();
        JavaFxToolkitExtension.runAndWait(() -> callsAfterFirstHolder.set(calls.get()));
        int callsAfterFirst = callsAfterFirstHolder.get();

        // Shrink to zero — surface released (deferred), render is a no-op.
        JavaFxToolkitExtension.runAndWait(() -> {
            holder[0].resize(0, 0);
            holder[0].requestRender();
            assertThat(calls.get()).isEqualTo(callsAfterFirst);
        });

        // Drain the deferred Platform.runLater(arena::close).
        JavaFxToolkitExtension.runAndWait(() -> {
            MemorySegment alive = aliveRef.get();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> alive.get(ValueLayout.JAVA_BYTE, 0L));

            // Regrow — fresh arena and segment.
            captured.set(null);
            holder[0].resize(20, 20);
            holder[0].requestRender();
            MemorySegment regrown = captured.get();
            assertThat(regrown).isNotNull();
            assertThat(regrown.byteSize()).isEqualTo(20L * 20 * 4);

            holder[0].dispose();
        });
    }

    @Test
    void rendererExceptionIsSwallowedAndDoesNotAdvanceFrameCount() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            GpuCanvas canvas = new GpuCanvas(ctx -> {
                calls.incrementAndGet();
                throw new RuntimeException("simulated renderer failure");
            });
            canvas.resize(8, 8);

            // Throws inside render — must not propagate out of requestRender,
            // and frameCount must not advance for failed frames.
            canvas.requestRender();

            assertThat(calls.get()).isEqualTo(1);
            assertThat(canvas.getFrameCount()).isZero();

            canvas.dispose();
        });
    }

    @Test
    void builderPropagatesAllSettings() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            GpuRenderer custom = ctx -> { };

            GpuCanvas canvas = GpuCanvas.create()
                    .renderer(custom)
                    .animated(false)
                    .clearColor(Color.web("#101418"))
                    .prefSize(640, 480)
                    .build();

            assertThat(canvas.getRenderer()).isSameAs(custom);
            assertThat(canvas.isAnimated()).isFalse();
            assertThat(canvas.getClearColor()).isEqualTo(Color.web("#101418"));
            assertThat(canvas.getPrefWidth()).isEqualTo(640.0);
            assertThat(canvas.getPrefHeight()).isEqualTo(480.0);
        });
    }

    @Test
    void getActivePipelineMatchesStaticDetection() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            GpuCanvas canvas = new GpuCanvas();

            assertThat(canvas.getActivePipeline()).isEqualTo(GpuPipeline.detect());
        });
    }
}
