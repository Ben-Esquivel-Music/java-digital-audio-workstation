package com.benesquivelmusic.daw.app.ui.help;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the "first launch" flag that gates the onboarding tour.
 *
 * <p>State is stored as a single line in a UTF-8 text file. The default
 * location is {@code ~/.benesquivelmusic-daw/onboarding.flag}; callers can
 * supply an alternate path (used by tests).</p>
 */
public final class OnboardingState {

    private static final String FILENAME = "onboarding.flag";
    private static final String COMPLETED_MARKER = "completed";

    private final Path file;

    public OnboardingState(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Default location under the user's home directory. */
    public static OnboardingState defaultLocation() {
        Path dir = Path.of(System.getProperty("user.home", "."), ".benesquivelmusic-daw");
        return new OnboardingState(dir.resolve(FILENAME));
    }

    /** Returns true when the tour has been completed (or explicitly skipped). */
    public boolean isCompleted() {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            String contents = Files.readString(file, StandardCharsets.UTF_8).trim();
            return contents.equals(COMPLETED_MARKER);
        } catch (IOException e) {
            return false;
        }
    }

    /** True when the tour should run on this launch. */
    public boolean shouldRunTour() {
        return !isCompleted();
    }

    /** Persist the "completed" marker so future launches skip the tour. */
    public void markCompleted() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, COMPLETED_MARKER, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Best-effort persistence — failing here just means the tour
            // shows again next launch, which is preferable to crashing.
        }
    }

    /** For tests / "Re-run tour" menu items. */
    public void reset() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignore) {
            // ignored
        }
    }

    /** Exposes the underlying file path (for diagnostics). */
    public Path file() {
        return file;
    }
}
