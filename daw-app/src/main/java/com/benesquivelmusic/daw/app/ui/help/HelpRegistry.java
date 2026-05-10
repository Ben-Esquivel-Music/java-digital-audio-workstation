package com.benesquivelmusic.daw.app.ui.help;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry of help topics and the mapping from UI control IDs to topic slugs.
 *
 * <p>Topics are loaded from classpath resources rooted at {@code /help/}.
 * The file {@code help/topics.list} lists one slug per line; each slug
 * corresponds to a {@code <slug>.md} file in the same directory.</p>
 *
 * <p>The registry is intentionally stateful for control-ID mappings — the
 * application registers IDs at startup. Topic lookup falls back to the
 * {@link #INDEX_SLUG index} topic when an unknown slug is requested, so
 * stale or broken links never produce an empty / error view.</p>
 */
public final class HelpRegistry {

    /** Slug of the top-level index topic (always present). */
    public static final String INDEX_SLUG = "index";

    private static final String RESOURCE_BASE = "/help/";
    private static final String TOPICS_LIST = RESOURCE_BASE + "topics.list";

    private static final Pattern TITLE_PATTERN = Pattern.compile("^\\s*#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");

    private final Map<String, HelpTopic> topics = new LinkedHashMap<>();
    private final Map<String, String> controlIdToSlug = new LinkedHashMap<>();

    /**
     * Loads all topics declared in {@code help/topics.list} from the classpath.
     * Always returns a registry containing at least the index topic — if
     * the resource list is missing, the registry contains a synthetic index
     * explaining the situation.
     *
     * @return a fully-loaded registry
     */
    public static HelpRegistry loadDefault() {
        return load(HelpRegistry.class);
    }

    /**
     * Loads topics relative to the classloader of {@code anchor}.
     */
    public static HelpRegistry load(Class<?> anchor) {
        Objects.requireNonNull(anchor, "anchor");
        HelpRegistry registry = new HelpRegistry();
        List<String> slugs = readTopicList(anchor);
        for (String slug : slugs) {
            String body = readResource(anchor, RESOURCE_BASE + slug + ".md");
            if (body == null) {
                continue;
            }
            registry.topics.put(slug, build(slug, body));
        }
        if (!registry.topics.containsKey(INDEX_SLUG)) {
            registry.topics.put(INDEX_SLUG, build(INDEX_SLUG,
                    "# Help Index\n\nNo help topics are bundled with this build.\n"));
        }
        return registry;
    }

    private static List<String> readTopicList(Class<?> anchor) {
        try (InputStream in = anchor.getResourceAsStream(TOPICS_LIST)) {
            if (in == null) {
                return List.of(INDEX_SLUG);
            }
            List<String> slugs = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        slugs.add(trimmed);
                    }
                }
            }
            return slugs;
        } catch (IOException e) {
            return List.of(INDEX_SLUG);
        }
    }

    private static String readResource(Class<?> anchor, String path) {
        try (InputStream in = anchor.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static HelpTopic build(String slug, String body) {
        Matcher titleMatcher = TITLE_PATTERN.matcher(body);
        String title = titleMatcher.find() ? titleMatcher.group(1).trim() : slug;

        List<String> related = new ArrayList<>();
        Matcher linkMatcher = LINK_PATTERN.matcher(body);
        while (linkMatcher.find()) {
            String target = linkMatcher.group(2).trim();
            // Treat anything that is not an http(s) URL as an internal slug.
            if (!target.contains("://") && !related.contains(target) && !target.equals(slug)) {
                related.add(target);
            }
        }
        return new HelpTopic(slug, title, body, related);
    }

    /**
     * Associates a UI control ID with a help topic slug. Existing
     * mappings for the same control ID are replaced.
     *
     * @param controlId stable identifier for the control (e.g. its
     *                  {@code Node.getId()} or a logical name)
     * @param slug      target topic slug
     */
    public void registerControl(String controlId, String slug) {
        Objects.requireNonNull(controlId, "controlId");
        Objects.requireNonNull(slug, "slug");
        controlIdToSlug.put(controlId, slug);
    }

    /**
     * Returns the topic slug bound to a control ID, or {@link Optional#empty()}.
     */
    public Optional<String> slugForControl(String controlId) {
        if (controlId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(controlIdToSlug.get(controlId));
    }

    /**
     * Resolves a slug to a topic, falling back to the {@link #INDEX_SLUG index}
     * if the slug is unknown.
     */
    public HelpTopic resolve(String slug) {
        if (slug != null) {
            HelpTopic t = topics.get(slug);
            if (t != null) {
                return t;
            }
        }
        return topics.get(INDEX_SLUG);
    }

    /** Returns true if the registry has a topic for the given slug. */
    public boolean hasTopic(String slug) {
        return slug != null && topics.containsKey(slug);
    }

    /** Returns all topics in the order declared in {@code topics.list}. */
    public List<HelpTopic> allTopics() {
        return List.copyOf(topics.values());
    }

    /**
     * Case-insensitive substring search across each topic's title and body.
     * Topics whose title matches are ranked above body-only matches.
     *
     * @param query search query (may be null/blank — returns all topics)
     * @return matching topics in rank order
     */
    public List<HelpTopic> search(String query) {
        if (query == null || query.isBlank()) {
            return allTopics();
        }
        String needle = query.toLowerCase(Locale.ROOT).trim();
        List<HelpTopic> titleHits = new ArrayList<>();
        List<HelpTopic> bodyHits = new ArrayList<>();
        for (HelpTopic topic : topics.values()) {
            if (topic.title().toLowerCase(Locale.ROOT).contains(needle)) {
                titleHits.add(topic);
            } else if (topic.body().toLowerCase(Locale.ROOT).contains(needle)) {
                bodyHits.add(topic);
            }
        }
        List<HelpTopic> all = new ArrayList<>(titleHits.size() + bodyHits.size());
        all.addAll(titleHits);
        all.addAll(bodyHits);
        return Collections.unmodifiableList(all);
    }
}
