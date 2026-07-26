package com.benesquivelmusic.daw.app.ui.settings;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The §6.1 always-on search index over the story-305
 * {@link SettingsCatalogue} — pure, in-memory string matching built at
 * dialog-open time (Settings View Design Book §6.1 / §5.2, story 306).
 *
 * <p>Every descriptor's searchable text — label, synonyms, description,
 * and the titles of the group and category it lives in — is normalized
 * once at build time: Unicode NFD decomposition, combining marks
 * ({@code \p{M}}) stripped, lower-cased in {@link Locale#ROOT}. Queries
 * are normalized identically (after stripping leading/trailing
 * whitespace), so matching is case- and diacritic-insensitive on both
 * sides ("LÁTENCY" finds "latency") and a pasted {@code " latency "}
 * finds what {@code "latency"} finds.
 * Because the index is built from the catalogue, a newly catalogued
 * descriptor is searchable with no extra wiring (§6.1).</p>
 *
 * <p>Hits are ranked by {@link MatchField} tier — exact label above
 * label-contains above synonym above description above group/category
 * title (§6.1: exact-label hits rank above description hits) — and a
 * descriptor appears at most once, under its strongest matching field.
 * Within a tier, hits keep catalogue order.
 * {@link #matchCountsByCategory(String)} feeds the rail's per-category
 * match counts and no-match dimming (§5.2) and, by construction,
 * agrees exactly with {@link #search(String)}'s groupings.</p>
 *
 * <p>Deliberately toolkit-free and I/O-free: the index operates on
 * plain descriptor text only — no JavaFX types, no device enumeration,
 * no disk usage (§2.7/§6.6 keep the heavier catalogue sources off this
 * path). Immutable after construction and safe to share.</p>
 */
public final class SettingsSearchIndex {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final List<IndexedSetting> entries;

    private SettingsSearchIndex(List<IndexedSetting> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Builds the §6.1 index from every descriptor in the catalogue at
     * open time. Iterates {@link SettingsCatalogue#categories()} so the
     * index order — and therefore the within-tier result order — is the
     * canonical catalogue order.
     *
     * @param catalogue the story-305 catalogue to index (must not be
     *                  {@code null})
     * @return an immutable index over every catalogued descriptor
     */
    public static SettingsSearchIndex of(SettingsCatalogue catalogue) {
        Objects.requireNonNull(catalogue, "catalogue must not be null");
        List<IndexedSetting> entries = new ArrayList<>();
        for (SettingsCatalogue.Category category : catalogue.categories()) {
            String normalizedCategoryTitle = normalize(category.title());
            for (SettingsCatalogue.Group group : category.groups()) {
                String normalizedGroupTitle = normalize(group.title());
                for (SettingDescriptor<?> descriptor : group.settings()) {
                    entries.add(new IndexedSetting(descriptor,
                            category.id(), group.id(),
                            normalize(descriptor.label()),
                            descriptor.synonyms().stream()
                                    .map(SettingsSearchIndex::normalize)
                                    .toList(),
                            normalize(descriptor.description()),
                            normalizedGroupTitle,
                            normalizedCategoryTitle));
                }
            }
        }
        return new SettingsSearchIndex(entries);
    }

    /** Rank tiers, strongest first (§6.1: exact-label above description). */
    public enum MatchField { LABEL_EXACT, LABEL, SYNONYM, DESCRIPTION, TITLE }

    /**
     * One hit; descriptor plus where it lives and why it matched.
     *
     * @param descriptor the matched descriptor
     * @param categoryId the {@link SettingsCatalogue.Category#id()} the
     *                   descriptor lives under
     * @param groupId    the {@link SettingsCatalogue.Group#id()} the
     *                   descriptor lives under
     * @param field      the strongest field the query matched
     */
    public record Match(SettingDescriptor<?> descriptor, String categoryId,
                        String groupId, MatchField field) {}

    /**
     * All hits for the query, ordered: by {@link MatchField} ordinal,
     * then catalogue order within a tier. Blank/empty (or {@code null})
     * query — including a query that is blank <em>after</em>
     * normalization, e.g. bare combining marks — returns an empty list.
     * Leading/trailing whitespace is stripped before matching, so a
     * pasted {@code " latency "} matches exactly what {@code "latency"}
     * does and filtering agrees with the row skin's highlight range,
     * which strips identically. Matching is case-insensitive and
     * diacritic-insensitive on both sides ({@link Normalizer} NFD +
     * strip {@code \p{M}} + {@code toLowerCase(Locale.ROOT)}). A
     * descriptor appears at most once, under its strongest field:
     * {@code LABEL_EXACT} = normalized query equals normalized label;
     * {@code LABEL} = label contains
     * query; {@code SYNONYM} = any synonym contains query;
     * {@code DESCRIPTION} = description contains query; {@code TITLE} =
     * the descriptor's group or category title contains query.
     *
     * @param query the raw, un-normalized user query (may be
     *              {@code null} or blank)
     * @return the ranked hits, immutable; empty for a blank query
     */
    public List<Match> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalizedQuery = normalize(query.strip());
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        // One bucket per tier keeps within-tier catalogue order without
        // relying on sort stability: entries are visited in catalogue
        // order and appended to exactly one bucket (strongest field).
        List<List<Match>> tiers = new ArrayList<>();
        for (int tier = 0; tier < MatchField.values().length; tier++) {
            tiers.add(new ArrayList<>());
        }
        for (IndexedSetting entry : entries) {
            MatchField field = strongestField(entry, normalizedQuery);
            if (field != null) {
                tiers.get(field.ordinal()).add(new Match(entry.descriptor(),
                        entry.categoryId(), entry.groupId(), field));
            }
        }
        List<Match> results = new ArrayList<>();
        for (List<Match> tier : tiers) {
            results.addAll(tier);
        }
        return List.copyOf(results);
    }

    /**
     * Per-category hit count for the query (rail counts/dimming, §5.2).
     * Empty query returns an empty map. Derived directly from
     * {@link #search(String)} so the counts agree exactly with the
     * search groupings; categories with zero hits are absent (the rail
     * treats absence as zero and dims the item).
     *
     * @param query the raw, un-normalized user query (may be
     *              {@code null} or blank)
     * @return category id to hit count, immutable, keyed in
     *         first-appearance order of the search results; empty for a
     *         blank query
     */
    public Map<String, Integer> matchCountsByCategory(String query) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Match match : search(query)) {
            counts.merge(match.categoryId(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /**
     * Classifies an entry against the normalized query.
     *
     * @return the strongest matching field, or {@code null} when the
     *         entry does not match at all
     */
    private static MatchField strongestField(IndexedSetting entry,
                                             String normalizedQuery) {
        if (entry.normalizedLabel().equals(normalizedQuery)) {
            return MatchField.LABEL_EXACT;
        }
        if (entry.normalizedLabel().contains(normalizedQuery)) {
            return MatchField.LABEL;
        }
        for (String synonym : entry.normalizedSynonyms()) {
            if (synonym.contains(normalizedQuery)) {
                return MatchField.SYNONYM;
            }
        }
        if (entry.normalizedDescription().contains(normalizedQuery)) {
            return MatchField.DESCRIPTION;
        }
        if (entry.normalizedGroupTitle().contains(normalizedQuery)
                || entry.normalizedCategoryTitle().contains(normalizedQuery)) {
            return MatchField.TITLE;
        }
        return null;
    }

    /**
     * The §6.1 normalization applied to both indexed text and queries:
     * NFD decomposition, combining marks stripped, lower-cased in
     * {@link Locale#ROOT}.
     */
    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("")
                .toLowerCase(Locale.ROOT);
    }

    /**
     * One descriptor's pre-normalized searchable text plus its §3.3
     * taxonomy address, captured at build time in catalogue order.
     */
    private record IndexedSetting(
            SettingDescriptor<?> descriptor, String categoryId, String groupId,
            String normalizedLabel, List<String> normalizedSynonyms,
            String normalizedDescription, String normalizedGroupTitle,
            String normalizedCategoryTitle) {}
}
