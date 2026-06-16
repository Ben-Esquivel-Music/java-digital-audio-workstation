package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Story 298 — the crash-recovery dialog (Project Manager Design Book §4.6.1).
 *
 * <p>Shown when a project's prior session did not exit cleanly and a
 * write-ahead journal is present on top of the newest checkpoint. It surfaces
 * the {@link RecoverySummary} in human terms — the last clean save, the last
 * journaled event, and how much replayable work is buffered — and offers three
 * mutually-exclusive recovery actions plus Cancel:</p>
 *
 * <ul>
 *   <li><b>Recover everything</b> (primary) → {@link RecoveryChoice#REPLAY_ALL}:
 *       replay the journal on top of the checkpoint.</li>
 *   <li><b>Restore checkpoint only</b> → {@link RecoveryChoice#CHECKPOINT_ONLY}:
 *       open the last clean save and discard the journal.</li>
 *   <li><b>Inspect events…</b> → {@link RecoveryChoice#INSPECT_EVENTS}: look at
 *       the captured event types before deciding.</li>
 *   <li><b>Cancel</b> → {@link RecoveryChoice#CANCEL}.</li>
 * </ul>
 *
 * <p>A collapsed "Show technical detail" expander lists the journal segment
 * file names, the journal byte size, and the distinct event types — the
 * {@code DialogPane} expandable content (§4.6.1), built with
 * {@link javafx.scene.control.DialogPane#setExpandableContent(Node)} and
 * collapsed by default.</p>
 *
 * <p>Built with the §5.9 {@link DawgDialog} chrome (Pattern A, like
 * {@code BackupSettingsDialog}): {@code setTitle} / {@code setHeaderText} /
 * {@code getDialogPane().setContent(...)} / {@code addAll(buttonTypes)} /
 * {@code setResultConverter(...)} / {@code sized(MEDIUM)}. The result converter
 * maps each {@link ButtonType} to its {@link RecoveryChoice}, so a caller's
 * {@code showAndWait()} yields the choice directly.</p>
 */
public final class RecoveryDialog extends DawgDialog<RecoveryChoice> {

    /** "no checkpoint" sentinel from {@link RecoverySummary#checkpointIndex()}. */
    private static final int NO_CHECKPOINT = -1;

    private final RecoverySummary summary;
    private final DateTimeFormatter clockFormat;

    // The three option buttons + Cancel, exposed for the tests.
    private final ButtonType recoverEverythingType =
            new ButtonType("Recover everything", ButtonBar.ButtonData.OK_DONE);
    private final ButtonType restoreCheckpointType =
            new ButtonType("Restore checkpoint only", ButtonBar.ButtonData.OTHER);
    private final ButtonType inspectEventsType =
            new ButtonType("Inspect events…", ButtonBar.ButtonData.HELP_2);
    private final ButtonType cancelType =
            new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

    // Pre-rendered figure strings, exposed for the tests.
    private final String lastSaveText;
    private final String lastEventText;
    private final String replayableText;

    /**
     * Creates a recovery dialog for {@code summary}, formatting the summary's
     * timestamps in the system zone.
     *
     * @param summary the recovery summary; must not be {@code null}
     */
    public RecoveryDialog(RecoverySummary summary) {
        this.summary = Objects.requireNonNull(summary, "summary must not be null");
        this.clockFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());

        this.lastSaveText = buildLastSaveText(summary);
        this.lastEventText = buildLastEventText(summary);
        this.replayableText = buildReplayableText(summary);

        setTitle("Recover unsaved work");
        setHeaderText("This project did not close cleanly");

        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().addAll(
                cancelType, inspectEventsType, restoreCheckpointType, recoverEverythingType);

        getDialogPane().setExpandableContent(buildTechnicalDetail());
        getDialogPane().setExpanded(false);

        // story 276 — sized() applies the §5.9 width band; DawgDialog's
        // super-constructor applies the flat header / accent primary button.
        sized(DawgDialog.Size.MEDIUM);

        setResultConverter(button -> {
            if (button == recoverEverythingType) {
                return RecoveryChoice.REPLAY_ALL;
            }
            if (button == restoreCheckpointType) {
                return RecoveryChoice.CHECKPOINT_ONLY;
            }
            if (button == inspectEventsType) {
                return RecoveryChoice.INSPECT_EVENTS;
            }
            return RecoveryChoice.CANCEL;
        });
    }

    // ── content ──────────────────────────────────────────────────────────────

    private Node buildContent() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        int row = 0;
        grid.add(new Label("Last clean save:"), 0, row);
        grid.add(figure(lastSaveText), 1, row);
        row++;
        grid.add(new Label("Last journaled event:"), 0, row);
        grid.add(figure(lastEventText), 1, row);
        row++;
        grid.add(new Label("Unsaved work to replay:"), 0, row);
        grid.add(figure(replayableText), 1, row);

        Label explain = new Label(
                "Recover everything replays the captured changes on top of the "
                        + "last clean save. Restore checkpoint only opens the last "
                        + "clean save and discards the captured changes.");
        explain.setWrapText(true);

        VBox box = new VBox(12, grid, explain);
        box.setPadding(new Insets(0));
        return box;
    }

    private Node buildTechnicalDetail() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(12, 16, 12, 16));

        int row = 0;
        grid.add(new Label("Segments:"), 0, row);
        grid.add(figure(joinOrDash(summary.segmentFileNames())), 1, row);
        row++;
        grid.add(new Label("Journal size:"), 0, row);
        grid.add(figure(summary.journalBytes() + " bytes"), 1, row);
        row++;
        grid.add(new Label("Event types:"), 0, row);
        grid.add(figure(joinOrDash(summary.eventTypes())), 1, row);
        return grid;
    }

    private static Label figure(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        // Story 266 / §3.2 — numeric/identifier figures use the mono class.
        label.getStyleClass().add("numeric-value");
        return label;
    }

    // ── figure formatting (pure, package-private for the tests) ───────────────

    /**
     * Builds the "last clean save" figure: {@code "Checkpoint #N · <time>"},
     * handling the no-checkpoint case ({@code checkpointIndex == -1}) and a
     * {@code null} checkpoint time.
     */
    String buildLastSaveText(RecoverySummary s) {
        if (s.checkpointIndex() == NO_CHECKPOINT) {
            return "No checkpoint found";
        }
        String when = formatTime(s.checkpointTime());
        return "Checkpoint #" + s.checkpointIndex()
                + (when.isEmpty() ? "" : " · " + when);
    }

    /**
     * Builds the "last journaled event" figure: {@code "<type> · <time>"},
     * handling an empty journal (blank type) and a {@code null} time.
     */
    String buildLastEventText(RecoverySummary s) {
        String type = s.lastEventType();
        if (type == null || type.isBlank()) {
            return "No events captured";
        }
        String when = formatTime(s.lastEventTime());
        return type + (when.isEmpty() ? "" : " · " + when);
    }

    /**
     * Builds the replayable-work figure from the record count and the
     * {@link RecoverySummary#replayableSpan() span} rendered in whole minutes,
     * e.g. {@code "120 events · ~2 min"}.
     */
    String buildReplayableText(RecoverySummary s) {
        long count = s.replayableEventCount();
        String events = count + (count == 1 ? " event" : " events");
        long minutes = replayableMinutes(s.replayableSpan());
        if (minutes <= 0) {
            return events;
        }
        return events + " · ~" + minutes + " min";
    }

    /** Whole replayable minutes, rounded up so any non-zero span shows at least 1 min. */
    static long replayableMinutes(Duration span) {
        if (span == null || span.isZero() || span.isNegative()) {
            return 0L;
        }
        long seconds = span.getSeconds();
        return (seconds + 59L) / 60L;
    }

    private String formatTime(Instant instant) {
        return instant == null ? "" : clockFormat.format(instant);
    }

    private static String joinOrDash(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "—";
        }
        return String.join(", ", values);
    }

    // ── test accessors ─────────────────────────────────────────────────────────

    /** @return the "Recover everything" → {@link RecoveryChoice#REPLAY_ALL} button type. */
    public ButtonType recoverEverythingButtonType() {
        return recoverEverythingType;
    }

    /** @return the "Restore checkpoint only" → {@link RecoveryChoice#CHECKPOINT_ONLY} button type. */
    public ButtonType restoreCheckpointButtonType() {
        return restoreCheckpointType;
    }

    /** @return the "Inspect events…" → {@link RecoveryChoice#INSPECT_EVENTS} button type. */
    public ButtonType inspectEventsButtonType() {
        return inspectEventsType;
    }

    /** @return the Cancel → {@link RecoveryChoice#CANCEL} button type. */
    public ButtonType cancelButtonType() {
        return cancelType;
    }

    /** @return the rendered "last clean save" figure string. */
    public String lastSaveText() {
        return lastSaveText;
    }

    /** @return the rendered "last journaled event" figure string. */
    public String lastEventText() {
        return lastEventText;
    }

    /** @return the rendered replayable-work figure string. */
    public String replayableText() {
        return replayableText;
    }
}
