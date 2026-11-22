package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.diagnostics.EngineFault;
import com.benesquivelmusic.daw.app.ui.DeviceNames;
import com.benesquivelmusic.daw.app.ui.EngineState;
import com.benesquivelmusic.daw.app.ui.EngineStateChange;
import com.benesquivelmusic.daw.app.ui.TakeDropouts;
import com.benesquivelmusic.daw.app.ui.status.EngineHealthStatus.XrunFigures;
import com.benesquivelmusic.daw.app.ui.status.StatusStripCell.Severity;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.Tooltip;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/**
 * The session status strip's two engine-health cells (story 338, rulings
 * E20, E22 to E24 and E26 to E28), built and bound by {@link SessionStatusStripSkin} over the strip's
 * {@link EngineHealthStatus}. Each cell's text, severity, tooltip, spoken
 * text and action are bindings over the model; nothing here sets a label's
 * text.
 *
 * <ul>
 *   <li>The Engine cell (rulings E26 to E28, E63 and E69) says nothing —
 *       it is hidden and takes no space — unless the engine is
 *       reconfiguring, its device is lost or an engine fault is latched. A
 *       loss wins over a fault, whose sentence then joins the tooltip; its
 *       action is the strip's Reconnect while the device is lost, otherwise
 *       the fault's own action. The strip never puts it in its overflow.</li>
 *   <li>The Xruns cell (rulings E18 and E22 to E24) always shows the count;
 *       it has the warning accent while the count grew in the last 30
 *       seconds or a take has dropouts, and its action opens the xrun
 *       log.</li>
 * </ul>
 *
 * <p>FX thread. {@link #dispose()} unbinds everything it bound.</p>
 */
final class EngineHealthCells {

    /** Style class of the Engine cell, whose width is capped so a long device name is ellipsized. */
    static final String ENGINE_CELL_STYLE_CLASS = "engine-health-cell";
    /** Style class of the Xruns cell. */
    static final String XRUN_CELL_STYLE_CLASS = "xrun-cell";

    private static final javafx.util.Duration TOOLTIP_DELAY = javafx.util.Duration.millis(350);

    /** What a cell shows: its text, severity, tooltip, spoken text and action ({@code null} for none). */
    private record CellView(boolean shown, String text, Severity severity, String tooltip, String spoken,
                            EventHandler<ActionEvent> action) {

        static final CellView QUIET = new CellView(false, "", Severity.NORMAL, "", "", null);
    }

    private final SessionStatusStrip strip;
    private final EngineHealthStatus model;
    private final ZoneId zone;
    private final StatusStripCell engineCell = new StatusStripCell();
    private final StatusStripCell xrunCell = new StatusStripCell();
    private final Tooltip engineTooltip = new Tooltip();
    private final Tooltip xrunTooltip = new Tooltip();
    private final ObjectBinding<CellView> engineView;
    private final ObjectBinding<CellView> xrunView;

    /**
     * Builds the two cells and binds them to {@code strip}'s engine-health model.
     *
     * @param strip the owning strip
     * @param zone  the zone the times are shown in
     */
    EngineHealthCells(SessionStatusStrip strip, ZoneId zone) {
        this.strip = strip;
        this.model = strip.engineHealth();
        this.zone = zone;
        engineCell.getStyleClass().add(ENGINE_CELL_STYLE_CLASS);
        xrunCell.getStyleClass().add(XRUN_CELL_STYLE_CLASS);
        engineView = Bindings.createObjectBinding(this::engineView, model.engineStateProperty(),
                model.engineFaultProperty());
        xrunView = Bindings.createObjectBinding(this::xrunView, model.xrunFiguresProperty(),
                model.takeDropoutsProperty());
        bind(engineCell, engineTooltip, engineView);
        bind(xrunCell, xrunTooltip, xrunView);
        engineCell.visibleProperty().bind(engineView.map(CellView::shown));
        engineCell.managedProperty().bind(engineView.map(CellView::shown));
    }

    private static void bind(StatusStripCell cell, Tooltip tooltip, ObservableValue<CellView> view) {
        cell.textProperty().bind(view.map(CellView::text));
        cell.severityProperty().bind(view.map(CellView::severity));
        cell.accessibleTextProperty().bind(view.map(CellView::spoken));
        cell.onActionProperty().bind(view.map(CellView::action));
        tooltip.textProperty().bind(view.map(CellView::tooltip));
        cell.accessibleHelpProperty().bind(tooltip.textProperty());
        tooltip.setShowDelay(TOOLTIP_DELAY);
        cell.setTooltip(tooltip);
    }

    /** @return the Engine cell */
    StatusStripCell engineCell() {
        return engineCell;
    }

    /** @return the Xruns cell */
    StatusStripCell xrunCell() {
        return xrunCell;
    }

    // ── the Engine cell ──────────────────────────────────────────────────────

    private CellView engineView() {
        EngineStateChange change = model.engineStateProperty().get();
        Optional<EngineFault> fault = model.engineFaultProperty().get();
        if (change.state() == EngineState.DEVICE_LOST) {
            return lostView(change, fault);
        }
        if (fault.isPresent()) {
            return faultView(fault.get());
        }
        if (change.state() == EngineState.RECONFIGURING) {
            return new CellView(true, EngineHealthText.text("engineHealth.reconfiguring.text"), Severity.NORMAL,
                    EngineHealthText.text("engineHealth.reconfiguring.tooltip"),
                    EngineHealthText.text("engineHealth.reconfiguring.spoken"), null);
        }
        return CellView.QUIET;
    }

    private CellView lostView(EngineStateChange change, Optional<EngineFault> fault) {
        Optional<String> name = DeviceNames.shown(change.device());
        String tooltipKey = change.cause() == EngineStateChange.LossCause.REOPEN_AFTER_RESET_FAILED
                ? "engineHealth.lost.tooltip.resetNotReopened" : "engineHealth.lost.tooltip";
        String text = name.map(n -> EngineHealthText.text("engineHealth.lost.text", n))
                .orElseGet(() -> EngineHealthText.text("engineHealth.lost.text.unnamed"));
        String tooltip = name.map(n -> EngineHealthText.text(tooltipKey, n))
                .orElseGet(() -> EngineHealthText.text(tooltipKey + ".unnamed"));
        String spoken = name.map(n -> EngineHealthText.text("engineHealth.lost.spoken", n))
                .orElseGet(() -> EngineHealthText.text("engineHealth.lost.spoken.unnamed"));
        if (fault.isPresent()) {
            tooltip = tooltip + " " + failedSentence(fault.get());
        }
        return new CellView(true, text, Severity.WARN, tooltip, spoken,
                _ -> strip.getOnReconnectAction().run());
    }

    private CellView faultView(EngineFault fault) {
        String words = faultWords(fault);
        String action = fault.action().label().toLowerCase(Locale.ROOT);
        String tooltip = failedSentence(fault) + " " + EngineHealthText.text("engineHealth.fault.click", action);
        return new CellView(true, EngineHealthText.text("engineHealth.fault.text", words), Severity.CRITICAL,
                tooltip, EngineHealthText.text("engineHealth.fault.spoken", words, action),
                _ -> fault.action().action().run());
    }

    /** "<Words> failed N times between hh:mm:ss and hh:mm:ss." (ruling E27). */
    private String failedSentence(EngineFault fault) {
        String words = faultWords(fault);
        String capitalized = words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
        return EngineHealthText.text("engineHealth.fault.failed", capitalized, fault.count(),
                Long.toString(fault.count()), EngineHealthText.toTheSecond(fault.firstSeen(), zone),
                EngineHealthText.toTheSecond(fault.lastSeen(), zone));
    }

    /** The words of ruling E27 for the fault's category; an input halt is an {@code audio.input} fault. */
    private static String faultWords(EngineFault fault) {
        String key = "engineHealth.fault.words." + fault.category().slug();
        String words = EngineHealthText.text(key);
        return words.equals(key) ? EngineHealthText.text("engineHealth.fault.words.other") : words;
    }

    // ── the Xruns cell ───────────────────────────────────────────────────────

    private CellView xrunView() {
        XrunFigures figures = model.xrunFiguresProperty().get();
        Optional<TakeDropouts> take = model.takeDropoutsProperty().get().filter(t -> t.dropouts() > 0);
        String count = Long.toString(figures.count());
        String text = take.map(t -> EngineHealthText.text(
                        t.recording() ? "xruns.text.take" : "xruns.text.lastTake", count, Long.toString(t.dropouts())))
                .orElseGet(() -> EngineHealthText.text("xruns.text", count));
        Severity severity = figures.recent() > 0 || take.isPresent() ? Severity.WARN : Severity.NORMAL;
        String tooltip = EngineHealthText.text("xruns.tooltip", figures.count(), count,
                EngineHealthText.toTheSecond(figures.since(), zone), Long.toString(figures.recent()),
                figures.lateBlocks(), Long.toString(figures.lateBlocks()));
        if (figures.underruns() == EngineHealthStatus.UnderrunReporting.NOT_REPORTED) {
            tooltip = tooltip + " " + EngineHealthText.text("xruns.tooltip.noUnderruns");
        }
        String since = EngineHealthText.toTheMinute(figures.since(), zone);
        String recent = Long.toString(figures.recent());
        String spoken = take.map(t -> EngineHealthText.text(
                        t.recording() ? "xruns.spoken.take" : "xruns.spoken.lastTake", count, since, recent,
                        t.dropouts(), Long.toString(t.dropouts())))
                .orElseGet(() -> EngineHealthText.text("xruns.spoken", count, since, recent));
        return new CellView(true, text, severity, tooltip, spoken, _ -> strip.getOnXrunLogAction().run());
    }

    /** Unbinds every property this bound; the cells keep their last values. */
    void dispose() {
        for (StatusStripCell cell : new StatusStripCell[] {engineCell, xrunCell}) {
            cell.textProperty().unbind();
            cell.severityProperty().unbind();
            cell.accessibleTextProperty().unbind();
            cell.accessibleHelpProperty().unbind();
            cell.onActionProperty().unbind();
            cell.setOnAction(null);
        }
        engineCell.visibleProperty().unbind();
        engineCell.managedProperty().unbind();
        engineTooltip.textProperty().unbind();
        xrunTooltip.textProperty().unbind();
        engineView.dispose();
        xrunView.dispose();
    }
}
