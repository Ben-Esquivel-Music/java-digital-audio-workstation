package com.benesquivelmusic.daw.app.ui.controls;

import java.util.Objects;

/**
 * Immutable view-model adapter for one send slot rendered by a
 * {@link MixerChannelStrip}.
 *
 * <p>Story 271 (Phase 2 of the UI Design Book §6 migration roadmap). As
 * with {@link InsertSlotModel}, the channel-strip
 * {@link javafx.scene.control.Control} is decoupled from {@code daw-core}'s
 * mutable {@link com.benesquivelmusic.daw.core.mixer.Send} — consumers map
 * their model into this immutable record so the control's
 * {@code sendsProperty()} observable list never holds an engine type.
 *
 * <p>Field mapping against
 * {@link com.benesquivelmusic.daw.core.mixer.Send}:
 * <ul>
 *   <li>{@code name}     — derived from {@code send.getTarget().getName()}
 *       (the return-bus name); must not be {@code null}.</li>
 *   <li>{@code level}    — {@code send.getLevel()} (0.0–1.0). Drives the
 *       compact send {@link Fader}.</li>
 *   <li>{@code preFader} — {@code send.getMode() == SendMode.PRE_FADER}
 *       (story §5.4 — "send name, level, pre/post toggle"; the legacy
 *       two-state {@code SendMode} view is sufficient for the strip's
 *       pre/post toggle per the Non-Goals: per-send tap-point visual
 *       polish is story 154).</li>
 * </ul>
 *
 * @param name     the send display name (typically the return-bus name);
 *                 must not be {@code null}
 * @param level    the send level in {@code [0.0, 1.0]}
 * @param preFader whether the send is pre-fader (vs. post-fader)
 */
public record SendSlotModel(String name, double level, boolean preFader) {

    /**
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public SendSlotModel {
        Objects.requireNonNull(name, "name");
    }
}
