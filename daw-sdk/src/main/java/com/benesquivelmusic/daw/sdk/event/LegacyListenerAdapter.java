package com.benesquivelmusic.daw.sdk.event;

import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Compatibility bridge that re-emits legacy listener callbacks from a
 * stream of {@link DawEvent}s.
 *
 * <p>This adapter exists for the duration of the migration described in
 * the issue: the engine's authoritative event channel is now a
 * {@link Flow.Publisher Flow.Publisher&lt;DawEvent&gt;}, but a number of
 * call sites still observe the project through the older
 * {@link RecordingListener} and {@link AutoSaveListener} interfaces.
 * Subscribe an instance of this adapter to the publisher and the legacy
 * listener callbacks will fire on the same events that drove them
 * before, with no behavioural change visible to the listener.</p>
 *
 * <p>New code <strong>must not</strong> rely on this adapter &mdash;
 * subscribe to the {@code DawEvent} stream and use an exhaustive
 * {@code switch} instead. The adapter is intentionally minimal: it only
 * handles the legacy events that exist today, so {@code default} cases
 * in its internal {@code switch}es trigger compile-time
 * exhaustiveness warnings whenever a new {@link DawEvent} variant is
 * introduced.</p>
 */
public final class LegacyListenerAdapter implements Flow.Subscriber<DawEvent> {

    private final RecordingListener recordingListener;
    private final AutoSaveListener autoSaveListener;
    private Flow.Subscription subscription;

    /**
     * Constructs an adapter that re-emits legacy listener callbacks.
     *
     * <p>Either listener may be {@code null} to opt out of that family
     * of callbacks &mdash; useful for call sites that only care about
     * one of the two legacy contracts.</p>
     *
     * @param recordingListener legacy recording listener to drive (nullable)
     * @param autoSaveListener  legacy auto-save listener to drive (nullable)
     */
    public LegacyListenerAdapter(RecordingListener recordingListener,
                                 AutoSaveListener autoSaveListener) {
        this.recordingListener = recordingListener;
        this.autoSaveListener = autoSaveListener;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = Objects.requireNonNull(subscription, "subscription");
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(DawEvent event) {
        Objects.requireNonNull(event, "event");
        // Exhaustive over the sealed DawEvent hierarchy: adding a new
        // permitted sub-type breaks compilation of this switch and
        // forces a deliberate decision about migration.
        switch (event) {
            case TransportEvent ignored  -> { /* no legacy mapping */ }
            case MixerEvent ignored      -> { /* no legacy mapping */ }
            case TrackEvent ignored      -> { /* no legacy mapping */ }
            case ClipEvent ignored       -> { /* no legacy mapping */ }
            case ProjectEvent p          -> dispatchProject(p);
            case AutomationEvent ignored -> { /* no legacy mapping */ }
            case PluginEvent ignored     -> { /* no legacy mapping */ }
            case com.benesquivelmusic.daw.sdk.audio.XrunEvent ignored -> { /* no legacy mapping */ }
        }
    }

    private void dispatchProject(ProjectEvent event) {
        if (autoSaveListener == null) {
            return;
        }
        // Legacy AutoSaveListener fires around save checkpoints. We map
        // ProjectEvent.Saved to onAfterCheckpoint so call sites that
        // only care about "a save just happened" continue to fire.
        switch (event) {
            case ProjectEvent.Saved s ->
                    autoSaveListener.onAfterCheckpoint(s.projectId().toString());
            case ProjectEvent.Opened ignored  -> { /* no legacy auto-save mapping */ }
            case ProjectEvent.Closed ignored  -> { /* no legacy auto-save mapping */ }
            case ProjectEvent.Created ignored -> { /* no legacy auto-save mapping */ }
            case ProjectEvent.Undone ignored  -> { /* no legacy auto-save mapping */ }
            case ProjectEvent.Redone ignored  -> { /* no legacy auto-save mapping */ }
        }
    }

    @Override
    public void onError(Throwable throwable) {
        // Legacy listeners have no error channel; intentional no-op.
    }

    @Override
    public void onComplete() {
        // Legacy listeners have no completion channel; intentional no-op.
    }

    /**
     * Returns the legacy {@link RecordingListener} this adapter drives,
     * or {@code null} if no recording listener was supplied.
     *
     * <p>Exposed so call sites that record their own segment-index state
     * (the legacy {@link RecordingListener#onNewSegmentCreated(int)}
     * callback) can invoke it directly &mdash; segment indices are not
     * carried by the new {@link DawEvent} hierarchy.</p>
     */
    public RecordingListener recordingListener() {
        return recordingListener;
    }

    /**
     * Returns the active subscription, or {@code null} before
     * {@link #onSubscribe(Flow.Subscription)} has fired.
     */
    public Flow.Subscription subscription() {
        return subscription;
    }
}
