package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioChannelInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reconciles persisted channel-name snapshots on a freshly loaded
 * {@link DawProject} against the live driver-reported names — story 199.
 *
 * <p>When the driver now reports a different name at the same channel
 * index than the project last saved, the live name wins (it is the
 * source of truth: the user just plugged in or reconfigured the
 * device). The detector returns a single aggregated warning summarising
 * <em>every</em> mismatch detected, e.g.
 * {@code "Channel names changed since last save: 'Mic 3' → 'Hi-Z Inst 3'"} —
 * one notification per project, never one per track or per channel.</p>
 */
public final class ChannelNameSnapshotReconciler {

    private ChannelNameSnapshotReconciler() {
    }

    /**
     * Result of {@link #reconcile reconciliation}. {@link #warning()}
     * is empty when no mismatches were found; otherwise it carries a
     * single human-readable summary of every rename detected (deduped
     * by {@code "old → new"} pair so projects with N tracks pinned to
     * "Mic 3" still produce just one warning entry).
     *
     * <p>The reconciler also rewrites the snapshot on each affected
     * track / mixer channel to the live name, so the next save will
     * carry the up-to-date names and a subsequent load will not warn
     * again.</p>
     *
     * @param warning aggregated user-facing warning summary, or empty
     *                when no rename was detected
     * @param renames the (oldName, newName) pairs detected, in
     *                first-seen order
     */
    public record ReconciliationResult(Optional<String> warning,
                                       List<NameChange> renames) {
        public ReconciliationResult {
            Objects.requireNonNull(warning, "warning must not be null");
            renames = List.copyOf(Objects.requireNonNull(renames, "renames must not be null"));
        }
    }

    /**
     * A single rename detected during reconciliation.
     *
     * @param channelIndex the (zero-based) channel index where the
     *                     rename was detected
     * @param oldName      the snapshot the project was saved with
     * @param newName      the live driver-reported name
     */
    public record NameChange(int channelIndex, String oldName, String newName) {
        public NameChange {
            Objects.requireNonNull(oldName, "oldName must not be null");
            Objects.requireNonNull(newName, "newName must not be null");
        }
    }

    /**
     * Walks every track's input routing and every mixer channel's
     * output routing, compares the persisted snapshot against the live
     * driver-reported name at the same first-channel index, and
     * collects exactly one warning entry per distinct rename.
     *
     * <p>The snapshot on each affected track / mixer channel is
     * rewritten to the live name as a side-effect, so the rename is
     * persisted on the next save and not warned about again on
     * subsequent loads.</p>
     *
     * @param project       the freshly loaded project; must not be null
     * @param liveInputs    live driver-reported input channels; must not be null
     * @param liveOutputs   live driver-reported output channels; must not be null
     * @return the reconciliation result; never null
     */
    public static ReconciliationResult reconcile(DawProject project,
                                                 List<AudioChannelInfo> liveInputs,
                                                 List<AudioChannelInfo> liveOutputs) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(liveInputs, "liveInputs must not be null");
        Objects.requireNonNull(liveOutputs, "liveOutputs must not be null");

        // Dedupe by (oldName -> newName) so 80 tracks routed to "Mic 3"
        // surface as a single warning, not 80.
        Map<String, NameChange> renamesByOld = new LinkedHashMap<>();

        for (Track track : project.getTracks()) {
            String snapshot = track.getInputRoutingDisplayName();
            if (snapshot.isEmpty()) {
                continue;
            }
            InputRouting routing = track.getInputRouting();
            if (routing.isNone()) {
                continue;
            }
            String live = liveNameAt(liveInputs, routing.firstChannel());
            if (live != null && !live.equals(snapshot)) {
                renamesByOld.computeIfAbsent(snapshot,
                        old -> new NameChange(routing.firstChannel(), old, live));
                track.setInputRoutingDisplayName(live);
            }
        }

        for (MixerChannel channel : project.getMixer().getChannels()) {
            String snapshot = channel.getOutputRoutingDisplayName();
            if (snapshot.isEmpty()) {
                continue;
            }
            OutputRouting routing = channel.getOutputRouting();
            if (routing.isMaster()) {
                continue;
            }
            String live = liveNameAt(liveOutputs, routing.firstChannel());
            if (live != null && !live.equals(snapshot)) {
                renamesByOld.computeIfAbsent(snapshot,
                        old -> new NameChange(routing.firstChannel(), old, live));
                channel.setOutputRoutingDisplayName(live);
            }
        }

        List<NameChange> renames = new ArrayList<>(renamesByOld.values());
        if (renames.isEmpty()) {
            return new ReconciliationResult(Optional.empty(), List.of());
        }

        StringBuilder msg = new StringBuilder("Channel names changed since last save: ");
        for (int i = 0; i < renames.size(); i++) {
            if (i > 0) {
                msg.append(", ");
            }
            NameChange c = renames.get(i);
            msg.append('\'').append(c.oldName()).append("' → '").append(c.newName()).append('\'');
        }
        return new ReconciliationResult(Optional.of(msg.toString()), renames);
    }

    private static String liveNameAt(List<AudioChannelInfo> live, int index) {
        for (AudioChannelInfo info : live) {
            if (info.index() == index) {
                return info.displayName();
            }
        }
        return null;
    }
}
