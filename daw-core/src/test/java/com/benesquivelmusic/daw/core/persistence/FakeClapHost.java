package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginFormat;
import com.benesquivelmusic.daw.sdk.plugin.ExternalPluginHost;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginStateRejectedException;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;

import java.util.List;
import java.util.Objects;

/**
 * A CLAP plugin, as a test stands one in for (story 334: no CLAP binary is
 * reachable from a test): an {@link ExternalPluginHost} of format
 * {@link ExternalPluginFormat#CLAP} whose state is an opaque byte blob, and
 * whose output is its input times {@code state[0] / 255} (unity while it
 * holds no state), so a render tells whether the state arrived. Its state
 * load can be made to throw or to report failure, and its state save to
 * fail.
 */
public final class FakeClapHost implements ExternalPluginHost {

    /** The plugin's stable id. */
    public static final String ID = "com.example.glue";
    /** The plugin's name, which its insert shows. */
    public static final String NAME = "Glue";

    /** What {@link #loadState(byte[])} does with a non-empty state. */
    public enum Load {
        /** Takes it. */
        ACCEPT,
        /** Throws an {@link IllegalArgumentException}: the call failed. */
        THROW,
        /** Throws a {@link PluginStateRejectedException}: the plugin reported that it did not take it. */
        REPORT_FAILURE
    }

    private final String version;
    private final Load load;
    private volatile byte[] state = new byte[0];
    private volatile boolean saveFails;
    private int channels = 2;
    private boolean disposed;

    /** A host at version 1.4.4 that takes its state. */
    public FakeClapHost() {
        this("1.4.4", Load.ACCEPT);
    }

    /** A host at {@code version} that treats a loaded state as {@code load} says. */
    public FakeClapHost(String version, Load load) {
        this.version = Objects.requireNonNull(version, "version must not be null");
        this.load = Objects.requireNonNull(load, "load must not be null");
    }

    /** Sets the state the plugin holds, as a user's edit would. */
    public void setState(byte[] state) {
        this.state = state.clone();
    }

    /** The state the plugin holds. */
    public byte[] state() {
        return state.clone();
    }

    /** Makes every later {@link #saveState()} fail as a CLAP save that failed does. */
    public void failSaves() {
        saveFails = true;
    }

    /** Whether {@link #dispose()} ran. */
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public ExternalPluginFormat getFormat() {
        return ExternalPluginFormat.CLAP;
    }

    @Override
    public List<PluginParameter> getParameters() {
        return List.of();
    }

    @Override
    public double getParameterValue(int parameterId) {
        return 0.0;
    }

    @Override
    public void setParameterValue(int parameterId, double value) {
    }

    @Override
    public int getLatencySamples() {
        return 0;
    }

    @Override
    public byte[] saveState() {
        if (saveFails) {
            throw new IllegalStateException("The CLAP plugin did not save its state");
        }
        return state.clone();
    }

    @Override
    public void loadState(byte[] state) {
        if (state.length == 0) {
            return;
        }
        switch (load) {
            case ACCEPT -> this.state = state.clone();
            case THROW -> throw new IllegalArgumentException("simulated state load failure");
            case REPORT_FAILURE -> throw new PluginStateRejectedException(
                    "the plugin reported that it did not load its state");
        }
    }

    @Override
    public PluginDescriptor getDescriptor() {
        return new PluginDescriptor(ID, NAME, version, "Example Audio", PluginType.EFFECT);
    }

    @Override
    public void initialize(PluginContext context) {
        channels = context.getAudioChannels();
    }

    @Override
    public void activate() {
    }

    @Override
    public void deactivate() {
    }

    @Override
    public void dispose() {
        disposed = true;
    }

    @Override
    public void process(float[][] input, float[][] output, int frames) {
        byte[] held = state;
        double gain = held.length == 0 ? 1.0 : (held[0] & 0xFF) / 255.0;
        for (int channel = 0; channel < output.length; channel++) {
            for (int frame = 0; frame < frames; frame++) {
                output[channel][frame] = (float) (input[channel][frame] * gain);
            }
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public int getInputChannelCount() {
        return channels;
    }

    @Override
    public int getOutputChannelCount() {
        return channels;
    }
}
