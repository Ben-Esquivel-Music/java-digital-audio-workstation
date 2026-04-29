package com.benesquivelmusic.daw.app.ui.theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregates the result of running {@link ThemeContrastValidator} over
 * every {@link Theme.Pair} declared in a {@link Theme}.
 *
 * <p>This is the data model behind the contrast-audit pane in the
 * {@link ThemePickerDialog} and the data fixture used by
 * {@code ThemeRegistryTest} to assert that every bundled theme passes
 * WCAG AA for every declared pair.</p>
 */
public record ThemeAuditReport(Theme theme, List<Entry> entries) {

    public ThemeAuditReport {
        Objects.requireNonNull(theme, "theme must not be null");
        Objects.requireNonNull(entries, "entries must not be null");
        entries = List.copyOf(entries);
    }

    /** Audits every pair declared by {@code theme}. */
    public static ThemeAuditReport audit(Theme theme) {
        Objects.requireNonNull(theme, "theme must not be null");
        List<Entry> out = new ArrayList<>(theme.pairs().size());
        for (Theme.Pair pair : theme.pairs()) {
            String fgHex = theme.hex(pair.foreground());
            String bgHex = theme.hex(pair.background());
            double ratio = ThemeContrastValidator.contrastRatio(fgHex, bgHex);
            out.add(new Entry(pair, fgHex, bgHex, ratio,
                    ThemeContrastValidator.classify(ratio)));
        }
        return new ThemeAuditReport(theme, out);
    }

    /** Returns {@code true} when every declared pair reaches AA or better. */
    public boolean passesAA() {
        return entries.stream().allMatch(e -> e.tier() != ThemeContrastValidator.Tier.FAIL);
    }

    /** Returns {@code true} when every declared pair reaches AAA. */
    public boolean passesAAA() {
        return entries.stream().allMatch(e -> e.tier() == ThemeContrastValidator.Tier.AAA);
    }

    /** A single audited pair. */
    public record Entry(
            Theme.Pair pair,
            String foregroundHex,
            String backgroundHex,
            double ratio,
            ThemeContrastValidator.Tier tier) {
        public Entry {
            Objects.requireNonNull(pair, "pair must not be null");
            Objects.requireNonNull(foregroundHex, "foregroundHex must not be null");
            Objects.requireNonNull(backgroundHex, "backgroundHex must not be null");
            Objects.requireNonNull(tier, "tier must not be null");
        }
    }
}
