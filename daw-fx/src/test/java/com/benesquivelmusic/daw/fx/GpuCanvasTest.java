package com.benesquivelmusic.daw.fx;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
    void requestRenderInvokesRendererWithCanvasSize() throws InterruptedException {
        JavaFxToolkitExtension.runAndWait(() -> {
            AtomicInteger calls = new AtomicInteger();
            double[] observedSize = new double[2];
            GpuCanvas canvas = GpuCanvas.create()
                    .renderer(ctx -> {
                        calls.incrementAndGet();
                        observedSize[0] = ctx.width();
                        observedSize[1] = ctx.height();
                    })
                    .clearColor(Color.BLACK)
                    .build();
            canvas.resize(320, 200);

            canvas.requestRender();

            assertThat(calls.get()).isGreaterThanOrEqualTo(1);
            assertThat(observedSize[0]).isEqualTo(320.0);
            assertThat(observedSize[1]).isEqualTo(200.0);
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
