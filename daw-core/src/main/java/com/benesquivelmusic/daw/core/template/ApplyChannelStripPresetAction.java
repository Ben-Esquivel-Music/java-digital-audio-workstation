package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import java.util.Objects;

/**
 * An undoable action that applies a {@link ChannelStripPreset} to an existing
 * {@link MixerChannel}, replacing its current insert chain, sends, volume,
 * and pan.
 *
 * <p>Before execution the channel's current state is captured as a
 * {@link ChannelStripPreset} snapshot. Undoing restores that snapshot.</p>
 *
 * <p>This means any insert slots whose {@link com.benesquivelmusic.daw.core.mixer.InsertEffectType}
 * is not a built-in type (for example, CLAP plugins) are lost across an
 * apply/undo round-trip — the snapshot cannot represent them. Callers that
 * care about preserving such slots should save the channel state separately
 * before applying a preset.</p>
 */
public final class ApplyChannelStripPresetAction implements UndoableAction {

    private final MixerChannel channel;
    private final ChannelStripPreset preset;
    private final Mixer mixer;
    private final AudioFormat format;
    private ChannelStripPreset previousState;

    /**
     * Creates an apply-preset action.
     *
     * @param channel the target mixer channel
     * @param preset  the preset to apply
     * @param mixer   the mixer (used to resolve send target names)
     * @param format  the audio format for instantiating effect processors
     */
    public ApplyChannelStripPresetAction(MixerChannel channel,
                                         ChannelStripPreset preset,
                                         Mixer mixer,
                                         AudioFormat format) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.preset = Objects.requireNonNull(preset, "preset must not be null");
        this.mixer = Objects.requireNonNull(mixer, "mixer must not be null");
        this.format = Objects.requireNonNull(format, "format must not be null");
    }

    @Override
    public String description() {
        return "Apply Channel Strip Preset";
    }

    @Override
    public void execute() {
        if (previousState == null) {
            previousState = TrackTemplateService.captureChannelStrip(
                    "__previous__" + channel.getName(), channel);
        }
        TrackTemplateService.applyPreset(preset, channel, mixer, format);
    }

    @Override
    public void undo() {
        if (previousState != null) {
            TrackTemplateService.applyPreset(previousState, channel, mixer, format);
        }
    }
}
