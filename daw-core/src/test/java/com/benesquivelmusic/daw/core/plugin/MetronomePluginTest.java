package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.recording.ClickSound;
import com.benesquivelmusic.daw.core.recording.CountInMode;
import com.benesquivelmusic.daw.core.recording.Subdivision;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetronomePluginTest {

    private MetronomePlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new MetronomePlugin();
    }

    // ── Construction ───────────────────────────────────────────────────

    @Test
    void shouldHavePublicNoArgConstructor() {
        MetronomePlugin fresh = new MetronomePlugin();
        assertThat(fresh).isNotNull();
    }

    // ── Descriptor Metadata ────────────────────────────────────────────

    @Test
    void shouldReturnMenuLabel() {
        assertThat(plugin.getMenuLabel()).isEqualTo("Metronome");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(plugin.getMenuIcon()).isEqualTo("metronome");
    }

    @Test
    void shouldReturnUtilityCategory() {
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.UTILITY);
    }

    @Test
    void shouldReturnDescriptorWithMidiEffectType() {
        var descriptor = plugin.getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.MIDI_EFFECT);
        assertThat(descriptor.name()).isEqualTo("Metronome");
        assertThat(descriptor.id()).isEqualTo("com.benesquivelmusic.daw.metronome");
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void pluginIdConstantShouldMatchDescriptorId() {
        assertThat(MetronomePlugin.PLUGIN_ID).isEqualTo(plugin.getDescriptor().id());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    void initializeShouldRejectNullContext() {
        assertThatThrownBy(() -> plugin.initialize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void initializeShouldCreateMetronomeEngine() {
        plugin.initialize(stubContext());
        assertThat(plugin.getMetronome()).isNotNull();
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
    void disposeShouldReleaseMetronomeEngine() {
        plugin.initialize(stubContext());
        assertThat(plugin.getMetronome()).isNotNull();

        plugin.dispose();
        assertThat(plugin.getMetronome()).isNull();
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

    // ── Click Sound ────────────────────────────────────────────────────

    @Test
    void defaultClickSoundShouldBeWoodblock() {
        plugin.initialize(stubContext());
        assertThat(plugin.getClickSound()).isEqualTo(ClickSound.WOODBLOCK);
    }

    @Test
    void shouldAllowSettingClickSound() {
        plugin.initialize(stubContext());
        plugin.setClickSound(ClickSound.COWBELL);
        assertThat(plugin.getClickSound()).isEqualTo(ClickSound.COWBELL);
    }

    @Test
    void setClickSoundShouldRejectNull() {
        plugin.initialize(stubContext());
        assertThatThrownBy(() -> plugin.setClickSound(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getClickSoundShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.getClickSound())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setClickSoundShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.setClickSound(ClickSound.COWBELL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldSupportAllClickSounds() {
        plugin.initialize(stubContext());
        for (ClickSound sound : ClickSound.values()) {
            plugin.setClickSound(sound);
            assertThat(plugin.getClickSound()).isEqualTo(sound);
        }
    }

    // ── Volume ─────────────────────────────────────────────────────────

    @Test
    void defaultVolumeShouldBeOne() {
        plugin.initialize(stubContext());
        assertThat(plugin.getVolume()).isEqualTo(1.0f);
    }

    @Test
    void shouldAllowSettingVolume() {
        plugin.initialize(stubContext());
        plugin.setVolume(0.5f);
        assertThat(plugin.getVolume()).isEqualTo(0.5f);
    }

    @Test
    void shouldAllowZeroVolume() {
        plugin.initialize(stubContext());
        plugin.setVolume(0.0f);
        assertThat(plugin.getVolume()).isEqualTo(0.0f);
    }

    @Test
    void shouldAllowFullVolume() {
        plugin.initialize(stubContext());
        plugin.setVolume(1.0f);
        assertThat(plugin.getVolume()).isEqualTo(1.0f);
    }

    @Test
    void shouldRejectVolumeBelowZero() {
        plugin.initialize(stubContext());
        assertThatThrownBy(() -> plugin.setVolume(-0.1f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectVolumeAboveOne() {
        plugin.initialize(stubContext());
        assertThatThrownBy(() -> plugin.setVolume(1.1f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getVolumeShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.getVolume())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setVolumeShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.setVolume(0.5f))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Count-In Mode ──────────────────────────────────────────────────

    @Test
    void defaultCountInModeShouldBeOneBar() {
        assertThat(plugin.getCountInMode()).isEqualTo(CountInMode.ONE_BAR);
    }

    @Test
    void shouldAllowSettingCountInMode() {
        plugin.setCountInMode(CountInMode.TWO_BARS);
        assertThat(plugin.getCountInMode()).isEqualTo(CountInMode.TWO_BARS);
    }

    @Test
    void setCountInModeShouldRejectNull() {
        assertThatThrownBy(() -> plugin.setCountInMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportAllCountInModes() {
        for (CountInMode mode : CountInMode.values()) {
            plugin.setCountInMode(mode);
            assertThat(plugin.getCountInMode()).isEqualTo(mode);
        }
    }

    // ── Subdivision ────────────────────────────────────────────────────

    @Test
    void defaultSubdivisionShouldBeQuarter() {
        plugin.initialize(stubContext());
        assertThat(plugin.getSubdivision()).isEqualTo(Subdivision.QUARTER);
    }

    @Test
    void shouldAllowSettingSubdivision() {
        plugin.initialize(stubContext());
        plugin.setSubdivision(Subdivision.EIGHTH);
        assertThat(plugin.getSubdivision()).isEqualTo(Subdivision.EIGHTH);
    }

    @Test
    void setSubdivisionShouldRejectNull() {
        plugin.initialize(stubContext());
        assertThatThrownBy(() -> plugin.setSubdivision(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getSubdivisionShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.getSubdivision())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setSubdivisionShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.setSubdivision(Subdivision.EIGHTH))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldSupportAllSubdivisions() {
        plugin.initialize(stubContext());
        for (Subdivision sub : Subdivision.values()) {
            plugin.setSubdivision(sub);
            assertThat(plugin.getSubdivision()).isEqualTo(sub);
        }
    }

    // ── Enabled State ──────────────────────────────────────────────────

    @Test
    void defaultEnabledShouldBeTrue() {
        plugin.initialize(stubContext());
        assertThat(plugin.isEnabled()).isTrue();
    }

    @Test
    void shouldAllowDisablingMetronome() {
        plugin.initialize(stubContext());
        plugin.setEnabled(false);
        assertThat(plugin.isEnabled()).isFalse();
    }

    @Test
    void shouldAllowReEnablingMetronome() {
        plugin.initialize(stubContext());
        plugin.setEnabled(false);
        plugin.setEnabled(true);
        assertThat(plugin.isEnabled()).isTrue();
    }

    @Test
    void isEnabledShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.isEnabled())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setEnabledShouldThrowBeforeInitialize() {
        assertThatThrownBy(() -> plugin.setEnabled(false))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Sealed-interface discovery ─────────────────────────────────────

    @Test
    void shouldBeDiscoverableViaBuiltInDawPlugin() {
        var plugins = BuiltInDawPlugin.discoverAll();
        assertThat(plugins)
                .anyMatch(p -> p instanceof MetronomePlugin);
    }

    @Test
    void shouldAppearInMenuEntries() {
        var entries = BuiltInDawPlugin.menuEntries();
        assertThat(entries)
                .extracting(BuiltInDawPlugin.MenuEntry::label)
                .contains("Metronome");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }
}
