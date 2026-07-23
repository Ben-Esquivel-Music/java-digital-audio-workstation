package com.benesquivelmusic.daw.sdk.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PluginManifestWriter} output round-trips through
 * {@link PluginManifestReader} to an equal {@link PluginManifest} (Plugin View
 * Design Book §4.5), including non-default {@code category}/{@code iconHint},
 * strings that require JSON escaping, and the top-level-array bundle form
 * (§6.8, story 303).
 */
class PluginManifestWriterRoundTripTest {

    private final PluginManifestWriter writer = new PluginManifestWriter();
    private final PluginManifestReader reader = new PluginManifestReader();

    @Test
    void roundTripsFullManifest() {
        PluginManifest manifest = new PluginManifest(
                "com.acme.TubeWarmth",
                new PluginDescriptor(
                        "com.acme.tubewarmth", "TubeWarmth", "1.2.0", "Acme Audio",
                        PluginType.EFFECT, PluginCategory.DYNAMICS, "compressor"));

        PluginManifest roundTripped = reader.parse(writer.toJson(manifest)).manifest().orElseThrow();

        assertThat(roundTripped).isEqualTo(manifest);
    }

    @Test
    void roundTripsManifestWithDefaults() {
        PluginManifest manifest = new PluginManifest(
                "com.acme.SynthPlugin",
                new PluginDescriptor(
                        "com.acme.synth", "Acme Synth", "1.0.0", "Acme Audio", PluginType.INSTRUMENT));

        PluginManifest roundTripped = reader.parse(writer.toJson(manifest)).manifest().orElseThrow();

        assertThat(roundTripped).isEqualTo(manifest);
        assertThat(roundTripped.descriptor().category()).isEqualTo(PluginCategory.UTILITY);
        assertThat(roundTripped.descriptor().iconHint()).isEmpty();
    }

    @Test
    void omitsIconHintKeyForWhitespaceOnlyHint() {
        PluginManifest manifest = new PluginManifest(
                "com.acme.SynthPlugin",
                new PluginDescriptor(
                        "com.acme.synth", "Acme Synth", "1.0.0", "Acme Audio",
                        PluginType.INSTRUMENT, PluginCategory.UTILITY, "  \t"));

        String json = writer.toJson(manifest);

        assertThat(json).doesNotContain("iconHint");
        assertThat(reader.parse(json).manifest().orElseThrow().descriptor().iconHint()).isEmpty();
    }

    @Test
    void roundTripsBundleOfTwo() {
        PluginManifest first = new PluginManifest(
                "com.acme.TubeWarmth",
                new PluginDescriptor(
                        "com.acme.tubewarmth", "TubeWarmth", "1.2.0", "Acme Audio",
                        PluginType.EFFECT, PluginCategory.DYNAMICS, "compressor"));
        PluginManifest second = new PluginManifest(
                "com.acme.SynthPlugin",
                new PluginDescriptor(
                        "com.acme.synth", "Acme Synth", "1.0.0", "Acme Audio", PluginType.INSTRUMENT));

        PluginManifestReader.BundleResult result =
                reader.parseAll(writer.toJson(List.of(first, second)));

        assertThat(result.isValid()).isTrue();
        assertThat(result.manifests()).containsExactly(first, second);
    }

    @Test
    void oneElementListEmitsArrayReadBackByParseAll() {
        PluginManifest manifest = new PluginManifest(
                "com.acme.SoloPlugin",
                new PluginDescriptor(
                        "com.acme.solo", "Acme Solo", "1.0.0", "Acme Audio", PluginType.EFFECT));

        String json = writer.toJson(List.of(manifest));

        assertThat(json.stripLeading()).startsWith("[");
        PluginManifestReader.BundleResult result = reader.parseAll(json);
        assertThat(result.isValid()).isTrue();
        assertThat(result.manifests()).containsExactly(manifest);
    }

    @Test
    void escapesSpecialCharacters() {
        PluginManifest manifest = new PluginManifest(
                "com.acme.WeirdPlugin",
                new PluginDescriptor(
                        "com.acme.weird", "A \"quoted\" \\ name\nwith\ttabs", "1.0.0", "Acme Audio",
                        PluginType.EFFECT, PluginCategory.UTILITY, ""));

        PluginManifest roundTripped = reader.parse(writer.toJson(manifest)).manifest().orElseThrow();

        assertThat(roundTripped).isEqualTo(manifest);
    }
}
