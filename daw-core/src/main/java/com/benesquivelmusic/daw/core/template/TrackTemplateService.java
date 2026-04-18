package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.Mixer;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Applies {@link TrackTemplate}s and {@link ChannelStripPreset}s to a project.
 *
 * <p>This service bridges the portable template/preset records (which only
 * reference built-in effect types by enum and return buses by name) and the
 * live {@link MixerChannel} / {@link Track} objects in a project.</p>
 *
 * <p>All methods on this class are pure helpers — they do not modify the
 * undo history directly. For undoable integration use
 * {@link com.benesquivelmusic.daw.core.template.AddTrackFromTemplateAction}
 * and {@link com.benesquivelmusic.daw.core.template.ApplyChannelStripPresetAction}.</p>
 */
public final class TrackTemplateService {

    private TrackTemplateService() {
        // utility class
    }

    /**
     * Creates a new {@link Track} populated from the given template and adds
     * it to the project. The track's associated {@link MixerChannel} receives
     * the template's insert chain, sends, volume, and pan.
     *
     * @param template the template to instantiate from
     * @param project  the project to add the new track to
     * @param name     the name for the new track (if {@code null} the template
     *                 {@link TrackTemplate#nameHint() nameHint} is used)
     * @return the newly added track
     */
    public static Track createTrackFromTemplate(TrackTemplate template,
                                                DawProject project,
                                                String name) {
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(project, "project must not be null");
        String trackName = (name == null || name.isBlank()) ? template.nameHint() : name;

        Track track = new Track(trackName, template.trackType());
        track.setVolume(template.volume());
        track.setPan(template.pan());
        track.setColor(template.color());
        track.setInputRouting(template.inputRouting());
        project.addTrack(track);

        MixerChannel channel = project.getMixerChannelForTrack(track);
        if (channel != null) {
            channel.setVolume(template.volume());
            channel.setPan(template.pan());
            channel.setColor(template.color());
            channel.setOutputRouting(template.outputRouting());
            applyInserts(template.inserts(), channel, project.getFormat());
            applySends(template.sends(), channel, project.getMixer());
        }
        return track;
    }

    /**
     * Applies a channel strip preset to the given mixer channel, replacing the
     * channel's current insert chain and sends and setting the channel's
     * volume and pan to the preset values.
     *
     * @param preset  the preset to apply
     * @param channel the target channel
     * @param mixer   the mixer (used to resolve send target names)
     * @param format  the audio format (used to instantiate effect processors)
     */
    public static void applyPreset(ChannelStripPreset preset,
                                   MixerChannel channel,
                                   Mixer mixer,
                                   AudioFormat format) {
        Objects.requireNonNull(preset, "preset must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(mixer, "mixer must not be null");
        Objects.requireNonNull(format, "format must not be null");

        // Clear existing inserts and sends.
        while (channel.getInsertCount() > 0) {
            channel.removeInsert(channel.getInsertCount() - 1);
        }
        for (Send existing : new ArrayList<>(channel.getSends())) {
            channel.removeSend(existing);
        }

        channel.setVolume(preset.volume());
        channel.setPan(preset.pan());
        applyInserts(preset.inserts(), channel, format);
        applySends(preset.sends(), channel, mixer);
    }

    /**
     * Captures the current state of a mixer channel as a {@link ChannelStripPreset}.
     *
     * <p>Insert slots of unknown (CLAP / external) type are skipped.</p>
     *
     * @param presetName the name for the captured preset
     * @param channel    the channel to capture
     * @return the captured preset
     */
    public static ChannelStripPreset captureChannelStrip(String presetName, MixerChannel channel) {
        Objects.requireNonNull(presetName, "presetName must not be null");
        Objects.requireNonNull(channel, "channel must not be null");

        List<InsertEffectSpec> insertSpecs = new ArrayList<>();
        for (InsertSlot slot : channel.getInsertSlots()) {
            InsertEffectType type = slot.getEffectType();
            if (type == null || type == InsertEffectType.CLAP_PLUGIN) {
                continue; // unsupported for templates/presets
            }
            Map<Integer, Double> params = InsertEffectFactory.getParameterValues(type, slot.getProcessor());
            insertSpecs.add(new InsertEffectSpec(type, params, slot.isBypassed()));
        }

        List<SendSpec> sendSpecs = new ArrayList<>();
        for (Send send : channel.getSends()) {
            sendSpecs.add(new SendSpec(send.getTarget().getName(), send.getLevel(), send.getMode()));
        }

        return new ChannelStripPreset(
                presetName,
                insertSpecs,
                sendSpecs,
                channel.getVolume(),
                channel.getPan());
    }

    /**
     * Captures the current state of a track and its associated mixer channel
     * as a {@link TrackTemplate}.
     *
     * @param templateName the name for the captured template
     * @param track        the track to capture
     * @param project      the project the track belongs to
     * @return the captured template
     * @throws IllegalStateException if the track is not in the project
     */
    public static TrackTemplate captureTrack(String templateName, Track track, DawProject project) {
        Objects.requireNonNull(templateName, "templateName must not be null");
        Objects.requireNonNull(track, "track must not be null");
        Objects.requireNonNull(project, "project must not be null");

        MixerChannel channel = project.getMixerChannelForTrack(track);
        if (channel == null) {
            throw new IllegalStateException("track has no mixer channel: " + track.getName());
        }
        ChannelStripPreset strip = captureChannelStrip(templateName, channel);

        return new TrackTemplate(
                templateName,
                track.getType(),
                track.getName(),
                strip.inserts(),
                strip.sends(),
                track.getVolume(),
                track.getPan(),
                track.getColor(),
                track.getInputRouting(),
                channel.getOutputRouting());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void applyInserts(List<InsertEffectSpec> specs,
                                     MixerChannel channel,
                                     AudioFormat format) {
        for (InsertEffectSpec spec : specs) {
            if (channel.getInsertCount() >= MixerChannel.MAX_INSERT_SLOTS) {
                break;
            }
            InsertEffectType type = spec.type();
            InsertSlot slot = InsertEffectFactory.createSlot(
                    type, format.channels(), format.sampleRate());
            AudioProcessor processor = slot.getProcessor();
            BiConsumer<Integer, Double> handler =
                    InsertEffectFactory.createParameterHandler(type, processor);
            for (Map.Entry<Integer, Double> entry : spec.parameters().entrySet()) {
                handler.accept(entry.getKey(), entry.getValue());
            }
            slot.setBypassed(spec.bypassed());
            channel.addInsert(slot);
        }
    }

    private static void applySends(List<SendSpec> specs, MixerChannel channel, Mixer mixer) {
        for (SendSpec spec : specs) {
            MixerChannel target = findReturnBusByName(mixer, spec.targetName());
            if (target == null) {
                continue; // silently skip missing buses
            }
            channel.addSend(new Send(target, spec.level(), spec.mode()));
        }
    }

    private static MixerChannel findReturnBusByName(Mixer mixer, String name) {
        for (MixerChannel bus : mixer.getReturnBuses()) {
            if (bus.getName().equals(name)) {
                return bus;
            }
        }
        return null;
    }
}
