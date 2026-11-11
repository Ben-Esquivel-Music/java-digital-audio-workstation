package com.benesquivelmusic.daw.core.mixer;

import java.util.Objects;

/**
 * Why an insert is offline and what its file saved: the insert is kept at its
 * chain position, passing audio through, and is written back as saved
 * (Persistence Integrity book §4.5; story 334). Never a DOM node: saving runs
 * on a captured copy (book §2.3).
 *
 * @param cause                   why the open could not load it
 * @param saved                   the {@code <insert>} element as saved
 * @param sidechainResolvedAtOpen whether the saved {@code sidechain-source}
 *                                named a strip of the project at open; the
 *                                writer then writes the key from the live
 *                                sidechain source, which follows strip moves,
 *                                and otherwise the key as saved
 */
public record OfflineInsert(OfflineCause cause, SavedInsert saved, boolean sidechainResolvedAtOpen) {

    public OfflineInsert {
        Objects.requireNonNull(cause, "cause must not be null");
        Objects.requireNonNull(saved, "saved must not be null");
    }
}
