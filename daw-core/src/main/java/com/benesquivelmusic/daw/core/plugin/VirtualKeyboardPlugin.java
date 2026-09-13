package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.midi.KeyboardPreset;
import com.benesquivelmusic.daw.core.midi.KeyboardProcessor;
import com.benesquivelmusic.daw.core.midi.GraphKeyboardRenderer;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe;
import java.util.Optional;
import com.benesquivelmusic.daw.core.plugin.editor.VirtualKeyboardEditor;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;
import com.benesquivelmusic.daw.sdk.midi.SoundFontRenderer;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.Objects;

/**
 * Built-in virtual keyboard instrument plugin.
 *
 * <p>Wraps the DAW's {@link KeyboardProcessor} as a first-class plugin
 * so it appears in the Plugins menu alongside external plugins.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #initialize(PluginContext)} — creates a {@link SoundFontRenderer}
 *       and {@link KeyboardProcessor} configured with the context's sample rate
 *       and buffer size.</li>
 *   <li>{@link #activate()} — marks the plugin as active.</li>
 *   <li>{@link #deactivate()} — sends all-notes-off to stop any sustained notes.</li>
 *   <li>{@link #dispose()} — releases the processor and renderer resources.</li>
 * </ol>
 */
@BuiltInPlugin(label = "Virtual Keyboard", icon = "keyboard", category = BuiltInPluginCategory.INSTRUMENT)
public final class VirtualKeyboardPlugin implements BuiltInDawPlugin, AudioProcessor {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.keyboard";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Virtual Keyboard",
            "1.0.0",
            "DAW Built-in",
            PluginType.INSTRUMENT,
            PluginCategory.INSTRUMENT,
            "keyboard"
    );

    private SoundFontRenderer renderer;
    private KeyboardProcessor processor;
    private boolean active;
    private int audioChannels = 2;

    public VirtualKeyboardPlugin() {
    }

    /**
     * Package-private — allows tests to inject a stub renderer so that
     * {@link #initialize(PluginContext)} skips real audio hardware.
     */
    void setRenderer(SoundFontRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (renderer == null) {
            renderer = new GraphKeyboardRenderer();
        }
        renderer.initialize(context.getSampleRate(), context.getBufferSize());
        processor = new KeyboardProcessor(renderer, KeyboardPreset.grandPiano());
        audioChannels = context.getAudioChannels();
    }

    @Override public Optional<AudioProcessor> asAudioProcessor() {
        return processor == null ? Optional.empty() : Optional.of(this);
    }

    @Override @RealTimeSafe
    public void process(float[][] input, float[][] output, int frames) {
        for (int ch = 0; ch < output.length; ch++) {
            if (ch < input.length) {
                System.arraycopy(input[ch], 0, output[ch], 0, frames);
            } else {
                java.util.Arrays.fill(output[ch], 0, frames, 0f);
            }
        }
        if (active) {
            renderer.render(output, frames);
        }
    }
    @Override public void reset() { if (renderer != null) { renderer.allNotesOff(); } }
    @Override public int getInputChannelCount() { return audioChannels; }
    @Override public int getOutputChannelCount() { return audioChannels; }

    @Override
    public void activate() {
        active = true;
    }

    @Override
    public void deactivate() {
        active = false;
        if (processor != null) {
            processor.allNotesOff();
        }
    }

    @Override
    public void dispose() {
        active = false;
        if (processor != null) {
            processor.allNotesOff();
            processor = null;
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }

    /**
     * Returns the {@link KeyboardProcessor} created during
     * {@link #initialize(PluginContext)}, or {@code null} if the plugin
     * has not been initialized or has been disposed.
     *
     * @return the keyboard processor, or {@code null}
     */
    public KeyboardProcessor getProcessor() {
        return processor;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the {@link VirtualKeyboardEditor} panel — the story 302
     * (§8.3) replacement for the retired {@code daw-app}
     * {@code KeyboardProcessorView}: interactive piano canvas, preset /
     * velocity / curve / transpose controls, and MIDI record-play transport,
     * framed with minimal chrome.</p>
     */
    @Override
    public PluginEditorFactory editorFactory() {
        return new VirtualKeyboardEditor(this);
    }
}
