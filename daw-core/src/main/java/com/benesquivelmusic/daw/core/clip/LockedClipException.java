package com.benesquivelmusic.daw.core.clip;

/**
 * Thrown when a position-changing operation (move, nudge, slip, ripple,
 * cross-track drag) is attempted against a time-locked clip.
 *
 * <p>Callers should catch this exception and surface a status-bar message
 * such as "1 locked clip refused to move — unlock it (Ctrl+Shift+L) and try
 * again." Once caught, the model is guaranteed to be unchanged: the
 * affected actions perform an up-front lock check before mutating any
 * clip state.</p>
 */
public final class LockedClipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int lockedClipCount;

    /**
     * Creates a new exception describing a refused operation.
     *
     * @param operation       the operation name (e.g. {@code "Move"},
     *                        {@code "Nudge"}, {@code "Slip"})
     * @param lockedClipCount the number of locked clips that blocked the
     *                        operation (must be {@code > 0})
     */
    public LockedClipException(String operation, int lockedClipCount) {
        super(buildMessage(operation, lockedClipCount));
        if (lockedClipCount <= 0) {
            throw new IllegalArgumentException(
                    "lockedClipCount must be positive: " + lockedClipCount);
        }
        this.lockedClipCount = lockedClipCount;
    }

    /** The number of locked clips that blocked the refused operation. */
    public int getLockedClipCount() {
        return lockedClipCount;
    }

    private static String buildMessage(String operation, int count) {
        String op = operation == null || operation.isBlank() ? "Operation" : operation;
        return count == 1
                ? op + " refused: 1 clip is time-locked. "
                + "Unlock it (Ctrl+Shift+L) and try again."
                : op + " refused: " + count + " clips are time-locked. "
                + "Unlock them (Ctrl+Shift+L) and try again.";
    }

    /**
     * Counts locked clips in {@code clips} and throws
     * {@link LockedClipException} when at least one is locked.
     *
     * @param operation the operation name used in the exception message
     * @param clips     the clips to check; {@code null} entries are skipped
     * @throws LockedClipException if any clip in {@code clips} is locked
     */
    public static void requireUnlocked(String operation, Iterable<? extends Clip> clips) {
        if (clips == null) {
            return;
        }
        int count = 0;
        for (Clip c : clips) {
            if (c != null && c.isLocked()) {
                count++;
            }
        }
        if (count > 0) {
            throw new LockedClipException(operation, count);
        }
    }

    /**
     * Convenience overload for a single clip — throws when {@code clip}
     * is non-{@code null} and locked.
     */
    public static void requireUnlocked(String operation, Clip clip) {
        if (clip != null && clip.isLocked()) {
            throw new LockedClipException(operation, 1);
        }
    }
}
