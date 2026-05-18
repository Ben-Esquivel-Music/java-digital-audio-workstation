package com.benesquivelmusic.daw.app.ui.dialogs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sentinel marker for a {@code *Dialog} class that has <em>not yet</em>
 * been migrated to {@link DawgDialog} (story 276, UI Design Book §5.9).
 *
 * <p>{@code EveryDialogConformsTest} classpath-scans the UI packages and
 * asserts that every class whose simple name ends in {@code Dialog}
 * either {@code extends DawgDialog} or carries this annotation. The
 * annotation makes the not-yet-migrated state <strong>visible</strong>
 * and prevents drift: a new dialog cannot be added without consciously
 * either adopting the §5.9 chrome skeleton or recording a TODO here.</p>
 *
 * <p>The corrected dialog chrome (flat header, accent primary button,
 * tokenized section headers) still applies to annotated dialogs for
 * free, because the legacy {@code .dialog-pane} CSS rules are global;
 * this annotation only tracks the structural migration to the shared
 * {@link DawgDialog} skeleton.</p>
 *
 * <pre>{@code
 * @LegacyDialog("migrate to DawgDialog — story 276 follow-up")
 * public final class FooDialog extends Dialog<Void> { ... }
 * }</pre>
 *
 * @see DawgDialog
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LegacyDialog {

    /**
     * Mandatory TODO / reason string explaining why the dialog has not
     * yet been migrated to {@link DawgDialog}, or why it is structurally
     * exempt (e.g. an {@code Alert}-wrapper static helper).
     *
     * @return the migration TODO or exemption rationale, must be non-blank
     */
    String value();
}
