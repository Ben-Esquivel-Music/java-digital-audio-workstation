package com.benesquivelmusic.daw.core.mixer;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor}
 * implementation is a built-in insert effect discoverable by the
 * {@link ProcessorRegistry}.
 *
 * <p>Annotating a processor class is the single source of truth that binds
 * the class to an {@link InsertEffectType} value: the {@link #type()} string
 * must equal the {@code InsertEffectType.name()} of a non-CLAP enum constant.
 * The registry scans annotated classes at startup, builds a bidirectional
 * {@code InsertEffectType <-> Class} map, and caches constructor
 * {@link java.lang.invoke.MethodHandle}s so instances can be created without
 * a hard-coded switch statement.</p>
 *
 * <p>Adding a new built-in effect therefore becomes a single-file change:
 * add an enum constant to {@link InsertEffectType}, write the processor class
 * with {@code @InsertEffect}, and register the class in the
 * {@link ProcessorRegistry} known-classes list. No changes are required to
 * {@link InsertEffectFactory}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @InsertEffect(type = "COMPRESSOR", displayName = "Compressor")
 * public final class CompressorProcessor implements AudioProcessor { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface InsertEffect {

    /**
     * Stable persistence key that must match the {@link InsertEffectType#name()}
     * of the associated enum constant (e.g. {@code "COMPRESSOR"}).
     */
    String type();

    /** Human-readable display name (e.g. {@code "Compressor"}). */
    String displayName();

    /**
     * {@code true} if the processor only supports stereo (two-channel) audio
     * and must be instantiated with {@code channels == 2}. Such processors
     * are expected to expose a single-argument {@code (double sampleRate)}
     * constructor.
     */
    boolean stereoOnly() default false;
}
