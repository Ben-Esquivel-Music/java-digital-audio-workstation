package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.metering.MeterSubscription;
import com.benesquivelmusic.daw.core.metering.MeterFrame;
import com.benesquivelmusic.daw.sdk.editor.PluginParameterStore;
import com.benesquivelmusic.daw.sdk.plugin.PluginMeterSnapshot;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Story 318 — the {@code INSERT_IO} plumbing for plugin editor footers: bridges
 * a {@link MeterFeed} insert subscription into the editor's
 * {@link PluginParameterStore#publishMeters(PluginMeterSnapshot)}, the same
 * latest-wins channel the {@code EditorFrame} IN / OUT meters already read
 * every frame ({@code PluginEditorSession.tick}).
 *
 * <p>On each delivered block the bridge publishes
 * {@code new PluginMeterSnapshot(currentGainReduction, inPeakDb, outPeakDb)}
 * — peak dB is the loudest lane ({@link MeterFrame#toDb(double)}, negative
 * infinity for silence); the gain-reduction reading is whatever the plugin
 * itself last published, so a dynamics processor's GR survives the host's
 * I/O update. The record allocation happens on the FX thread (the pulse);
 * the store's {@code publishMeters} stays allocation-free.</p>
 *
 * <p>The store is read through a {@link Supplier} on every delivery, so a
 * session whose {@code [Reload]} replaced its store instance is picked up
 * automatically — nothing is cached.</p>
 *
 * <p>The coalescing surface is an explicit {@code surface} argument, not the
 * supplier: a caller hands in a stable object (the editor session), so a
 * second attach for the same editor <em>replaces</em> the first
 * ({@code MeterKey(point, surface)} equality, book §6.3 — one key per tap
 * point per surface). Passing the supplier would defeat that, because a
 * {@code this::store} method reference is a fresh object on every
 * evaluation and would never compare equal.</p>
 */
public final class InsertIoMeterBridge {

    private InsertIoMeterBridge() {
    }

    /**
     * Subscribes the insert's I/O pair and publishes each block into the
     * store the supplier currently returns.
     *
     * @param feed             the FX-pulse drain
     * @param pluginInstanceId {@code InsertSlot.getPluginInstanceId()} of the
     *                         live slot the editor is bound to
     * @param surface          the coalescing surface — a STABLE object for
     *                         the editor (its session), so re-attaching
     *                         replaces instead of accumulating
     * @param store            live supplier of the editor's store; a
     *                         {@code null} result skips the delivery
     * @param visible          {@code false} makes the pulse skip the
     *                         subscription entirely (hidden editor = zero cost)
     * @return an idempotent detach token — run it on editor close
     */
    public static Runnable attach(MeterFeed feed, UUID pluginInstanceId, Object surface,
                                  Supplier<PluginParameterStore> store, BooleanSupplier visible) {
        Objects.requireNonNull(feed, "feed must not be null");
        Objects.requireNonNull(pluginInstanceId, "pluginInstanceId must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(visible, "visible must not be null");
        MeterSubscription subscription = feed.subscribeInsertIo(pluginInstanceId, surface, visible,
                (input, output) -> publish(store, input, output));
        return subscription::dispose;
    }

    /** Builds and publishes the snapshot for one delivered block (FX thread). */
    static void publish(Supplier<PluginParameterStore> store, MeterFrame input, MeterFrame output) {
        PluginParameterStore target = store.get();
        if (target == null) {
            return;
        }
        double gainReductionDb = target.meters().gainReductionDb();
        target.publishMeters(new PluginMeterSnapshot(gainReductionDb,
                MeterFrame.toDb(input.maxPeak()), MeterFrame.toDb(output.maxPeak())));
    }
}
