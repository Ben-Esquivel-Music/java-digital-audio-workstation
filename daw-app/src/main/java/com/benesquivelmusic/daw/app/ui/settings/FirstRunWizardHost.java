package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.dialogs.DialogDismissibility;

import javafx.stage.Window;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Modal host lifecycle for {@link FirstRunWizard}.
 *
 * <p>The content owns the visible footer, while one hidden cancel-data
 * button keeps every JavaFX dismissal route live. Finish applies inside the
 * wizard's retryable error boundary; the host is dismissed only after the
 * callback, persisted first-run flag, outcome, and teardown succeed.</p>
 */
public final class FirstRunWizardHost {

    private final FirstRunWizard wizard;
    private final DawgDialog<Void> dialog = new DawgDialog<>();
    private final Consumer<String> restartNoticeSink;
    private Optional<String> restartNotice = Optional.empty();

    /**
     * Creates a host around an already-constructed wizard.
     *
     * @param wizard the wizard content
     * @param applyEdits applies the collected values and returns the canonical
     *                   restart notice when the pending edit set requires one
     * @param restartNoticeSink user-visible notice sink invoked after a
     *                          successful Finish has dismissed the host
     */
    public FirstRunWizardHost(
            FirstRunWizard wizard,
            Function<Map<String, Object>, Optional<String>> applyEdits,
            Consumer<String> restartNoticeSink) {
        this.wizard = Objects.requireNonNull(wizard, "wizard must not be null");
        Objects.requireNonNull(applyEdits, "applyEdits must not be null");
        this.restartNoticeSink = Objects.requireNonNull(
                restartNoticeSink, "restartNoticeSink must not be null");

        dialog.setTitle(wizard.title());
        dialog.setHeaderText(wizard.title());
        dialog.getDialogPane().setContent(wizard);
        DialogDismissibility.installHiddenCancel(dialog);
        dialog.setResultConverter(_ -> null);

        wizard.setOnFinished(edits -> restartNotice = Objects.requireNonNull(
                applyEdits.apply(edits), "applyEdits result must not be null"));
        wizard.setOnOutcomeRecorded(this::dismiss);
    }

    /** Assigns the owner before the host is shown. */
    public void initOwner(Window owner) {
        dialog.initOwner(Objects.requireNonNull(owner, "owner must not be null"));
    }

    /**
     * Shows the modal host and completes abnormal dismissal as Skip.
     *
     * @return the host's empty {@code Void} result
     */
    public Optional<Void> showAndWait() {
        try {
            Optional<Void> result = dialog.showAndWait();
            wizard.skipIfNoOutcome();
            if (wizard.wasFinished()) {
                restartNotice.ifPresent(restartNoticeSink);
            }
            return result;
        } finally {
            wizard.close();
        }
    }

    DawgDialog<Void> dialogForTest() {
        return dialog;
    }

    private void dismiss() {
        if (dialog.isShowing()) {
            dialog.close();
        }
    }
}
