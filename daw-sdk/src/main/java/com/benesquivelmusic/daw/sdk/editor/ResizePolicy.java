package com.benesquivelmusic.daw.sdk.editor;

/**
 * How a plugin editor surface may be resized by the host.
 *
 * <p>A plugin declares its preferred policy through {@link EditorHints#resize()}.
 * The host <em>enforces</em> the policy — a plugin cannot resize itself
 * (Plugin View Design Book §2.2, §6.3). The policy constrains which axes the
 * host's resize grip acts on; the host is free to coerce a request it cannot
 * honour (for example, width is inherited from the Workshop right pane in the
 * default {@code Workbench} concept §5.A, so a {@link #HORIZONTAL} request is
 * ignored while embedded but honoured once detached to a floating window).</p>
 *
 * @see EditorHints
 */
public enum ResizePolicy {

    /** The editor is a fixed size on both axes; the host shows no resize grip. */
    FIXED,

    /** Only the width may change; height is fixed. */
    HORIZONTAL,

    /** Only the height may change; width is fixed. */
    VERTICAL,

    /** The editor may be resized freely on both axes. */
    BOTH
}
