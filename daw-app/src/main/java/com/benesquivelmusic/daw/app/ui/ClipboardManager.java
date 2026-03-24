package com.benesquivelmusic.daw.app.ui;

/**
 * Tracks whether the application clipboard contains content that can be pasted.
 *
 * <p>This is a lightweight model that records whether a "copy" or "cut"
 * operation has placed content on the internal clipboard. It does <em>not</em>
 * interact with the system clipboard — only with in-app operations.</p>
 */
public final class ClipboardManager {

    private boolean hasContent;

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
     * Clears the clipboard content (e.g. after a paste that consumes the content,
     * or an explicit clear operation).
     */
    public void clear() {
        this.hasContent = false;
    }
}
