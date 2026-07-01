package com.benesquivelmusic.daw.sdk.editor;

import org.junit.jupiter.api.Test;

import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PluginManifestWriter} output round-trips through
 * {@link PluginManifestReader} to an equal {@link PluginManifest} (Plugin View
 * Design Book §4.5), including non-default {@code category}/{@code iconHint} and
 * strings that require JSON escaping.
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
