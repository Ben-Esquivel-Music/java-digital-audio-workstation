package com.benesquivelmusic.daw.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class, interface, method, field, constructor, or package as
 * <strong>internal implementation detail</strong> with no API stability
 * guarantees.
 *
 * <p>Elements marked {@code @Internal} live inside an exported module package
 * for technical reasons (split-package avoidance, friend-module access via
 * {@code exports ... to ...}, or staged internal-package migration), but are
 * <em>not</em> part of the stable public API. They may be renamed, moved, or
 * removed in any release — including patch releases — without deprecation.
 *
 * <p>Equivalent in spirit to JetBrains' {@code @ApiStatus.Internal}; an
 * in-tree annotation is used here to avoid a third-party dependency in
 * {@code daw-sdk}.
 *
 * <p>Plugin authors and downstream consumers <strong>must not</strong> reference
 * elements marked {@code @Internal}. The preferred long-term home for such
 * elements is a non-exported {@code .internal} sibling package; this annotation
 * is the staging marker until that move happens.
 *
 * <p>See {@code docs/ARCHITECTURE.md} (section "Module export tiers") for the
 * three-tier model: <em>public API</em>, <em>SPI</em>, <em>internal</em>.
 *
 * @see RealTimeSafe
 * @see ProcessorParam
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({
        ElementType.TYPE,
        ElementType.METHOD,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD,
        ElementType.PACKAGE
})
public @interface Internal {
    /**
     * Optional human-readable note explaining why the element is internal,
     * what it is used by, and (if applicable) the planned migration target.
     */
    String value() default "";
}
