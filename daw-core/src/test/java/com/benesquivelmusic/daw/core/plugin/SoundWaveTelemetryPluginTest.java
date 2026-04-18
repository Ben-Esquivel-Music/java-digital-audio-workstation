package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.telemetry.ArmedTrackSourceProvider;
import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundWaveTelemetryPluginTest {

    private SoundWaveTelemetryPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new SoundWaveTelemetryPlugin();
    }

    // ── Construction ───────────────────────────────────────────────────

    @Test
    void shouldHavePublicNoArgConstructor() {
        SoundWaveTelemetryPlugin fresh = new SoundWaveTelemetryPlugin();
        assertThat(fresh).isNotNull();
    }

    // ── Descriptor Metadata ────────────────────────────────────────────

    @Test
    void shouldReturnMenuLabel() {
        assertThat(plugin.getMenuLabel()).isEqualTo("Sound Wave Telemetry");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(plugin.getMenuIcon()).isEqualTo("surround");
    }

    @Test
    void shouldReturnAnalyzerCategory() {
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.ANALYZER);
    }

    @Test
    void shouldReturnDescriptorWithAnalyzerType() {
        var descriptor = plugin.getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.ANALYZER);
        assertThat(descriptor.name()).isEqualTo("Sound Wave Telemetry");
        assertThat(descriptor.id()).isEqualTo("com.benesquivelmusic.daw.builtin.sound-wave-telemetry");
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void pluginIdConstantShouldMatchDescriptorId() {
        assertThat(SoundWaveTelemetryPlugin.PLUGIN_ID).isEqualTo(plugin.getDescriptor().id());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    void initializeShouldRejectNullContext() {
        assertThatThrownBy(() -> plugin.initialize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void activateShouldMarkActive() {
        plugin.initialize(stubContext());
        plugin.activate();
        assertThat(plugin.isActive()).isTrue();
    }

    @Test
    void deactivateShouldMarkInactive() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        assertThat(plugin.isActive()).isFalse();
    }

    @Test
    void deactivateBeforeInitializeShouldNotThrow() {
        plugin.deactivate();
    }

    @Test
    void disposeShouldMarkInactive() {
        plugin.initialize(stubContext());
        plugin.activate();
        assertThat(plugin.isActive()).isTrue();

        plugin.dispose();
        assertThat(plugin.isActive()).isFalse();
    }

    @Test
    void disposeBeforeInitializeShouldNotThrow() {
        plugin.dispose();
    }

    @Test
    void shouldImplementFullLifecycle() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.deactivate();
        plugin.dispose();
    }

    // ── Sealed-interface discovery ─────────────────────────────────────

    @Test
    void shouldBeDiscoverableViaBuiltInDawPlugin() {
        var plugins = BuiltInDawPlugin.discoverAll();
        assertThat(plugins)
                .anyMatch(p -> p instanceof SoundWaveTelemetryPlugin);
    }

    @Test
    void shouldAppearInMenuEntries() {
        var entries = BuiltInDawPlugin.menuEntries();
        assertThat(entries)
                .extracting(BuiltInDawPlugin.MenuEntry::label)
                .contains("Sound Wave Telemetry");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    // ── ArmedTrackSourceProvider wiring ─────────────────────────────────

    @Test
    void activateShouldSubscribeToArmedTrackSourceProvider() {
        plugin.initialize(stubContext());
        ArmedTrackSourceProvider provider = new ArmedTrackSourceProvider(
                newProjectWithArmedTrack("Guitar"));
        plugin.setArmedTrackSourceProvider(provider);

        assertThat(plugin.isSubscribedToArmedTrackSourceProvider()).isFalse();

        plugin.activate();

        assertThat(plugin.isSubscribedToArmedTrackSourceProvider()).isTrue();
        // Activation should trigger an initial reconciliation.
        assertThat(provider.getManagedSources())
                .extracting(s -> s.name())
                .containsExactly("Guitar");
    }

    @Test
    void deactivateShouldUnsubscribeFromArmedTrackSourceProvider() {
        plugin.initialize(stubContext());
        ArmedTrackSourceProvider provider = new ArmedTrackSourceProvider(
                newProjectWithArmedTrack("Vocals"));
        plugin.setArmedTrackSourceProvider(provider);

        plugin.activate();
        assertThat(plugin.isSubscribedToArmedTrackSourceProvider()).isTrue();

        plugin.deactivate();
        assertThat(plugin.isSubscribedToArmedTrackSourceProvider()).isFalse();
    }

    @Test
    void disposeShouldUnsubscribeFromArmedTrackSourceProvider() {
        plugin.initialize(stubContext());
        ArmedTrackSourceProvider provider = new ArmedTrackSourceProvider(
                newProjectWithArmedTrack("Drums"));
        plugin.setArmedTrackSourceProvider(provider);

        plugin.activate();
        plugin.dispose();

        assertThat(plugin.isSubscribedToArmedTrackSourceProvider()).isFalse();
        assertThat(plugin.getArmedTrackSourceProvider()).isNull();
    }

    @Test
    void settingProviderWhileActiveReSubscribes() {
        plugin.initialize(stubContext());
        plugin.activate();

        ArmedTrackSourceProvider provider = new ArmedTrackSourceProvider(
                newProjectWithArmedTrack("Bass"));
        plugin.setArmedTrackSourceProvider(provider);

        assertThat(plugin.isSubscribedToArmedTrackSourceProvider()).isTrue();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static DawProject newProjectWithArmedTrack(String name) {
        DawProject project = new DawProject("Test", AudioFormat.CD_QUALITY);
        project.setRoomConfiguration(new RoomConfiguration(
                new RoomDimensions(10, 8, 3), WallMaterial.DRYWALL));
        Track track = project.createAudioTrack(name);
        track.setArmed(true);
        return project;
    }

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
