package com.benesquivelmusic.daw.app.diagnostics;

import com.benesquivelmusic.daw.core.plugin.PluginResolution;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The one guarded reader of a throwable (story 336, its "Note from Story
 * 334").
 *
 * <p>A plugin's exception can throw while it is read: once the plugin's
 * class loader is closed, a message that needs a class only the plugin's JAR
 * holds throws {@link NoClassDefFoundError} (dawg-annotations-reflection,
 * "### Other reflection / {@code MethodHandles} uses (NOT annotation-driven)":
 * {@code ExternalPluginLoader} closes a plugin's loader after disposal). So
 * every call into a throwable here — {@code getMessage}, {@code getStackTrace},
 * {@code getCause}, {@code getSuppressed}, {@code getSimpleName} — sits inside
 * {@code catch (Throwable)}, and {@code toString()} and
 * {@code printStackTrace()} are never called. {@code getClass().getName()}
 * loads no class and is always safe.</p>
 *
 * <p>It is also the one place that keeps a value on its line (story 336,
 * studio-engineer ruling R992-2): every line of {@code daw-*.log}, the
 * console and the last-resort record ends through {@link #endLine}, which
 * writes any of the seven characters {@code \R} matches inside the line as
 * its escape, <code>&#92;u000A</code> for LF ({@link #oneLine}), so a line
 * holds no line break but the one its writer ends it with.
 * {@link LastResortLog}, which uses no {@code java.util.logging} (story 336,
 * studio-engineer ruling E11), ends its lines here too: this class uses none
 * either.</p>
 */
public final class GuardedThrowables {

    /** How many carriers {@link #unwrapCarriers} passes at most. */
    static final int MAX_UNWRAP_DEPTH = 8;

    /** How deep {@link #appendChain} nests causes and suppressed exceptions. */
    static final int MAX_CHAIN_DEPTH = 16;

    /** The longest reason clause, its ellipsis included. */
    static final int MAX_REASON_LENGTH = 120;

    /** The JavaFX launcher whose {@code RuntimeException} wrappers carry a launch failure. */
    private static final String LAUNCHER_IMPL = "com.sun.javafx.application.LauncherImpl";

    /** The digits of a line break's escape ({@link #oneLine}). */
    private static final String HEX_DIGITS = "0123456789ABCDEF";

    private GuardedThrowables() {
    }

    /**
     * The throwable a carrier holds: follows
     * {@code getCause()} only while the current throwable is a carrier and its
     * cause is not {@code null}, at most {@link #MAX_UNWRAP_DEPTH} times. It
     * never follows the cause of a non-carrier. The carriers are
     * {@link CompletionException}, {@link ExecutionException},
     * {@link InvocationTargetException}, {@link UndeclaredThrowableException}
     * and {@link ExceptionInInitializerError}.
     *
     * @param failure the throwable as it was caught
     * @return the innermost throwable the carriers held, or {@code failure} itself
     */
    public static Throwable unwrapCarriers(Throwable failure) {
        return unwrapped(failure, carrierPath(failure));
    }

    /**
     * As {@link #unwrapCarriers}, for a failure that reached the default
     * handler on the launch thread ({@code app.launch}): the JavaFX
     * launcher's own wrapper is a carrier too — a
     * {@link RuntimeException} of exactly that class, with a cause, whose
     * frame 0 is in {@code com.sun.javafx.application.LauncherImpl}
     * ("Application launch exception", "Exception in Application start
     * method", "Unable to construct Application instance" and the others).
     *
     * @param failure the throwable as it was caught
     * @return the innermost throwable the carriers held, or {@code failure} itself
     */
    public static Throwable unwrapLaunchCarriers(Throwable failure) {
        return unwrapped(failure, launchCarrierPath(failure));
    }

    /**
     * The carriers {@link #unwrapCarriers} passes, outermost first; empty when
     * {@code failure} is no carrier. {@code FailureFunnel.severityOf} counts
     * an {@link ExceptionInInitializerError} anywhere on this path.
     *
     * @param failure the throwable as it was caught
     * @return the carriers passed while unwrapping
     */
    public static List<Throwable> carrierPath(Throwable failure) {
        return path(failure, false);
    }

    /**
     * The carriers {@link #unwrapLaunchCarriers} passes, outermost first.
     *
     * @param failure the throwable as it was caught
     * @return the carriers passed while unwrapping, the launcher's wrappers included
     */
    public static List<Throwable> launchCarrierPath(Throwable failure) {
        return path(failure, true);
    }

    private static Throwable unwrapped(Throwable failure, List<Throwable> path) {
        if (path.isEmpty()) {
            return failure;
        }
        Throwable last = path.getLast();
        return cause(last).orElse(last);
    }

    private static List<Throwable> path(Throwable failure, boolean launch) {
        List<Throwable> path = new ArrayList<>(2);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && path.size() < MAX_UNWRAP_DEPTH && seen.add(current)
                && isCarrier(current, launch)) {
            Optional<Throwable> cause = cause(current);
            if (cause.isEmpty()) {
                break;
            }
            path.add(current);
            current = cause.get();
        }
        return List.copyOf(path);
    }

    /**
     * Whether {@code failure} is one of the JavaFX launcher's own wrappers,
     * whatever its message.
     *
     * @param failure the throwable to test
     * @return whether it is a {@code LauncherImpl} {@code RuntimeException} wrapper with a cause
     */
    public static boolean isLauncherWrapper(Throwable failure) {
        if (failure.getClass() != RuntimeException.class || cause(failure).isEmpty()) {
            return false;
        }
        StackTraceElement[] frames = frames(failure);
        if (frames.length == 0) {
            return false;
        }
        String frameClass = frames[0].getClassName();
        return LAUNCHER_IMPL.equals(frameClass) || frameClass.startsWith(LAUNCHER_IMPL + "$");
    }

    private static boolean isCarrier(Throwable failure, boolean launch) {
        return failure instanceof CompletionException
                || failure instanceof ExecutionException
                || failure instanceof InvocationTargetException
                || failure instanceof UndeclaredThrowableException
                || failure instanceof ExceptionInInitializerError
                || launch && isLauncherWrapper(failure);
    }

    /**
     * {@code failure.getClass().getName()}, which loads no class.
     *
     * @param failure the throwable to name
     * @return its class's binary name
     */
    public static String className(Throwable failure) {
        return failure.getClass().getName();
    }

    /**
     * {@code failure.getMessage()}, guarded: empty when it is {@code null} or
     * when reading it throws.
     *
     * @param failure the throwable to read
     * @return its message, when it has one that can be read
     */
    public static Optional<String> message(Throwable failure) {
        try {
            return Optional.ofNullable(failure.getMessage());
        } catch (Throwable unreadable) {
            return Optional.empty();
        }
    }

    /**
     * {@code failure.getStackTrace()}, guarded: empty when reading it throws
     * or returns {@code null}, and without the {@code null} elements an
     * overriding {@code getStackTrace()} may return, so a caller that reads a
     * frame never meets one ({@link Fingerprint#of}, {@link #isLauncherWrapper}).
     *
     * @param failure the throwable to read
     * @return its frames, frame 0 the throw site, none of them {@code null}
     */
    public static StackTraceElement[] frames(Throwable failure) {
        try {
            StackTraceElement[] frames = failure.getStackTrace();
            if (frames == null) {
                return new StackTraceElement[0];
            }
            for (StackTraceElement frame : frames) {
                if (frame == null) {
                    return Arrays.stream(frames).filter(Objects::nonNull).toArray(StackTraceElement[]::new);
                }
            }
            return frames;
        } catch (Throwable unreadable) {
            return new StackTraceElement[0];
        }
    }

    /**
     * The reason clause a toast ends with: the repo's
     * one naming rule, {@link PluginResolution.FailedToLoad#messageOrName}
     * (the message when it is not blank, else the simple class name, else the
     * full class name), inside {@code catch (Throwable)} so that nothing it
     * throws — not even a {@link VirtualMachineError} it rethrows — escapes an
     * uncaught-exception handler; on any throw, the class name. Then the first
     * line only, one trailing full stop dropped, and at most
     * {@link #MAX_REASON_LENGTH} characters, a cut reason ending with "…".
     *
     * @param unwrapped the throwable the carriers held ({@link #unwrapCarriers})
     * @return the reason clause, never blank
     */
    public static String reason(Throwable unwrapped) {
        String named;
        try {
            named = PluginResolution.FailedToLoad.messageOrName(unwrapped);
        } catch (Throwable unreadable) {
            named = className(unwrapped);
        }
        String reason = firstLine(named == null ? "" : named).strip();
        if (reason.endsWith(".")) {
            reason = reason.substring(0, reason.length() - 1).strip();
        }
        if (reason.isEmpty()) {
            reason = simpleName(unwrapped);
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH - 1) + "…";
        }
        return reason;
    }

    /**
     * Appends {@code failure}'s chain the way the log writes it:
     * each line begins with a tab; the exception line is
     * {@code \t<class>: <message>}; its frames {@code \t\tat …}, every one of
     * them; its suppressed exceptions one tab deeper ({@code \t\tSuppressed: …});
     * its cause at its own depth ({@code \tCaused by: …}). Every call into a
     * throwable is guarded: an unreadable message is written as
     * {@code <the message could not be read: <class of what was thrown>>}, and
     * an unreadable stack, cause or suppressed list the same way, and the walk
     * carries on. A cycle is written once and named; the nesting stops at
     * {@link #MAX_CHAIN_DEPTH}.
     *
     * @param out           where to append
     * @param failure       the throwable to write
     * @param lineSeparator the line separator each line ends with
     */
    public static void appendChain(StringBuilder out, Throwable failure, String lineSeparator) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        appendThrowable(out, failure, "\t", "", seen, 1, lineSeparator);
    }

    private static void appendThrowable(StringBuilder out, Throwable failure, String indent, String caption,
                                        Set<Throwable> seen, int depth, String lineSeparator) {
        int start = out.length();
        if (!seen.add(failure)) {
            out.append(indent).append(caption).append("<circular reference: ").append(className(failure))
                    .append('>');
            endLine(out, start, lineSeparator);
            return;
        }
        if (depth > MAX_CHAIN_DEPTH) {
            out.append(indent).append(caption).append("<the chain goes deeper than ").append(MAX_CHAIN_DEPTH)
                    .append(" exceptions; not written: ").append(className(failure)).append('>');
            endLine(out, start, lineSeparator);
            return;
        }
        out.append(indent).append(caption).append(className(failure));
        String messageText;
        boolean hasMessage;
        try {
            messageText = failure.getMessage();
            hasMessage = messageText != null;
        } catch (Throwable unreadable) {
            messageText = "<the message could not be read: " + className(unreadable) + ">";
            hasMessage = true;
        }
        if (hasMessage) {
            List<String> lines = messageText.lines().toList();
            out.append(": ").append(lines.isEmpty() ? "" : lines.getFirst());
            endLine(out, start, lineSeparator);
            for (int i = 1; i < lines.size(); i++) {
                int lineStart = out.length();
                out.append(indent).append(lines.get(i));
                endLine(out, lineStart, lineSeparator);
            }
        } else {
            endLine(out, start, lineSeparator);
        }
        try {
            StackTraceElement[] frames = failure.getStackTrace();
            if (frames != null) {
                for (StackTraceElement frame : frames) {
                    int lineStart = out.length();
                    out.append(indent).append("\tat ").append(frame);
                    endLine(out, lineStart, lineSeparator);
                }
            }
        } catch (Throwable unreadable) {
            int lineStart = out.length();
            out.append(indent).append("\t<the stack trace could not be read: ").append(className(unreadable))
                    .append('>');
            endLine(out, lineStart, lineSeparator);
        }
        Throwable[] suppressed;
        try {
            suppressed = failure.getSuppressed();
        } catch (Throwable unreadable) {
            suppressed = new Throwable[0];
            int lineStart = out.length();
            out.append(indent).append("\t<the suppressed exceptions could not be read: ")
                    .append(className(unreadable)).append('>');
            endLine(out, lineStart, lineSeparator);
        }
        if (suppressed != null) {
            for (Throwable each : suppressed) {
                if (each != null) {
                    appendThrowable(out, each, indent + "\t", "Suppressed: ", seen, depth + 1, lineSeparator);
                }
            }
        }
        Throwable cause;
        try {
            cause = failure.getCause();
        } catch (Throwable unreadable) {
            cause = null;
            int lineStart = out.length();
            out.append(indent).append("<the cause could not be read: ").append(className(unreadable))
                    .append('>');
            endLine(out, lineStart, lineSeparator);
        }
        if (cause != null) {
            appendThrowable(out, cause, indent, "Caused by: ", seen, depth + 1, lineSeparator);
        }
    }

    /**
     * {@code text} on one line: each of the seven characters {@code \R}
     * matches — LF, CR, VT, FF, NEL, LS and PS — is written as a backslash, a
     * lower-case {@code u} and four upper-case hex digits, so LF reads
     * <code>&#92;u000A</code> and CR LF is two escapes (story 336,
     * studio-engineer ruling R992-2). It is a visible marker, not a
     * reversible encoding. Public so that {@code DawLauncher} quotes a
     * rejected session-memory setting with it, keeping its WARNING on one line
     * (story 336, studio-engineer ruling R992-8).
     *
     * @param text the value
     * @return {@code text} itself when it holds none of them, else a copy with each one escaped
     */
    public static String oneLine(String text) {
        int first = 0;
        while (first < text.length() && !isLineBreak(text.charAt(first))) {
            first++;
        }
        if (first == text.length()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 10);
        out.append(text, 0, first);
        for (int at = first; at < text.length(); at++) {
            char c = text.charAt(at);
            if (isLineBreak(c)) {
                out.append('\\').append('u');
                for (int shift = 12; shift >= 0; shift -= 4) {
                    out.append(HEX_DIGITS.charAt((c >> shift) & 0xF));
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Ends the line that begins at {@code lineStart} in {@code out}: any of
     * the seven characters {@code \R} matches inside it is written as its
     * escape ({@link #oneLine}), then {@code lineSeparator} is appended. Every
     * line {@link #appendChain}, {@link LogLineFormatter} and
     * {@link LastResortLog} write ends here (story 336, studio-engineer
     * ruling R992-2).
     *
     * @param out           the text being written
     * @param lineStart     where the line begins in {@code out}
     * @param lineSeparator what ends the line
     */
    static void endLine(StringBuilder out, int lineStart, String lineSeparator) {
        for (int at = lineStart; at < out.length(); at++) {
            if (isLineBreak(out.charAt(at))) {
                out.replace(at, out.length(), oneLine(out.substring(at)));
                break;
            }
        }
        out.append(lineSeparator);
    }

    /** Whether {@code c} is one of the seven characters {@code \R} matches: LF, CR, VT, FF, NEL, LS or PS. */
    private static boolean isLineBreak(char c) {
        return c == 0x0A || c == 0x0D || c == 0x0B || c == 0x0C || c == 0x85 || c == 0x2028 || c == 0x2029;
    }

    /** {@code failure.getCause()}, guarded: empty when it is {@code null}, itself, or unreadable. */
    private static Optional<Throwable> cause(Throwable failure) {
        try {
            Throwable cause = failure.getCause();
            return cause == failure ? Optional.empty() : Optional.ofNullable(cause);
        } catch (Throwable unreadable) {
            return Optional.empty();
        }
    }

    /** The simple class name, guarded; the full name for an anonymous class or when it cannot be read. */
    private static String simpleName(Throwable failure) {
        try {
            String simple = failure.getClass().getSimpleName();
            return simple.isEmpty() ? className(failure) : simple;
        } catch (Throwable unreadable) {
            return className(failure);
        }
    }

    private static String firstLine(String text) {
        int end = 0;
        while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
            end++;
        }
        return text.substring(0, end);
    }
}
