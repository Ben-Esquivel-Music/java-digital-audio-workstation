package com.benesquivelmusic.daw.sdk.annotation;

import java.lang.annotation.*;

/**
 * Indicates that the annotated method or type is safe to call on the real-time
 * audio thread.
 *
 * <p>A real-time-safe method guarantees:</p>
 * <ul>
 *   <li>Zero heap allocations (no {@code new}, no autoboxing, no varargs)</li>
 *   <li>Zero lock acquisition (no {@code synchronized}, no {@link java.util.concurrent.locks.Lock})</li>
 *   <li>Zero blocking I/O (no file, network, or console operations)</li>
 *   <li>Zero system calls that may block (no {@link Thread#sleep}, no {@link Object#wait})</li>
 * </ul>
 *
 * <p>When applied to a type, it indicates that all public methods of that type
 * are real-time-safe unless otherwise documented.</p>
 *
 * <p>This annotation is informational and serves as documentation for plugin
 * developers and audio engine maintainers. It is retained at runtime to allow
 * tooling and test frameworks to verify real-time safety contracts.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RealTimeSafe {
}
