package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.event.DispatchMode;
import com.benesquivelmusic.daw.sdk.event.EventBus;
import com.benesquivelmusic.daw.sdk.event.UiEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Story 294 — the idempotent {@link UiEvent.SettingsApplied} (re)subscription
 * shared by the three appearance managers ({@code ThemeManager},
 * {@code DensityManager}, {@code MotionManager}).
 *
 * <p>Each manager is an app-lifetime singleton that subscribes to the shared
 * {@code EventBus} so the Preferences dialog can <em>publish, not poke</em>
 * (Control Synchronization Design Book §6.7). The subscription lifecycle is
 * identical across all three — cancel any prior subscription, then subscribe on
 * {@link DispatchMode#ON_UI_THREAD} (every appearance apply touches scenes /
 * dialog-panes on the FX thread) — so that boilerplate lives here once instead
 * of being copy-pasted into each manager. Each manager keeps only its own
 * retained-subscription field and its own per-slice apply handler, which is the
 * single part that differs between them.</p>
 */
public final class SettingsAppliedSubscriptions {

    private SettingsAppliedSubscriptions() {
        // Utility class — no instances.
    }

    /**
     * Cancels {@code prior} (if any) and subscribes {@code handler} to
     * {@link UiEvent.SettingsApplied} on {@code bus} using
     * {@link DispatchMode#ON_UI_THREAD}, returning the new handle for the caller
     * to retain.
     *
     * <p><strong>Idempotent re-wiring.</strong> Closing the prior subscription
     * first means calling this again (e.g. swapping buses in a test) never leaks
     * the previous subscription. Callers hold the returned handle in a field and
     * pass it back as {@code prior} on the next call; the field plus the
     * caller's own {@code synchronized} method guard the swap.</p>
     *
     * @param bus     the event bus to subscribe on (must not be {@code null})
     * @param prior   the previously-returned handle to cancel, or {@code null}
     *                when not yet wired
     * @param handler the per-manager apply callback (must not be {@code null})
     * @return the new subscription handle
     */
    public static EventBus.Subscription resubscribe(
            EventBus bus,
            EventBus.Subscription prior,
            Consumer<? super UiEvent.SettingsApplied> handler) {
        Objects.requireNonNull(bus, "bus must not be null");
        Objects.requireNonNull(handler, "handler must not be null");
        if (prior != null) {
            prior.close();
        }
        return bus.on(UiEvent.SettingsApplied.class, DispatchMode.ON_UI_THREAD, handler);
    }
}
