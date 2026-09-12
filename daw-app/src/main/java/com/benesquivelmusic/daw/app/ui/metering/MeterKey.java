package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.core.metering.MeterTapPoint;

import java.util.Objects;

/**
 * The coalescing identity of a {@link MeterFeed} subscription: <em>one key
 * per tap point per surface</em> (Audio Engine Wiring Design Book §6.3 — the
 * established one-key-per-fact rule of {@code FxDispatcher.onFx(key, work)}
 * applied to meters). A second {@code subscribe} with an equal key replaces
 * the first, which is disposed, so a surface can never accumulate two live
 * subscriptions for the same meter.
 *
 * @param point   the tap point (value semantics — {@code MeterTapPoint}
 *                records compare by graph position)
 * @param surface the consuming surface: the display instance, the
 *                view-model, the editor session — any object whose identity
 *                (or equality) names "this meter on this screen"
 */
public record MeterKey(MeterTapPoint point, Object surface) {

    public MeterKey {
        Objects.requireNonNull(point, "point must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
    }
}
