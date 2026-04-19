package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.PluginFault;
import com.benesquivelmusic.daw.core.plugin.PluginInvocationSupervisor;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.format.DateTimeFormatter;
import java.util.concurrent.Flow;

/**
 * Non-modal window that lists {@link PluginFault} events, each with an
 * expandable stack trace and a "Re-enable" button.
 *
 * <p>The dialog subscribes to {@link PluginInvocationSupervisor#publisher()}
 * and marshals incoming events onto the JavaFX application thread.</p>
 */
public final class PluginFaultLogDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT;

    private final Stage stage;
    private final ObservableList<FaultRow> rows = FXCollections.observableArrayList();
    private final PluginInvocationSupervisor supervisor;
    private final FaultSubscriber subscriber = new FaultSubscriber();

    public PluginFaultLogDialog(PluginInvocationSupervisor supervisor) {
        this.supervisor = supervisor;
        this.stage = new Stage();
        stage.setTitle("Plugin Fault Log");
        stage.initModality(Modality.NONE);

        ListView<FaultRow> list = new ListView<>(rows);
        list.setCellFactory(_ -> new FaultCell());
        list.setPlaceholder(new Label("No plugin faults recorded this session."));

        Label footnote = new Label(
                "This log records exceptions and some JVM errors thrown by "
                        + "plugin audio code. Native segfaults (for example in "
                        + "FFM downcalls) cannot be caught here, and severe JVM "
                        + "failures such as OOM may leave the process too "
                        + "unstable to log reliably.");
        footnote.setWrapText(true);
        footnote.setPadding(new Insets(6, 8, 0, 8));
        footnote.getStyleClass().add("plugin-fault-footnote");

        BorderPane root = new BorderPane(list);
        root.setBottom(footnote);
        root.setPadding(new Insets(8));
        stage.setScene(new Scene(root, 720, 480));

        // Cancel the subscription when the window is dismissed via the X
        // button so the publisher doesn't retain this dialog and the rows
        // list doesn't keep growing after the window is closed.
        stage.setOnHidden(_ -> subscriber.cancel());

        supervisor.publisher().subscribe(subscriber);
    }

    /** Brings the dialog to the front, creating/showing the stage as needed. */
    public void showDialog() {
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
    }

    /**
     * Closes the dialog stage and cancels the fault subscription so the
     * publisher does not retain a reference to this instance after close.
     */
    public void close() {
        subscriber.cancel();
        stage.close();
    }

    private record FaultRow(PluginFault fault) {
    }

    private final class FaultSubscriber implements Flow.Subscriber<PluginFault> {
        private volatile Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(PluginFault item) {
            Platform.runLater(() -> rows.addFirst(new FaultRow(item)));
        }

        @Override
        public void onError(Throwable throwable) {
            // Publisher errors should not happen in practice; requesting more
            // would be pointless if the publisher is broken.
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

    private final class FaultCell extends ListCell<FaultRow> {

        @Override
        protected void updateItem(FaultRow row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            PluginFault f = row.fault();

            String when = f.clock() == null ? "" : TIME.format(f.clock());
            Label header = new Label(
                    when + "  —  " + f.pluginId() + "  —  "
                            + f.exceptionClass() + (f.message() == null ? "" : ": " + f.message())
                            + (f.quarantined() ? "  [QUARANTINED]" : ""));
            header.setWrapText(true);
            header.getStyleClass().add("plugin-fault-header");

            TextArea stack = new TextArea(f.stackTrace());
            stack.setEditable(false);
            stack.setWrapText(false);
            stack.setPrefRowCount(6);

            TitledPane details = new TitledPane("Stack trace", stack);
            details.setExpanded(false);

            Button reenable = new Button(
                    f.quarantined() ? "Clear quarantine & re-enable" : "Re-enable");
            reenable.setOnAction(_ -> {
                boolean reenabled = supervisor.reenable(f.pluginId());
                // If the slot is no longer tracked (chain rebuilt, plugin
                // removed) fall back to clearing quarantine only so the user
                // can re-arm the next load. Disable the button either way to
                // give visual feedback that the click was handled.
                if (!reenabled && f.quarantined()) {
                    supervisor.clearQuarantine(f.pluginId());
                }
                reenable.setDisable(true);
            });

            HBox actions = new HBox(6, reenable);
            actions.setPadding(new Insets(4, 0, 0, 0));

            VBox body = new VBox(4, header, details, actions);
            body.setPadding(new Insets(6));
            setText(null);
            setGraphic(body);
        }
    }
}
