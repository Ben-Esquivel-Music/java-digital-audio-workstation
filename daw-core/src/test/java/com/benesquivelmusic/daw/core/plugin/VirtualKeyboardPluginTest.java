package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.midi.KeyboardPreset;
import com.benesquivelmusic.daw.sdk.midi.MidiEvent;
import com.benesquivelmusic.daw.sdk.midi.SoundFontInfo;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualKeyboardPluginTest {

    private StubRenderer renderer;
    private VirtualKeyboardPlugin plugin;

    @BeforeEach
    void setUp() {
        renderer = new StubRenderer();
        plugin = new VirtualKeyboardPlugin();
        plugin.setRenderer(renderer);
    }

    // ── Construction ───────────────────────────────────────────────────

    @Test
    void shouldHavePublicNoArgConstructor() {
        VirtualKeyboardPlugin fresh = new VirtualKeyboardPlugin();
        assertThat(fresh).isNotNull();
    }

    // ── Descriptor Metadata ────────────────────────────────────────────

    @Test
    void shouldReturnMenuLabel() {
        assertThat(plugin.getMenuLabel()).isEqualTo("Virtual Keyboard");
    }

    @Test
    void shouldReturnMenuIcon() {
        assertThat(plugin.getMenuIcon()).isEqualTo("keyboard");
    }

    @Test
    void shouldReturnInstrumentCategory() {
        assertThat(plugin.getCategory()).isEqualTo(BuiltInPluginCategory.INSTRUMENT);
    }

    @Test
    void shouldReturnDescriptorWithInstrumentType() {
        var descriptor = plugin.getDescriptor();
        assertThat(descriptor.type()).isEqualTo(PluginType.INSTRUMENT);
        assertThat(descriptor.name()).isEqualTo("Virtual Keyboard");
        assertThat(descriptor.id()).isEqualTo("com.benesquivelmusic.daw.keyboard");
        assertThat(descriptor.vendor()).isEqualTo("DAW Built-in");
    }

    @Test
    void pluginIdConstantShouldMatchDescriptorId() {
        assertThat(VirtualKeyboardPlugin.PLUGIN_ID).isEqualTo(plugin.getDescriptor().id());
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    void initializeShouldRejectNullContext() {
        assertThatThrownBy(() -> plugin.initialize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void initializeShouldCreateKeyboardProcessor() {
        assertThat(plugin.getProcessor()).isNull();
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull();
    }

    @Test
    void initializeShouldConfigureProcessorWithDefaultPreset() {
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor().getPreset()).isEqualTo(KeyboardPreset.grandPiano());
    }

    @Test
    void deactivateShouldSendAllNotesOff() {
        plugin.initialize(stubContext());
        plugin.activate();

        plugin.getProcessor().noteOn(60, 100);
        assertThat(plugin.getProcessor().isNoteActive(60)).isTrue();

        plugin.deactivate();
        assertThat(plugin.getProcessor().isNoteActive(60)).isFalse();
    }

    @Test
    void deactivateBeforeInitializeShouldNotThrow() {
        plugin.deactivate();
    }

    @Test
    void disposeShouldReleaseProcessor() {
        plugin.initialize(stubContext());
        assertThat(plugin.getProcessor()).isNotNull();

        plugin.dispose();
        assertThat(plugin.getProcessor()).isNull();
    }

    @Test
    void disposeShouldCloseRenderer() {
        plugin.initialize(stubContext());
        assertThat(renderer.closed).isFalse();

        plugin.dispose();
        assertThat(renderer.closed).isTrue();
    }

    @Test
    void disposeShouldSendAllNotesOffBeforeRelease() {
        plugin.initialize(stubContext());
        plugin.activate();
        plugin.getProcessor().noteOn(60, 100);

        plugin.dispose();
        assertThat(renderer.receivedEvents).anyMatch(
                e -> e.type() == MidiEvent.Type.NOTE_OFF && e.data1() == 60);
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

    // ── Helpers ─────────────────────────────────────────────────────────

    private static PluginContext stubContext() {
        return new PluginContext() {
            @Override public double getSampleRate() { return 44100; }
            @Override public int getBufferSize() { return 512; }
            @Override public void log(String message) {}
        };
    }

    private static final class StubRenderer implements SoundFontRenderer {
        final List<MidiEvent> receivedEvents = new CopyOnWriteArrayList<>();
        volatile boolean allNotesOffCalled;
        volatile boolean closed;

        @Override public void initialize(double sampleRate, int bufferSize) {}
        @Override public SoundFontInfo loadSoundFont(Path path) { return new SoundFontInfo(0, path, List.of()); }
        @Override public void unloadSoundFont(int soundFontId) {}
        @Override public List<SoundFontInfo> getLoadedSoundFonts() { return Collections.emptyList(); }
        @Override public void selectPreset(int channel, int bank, int program) {}
        @Override public void sendEvent(MidiEvent event) { receivedEvents.add(event); }
        @Override public void render(float[][] outputBuffer, int numFrames) {}
        @Override public float[][] bounce(List<MidiEvent> events, int totalFrames) { return new float[2][totalFrames]; }
        @Override public void setReverbEnabled(boolean enabled) {}
        @Override public void setChorusEnabled(boolean enabled) {}
        @Override public void setGain(float gain) {}
        @Override public boolean isAvailable() { return true; }
        @Override public String getRendererName() { return "Stub"; }
        @Override public void allNotesOff() { allNotesOffCalled = true; }
        @Override public void close() { closed = true; }
    }
}
