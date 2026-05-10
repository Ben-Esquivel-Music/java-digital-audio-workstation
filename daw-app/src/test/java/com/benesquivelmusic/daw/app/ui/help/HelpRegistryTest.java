package com.benesquivelmusic.daw.app.ui.help;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HelpRegistryTest {

    @Test
    void loadDefaultIncludesIndexAndKnownTopics() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        assertThat(registry.hasTopic("index")).isTrue();
        assertThat(registry.hasTopic("transport")).isTrue();
        assertThat(registry.hasTopic("mixer")).isTrue();
    }

    @Test
    void resolveUnknownSlugFallsBackToIndex() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        HelpTopic resolved = registry.resolve("does-not-exist");

        assertThat(resolved).isNotNull();
        assertThat(resolved.slug()).isEqualTo(HelpRegistry.INDEX_SLUG);
    }

    @Test
    void resolveNullSlugFallsBackToIndex() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        HelpTopic resolved = registry.resolve(null);

        assertThat(resolved.slug()).isEqualTo(HelpRegistry.INDEX_SLUG);
    }

    @Test
    void registerControlMapsIdToSlug() {
        HelpRegistry registry = HelpRegistry.loadDefault();
        registry.registerControl("playButton", "transport");

        assertThat(registry.slugForControl("playButton")).contains("transport");
        assertThat(registry.slugForControl("unknown")).isEmpty();
        assertThat(registry.slugForControl(null)).isEmpty();
    }

    @Test
    void searchByTitleRanksTitleHitsFirst() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        List<HelpTopic> hits = registry.search("Transport");

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).slug()).isEqualTo("transport");
    }

    @Test
    void searchByBodyFindsTopicsWithoutTitleMatch() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        // The arrangement topic body mentions "automation" but the title is just
        // "Arrangement View" — so a body-only match is the only way to find it.
        List<HelpTopic> hits = registry.search("automation");

        assertThat(hits).extracting(HelpTopic::slug).contains("arrangement");
    }

    @Test
    void searchBlankQueryReturnsAllTopics() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        assertThat(registry.search("")).hasSize(registry.allTopics().size());
        assertThat(registry.search(null)).hasSize(registry.allTopics().size());
    }

    @Test
    void topicTitleParsedFromFirstHeading() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        assertThat(registry.resolve("transport").title()).isEqualTo("Transport Controls");
        assertThat(registry.resolve("mixer").title()).isEqualTo("Mixer");
    }

    @Test
    void topicRelatedSlugsIncludeInternalLinkTargets() {
        HelpRegistry registry = HelpRegistry.loadDefault();

        HelpTopic transport = registry.resolve("transport");

        assertThat(transport.related()).contains("mixer", "arrangement");
    }
}
