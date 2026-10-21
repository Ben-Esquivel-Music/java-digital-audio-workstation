package com.benesquivelmusic.daw.core.persistence;

/**
 * Why a row of the missing-assets report exists (Persistence Integrity book
 * §4.3, §6.2). Every reason is relinkable; the user-facing label of each is
 * the application's (a resource string), the row's detail is in English
 * from the core.
 */
public enum MissingAssetReason {

    /** No file at the reference ("Missing"); for a take, at least one segment is absent. */
    NOT_FOUND,

    /**
     * The reference names no file on this platform ("Missing"): it escapes the
     * project directory, is the directory itself, does not parse, is drive- or
     * root-relative, or is otherwise not an absolute path
     * ({@link ProjectPaths#unresolvable}, {@link ProjectPaths#isAbsoluteReference}).
     */
    UNRESOLVABLE,

    /** The file is there but did not decode ("Can't read"; the reason is the row's detail). */
    UNREADABLE,

    /**
     * The file decoded but holds less audio than a clip spans ("Shorter than
     * the clip"); the clips were loaded as they are. Nothing persisted tells a
     * file that changed from a tempo slowed after the clip was cut, so the
     * row claims only the shortfall. The only reason whose clips play.
     */
    CHANGED_ON_DISK,

    /**
     * An ADM BWF clip whose name no longer says which bed or object of the
     * file it plays, or an ADM import's file that no longer carries ADM
     * metadata ("Needs re-import (ADM: object unknown)"); nothing is
     * guessed.
     */
    ADM_OBJECT_UNKNOWN,

    /** Decoding would need more than half of the heap, or ran out of memory ("Too large to load"). */
    TOO_LARGE;

    /**
     * Whether a row with this reason silences clips: every reason but
     * {@link #CHANGED_ON_DISK}, whose clips were loaded as they are. Which of
     * a row's holders are silent is the row's own
     * {@link MissingAsset#silentHolders()} — for an
     * {@link #ADM_OBJECT_UNKNOWN} row, possibly only some of them. The
     * report counts the assets of these rows as missing
     * ({@link MissingAssetsReport#missingAssetCount()}).
     *
     * @return {@code true} if a row with this reason may hold clips without audio
     */
    public boolean silencesClips() {
        return this != CHANGED_ON_DISK;
    }
}
