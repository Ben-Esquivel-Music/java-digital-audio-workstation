package com.benesquivelmusic.daw.core.export;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Result of an ADM BWF export attempt, containing validation errors,
 * warnings, and the success/failure status.
 *
 * <p>Before an export is written to disk, the Atmos session configuration
 * is validated. If validation fails, the result carries the error messages
 * and the export is not performed. Even on success, the result may carry
 * informational warnings.</p>
 */
public final class AtmosExportResult {

    private final boolean success;
    private final List<String> errors;
    private final List<String> warnings;

    private AtmosExportResult(boolean success, List<String> errors, List<String> warnings) {
        this.success = success;
        this.errors = Collections.unmodifiableList(Objects.requireNonNull(errors));
        this.warnings = Collections.unmodifiableList(Objects.requireNonNull(warnings));
    }

    /**
     * Creates a successful export result.
     *
     * @param warnings any informational warnings (may be empty)
     * @return a successful result
     */
    public static AtmosExportResult success(List<String> warnings) {
        return new AtmosExportResult(true, List.of(), warnings);
    }

    /**
     * Creates a successful export result with no warnings.
     *
     * @return a successful result
     */
    public static AtmosExportResult success() {
        return new AtmosExportResult(true, List.of(), List.of());
    }

    /**
     * Creates a failed export result with validation errors.
     *
     * @param errors   the validation errors that prevented export
     * @param warnings any additional warnings
     * @return a failed result
     */
    public static AtmosExportResult failure(List<String> errors, List<String> warnings) {
        return new AtmosExportResult(false, errors, warnings);
    }

    /**
     * Creates a failed export result with validation errors and no warnings.
     *
     * @param errors the validation errors that prevented export
     * @return a failed result
     */
    public static AtmosExportResult failure(List<String> errors) {
        return new AtmosExportResult(false, errors, List.of());
    }

    /** Returns {@code true} if the export was successful. */
    public boolean isSuccess() {
        return success;
    }

    /** Returns the validation error messages (empty on success). */
    public List<String> getErrors() {
        return errors;
    }

    /** Returns informational warnings (may be non-empty even on success). */
    public List<String> getWarnings() {
        return warnings;
    }
}
