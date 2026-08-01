package com.benesquivelmusic.daw.app.ui.settings;

/**
 * The kind of control a setting renders as (Settings View Design Book
 * §3.4, story 305).
 *
 * <p>Constants in the book's §3.4 order. The descriptor records the
 * kind as metadata only — no descriptor holds a scene-graph node; the
 * generic setting row that renders these arrives with story 306. The
 * enum shape is pinned by {@code DescriptorEnumsAreCanonicalTest}.</p>
 */
public enum ControlKind {

    /** An on/off switch or check box. */
    TOGGLE,

    /** A fixed-option picker (combo box). */
    CHOICE,

    /** A continuous numeric slider. */
    SLIDER,

    /**
     * A discrete numeric stepper. First used by the story-308 absorbed
     * Recording/Backups descriptors (click channel, retention buckets).
     */
    STEPPER,

    /** A free-form text field. */
    TEXT,

    /** A file / folder path picker. */
    PATH,

    /** A key-combination capture field (Key Bindings rows). */
    KEY_CAPTURE
}
