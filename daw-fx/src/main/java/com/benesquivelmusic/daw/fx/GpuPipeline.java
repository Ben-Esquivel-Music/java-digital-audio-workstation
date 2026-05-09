package com.benesquivelmusic.daw.fx;

import java.lang.reflect.Method;

/**
 * Active JavaFX/Prism rendering backend.
 *
 * <p>JavaFX rasterises its scene graph and {@link javafx.scene.canvas.Canvas}
 * surfaces through Prism, which selects a pipeline at startup based on
 * platform capabilities and the {@code prism.order} system property. This
 * enum reports which backend Prism actually picked, which is the closest a
 * downstream caller can get to "is the GPU actually doing the work?" without
 * digging into vendor-specific APIs.
 *
 * <p>{@link #detect()} is best-effort: it reflects into
 * {@code com.sun.prism.GraphicsPipeline}, which is not exported by the
 * {@code javafx.graphics} module. When the JVM denies that access (no
 * {@code --add-exports javafx.graphics/com.sun.prism=...}), {@link #detect()}
 * falls back to the {@code prism.order} property, then to {@link #UNKNOWN}.
 */
public enum GpuPipeline {

    /** Direct3D 11 — the default Prism pipeline on Windows. */
    DIRECT3D,
    /** Metal — the default Prism pipeline on macOS. */
    METAL,
    /** OpenGL ES 2 — the default Prism pipeline on Linux. */
    OPEN_GL,
    /** CPU rasteriser. Selected when no hardware pipeline is available. */
    SOFTWARE,
    /** The pipeline could not be determined. */
    UNKNOWN;

    /** Returns {@code true} if this pipeline issues commands to the GPU. */
    public boolean isHardwareAccelerated() {
        return this == DIRECT3D || this == METAL || this == OPEN_GL;
    }

    /**
     * Reports the active Prism pipeline.
     *
     * <p>Prism must be initialised for this to return anything other than
     * {@link #UNKNOWN}; in practice that means a JavaFX {@code Stage} or
     * {@code Platform.startup} has run.
     *
     * @return the detected backend, or {@link #UNKNOWN} if introspection failed
     */
    public static GpuPipeline detect() {
        GpuPipeline reflected = detectViaReflection();
        if (reflected != UNKNOWN) {
            return reflected;
        }
        return detectFromSystemProperty();
    }

    private static GpuPipeline detectViaReflection() {
        try {
            Class<?> pipelineClass = Class.forName("com.sun.prism.GraphicsPipeline");
            Method getPipeline = pipelineClass.getMethod("getPipeline");
            Object pipeline = getPipeline.invoke(null);
            if (pipeline == null) {
                return UNKNOWN;
            }
            return classify(pipeline.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return UNKNOWN;
        }
    }

    private static GpuPipeline detectFromSystemProperty() {
        String order = System.getProperty("prism.order");
        if (order == null || order.isBlank()) {
            return UNKNOWN;
        }
        String first = order.split(",", 2)[0].strip().toLowerCase();
        return switch (first) {
            case "d3d" -> DIRECT3D;
            case "mtl", "metal" -> METAL;
            case "es2", "gl", "opengl" -> OPEN_GL;
            case "sw", "j2d", "software" -> SOFTWARE;
            default -> UNKNOWN;
        };
    }

    private static GpuPipeline classify(String pipelineClassName) {
        if (pipelineClassName.contains("D3D")) return DIRECT3D;
        if (pipelineClassName.contains("MTL") || pipelineClassName.contains("Metal")) return METAL;
        if (pipelineClassName.contains("ES2") || pipelineClassName.contains("GL")) return OPEN_GL;
        if (pipelineClassName.contains("SW") || pipelineClassName.contains("Software")
                || pipelineClassName.contains("J2D")) {
            return SOFTWARE;
        }
        return UNKNOWN;
    }
}
