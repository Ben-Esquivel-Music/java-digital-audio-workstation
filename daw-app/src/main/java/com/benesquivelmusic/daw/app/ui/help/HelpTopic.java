package com.benesquivelmusic.daw.app.ui.help;

import java.util.List;
import java.util.Objects;

/**
 * Immutable record describing a single help topic loaded from a markdown file
 * in {@code daw-app/src/main/resources/help/}.
 *
 * @param slug    short identifier matching the markdown file name
 *                (without the {@code .md} extension)
 * @param title   first {@code # heading} of the markdown source (or
 *                the slug, if no heading is present)
 * @param body    full markdown source for the topic
 * @param related slugs referenced from the topic's {@code [text](slug)} links
 */
public record HelpTopic(String slug, String title, String body, List<String> related) {

    public HelpTopic {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        related = related == null ? List.of() : List.copyOf(related);
    }
}
