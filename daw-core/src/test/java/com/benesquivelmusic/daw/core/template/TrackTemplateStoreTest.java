package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.SendMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackTemplateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSaveAndReloadTemplate(@TempDir Path dir) throws IOException {
        TrackTemplateStore store = new TrackTemplateStore(dir);
        TrackTemplate template = TrackTemplateFactory.vocalTrack();

        Path saved = store.saveTemplate(template);
        assertThat(saved).exists();
        assertThat(saved.getParent()).isEqualTo(store.getTemplatesDirectory());

        List<TrackTemplate> loaded = store.loadTemplates();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().templateName()).isEqualTo(template.templateName());
        assertThat(loaded.getFirst().inserts()).hasSameSizeAs(template.inserts());
    }

    @Test
    void shouldSaveAndReloadPreset() throws IOException {
        TrackTemplateStore store = new TrackTemplateStore(tempDir);
        ChannelStripPreset preset = new ChannelStripPreset(
                "My Preset",
                List.of(InsertEffectSpec.of(InsertEffectType.COMPRESSOR, Map.of(0, -10.0))),
                List.of(new SendSpec("Reverb Return", 0.2, SendMode.POST_FADER)),
                0.8, 0.0);

        store.savePreset(preset);
        List<ChannelStripPreset> loaded = store.loadPresets();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst()).isEqualTo(preset);
    }

    @Test
    void loadingFromMissingDirectoryShouldReturnEmptyList() throws IOException {
        TrackTemplateStore store = new TrackTemplateStore(tempDir.resolve("does-not-exist"));
        assertThat(store.loadTemplates()).isEmpty();
        assertThat(store.loadPresets()).isEmpty();
    }

    @Test
    void allTemplatesShouldIncludeFactoryTemplatesBeforeUserTemplates() throws IOException {
        TrackTemplateStore store = new TrackTemplateStore(tempDir);
        TrackTemplate custom = new TrackTemplate(
                "My Custom",
                TrackTemplateFactory.vocalTrack().trackType(),
                "Cx",
                List.of(), List.of(), 1.0, 0.0,
                TrackTemplateFactory.vocalTrack().color(),
                TrackTemplateFactory.vocalTrack().inputRouting(),
                TrackTemplateFactory.vocalTrack().outputRouting());
        store.saveTemplate(custom);

        List<TrackTemplate> all = store.allTemplates();
        assertThat(all).extracting(TrackTemplate::templateName)
                .startsWith("Vocal Track", "Drum Bus", "Guitar Track", "Synth Track")
                .contains("My Custom");
    }

    @Test
    void shouldSkipMalformedFilesWhenLoading() throws IOException {
        TrackTemplateStore store = new TrackTemplateStore(tempDir);
        store.saveTemplate(TrackTemplateFactory.guitarTrack());
        Path badFile = store.getTemplatesDirectory().resolve("bad.xml");
        Files.writeString(badFile, "<not-a-template/>", StandardCharsets.UTF_8);

        List<TrackTemplate> loaded = store.loadTemplates();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().templateName()).isEqualTo("Guitar Track");
    }

    @Test
    void shouldSanitizeFileName() {
        assertThat(TrackTemplateStore.sanitizeFileName("Vocal/Track: v1")).isEqualTo("Vocal_Track_v1");
        assertThat(TrackTemplateStore.sanitizeFileName("  ")).isEqualTo("untitled");
    }
}
