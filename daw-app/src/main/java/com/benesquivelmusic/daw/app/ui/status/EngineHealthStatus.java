package com.benesquivelmusic.daw.app.ui.status;

import com.benesquivelmusic.daw.app.diagnostics.EngineFault;
import com.benesquivelmusic.daw.app.ui.EngineState;
import com.benesquivelmusic.daw.app.ui.EngineStateChange;
import com.benesquivelmusic.daw.app.ui.TakeDropouts;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The engine's health as the session status strip and the xrun log show it
 * (story 338): the engine state with the device it concerns
 * and how a loss happened, the latched engine fault, the xrun figures, the
 * take's dropout figures and the newest lines of the xrun log. A model of
 * the FX thread: its feeds write it there, and its readers — the strip's
 * Engine and Xruns cells and the xrun log dialog — bind to its properties.
 *
 * <p>A fresh model says nothing is wrong: the engine STOPPED, no fault, no
 * xruns, no take figures and an empty log. That is also what the engine
 * controller starts from, so the first change delivered to it needs no
 * seeding read.</p>
 *
 * <p>FX thread.</p>
 */
public final class EngineHealthStatus {

    /** How many lines the xrun log keeps, the newest (ruling E22). */
    public static final int MAX_LOG_LINES = 100;

    /**
     * Whether the selected driver reports output underruns (rulings E18 and
     * E116): nothing is said until a stream on it has answered, and the answer
     * is kept through a closed stream until another driver or device is
     * chosen in Settings.
     */
    public enum UnderrunReporting {
        /** No stream on the selected driver has answered yet. */
        NOT_ASKED,
        /** The driver reports output underruns. */
        REPORTED,
        /** The driver does not report output underruns. */
        NOT_REPORTED
    }

    /**
     * The xrun figures at one moment (rulings E17, E18, E22 and E23).
     *
     * @param count            the dropouts since {@code since}: the "Xruns N"
     * @param recent           how many of them arrived in the last 30 seconds
     * @param lateBlocks       late blocks since {@code since}, which never raise the count
     * @param since            when the count last restarted
     * @param underruns        whether the selected driver reports output underruns, as its stream answered
     */
    public record XrunFigures(long count, long recent, long lateBlocks, Instant since, UnderrunReporting underruns) {

        /** Checks that no figure is negative and that the time is given. */
        public XrunFigures {
            Objects.requireNonNull(since, "since must not be null");
            Objects.requireNonNull(underruns, "underruns must not be null");
            if (count < 0 || recent < 0 || lateBlocks < 0) {
                throw new IllegalArgumentException("xrun figures must not be negative: " + count + ", " + recent
                        + ", " + lateBlocks);
            }
        }
    }

    private final ReadOnlyObjectWrapper<EngineStateChange> engineState = new ReadOnlyObjectWrapper<>(this,
            "engineState", new EngineStateChange(EngineState.STOPPED, Optional.empty()));
    private final ReadOnlyObjectWrapper<Optional<EngineFault>> engineFault =
            new ReadOnlyObjectWrapper<>(this, "engineFault", Optional.empty());
    private final ReadOnlyObjectWrapper<XrunFigures> xrunFigures;
    private final ReadOnlyObjectWrapper<Optional<TakeDropouts>> takeDropouts =
            new ReadOnlyObjectWrapper<>(this, "takeDropouts", Optional.empty());
    private final ObservableList<String> log = FXCollections.observableArrayList();
    private final ObservableList<String> readOnlyLog = FXCollections.unmodifiableObservableList(log);

    /** A model that says nothing is wrong; its xrun count restarted now. */
    public EngineHealthStatus() {
        xrunFigures = new ReadOnlyObjectWrapper<>(this, "xrunFigures",
                new XrunFigures(0L, 0L, 0L, Instant.now(), UnderrunReporting.NOT_ASKED));
    }

    // ── engine state ─────────────────────────────────────────────────────────

    /**
     * Takes an engine-state change as the controller published it: its
     * state, the device it concerns and, for a loss, how it happened — one
     * value, so no reader sees the new state with the previous device.
     *
     * @param change the change; must not be {@code null}
     */
    public void onEngineStateChange(EngineStateChange change) {
        engineState.set(Objects.requireNonNull(change, "change must not be null"));
    }

    /** @return the engine's state with its device and loss cause, as last published by its controller */
    public ReadOnlyObjectProperty<EngineStateChange> engineStateProperty() {
        return engineState.getReadOnlyProperty();
    }

    // ── engine fault ─────────────────────────────────────────────────────────

    /**
     * Mirrors {@code fact} — the window's audio health drain's engine-fault
     * fact — until {@link #unbindEngineFault()}.
     *
     * @param fact the latched fault; must not be {@code null}
     */
    public void bindEngineFault(ObservableValue<Optional<EngineFault>> fact) {
        engineFault.bind(Objects.requireNonNull(fact, "fact must not be null"));
    }

    /** Stops mirroring the drain's fact and forgets the fault it showed. */
    public void unbindEngineFault() {
        engineFault.unbind();
        engineFault.set(Optional.empty());
    }

    /** @return the latched engine fault, while one is latched */
    public ReadOnlyObjectProperty<Optional<EngineFault>> engineFaultProperty() {
        return engineFault.getReadOnlyProperty();
    }

    // ── xruns and the take's dropouts ────────────────────────────────────────

    /**
     * Sets the xrun figures; an equal value changes nothing.
     *
     * @param figures the figures now; must not be {@code null}
     */
    public void setXrunFigures(XrunFigures figures) {
        Objects.requireNonNull(figures, "figures must not be null");
        if (!figures.equals(xrunFigures.get())) {
            xrunFigures.set(figures);
        }
    }

    /** @return the xrun figures */
    public ReadOnlyObjectProperty<XrunFigures> xrunFiguresProperty() {
        return xrunFigures.getReadOnlyProperty();
    }

    /**
     * Sets the dropout figures of the take in flight or of the last one
     * (ruling E24); an equal value changes nothing.
     *
     * @param figures the figures, empty when there is no take to report
     */
    public void setTakeDropouts(Optional<TakeDropouts> figures) {
        Objects.requireNonNull(figures, "figures must not be null");
        if (!figures.equals(takeDropouts.get())) {
            takeDropouts.set(figures);
        }
    }

    /** @return the dropout figures of the take in flight or of the last one */
    public ReadOnlyObjectProperty<Optional<TakeDropouts>> takeDropoutsProperty() {
        return takeDropouts.getReadOnlyProperty();
    }

    // ── the xrun log ─────────────────────────────────────────────────────────

    /**
     * Appends {@code lines}, oldest first, keeping the newest
     * {@value #MAX_LOG_LINES}.
     *
     * @param lines the new lines, formatted
     */
    public void appendXrunLog(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        log.addAll(lines);
        int excess = log.size() - MAX_LOG_LINES;
        if (excess > 0) {
            log.remove(0, excess);
        }
    }

    /** Forgets every line of the xrun log. */
    public void clearXrunLog() {
        log.clear();
    }

    /** @return the xrun log's lines, oldest first; read-only and live */
    public ObservableList<String> xrunLog() {
        return readOnlyLog;
    }
}
