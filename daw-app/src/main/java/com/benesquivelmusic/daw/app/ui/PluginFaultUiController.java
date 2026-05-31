package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.plugin.PluginFault;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;

import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Connects a {@link PluginInvocationSupervisor} to the notification bar and
 * the {@link PluginFaultLogDialog}.
 *
 * <p>When a plugin faults, a toast is shown ("Plugin X was bypassed due to
 * an error — click for details"). Clicking the notification opens the fault
 * log dialog for the full stack trace.</p>
 */
public final class PluginFaultUiController {

    private final NotificationBar notificationBar;
    private final PluginFaultLogDialog dialog;
    private final ToastSubscriber toastSubscriber = new ToastSubscriber();

    /**
     * The FX-thread marshalling seam (story 289), injected on the production
     * path. May be {@code null} in a pure-unit context (the compatibility
     * constructor defaults it to {@link FxDispatcher#getDefault()});
     * {@link #postFx} tolerates the null.
     */
    private final FxDispatcher fxDispatcher;

    /**
     * Creates a controller that marshals fault toasts onto the FX thread
     * through the {@link FxDispatcher#getDefault() app-scoped default} seam.
     */
    public PluginFaultUiController(PluginInvocationSupervisor supervisor,
                                   NotificationBar notificationBar) {
        this(supervisor, notificationBar, FxDispatcher.getDefault());
    }

    /**
     * Creates a controller with an explicit FX-thread marshalling seam
     * (story 289).
     *
     * @param supervisor      the plugin invocation supervisor whose faults to surface
     * @param notificationBar the bar that shows fault toasts
     * @param fxDispatcher    the FX-thread marshalling seam, or {@code null} to use
     *                        the {@link FxDispatcher#getDefault() app-scoped default}
     */
    public PluginFaultUiController(PluginInvocationSupervisor supervisor,
                                   NotificationBar notificationBar,
                                   FxDispatcher fxDispatcher) {
        Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        // May be null in a pure-unit context; postFx() falls back to the
        // static seam, preserving today's behaviour byte-for-byte.
        this.fxDispatcher = fxDispatcher;
        this.dialog = new PluginFaultLogDialog(supervisor);
        supervisor.publisher().subscribe(toastSubscriber);
    }

    /**
     * Posts {@code work} to the FX thread through the injected
     * {@link FxDispatcher} when present, else the static app-scoped seam — the
     * null branch reproduces today's behaviour exactly (story 289).
     */
    private void postFx(Runnable work) {
        FxDispatcher.runOnFx(fxDispatcher, work);
    }

    /** Opens the fault log dialog (for menu-bar wiring). */
    public void openFaultLog() {
        dialog.showDialog();
    }

    /**
     * Cancels the toast subscription and closes the fault log dialog (which
     * cancels its own subscription). Called on application shutdown so the
     * publisher does not retain references after the UI is gone.
     */
    public void dispose() {
        toastSubscriber.cancel();
        dialog.close();
    }

    private final class ToastSubscriber implements Flow.Subscriber<PluginFault> {
        private volatile Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PluginFault item) {
            postFx(() -> {
                String msg = "Plugin " + item.pluginId() + " was bypassed due to an error — "
                        + "open Plugin Fault Log for details"
                        + (item.quarantined() ? " (quarantined)" : "");
                notificationBar.show(NotificationLevel.ERROR, msg);
            });
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }

        void cancel() {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
