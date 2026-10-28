package com.benesquivelmusic.daw.app.ui.dialogs;

import com.benesquivelmusic.daw.core.persistence.journal.RecoverySummary;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.text.MessageFormat;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Story 298 — the crash-recovery dialog (Project Manager Design Book §4.6.1).
 *
 * <p>Shown when a project's prior session did not exit cleanly. It renders
 * the {@link RecoverySummary} of the one recovery plan the open built, so it
 * describes exactly what each choice then does: four rows (the last clean
 * save, which Restore checkpoint only opens; the recovery point Recover
 * everything starts from; the journaled changes after it; the last of them),
 * then an explanation made of independent sentences in a fixed order, each
 * only when it applies. It offers three mutually-exclusive recovery actions
 * plus Cancel:</p>
 *
 * <ul>
 *   <li><b>Recover everything</b> (primary; the default button unless the
 *       last clean save is newer than the recovery point) →
 *       {@link RecoveryChoice#REPLAY_ALL}.</li>
 *   <li><b>Restore checkpoint only</b> (the default button when the last
 *       clean save is newer than the recovery point, so Enter never replaces
 *       a newer save) → {@link RecoveryChoice#CHECKPOINT_ONLY}.</li>
 *   <li><b>Inspect events…</b> → {@link RecoveryChoice#INSPECT_EVENTS}: look at
 *       the journaled change types before deciding
 *       ({@link #inspectEventsText(RecoverySummary)}).</li>
 *   <li><b>Cancel</b> → {@link RecoveryChoice#CANCEL}.</li>
 * </ul>
 *
 * <p>A collapsed "Show technical detail" expander lists the recovery point's
 * file, the journal segment file names and size, any unreadable bytes at the
 * end, the change types and the session records — the {@code DialogPane}
 * expandable content (§4.6.1), built with
 * {@link javafx.scene.control.DialogPane#setExpandableContent(Node)} and
 * collapsed by default.</p>
 *
 * <p>Every row label, figure, sentence and detail label comes from the
 * application bundle ({@code recoveryDialog.*}, {@code inspectEvents.*}),
 * read through {@code DawgDialog.msg} and {@link MessageFormat} at
 * {@link Locale#ROOT}: counts choose their plural as numbers and are shown
 * without grouping, and times use the {@value #CLOCK_PATTERN} pattern in the
 * system zone. The words are built by pure, toolkit-free static builders; the
 * dialog does no file I/O (the summary was read on the open's worker).</p>
 *
 * <p>Built with the §5.9 {@link DawgDialog} chrome (Pattern A):
 * {@code setTitle} / {@code setHeaderText} /
 * {@code getDialogPane().setContent(...)} / {@code addAll(buttonTypes)} /
 * {@code setResultConverter(...)} / {@code sized(MEDIUM)}. The result converter
 * maps each {@link ButtonType} to its {@link RecoveryChoice}, so a caller's
 * {@code showAndWait()} yields the choice directly.</p>
 */
public final class RecoveryDialog extends DawgDialog<RecoveryChoice> {

    /** The pattern every recovery time is shown in, in the system zone. */
    static final String CLOCK_PATTERN = "yyyy-MM-dd HH:mm";

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
    private final String recoveryPointText;
    private final String changesText;
    private final String lastEventText;
    private final String explainText;
    private final List<Detail> technicalDetail;

    /**
     * One row of the collapsed technical detail.
     *
     * @param label the row's label
     * @param value the row's value
     */
    public record Detail(String label, String value) {

        /** @throws NullPointerException if a component is {@code null} */
        public Detail {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    /**
     * Creates a recovery dialog for {@code summary}, formatting the summary's
     * times in the system zone.
     *
     * @param summary the recovery summary; must not be {@code null}
     */
    public RecoveryDialog(RecoverySummary summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        DateTimeFormatter clock = clock(ZoneId.systemDefault());

        this.lastSaveText = buildLastSaveText(summary, clock);
        this.recoveryPointText = buildRecoveryPointText(summary, clock);
        this.changesText = buildChangesText(summary);
        this.lastEventText = buildLastEventText(summary, clock);
        this.explainText = buildExplainText(summary, clock);
        this.technicalDetail = buildTechnicalDetail(summary);

        setTitle("Recover unsaved work");
        setHeaderText("This project did not close cleanly");

        getDialogPane().setContent(buildContent());
        getDialogPane().getButtonTypes().addAll(
                cancelType, inspectEventsType, restoreCheckpointType, recoverEverythingType);

        getDialogPane().setExpandableContent(buildTechnicalDetailNode());
        getDialogPane().setExpanded(false);

        // story 276 — sized() applies the §5.9 width band; DawgDialog's
        // super-constructor applies the flat header / accent primary button.
        sized(DawgDialog.Size.MEDIUM);

        // Enter never replaces a save at least as new as the recovery point.
        // DialogPane resets the default flags whenever the button types or the
        // expandable content change, so this step must come last.
        if (summary.recovery() instanceof RecoverySummary.Replay replay
                && replay.headRelation() == RecoverySummary.HeadRelation.HEAD_NEWER) {
            ((Button) getDialogPane().lookupButton(recoverEverythingType)).setDefaultButton(false);
            ((Button) getDialogPane().lookupButton(restoreCheckpointType)).setDefaultButton(true);
        }

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

    /**
     * The body of the Inspect events view ("Captured events") for
     * {@code summary}: how many changes were journaled after the recovery
     * point and how many segment files the journal has, then their types —
     * or, when the replay cannot start, the changes that cannot be replayed
     * and why; or,
     * when an interrupted save is promoted, that no journaled change is part
     * of the recovery. Never a session (control) record type. Times in the
     * system zone. Pure: no I/O.
     *
     * @param summary the recovery summary
     * @return the body text
     */
    public static String inspectEventsText(RecoverySummary summary) {
        return buildInspectEventsText(Objects.requireNonNull(summary, "summary must not be null"),
                clock(ZoneId.systemDefault()));
    }

    // ── content ──────────────────────────────────────────────────────────────

    private Node buildContent() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        int row = 0;
        grid.add(new Label(text("recoveryDialog.label.lastCleanSave")), 0, row);
        grid.add(figure(lastSaveText), 1, row);
        row++;
        grid.add(new Label(text("recoveryDialog.label.recoveryPoint")), 0, row);
        grid.add(figure(recoveryPointText), 1, row);
        row++;
        grid.add(new Label(text("recoveryDialog.label.changes")), 0, row);
        grid.add(figure(changesText), 1, row);
        row++;
        grid.add(new Label(text("recoveryDialog.label.lastEvent")), 0, row);
        grid.add(figure(lastEventText), 1, row);

        Label explain = new Label(explainText);
        explain.setWrapText(true);

        VBox box = new VBox(12, grid, explain);
        box.setPadding(new Insets(0));
        return box;
    }

    private Node buildTechnicalDetailNode() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(12, 16, 12, 16));

        int row = 0;
        for (Detail detail : technicalDetail) {
            grid.add(new Label(detail.label()), 0, row);
            grid.add(figure(detail.value()), 1, row);
            row++;
        }
        return grid;
    }

    private static Label figure(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        // Story 266 / §3.2 — numeric/identifier figures use the mono class.
        label.getStyleClass().add("numeric-value");
        return label;
    }

    // ── words (pure, toolkit-free, package-private for the tests) ────────────

    /** The recovery times' formatter: {@value #CLOCK_PATTERN} in {@code zone}. */
    static DateTimeFormatter clock(ZoneId zone) {
        return DateTimeFormatter.ofPattern(CLOCK_PATTERN).withZone(zone);
    }

    /**
     * The "Last clean save" figure: {@code project.daw}'s time, the file
     * Restore checkpoint only opens, or that it is missing.
     */
    static String buildLastSaveText(RecoverySummary s, DateTimeFormatter clock) {
        return s.lastCleanSave()
                .map(saved -> text("recoveryDialog.lastCleanSave", clock.format(saved)))
                .orElseGet(() -> text("recoveryDialog.lastCleanSave.missing"));
    }

    /**
     * The "Recovery point" figure: the file Recover everything starts from and
     * its time, the interrupted save it promotes, or why the replay cannot
     * start.
     */
    static String buildRecoveryPointText(RecoverySummary s, DateTimeFormatter clock) {
        return switch (s.recovery()) {
            case RecoverySummary.Replay replay -> {
                String when = clock.format(replay.baseTime());
                yield switch (replay.baseKind()) {
                    case CHECKPOINT_CAPTURE -> text("recoveryDialog.point.checkpoint", checkpointNumber(replay), when);
                    case SESSION_START -> text("recoveryDialog.point.sessionStart", when);
                    case SAVED_HEAD -> text("recoveryDialog.point.savedHead", when);
                    case NEWEST_CHECKPOINT ->
                            text("recoveryDialog.point.newestCheckpoint", checkpointNumber(replay), when);
                };
            }
            case RecoverySummary.PromoteNewerHead promote ->
                    text("recoveryDialog.point.interruptedSave", clock.format(promote.writtenAt()));
            case RecoverySummary.ReplayFails fails ->
                    text("recoveryDialog.point.unavailable", reasonText(fails.reason()));
        };
    }

    /**
     * The "Changes after it" figure: the journaled changes after the recovery
     * point with their span in whole minutes (rounded up), {@code None} when
     * there are none or an interrupted save is promoted, or the changes that
     * cannot be replayed.
     */
    static String buildChangesText(RecoverySummary s) {
        return switch (s.recovery()) {
            case RecoverySummary.Replay replay -> {
                RecoverySummary.Changes changes = replay.changes();
                if (changes.count() == 0L) {
                    yield text("recoveryDialog.changes.none");
                }
                long minutes = spanMinutes(changes.span());
                yield minutes > 0L
                        ? count("recoveryDialog.changes.span", changes.count(), Long.toString(minutes))
                        : count("recoveryDialog.changes", changes.count());
            }
            case RecoverySummary.PromoteNewerHead _ -> text("recoveryDialog.changes.none");
            case RecoverySummary.ReplayFails fails -> count("recoveryDialog.changes.cannotReplay", fails.changes().count());
        };
    }

    /**
     * The "Last journaled event" figure: the last change after the recovery
     * point and its time, or that there is none.
     */
    static String buildLastEventText(RecoverySummary s, DateTimeFormatter clock) {
        Optional<RecoverySummary.Changes> changes = changesOf(s);
        if (changes.isEmpty() || changes.get().lastType().isEmpty()) {
            return text("recoveryDialog.lastEvent.none");
        }
        RecoverySummary.Changes last = changes.get();
        return text("recoveryDialog.lastEvent", last.lastType().get(), clock.format(last.lastTime().orElseThrow()));
    }

    /**
     * The explanation below the rows: these sentences, in this order, each
     * only when it applies, joined by one space —
     * <ol>
     *   <li>for a replay, the warning that {@code project.daw} is newer than
     *       the recovery point, or that the two are identical (when no change
     *       is rebuilt);</li>
     *   <li>what Recover everything does — exactly one sentence;</li>
     *   <li>for a replay, that the changes it does not rebuild will not be in
     *       the recovered project;</li>
     *   <li>what Restore checkpoint only does — exactly one sentence;</li>
     *   <li>for a checkpoint recovery point, that the checkpoint is not
     *       deleted;</li>
     *   <li>which choices keep the interrupted save as a snapshot (and, when
     *       the replay cannot start, that Recover everything leaves it in
     *       place).</li>
     * </ol>
     */
    static String buildExplainText(RecoverySummary s, DateTimeFormatter clock) {
        boolean headMissing = s.lastCleanSave().isEmpty();
        List<String> sentences = new ArrayList<>(6);
        if (s.recovery() instanceof RecoverySummary.Replay replay) {
            if (replay.headRelation() == RecoverySummary.HeadRelation.HEAD_NEWER) {
                sentences.add(text("recoveryDialog.explain.headNewer"));
            } else if (replay.headRelation() == RecoverySummary.HeadRelation.IDENTICAL
                    && replay.changes().rebuilt() == 0L) {
                sentences.add(text("recoveryDialog.explain.sameProject"));
            }
        }
        sentences.add(recoverSentence(s, headMissing));
        if (s.recovery() instanceof RecoverySummary.Replay replay) {
            long notRebuilt = replay.changes().count() - replay.changes().rebuilt();
            if (notRebuilt > 0L) {
                sentences.add(count("recoveryDialog.explain.notRebuilt", notRebuilt));
            }
        }
        sentences.add(restoreSentence(s, headMissing));
        if (s.recovery() instanceof RecoverySummary.Replay replay && isCheckpoint(replay.baseKind())) {
            sentences.add(text("recoveryDialog.explain.checkpointKept", checkpointNumber(replay)));
        }
        interruptedSaveSentence(s, headMissing, clock).ifPresent(sentences::add);
        return String.join(" ", sentences);
    }

    /**
     * The collapsed technical detail, in order: the recovery point's file,
     * the segments, the journal size, the unreadable bytes at the end (only
     * when there are some), the change types, the session records, and — when
     * journaled persistence is off and a journal is present — that the
     * journal was kept and not read. An empty list shows a dash.
     */
    static List<Detail> buildTechnicalDetail(RecoverySummary s) {
        List<Detail> rows = new ArrayList<>(7);
        rows.add(new Detail(text("recoveryDialog.detail.recoveryPointFile"), recoveryPointFile(s)));
        rows.add(new Detail(text("recoveryDialog.detail.segments"), joinOrDash(s.segmentFileNames())));
        rows.add(new Detail(text("recoveryDialog.detail.journalSize"), bytes(s.journalBytes())));
        if (s.unreadableJournalBytes() > 0L) {
            rows.add(new Detail(text("recoveryDialog.detail.unreadable"), bytes(s.unreadableJournalBytes())));
        }
        rows.add(new Detail(text("recoveryDialog.detail.changeTypes"),
                joinOrDash(changesOf(s).map(RecoverySummary.Changes::types).orElse(List.of()))));
        rows.add(new Detail(text("recoveryDialog.detail.sessionRecords"), joinOrDash(s.sessionRecordTypes())));
        if (!s.journalEnabled() && s.journalPresent()) {
            rows.add(new Detail(text("recoveryDialog.detail.journal"), text("recoveryDialog.detail.journalNotRead")));
        }
        return List.copyOf(rows);
    }

    /** {@link #inspectEventsText(RecoverySummary)}, with its times formatted by {@code clock}. */
    static String buildInspectEventsText(RecoverySummary s, DateTimeFormatter clock) {
        int segments = s.segmentFileNames().size();
        return switch (s.recovery()) {
            case RecoverySummary.Replay replay -> count("inspectEvents.summary", replay.changes().count(),
                    segments, Integer.toString(segments)) + changeTypesBlock(replay.changes());
            case RecoverySummary.ReplayFails fails -> count("inspectEvents.cannotReplay", fails.changes().count(),
                    reasonText(fails.reason())) + changeTypesBlock(fails.changes());
            case RecoverySummary.PromoteNewerHead promote ->
                    text("inspectEvents.interruptedSave", clock.format(promote.writtenAt()));
        };
    }

    /** Whole minutes of {@code span}, rounded up so any non-zero span shows at least 1 min. */
    static long spanMinutes(Duration span) {
        if (span == null || span.isZero() || span.isNegative()) {
            return 0L;
        }
        long seconds = span.getSeconds();
        return (seconds + 59L) / 60L;
    }

    /** The one sentence on what Recover everything does. */
    private static String recoverSentence(RecoverySummary s, boolean headMissing) {
        return switch (s.recovery()) {
            case RecoverySummary.ReplayFails fails -> text(headMissing
                    ? "recoveryDialog.explain.cannotReplayNoHead" : "recoveryDialog.explain.cannotReplay",
                    reasonText(fails.reason()));
            case RecoverySummary.PromoteNewerHead _ -> text("recoveryDialog.explain.recoverInterruptedSave");
            case RecoverySummary.Replay replay when replay.baseKind() == RecoverySummary.BaseKind.SAVED_HEAD ->
                    text("recoveryDialog.explain.recoverSavedHead");
            case RecoverySummary.Replay replay when headMissing ->
                    text("recoveryDialog.explain.recoverNoHead", phrase(replay));
            case RecoverySummary.Replay replay -> text("recoveryDialog.explain.recover", phrase(replay));
        };
    }

    /**
     * The one sentence on what Restore checkpoint only does: it opens
     * {@code project.daw}, so it cannot open a project without one; it keeps
     * the journal when journaled persistence is off and deletes it when it is
     * on.
     */
    private static String restoreSentence(RecoverySummary s, boolean headMissing) {
        if (headMissing) {
            return text("recoveryDialog.explain.restoreNoHead");
        }
        if (s.journalPresent()) {
            return text(s.journalEnabled()
                    ? "recoveryDialog.explain.restoreDeletesJournal" : "recoveryDialog.explain.restoreKeepsJournal");
        }
        return text("recoveryDialog.explain.restore");
    }

    /**
     * The sentence on the interrupted save: Restore checkpoint only keeps it
     * as a snapshot when Recover everything promotes it; both choices keep it
     * beside a journal they recover, a sentence that says when it was written
     * and that it is newer than the last clean save; and when the replay
     * cannot start, the sentence says the same, that Restore checkpoint only
     * keeps it as a snapshot and that Recover everything leaves it in place.
     * None without {@code project.daw}: Restore checkpoint only then refuses
     * before keeping anything.
     */
    private static Optional<String> interruptedSaveSentence(RecoverySummary s, boolean headMissing,
                                                            DateTimeFormatter clock) {
        if (headMissing) {
            return Optional.empty();
        }
        return switch (s.recovery()) {
            case RecoverySummary.PromoteNewerHead _ ->
                    Optional.of(text("recoveryDialog.explain.interruptedSaveKeptByRestore"));
            case RecoverySummary.Replay replay -> replay.retainedInterruptedSave().map(save ->
                    text("recoveryDialog.explain.interruptedSaveKeptByBoth", clock.format(save.writtenAt())));
            case RecoverySummary.ReplayFails fails -> fails.retainedInterruptedSave().map(save ->
                    text("recoveryDialog.explain.interruptedSaveKeptAside", clock.format(save.writtenAt())));
        };
    }

    /** How the recover sentence names a checkpoint or session-start recovery point. */
    private static String phrase(RecoverySummary.Replay replay) {
        return isCheckpoint(replay.baseKind())
                ? text("recoveryDialog.phrase.checkpoint", checkpointNumber(replay))
                : text("recoveryDialog.phrase.sessionStart");
    }

    /** Why the replay cannot start, in words. */
    private static String reasonText(RecoverySummary.CannotReplay reason) {
        return switch (reason) {
            case RecoverySummary.BaseMissing _ -> text("recoveryDialog.reason.baseMissing");
            case RecoverySummary.BaseChanged _ -> text("recoveryDialog.reason.baseChanged");
            case RecoverySummary.SegmentDamaged damaged ->
                    text("recoveryDialog.reason.segmentDamaged", damaged.segmentFileName());
            case RecoverySummary.NoBase _ -> text("recoveryDialog.reason.noBase");
        };
    }

    /** The recovery point's project-relative file, the interrupted save's file name, or a dash. */
    private static String recoveryPointFile(RecoverySummary s) {
        return switch (s.recovery()) {
            case RecoverySummary.Replay replay -> replay.baseFileName();
            case RecoverySummary.PromoteNewerHead promote -> promote.tempFileName();
            case RecoverySummary.ReplayFails _ -> "—";
        };
    }

    /** The journaled changes the summary describes; none when an interrupted save is promoted. */
    private static Optional<RecoverySummary.Changes> changesOf(RecoverySummary s) {
        return switch (s.recovery()) {
            case RecoverySummary.Replay replay -> Optional.of(replay.changes());
            case RecoverySummary.ReplayFails fails -> Optional.of(fails.changes());
            case RecoverySummary.PromoteNewerHead _ -> Optional.empty();
        };
    }

    /** The bullet list of change types after the Inspect events sentence. */
    private static String changeTypesBlock(RecoverySummary.Changes changes) {
        StringBuilder block = new StringBuilder("\n\n").append(text("inspectEvents.changeTypes"));
        if (changes.types().isEmpty()) {
            block.append("\n  ").append(text("inspectEvents.none"));
        }
        for (String type : changes.types()) {
            block.append("\n  • ").append(type);
        }
        return block.toString();
    }

    private static boolean isCheckpoint(RecoverySummary.BaseKind kind) {
        return kind == RecoverySummary.BaseKind.CHECKPOINT_CAPTURE || kind == RecoverySummary.BaseKind.NEWEST_CHECKPOINT;
    }

    /** A checkpoint recovery point's index, as shown (no grouping); the summary always has one for it. */
    private static String checkpointNumber(RecoverySummary.Replay replay) {
        return Long.toString(replay.checkpointIndex().orElseThrow());
    }

    private static String bytes(long count) {
        return count("recoveryDialog.detail.bytes", count);
    }

    private static String joinOrDash(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "—";
        }
        return String.join(", ", values);
    }

    /** The bundle's {@code key} formatted with {@code arguments} through {@link MessageFormat} at {@link Locale#ROOT}. */
    private static String text(String key, Object... arguments) {
        return new MessageFormat(msg(key), Locale.ROOT).format(arguments);
    }

    /**
     * The count message {@code key} for {@code count}: the count chooses the
     * plural as {@code {0}} and is shown, without grouping, as {@code {1}};
     * {@code more} follow as {@code {2}}, {@code {3}}, ….
     */
    private static String count(String key, long count, Object... more) {
        Object[] arguments = new Object[2 + more.length];
        arguments[0] = count;
        arguments[1] = Long.toString(count);
        System.arraycopy(more, 0, arguments, 2, more.length);
        return text(key, arguments);
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

    /** @return the rendered "Last clean save" figure. */
    public String lastSaveText() {
        return lastSaveText;
    }

    /** @return the rendered "Recovery point" figure. */
    public String recoveryPointText() {
        return recoveryPointText;
    }

    /** @return the rendered "Changes after it" figure. */
    public String changesText() {
        return changesText;
    }

    /** @return the rendered "Last journaled event" figure. */
    public String lastEventText() {
        return lastEventText;
    }

    /** @return the rendered explanation below the rows. */
    public String explainText() {
        return explainText;
    }

    /** @return the collapsed technical detail's rows, in order. */
    public List<Detail> technicalDetail() {
        return technicalDetail;
    }
}
