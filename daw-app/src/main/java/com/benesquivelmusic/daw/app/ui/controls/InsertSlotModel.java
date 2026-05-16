package com.benesquivelmusic.daw.app.ui.controls;

import java.util.Objects;

/**
 * Immutable view-model adapter for one insert-effect slot rendered by a
 * {@link MixerChannelStrip}.
 *
 * <p>Story 271 (Phase 2 of the UI Design Book §6 migration roadmap). The
 * channel-strip {@link javafx.scene.control.Control} is deliberately
 * decoupled from {@code daw-core}'s mutable
 * {@link com.benesquivelmusic.daw.core.mixer.InsertSlot} — consumers map
 * their model into this immutable record so the control's
 * {@code insertsProperty()} observable list never holds an engine type
 * (matching the §2.5 "themability is a stylesheet swap" / "the strip
 * exposes a strict {@code Property<T>}-driven API" promise).
 *
 * <p>Field mapping against
 * {@link com.benesquivelmusic.daw.core.mixer.InsertSlot}:
 * <ul>
 *   <li>{@code name}     — {@code InsertSlot.getName()}.</li>
 *   <li>{@code active}   — a slot that holds a processor and is engaged;
 *       the consumer supplies {@code !slot.isBypassed()} (an empty slot
 *       maps to {@code active = false}).</li>
 *   <li>{@code bypassed} — {@code InsertSlot.isBypassed()}. The status dot
 *       renders {@code -mcs-accent} when {@code active}, otherwise
 *       {@code -mcs-text-mute} (story §5.4 — "{@code -accent} if active,
 *       {@code -text-mute} if bypassed").</li>
 * </ul>
 *
 * @param name     the slot display name (e.g. {@code "Compressor"}); must
 *                 not be {@code null}
 * @param active   whether the slot holds an engaged processor
 * @param bypassed whether the slot is bypassed
 */
public record InsertSlotModel(String name, boolean active, boolean bypassed) {

    /**
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public InsertSlotModel {
        Objects.requireNonNull(name, "name");
    }
}
