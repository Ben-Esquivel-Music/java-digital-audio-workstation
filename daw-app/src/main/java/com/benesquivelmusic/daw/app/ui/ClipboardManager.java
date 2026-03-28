package com.benesquivelmusic.daw.app.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Tracks whether the application clipboard contains content that can be pasted.
 *
 * <p>This is a lightweight model that records whether a "copy" or "cut"
 * operation has placed content on the internal clipboard. It does <em>not</em>
 * interact with the system clipboard — only with in-app operations.</p>
 *
 * <p>In addition to the simple {@link #markCopied()} flag, the clipboard can
 * store a list of {@link ClipboardEntry} instances representing the clips
 * that were copied or cut. These entries are available via
 * {@link #getEntries()} and are used by paste and duplicate operations.</p>
 */
public final class ClipboardManager {

    private boolean hasContent;
    private final List<ClipboardEntry> entries = new ArrayList<>();

    /**
     * Creates a clipboard manager with no content.
     */
    public ClipboardManager() {
        this.hasContent = false;
    }

    /**
     * Returns {@code true} if the clipboard contains content that can be pasted.
     *
     * @return whether the clipboard has content
     */
    public boolean hasContent() {
        return hasContent;
    }

    /**
     * Marks the clipboard as containing content (e.g. after a copy or cut).
     */
    public void markCopied() {
        this.hasContent = true;
    }

    /**
     * Stores the given clipboard entries, replacing any previous content,
     * and marks the clipboard as containing content.
     *
     * @param newEntries the entries to store (must not be {@code null} or empty)
     * @throws NullPointerException     if {@code newEntries} is {@code null}
     * @throws IllegalArgumentException if {@code newEntries} is empty
     */
    public void copyClips(List<ClipboardEntry> newEntries) {
        Objects.requireNonNull(newEntries, "newEntries must not be null");
        if (newEntries.isEmpty()) {
            throw new IllegalArgumentException("newEntries must not be empty");
        }
        entries.clear();
        entries.addAll(newEntries);
        this.hasContent = true;
    }

    /**
     * Returns an unmodifiable view of the current clipboard entries.
     *
     * @return the clipboard entries (empty if no content has been stored)
     */
    public List<ClipboardEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Clears the clipboard content (e.g. after a paste that consumes the content,
     * or an explicit clear operation).
     */
    public void clear() {
        this.hasContent = false;
        entries.clear();
    }
}
