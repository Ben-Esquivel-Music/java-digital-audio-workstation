package com.benesquivelmusic.daw.core.plugin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a custom, extensible capability key on an
 * {@link com.benesquivelmusic.daw.sdk.audio.AudioProcessor} class for
 * reflective discovery by {@link PluginCapabilityIntrospector}.
 *
 * <p>Processor authors use this annotation to advertise open-ended
 * capabilities (e.g., {@code "oversampled"}, {@code "linearPhase"}) without
 * needing to extend {@link PluginCapabilities} with a new boolean field for
 * every new feature. Discovered keys are surfaced through
 * {@link PluginCapabilities#customCapabilities()}.</p>
 *
 * <p>The annotation is repeatable to allow declaring multiple keys.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ProcessorCapability.List.class)
public @interface ProcessorCapability {

    /** Capability key; must be non-blank. */
    String value();

    /** Container annotation for repeatable use; clients should use {@link ProcessorCapability} directly. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        ProcessorCapability[] value();
    }
}
