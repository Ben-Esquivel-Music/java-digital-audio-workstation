package com.benesquivelmusic.daw.core.plugin;

import com.benesquivelmusic.daw.core.midi.KeyboardPreset;
import com.benesquivelmusic.daw.core.midi.KeyboardProcessor;
import com.benesquivelmusic.daw.core.midi.javasound.JavaSoundRenderer;
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
public final class VirtualKeyboardPlugin implements BuiltInDawPlugin {

    /** Stable plugin identifier — used by the host to map plugins to views. */
    public static final String PLUGIN_ID = "com.benesquivelmusic.daw.keyboard";

    private static final PluginDescriptor DESCRIPTOR = new PluginDescriptor(
            PLUGIN_ID,
            "Virtual Keyboard",
            "1.0.0",
            "DAW Built-in",
            PluginType.INSTRUMENT
    );

    private SoundFontRenderer renderer;
    private KeyboardProcessor processor;
    private boolean active;

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
    public String getMenuLabel() {
        return "Virtual Keyboard";
    }

    @Override
    public String getMenuIcon() {
        return "keyboard";
    }

    @Override
    public BuiltInPluginCategory getCategory() {
        return BuiltInPluginCategory.INSTRUMENT;
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void initialize(PluginContext context) {
        Objects.requireNonNull(context, "context must not be null");
        if (renderer == null) {
            renderer = new JavaSoundRenderer();
        }
        renderer.initialize(context.getSampleRate(), context.getBufferSize());
        processor = new KeyboardProcessor(renderer, KeyboardPreset.grandPiano());
    }

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
}
