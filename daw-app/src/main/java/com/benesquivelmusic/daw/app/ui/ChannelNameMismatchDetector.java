package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;

import java.util.List;
import java.util.Optional;

/**
 * Compares each track's saved {@code inputRoutingDisplayName} (and each
 * mixer channel's saved {@code outputRoutingDisplayName}) against the
 * live driver-reported channel name at the same index, and returns the
 * <em>first</em> mismatch as a single short {@code "'old' \u2192 'new'"}
 * label suitable for a one-shot {@code NotificationManager} warning.
 *
 * <p>Story 215 — "Driver-Reported Channel Names in Routing UI" — calls
 * for surfacing exactly one notification per project load when the
 * driver has renamed channels since the project was last saved (e.g.
 * the user renamed {@code "Mic 3"} to {@code "Hi-Z Inst 3"} in the
 * driver panel). Even when 50 tracks all reference renamed channels
 * the user must see a single message, not 50.</p>
 *
 * <p>Extracted as a pure function so it can be unit tested without a
 * JavaFX toolkit / {@code MainController} / {@code NotificationBar}.</p>
 */
final class ChannelNameMismatchDetector {

    private ChannelNameMismatchDetector() {
    }

    /**
     * Returns the first detected channel-name mismatch, or
     * {@link Optional#empty()} when every saved snapshot matches the
     * live driver-reported names (or there is nothing to compare).
     *
     * @param project     the project just loaded from disk; {@code null} is
     *                    tolerated (returns empty) for defensive call sites
     * @param liveInputs  driver-reported input-channel metadata; the
     *                    backend's empty list short-circuits to "no
     *                    mismatch" (we cannot tell)
     * @param liveOutputs driver-reported output-channel metadata; same
     *                    short-circuit semantics
     * @return the {@code "'saved' \u2192 'live'"} label of the first
     *         mismatch, or {@link Optional#empty()}
     */
    static Optional<String> detect(DawProject project,
                                   List<AudioChannelInfo> liveInputs,
                                   List<AudioChannelInfo> liveOutputs) {
        if (project == null
                || (liveInputs.isEmpty() && liveOutputs.isEmpty())) {
            return Optional.empty();
        }
        for (Track track : project.getTracks()) {
            String saved = track.getInputRoutingDisplayName();
            if (saved == null || saved.isBlank()) continue;
            InputRouting routing = track.getInputRouting();
            int idx = routing.firstChannel();
            if (idx < 0 || idx >= liveInputs.size()) continue;
            String live = liveInputs.get(idx).displayName();
            if (!saved.equals(live)) {
                return Optional.of(format(saved, live));
            }
        }
        for (MixerChannel channel : project.getMixer().getChannels()) {
            String saved = channel.getOutputRoutingDisplayName();
            if (saved == null || saved.isBlank()) continue;
            OutputRouting routing = channel.getOutputRouting();
            int idx = routing.firstChannel();
            if (idx < 0 || idx >= liveOutputs.size()) continue;
            String live = liveOutputs.get(idx).displayName();
            if (!saved.equals(live)) {
                return Optional.of(format(saved, live));
            }
        }
        return Optional.empty();
    }

    private static String format(String saved, String live) {
        return "'" + saved + "' \u2192 '" + live + "'";
    }
}
