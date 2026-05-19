package com.benesquivelmusic.daw.app.ui.theme;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sentinel marker for a UI class that still constructs colours with a
 * literal {@code Color.web(...)} / {@code Color.rgb(...)} call instead of
 * resolving them from a {@code -token} CSS lookup (story 277, UI Design
 * Book §3.1 / §6 Phase 3).
 *
 * <p>{@code LegacyHardcodedColorAuditTest} scans the {@code
 * com.benesquivelmusic.daw.app.ui} source tree for {@code Color.web(} and
 * {@code Color.rgb(} calls and asserts that every offending source file
 * carries this annotation with a non-blank TODO. The annotation makes the
 * not-yet-tokenized state <strong>visible</strong> and prevents drift: a
 * new control cannot hard-code a paint without consciously recording why
 * it is exempt and what the migration is.</p>
 *
 * <p>This is the direct sibling of
 * {@link com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog} (story
 * 276): same pattern (mandatory non-blank {@code value()} TODO,
 * {@code @Documented}, type-targeted), same intent (a green gate that
 * tracks technical debt without forcing a risky bulk refactor). Story
 * 277's Non-Goals explicitly defer the ~365-call Canvas/inline-paint
 * tokenization; the audit "tolerates a sentinel {@code
 * @HardcodedColorAllowed} annotation with a TODO" so the debt is tracked,
 * not hidden.</p>
 *
 * <p>Retention is {@link RetentionPolicy#SOURCE}: the audit is a
 * source-file text scan (no module/reflection concerns), so the marker
 * does not need to survive compilation.</p>
 *
 * <pre>{@code
 * @HardcodedColorAllowed("story 277 follow-up: migrate Canvas paints to resolved -token CSS")
 * public final class FooRenderer { ... }
 * }</pre>
 *
 * @see com.benesquivelmusic.daw.app.ui.dialogs.LegacyDialog
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface HardcodedColorAllowed {

    /**
     * Mandatory TODO / reason string explaining why the class still
     * hard-codes colour, and what the migration is (or why it is exempt
     * by design, e.g. it parses arbitrary user-supplied JSON hex).
     *
     * @return the migration TODO or exemption rationale, must be non-blank
     */
    String value();
}
