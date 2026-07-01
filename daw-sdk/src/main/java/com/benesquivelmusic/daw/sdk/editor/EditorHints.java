package com.benesquivelmusic.daw.sdk.editor;

import java.util.Objects;

/**
 * What a plugin editor asks the host for: a preferred content size and the
 * resize / chrome policies the host should apply (Plugin View Design Book
 * §4.1). These are <em>hints</em> — the host obeys them where it can and
 * coerces them where it cannot (for example the embedded Workshop pane fixes
 * the width, so a {@link ResizePolicy#BOTH} hint behaves as
 * {@link ResizePolicy#VERTICAL} until the editor is detached to a floating
 * window §5.A/§5.B).
 *
 * @param preferredWidth  the preferred content width in pixels (must be &gt; 0)
 * @param preferredHeight the preferred content height in pixels (must be &gt; 0)
 * @param resize          which axes the host may resize (never {@code null})
 * @param chrome          how much host chrome to show (never {@code null})
 */
public record EditorHints(
        int preferredWidth,
        int preferredHeight,
        ResizePolicy resize,
        ChromePolicy chrome) {

    public EditorHints {
        if (preferredWidth <= 0) {
            throw new IllegalArgumentException(
                    "preferredWidth must be > 0, got " + preferredWidth);
        }
        if (preferredHeight <= 0) {
            throw new IllegalArgumentException(
                    "preferredHeight must be > 0, got " + preferredHeight);
        }
        Objects.requireNonNull(resize, "resize must not be null");
        Objects.requireNonNull(chrome, "chrome must not be null");
    }

    /**
     * A compact editor with standard chrome — the default for a declarative
     * plugin generated from {@code getParameters()} (§4.2). Reflows its
     * parameter grid horizontally as the pane width changes (§6.2).
     *
     * @return compact hints, never {@code null}
     */
    public static EditorHints compact() {
        return new EditorHints(360, 240, ResizePolicy.BOTH, ChromePolicy.STANDARD);
    }

    /**
     * A roomier editor with standard chrome — a sensible default for a
     * {@link PluginEditorFactory.Panel} that ships a custom layout.
     *
     * @return standard hints, never {@code null}
     */
    public static EditorHints standard() {
        return new EditorHints(640, 420, ResizePolicy.BOTH, ChromePolicy.STANDARD);
    }

    /**
     * A full-surface editor whose chrome retracts on inactivity — a sensible
     * default for a {@link PluginEditorFactory.Canvas} analyzer or scope that
     * wants the chrome out of the way (§5.D).
     *
     * @return immersive hints, never {@code null}
     */
    public static EditorHints immersive() {
        return new EditorHints(720, 480, ResizePolicy.BOTH, ChromePolicy.IMMERSIVE);
    }

    /** Returns a copy of these hints with the given chrome policy. */
    public EditorHints withChrome(ChromePolicy newChrome) {
        return new EditorHints(preferredWidth, preferredHeight, resize, newChrome);
    }

    /** Returns a copy of these hints with the given resize policy. */
    public EditorHints withResize(ResizePolicy newResize) {
        return new EditorHints(preferredWidth, preferredHeight, newResize, chrome);
    }

    /** Returns a copy of these hints with the given preferred content size. */
    public EditorHints withSize(int newPreferredWidth, int newPreferredHeight) {
        return new EditorHints(newPreferredWidth, newPreferredHeight, resize, chrome);
    }
}
