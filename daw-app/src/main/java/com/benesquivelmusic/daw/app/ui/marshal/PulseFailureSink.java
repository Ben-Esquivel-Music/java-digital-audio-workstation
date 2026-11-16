package com.benesquivelmusic.daw.app.ui.marshal;

/**
 * Where a {@linkplain FxDispatcher#FxDispatcher(PulseFailureSink) production
 * dispatcher} reports a unit of pulse work that threw (story 337, ruling
 * E32; Failure Surfacing book §2.9 "Isolate every fan-out", §5.1 row 12).
 *
 * <p>The interface lives here, beside the dispatcher, so the marshalling
 * seam imports nothing from the diagnostics package: the failure funnel
 * implements it ({@code FailureFunnel.pulseFailureSink()}), and the
 * composition root ({@code DawApplication}) passes it in — injected, never
 * defaulted (book §2.7). The funnel's isolated-consumer policy, not the
 * dispatcher, decides what the user sees (story 337, ruling E34).</p>
 *
 * <p>Called on the FX thread, once per failed item per pulse, after that
 * item threw and before the next item runs. An implementation should not
 * throw; if it does, the dispatcher contains the throw and carries on with
 * the frame (story 337, ruling E33).</p>
 */
@FunctionalInterface
public interface PulseFailureSink {

    /**
     * Reports one pulse item that threw.
     *
     * @param kind     which kind of item it was
     * @param offender a description of the item for the log only — the participant's class, the key and
     *                 runnable class of keyed work, or the channel consumer's class; never part of a
     *                 fingerprint (story 337, ruling E36)
     * @param failure  what the item threw, exactly as it was thrown
     */
    void pulseItemFailed(PulseItemKind kind, String offender, Throwable failure);
}
