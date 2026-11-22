package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackendException;
import com.benesquivelmusic.daw.sdk.audio.BackendThreadCounters;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The engine's own input-halt counter source (story 338, rulings E11 and E15): the
 * capture inputs the engine declared stalled after they had delivered, and
 * the newest one's fault, reported as {@link BackendThreadCounters#inputHalts()}
 * and {@link BackendThreadCounters#lastInputFault()} to the engine's health
 * record, which treats them as story 337 treats an input halt (ruling E11).
 *
 * <p>Any thread records. The fault is stored before the count and
 * {@link #counters()} reads the count first, so a reader that sees a count
 * sees its fault or a newer one (story 337, ruling E82).</p>
 */
final class InputStallLedger {

    /**
     * Longest first line of the fault (story 337, ruling E85): the length a
     * failure notice keeps of a reason.
     */
    static final int MAX_FAULT_LINE_LENGTH = 120;

    private static final String NAMED_INPUT_PREFIX = "input from “";
    private static final String NAMED_INPUT_SUFFIX = "” stopped delivering audio";
    private static final String UNNAMED_INPUT_STOPPED = "the audio input stopped delivering audio";

    private volatile Throwable lastStallFault;
    private final AtomicLong declaredStalls = new AtomicLong();

    /** The name the health record gives this source in a log line about it. */
    static String sourceName() {
        return "audio input watchdog";
    }

    /** Counts one stall of the input named {@code inputName}, its fault first. */
    void recordStall(String inputName) {
        lastStallFault = new AudioBackendException(stoppedMessage(inputName));
        declaredStalls.incrementAndGet();
    }

    /** The report: the stalls as input halts, the newest one's fault, nothing else. */
    BackendThreadCounters counters() {
        long stalls = declaredStalls.get();
        Throwable fault = lastStallFault;
        return new BackendThreadCounters(0L, 0L, stalls, 0L, OptionalLong.empty(),
                Optional.empty(), Optional.ofNullable(fault), Optional.empty());
    }

    /**
     * {@code input from “<name>” stopped delivering audio}, or
     * {@code the audio input stopped delivering audio} for an input with no
     * name to show ({@link InputNames}, ruling E127); a long name is cut,
     * ending in "…", so the line is at most {@link #MAX_FAULT_LINE_LENGTH}
     * characters.
     */
    static String stoppedMessage(String inputName) {
        Optional<String> shown = InputNames.shown(inputName);
        if (shown.isEmpty()) {
            return UNNAMED_INPUT_STOPPED;
        }
        String name = shown.get();
        int room = MAX_FAULT_LINE_LENGTH - NAMED_INPUT_PREFIX.length() - NAMED_INPUT_SUFFIX.length();
        if (name.length() > room) {
            int cut = room - 1;
            if (Character.isHighSurrogate(name.charAt(cut - 1))) {
                cut--;
            }
            name = name.substring(0, cut) + "…";
        }
        return NAMED_INPUT_PREFIX + name + NAMED_INPUT_SUFFIX;
    }
}
