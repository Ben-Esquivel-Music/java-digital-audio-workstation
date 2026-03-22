package com.benesquivelmusic.daw.sdk.session;

import java.util.List;
import java.util.Objects;

/**
 * Result of importing a session file.
 *
 * <p>Contains the reconstructed {@link SessionData} and a list of warning
 * messages for features that could not be mapped during import.</p>
 *
 * @param sessionData the imported session data
 * @param warnings    warnings about unsupported or partially-mapped features
 */
public record SessionImportResult(
        SessionData sessionData,
        List<String> warnings
) {
    public SessionImportResult {
        Objects.requireNonNull(sessionData, "sessionData must not be null");
        Objects.requireNonNull(warnings, "warnings must not be null");
        warnings = List.copyOf(warnings);
    }
}
