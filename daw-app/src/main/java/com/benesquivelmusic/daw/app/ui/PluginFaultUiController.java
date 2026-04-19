package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.PluginFault;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import javafx.application.Platform;

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

    public PluginFaultUiController(PluginInvocationSupervisor supervisor,
                                   NotificationBar notificationBar) {
        Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.notificationBar = Objects.requireNonNull(notificationBar, "notificationBar must not be null");
        this.dialog = new PluginFaultLogDialog(supervisor);
        supervisor.publisher().subscribe(toastSubscriber);
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
            Platform.runLater(() -> {
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
