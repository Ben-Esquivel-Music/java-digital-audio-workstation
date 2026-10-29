package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.CloseReadiness;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.UnsavedChangesAnswer;
import com.benesquivelmusic.daw.app.ui.ProjectLifecycleController.UnsavedChangesPrompt;
import com.benesquivelmusic.daw.app.ui.dialogs.DawgDialog;
import com.benesquivelmusic.daw.app.ui.theme.ThemeManager;
import com.benesquivelmusic.daw.core.persistence.ProjectLock;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The prompts of the close protocol (story 333), owned by the main window.
 * This part is the unsaved-changes prompt of every close (ruling R2): one
 * prompt for the window's close box, File ▸ Exit, File ▸ Close Project and
 * the close-then-continue primitive, shown through the lifecycle
 * controller's one unsaved-changes Alert
 * ({@link ProjectLifecycleController#askAboutUnsavedChanges(UnsavedChangesPrompt)}),
 * so the doors and the close share one implementation.
 *
 * <p>The prompt's words come from the bundle ({@link ProjectCloseText}, the
 * ruled wording): the header names the project, or says it is open
 * read-only; the content says what Discard leaves — the last save's time,
 * a folder left as it was created, a project never saved, or the read-only
 * folder that Save As leaves as it is — after the lines that come before
 * it: the last save's failure while it stands, then the take just recorded.
 * The buttons are {@code Save and close} (with a trailing ellipsis when a
 * chooser follows: no folder, or read-only), the default button (Enter);
 * {@code Discard and close}, apart on the left, never the default nor the
 * cancel button; and {@code Cancel}, which Escape and the window's close
 * box press. Not a {@code *Dialog}: it builds and shows an Alert, as the
 * doors' prompt does (known limit: no dismissibility gate covers Alerts).</p>
 *
 * <p>It also shows the takeover dialog of a lock another session took
 * (ruling R7), in its two variants — a save's ({@code Save As…} | {@code Not
 * now}) and a close's ({@code Save As…} | {@code Quit without saving} or
 * {@code Close without saving} | {@code Cancel}), {@code Save As…} the
 * default button with the focus, Escape and the close box its last answer —
 * and the refusal of a Save As target (ruling Q6): a modal warning with one
 * {@code OK}, after which the target is asked again.</p>
 *
 * <p>And the take rule's confirmation (ruling R1): "Recording in Progress",
 * {@code Stop the take and close} apart on the left, never the default, and
 * {@code Keep recording} the default button with the focus, which Escape
 * and the close box press — Enter and Space on the focused button both
 * keep recording. The closing progress surface's words that depend on the
 * close — a seal that failed (ruling R12 #6, Q4) and another switch still
 * running (Q3) — are built here too ({@link #sealFailedNote},
 * {@link #switchNote}).</p>
 */
final class ProjectClosePrompts implements ClosePromptAnswers {

    /** The answers of the takeover dialog (ruling R7). */
    enum TakenOverAnswer {
        /** {@code Save As…}: keep the work in a new project. */
        SAVE_AS,
        /** {@code Quit without saving} / {@code Close without saving}: a close's variant only. */
        WITHOUT_SAVING,
        /** {@code Not now} on a save, {@code Cancel} on a close: also Escape and the close box. */
        CANCEL
    }

    /**
     * The takeover dialog's words and buttons (ruling R7).
     *
     * @param header             who took the lock over, or that its file was removed
     * @param content            what was and was not written, and what the user may do
     * @param saveAsLabel        the default button's label
     * @param withoutSavingLabel a close's {@code Quit without saving} / {@code Close without saving}; empty
     *                           on a save
     * @param cancelLabel        the last button's label, pressed by Escape and the close box
     * @param owner              the window that owns the dialog, or empty
     */
    record TakenOverPrompt(String header, String content, String saveAsLabel, Optional<String> withoutSavingLabel,
                           String cancelLabel, Optional<Window> owner) {
        TakenOverPrompt {
            Objects.requireNonNull(header, "header must not be null");
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(saveAsLabel, "saveAsLabel must not be null");
            Objects.requireNonNull(withoutSavingLabel, "withoutSavingLabel must not be null");
            Objects.requireNonNull(cancelLabel, "cancelLabel must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
        }
    }

    /** The time of a save today (rulings K1, L1, R2). */
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    /** The time of a save on another day (rulings K1, L1, R2). */
    private static final DateTimeFormatter ANOTHER_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final ProjectLifecycleController lifecycle;
    private final Supplier<Optional<Window>> owner;
    private final ZoneId zone;

    /**
     * Creates the close prompts over {@code lifecycle}'s unsaved-changes
     * Alert, owned by {@code owner}'s window, times in the JVM's default zone
     * — the zone the strip's times are in.
     *
     * @param lifecycle the lifecycle controller whose Alert shows the prompt
     * @param owner     the main window, when there is one
     */
    ProjectClosePrompts(ProjectLifecycleController lifecycle, Supplier<Optional<Window>> owner) {
        this(lifecycle, owner, ZoneId.systemDefault());
    }

    /** As {@link #ProjectClosePrompts(ProjectLifecycleController, Supplier)}, times in {@code zone}. */
    ProjectClosePrompts(ProjectLifecycleController lifecycle, Supplier<Optional<Window>> owner, ZoneId zone) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
    }

    /**
     * The unsaved-changes prompt of a close of the project {@code readiness}
     * describes (ruling R2), owned by the main window.
     *
     * @param readiness       the open project, as the close read it
     * @param includesTheTake whether the close stopped or waited out a take
     *                        whose publication put at least one clip into the
     *                        project — the take line is shown only then (R2:
     *                        "Omit it rather than guess")
     * @return the prompt
     */
    UnsavedChangesPrompt unsavedChanges(CloseReadiness readiness, boolean includesTheTake) {
        Objects.requireNonNull(readiness, "readiness must not be null");
        String name = readiness.projectName();
        String header = readiness.readOnly()
                ? ProjectCloseText.text("close.unsaved.header.readOnly", name)
                : ProjectCloseText.text("close.unsaved.header", name);
        List<String> lines = new ArrayList<>();
        if (!readiness.readOnly()) {
            // A read-only folder's "failure" is the refusal its content line already says (stage-4 review ENG-N2).
            readiness.headFailure().ifPresent(failure -> lines.add(ProjectCloseText.text(
                    "close.unsaved.lastSaveFailed", ProjectLifecycleController.failureReason(failure))));
        }
        if (includesTheTake) {
            lines.add(ProjectCloseText.text("close.unsaved.take"));
        }
        lines.add(content(readiness));
        boolean chooserFollows = readiness.readOnly() || !readiness.hasFolder();
        return new UnsavedChangesPrompt(header, String.join("\n", lines),
                ProjectCloseText.text(chooserFollows ? "close.unsaved.saveWithChooser" : "close.unsaved.save"),
                ProjectCloseText.text("close.unsaved.discard"),
                Optional.of(ProjectCloseText.text("close.unsaved.cancel")),
                true, owner.get());
    }

    /**
     * Shows {@code prompt} and waits for its answer, in a nested event loop
     * (an Alert's {@code showAndWait}). Its owner, a minimised main window,
     * is restored and brought to the front first: an owned window is hidden
     * while its owner is minimised, so a close asked from the taskbar would
     * otherwise wait on a prompt nobody can see (the studio engineer's
     * stage-4 S5). FX thread, outside a pulse.
     *
     * @param prompt the prompt
     * @return the answer; the window closed without an answer is a Cancel
     */
    @Override
    public UnsavedChangesAnswer askBeforeClosing(UnsavedChangesPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        prompt.owner().ifPresent(ProjectClosePrompts::bringToFront);
        return lifecycle.askAboutUnsavedChanges(prompt);
    }

    /**
     * The take rule's confirmation's header (ruling R1): the application's
     * close for the window's close box and File ▸ Exit, the project's for
     * Close Project.
     *
     * @param exitsApplication whether the close ends the application
     * @param projectName      the open project's name
     * @return the header
     */
    static String stopTakeHeader(boolean exitsApplication, String projectName) {
        return exitsApplication ? ProjectCloseText.text("close.recording.header.exit")
                : ProjectCloseText.text("close.recording.header.project",
                Objects.requireNonNull(projectName, "projectName must not be null"));
    }

    /**
     * Shows the take rule's confirmation and waits for its answer, in a
     * nested event loop (an Alert's {@code showAndWait}; ruling R1): titled
     * "Recording in Progress", {@code header}, the ruled content;
     * {@code Stop the take and close} ({@link ButtonBar.ButtonData#LEFT},
     * never the default) and {@code Keep recording}
     * ({@link ButtonBar.ButtonData#CANCEL_CLOSE}: Escape and the close box),
     * made the default button explicitly — a cancel button is not one by
     * itself — and given the focus when the window shows, so Enter and Space
     * both keep recording (the studio engineer's N1). Owned by the main
     * window, restored and brought to the front first. FX thread, outside a
     * pulse.
     *
     * @param header the header ({@link #stopTakeHeader})
     * @return the answer; the window closed without an answer keeps recording
     */
    @Override
    public StopTakeAnswer confirmStopTake(String header) {
        Objects.requireNonNull(header, "header must not be null");
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(ProjectCloseText.text("close.recording.title"));
        alert.setHeaderText(header);
        alert.setContentText(ProjectCloseText.text("close.recording.content"));
        ButtonType stop = new ButtonType(ProjectCloseText.text("close.recording.stop"), ButtonBar.ButtonData.LEFT);
        ButtonType keep = new ButtonType(ProjectCloseText.text("close.recording.keep"),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(stop, keep);
        Button keepButton = (Button) alert.getDialogPane().lookupButton(keep);
        keepButton.setDefaultButton(true);
        ((Button) alert.getDialogPane().lookupButton(stop)).setDefaultButton(false);
        alert.setOnShown(_ -> keepButton.requestFocus());
        Optional<Window> mainWindow = owner.get();
        mainWindow.ifPresent(ProjectClosePrompts::bringToFront);
        mainWindow.ifPresent(alert::initOwner);
        ThemeManager.getDefault().applyTo(alert.getDialogPane());

        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == stop ? StopTakeAnswer.STOP_THE_TAKE
                : StopTakeAnswer.KEEP_RECORDING;
    }

    /**
     * The closing surface's note of a journal that could not be sealed
     * (ruling R12 #6, Q4.6): the exit's, offering {@code Quit anyway}, or
     * Close Project's, offering {@code Close anyway}.
     *
     * @param cause            why the seal failed ({@link ProjectLifecycleController#failureReason})
     * @param exitsApplication whether the close ends the application
     * @return the note
     */
    static String sealFailedNote(Throwable cause, boolean exitsApplication) {
        return ProjectCloseText.text(exitsApplication ? "close.sealFailed.exit" : "close.sealFailed.project",
                ProjectLifecycleController.failureReason(Objects.requireNonNull(cause, "cause must not be null")));
    }

    /**
     * The closing surface's note of an exit still waiting, after ten
     * seconds, for another switch to end (ruling Q3).
     *
     * @param door the pending switch's name
     * @return the note
     */
    static String switchNote(String door) {
        return ProjectCloseText.text("close.note.switch", Objects.requireNonNull(door, "door must not be null"));
    }

    /** Restores {@code window} when it is a minimised stage, and brings it to the front. FX thread. */
    private static void bringToFront(Window window) {
        if (window instanceof Stage stage) {
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            stage.toFront();
        }
    }

    /**
     * The takeover dialog of a save that met a lock another session took
     * (ruling R7, its Ctrl+S variant): {@code Save As…} | {@code Not now}.
     *
     * @param projectName the open project's name
     * @param holder      the lock found on disk, or {@code null} when its file is gone
     * @return the prompt
     */
    TakenOverPrompt takenOverOnSave(String projectName, ProjectLock holder) {
        return new TakenOverPrompt(takenOverHeader(projectName, holder), ProjectCloseText.text("takeover.content.save"),
                ProjectCloseText.text("takeover.saveAs"), Optional.empty(), ProjectCloseText.text("takeover.notNow"),
                owner.get());
    }

    /**
     * The takeover dialog of a close that met a lock another session took
     * (ruling R7, its exit variant): {@code Save As…} | {@code Quit without
     * saving} — {@code Close without saving} when the application does not
     * exit — | {@code Cancel}. A loss known only from the lease's status — a
     * heartbeat saw the lock gone or replaced — has its own header, which
     * names no holder and claims no removal: no lock is read at GUARD (the
     * studio engineer's stage-6 S1).
     *
     * @param projectName           the open project's name
     * @param holder                the lock found on disk, or {@code null} when its file is gone
     * @param knownOnlyFromTheLease whether the loss is known only from the lease's status
     * @param exitsApplication      whether the close ends the application
     * @return the prompt
     */
    TakenOverPrompt takenOverAtClose(String projectName, ProjectLock holder, boolean knownOnlyFromTheLease,
                                     boolean exitsApplication) {
        String header = knownOnlyFromTheLease
                ? ProjectCloseText.text("takeover.header.leaseLost",
                        Objects.requireNonNull(projectName, "projectName must not be null"))
                : takenOverHeader(projectName, holder);
        return new TakenOverPrompt(header, ProjectCloseText.text("takeover.content.exit"),
                ProjectCloseText.text("takeover.saveAs"),
                Optional.of(ProjectCloseText.text(exitsApplication ? "takeover.quitWithout" : "takeover.closeWithout")),
                ProjectCloseText.text("takeover.cancel"), owner.get());
    }

    /**
     * Shows the takeover dialog and waits for its answer, in a nested event
     * loop (an Alert's {@code showAndWait}), titled "Project Taken Over":
     * {@code Save As…} is the default button and has the focus; the
     * prompt's last button is the cancel button, pressed by Escape and the
     * window's close box. A minimised owner is restored and brought to the
     * front first, as for {@link #askBeforeClosing}. FX thread, outside a
     * pulse.
     *
     * @param prompt the prompt
     * @return the answer; the window closed without an answer is a {@link TakenOverAnswer#CANCEL}
     */
    @Override
    public TakenOverAnswer takenOver(TakenOverPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(ProjectCloseText.text("takeover.title"));
        alert.setHeaderText(prompt.header());
        alert.setContentText(prompt.content());
        ButtonType saveAs = new ButtonType(prompt.saveAsLabel(), ButtonBar.ButtonData.OK_DONE);
        Optional<ButtonType> withoutSaving = prompt.withoutSavingLabel()
                .map(label -> new ButtonType(label, ButtonBar.ButtonData.OTHER));
        ButtonType cancel = new ButtonType(prompt.cancelLabel(), ButtonBar.ButtonData.CANCEL_CLOSE);
        if (withoutSaving.isPresent()) {
            alert.getButtonTypes().setAll(saveAs, withoutSaving.get(), cancel);
        } else {
            alert.getButtonTypes().setAll(saveAs, cancel);
        }
        Button saveAsButton = (Button) alert.getDialogPane().lookupButton(saveAs);
        saveAsButton.setDefaultButton(true);
        withoutSaving.ifPresent(type -> ((Button) alert.getDialogPane().lookupButton(type)).setDefaultButton(false));
        alert.setOnShown(_ -> saveAsButton.requestFocus());
        prompt.owner().ifPresent(ProjectClosePrompts::bringToFront);
        prompt.owner().ifPresent(alert::initOwner);
        ThemeManager.getDefault().applyTo(alert.getDialogPane());

        Optional<ButtonType> answer = alert.showAndWait();
        if (answer.isPresent() && answer.get() == saveAs) {
            return TakenOverAnswer.SAVE_AS;
        }
        if (answer.isPresent() && withoutSaving.isPresent() && answer.get() == withoutSaving.get()) {
            return TakenOverAnswer.WITHOUT_SAVING;
        }
        return TakenOverAnswer.CANCEL;
    }

    /**
     * Refuses a Save As target (ruling Q6): a modal warning titled "Save
     * Project As", owned by the main window, with {@code message} and one
     * {@code OK} — the default button, also pressed by Escape and the close
     * box — and waits for it. FX thread, outside a pulse.
     *
     * @param message the refusal, one of ruling R5's three texts
     */
    @Override
    public void refuseSaveAsTarget(String message) {
        DawgDialog<ButtonType> refusal = DawgDialog.warn(ProjectCloseText.text("saveAs.title"),
                Objects.requireNonNull(message, "message must not be null"));
        owner.get().ifPresent(refusal::initOwner);
        refusal.showAndWait();
    }

    /** The takeover dialog's header: the holder, or that the lock file was removed (ruling R7). */
    private static String takenOverHeader(String projectName, ProjectLock holder) {
        Objects.requireNonNull(projectName, "projectName must not be null");
        if (holder == null) {
            return ProjectCloseText.text("takeover.header.noHolder", projectName);
        }
        return ProjectCloseText.text("takeover.header.holder", projectName, holder.user(), holder.hostname(),
                Long.toString(holder.pid()));
    }

    /** What Discard leaves, the prompt's last line (ruling R2). */
    private String content(CloseReadiness readiness) {
        String name = readiness.projectName();
        if (readiness.readOnly()) {
            return ProjectCloseText.text("close.unsaved.content.readOnly");
        }
        if (!readiness.hasFolder()) {
            return ProjectCloseText.text("close.unsaved.content.noFolder");
        }
        return readiness.headSavedAt()
                .map(savedAt -> ProjectCloseText.text("close.unsaved.content.saved", name,
                        timeOf(savedAt, readiness.today())))
                .orElseGet(() -> ProjectCloseText.text("close.unsaved.content.empty", name));
    }

    /** {@code HH:mm} today, {@code yyyy-MM-dd HH:mm} on another day, in {@link Locale#ROOT}. */
    private String timeOf(Instant savedAt, LocalDate today) {
        ZonedDateTime local = savedAt.atZone(zone);
        return (local.toLocalDate().equals(today) ? TODAY : ANOTHER_DAY).format(local);
    }
}
