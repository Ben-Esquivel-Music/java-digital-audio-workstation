package com.benesquivelmusic.daw.app.ui.settings;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The §3.4 setting descriptor — an immutable, transparent data carrier
 * for exactly the nine per-setting metadata fields (Settings View Design
 * Book §3.4, story 305).
 *
 * <p>A descriptor <em>describes</em> a setting; it never stores one.
 * {@code SettingsModel} over {@link java.util.prefs.Preferences} stays
 * the single source of truth, and the descriptor's {@link #validator()}
 * delegates to the existing setter guard rather than replacing it (see
 * {@link SettingsCatalogue}). The descriptor holds no scene-graph node —
 * the generic setting row that renders it arrives with story 306.</p>
 *
 * @param id           the setting's identity — the {@code Preferences}
 *                     key (or manager {@code PREF_KEY}, or
 *                     {@code "keybinding." + action.name()})
 * @param label        the resolved, localized display label (no
 *                     trailing colon; call sites add punctuation)
 * @param description  the resolved, localized one-sentence description
 * @param synonyms     lowercase extra search terms for the §6.1 search
 *                     index (search data, not display strings);
 *                     defensively copied to an immutable list
 * @param scope        the setting's reach (§3.2)
 * @param applyClass   when a change takes effect (§6.4)
 * @param controlKind  the kind of control the setting renders as (§3.4)
 * @param defaultValue the canonical default, derived from the owning
 *                     authority; nullable — a key-binding action may
 *                     have no factory binding
 * @param validator    per-value acceptance check delegating to the
 *                     canonical setter guard; never {@code null}
 * @param <T>          the setting's value type
 */
public record SettingDescriptor<T>(
        String id, String label, String description, List<String> synonyms,
        Scope scope, ApplyClass applyClass, ControlKind controlKind,
        T defaultValue, Predicate<T> validator) {

    /**
     * Validates every component except {@code defaultValue} (nullable by
     * design) and defensively copies {@code synonyms}.
     */
    public SettingDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(synonyms, "synonyms must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(applyClass, "applyClass must not be null");
        Objects.requireNonNull(controlKind, "controlKind must not be null");
        Objects.requireNonNull(validator, "validator must not be null");
        synonyms = List.copyOf(synonyms);
    }

    /**
     * Tests an untyped candidate against this descriptor's validator.
     *
     * <p>Type rejection is best-effort: a wrong-typed candidate fails
     * only when the validator body itself casts the value — as every
     * setter-delegating validator does — and that
     * {@link ClassCastException} is caught and reported as a plain
     * rejection. Validators that never cast (e.g. {@code Objects::nonNull}
     * or the always-true key-binding validators) accept any candidate
     * their predicate accepts, regardless of runtime type. Callers
     * holding an {@code Object} (e.g. a dialog field's parsed value)
     * still need no per-type dispatch.</p>
     *
     * @param value the candidate value (may be {@code null})
     * @return {@code true} if the validator accepts the value
     */
    public boolean accepts(Object value) {
        @SuppressWarnings("unchecked")
        Predicate<Object> untyped = (Predicate<Object>) validator;
        try {
            return untyped.test(value);
        } catch (ClassCastException wrongType) {
            return false;
        }
    }
}
