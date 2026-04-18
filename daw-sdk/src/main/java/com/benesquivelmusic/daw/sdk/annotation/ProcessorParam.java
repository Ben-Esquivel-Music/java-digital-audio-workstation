package com.benesquivelmusic.daw.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a DSP processor parameter on a getter method for reflective
 * discovery by the host.
 *
 * <p>Processor parameters are typically exposed as {@code public double getXxx()}
 * / {@code public void setXxx(double value)} accessor pairs. Annotating the
 * getter with {@code @ProcessorParam} allows the host to reflectively build
 * parameter descriptors, parameter-change handlers, and parameter-value maps
 * without hand-written switch statements per processor.</p>
 *
 * <p>The matching setter is resolved by the standard JavaBeans naming
 * convention — {@code getXxx} pairs with {@code setXxx(double)}. The annotation
 * is retained at runtime so the parameter editor UI, automation system, and
 * preset serializer can introspect it at any time.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @ProcessorParam(id = 0, name = "Threshold", min = -60.0, max = 0.0,
 *                 defaultValue = -20.0, unit = "dB")
 * public double getThresholdDb() { return thresholdDb; }
 *
 * public void setThresholdDb(double thresholdDb) { this.thresholdDb = thresholdDb; }
 * }</pre>
 *
 * @see com.benesquivelmusic.daw.sdk.plugin.PluginParameter
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ProcessorParam {

    /** Stable, unique parameter id within the processor. */
    int id();

    /** Human-readable parameter name (e.g., "Threshold"). */
    String name();

    /** Minimum allowed value. */
    double min();

    /** Maximum allowed value. */
    double max();

    /** Default (reset) value; must lie within {@code [min, max]}. */
    double defaultValue();

    /** Optional unit label (e.g., "dB", "ms", "Hz", "%"). Empty string if unitless. */
    String unit() default "";
}
