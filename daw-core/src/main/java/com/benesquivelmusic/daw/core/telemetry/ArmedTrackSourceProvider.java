package com.benesquivelmusic.daw.core.telemetry;

import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Service that reconciles a {@link DawProject}'s armed tracks with the
 * {@link SoundSource}s declared on a {@link RoomConfiguration}.
 *
 * <p>Sound Wave Telemetry users should not have to redeclare the list of
 * instruments they are capturing: the Arrangement View's armed tracks
 * already carry that information. This provider observes the set of armed
 * tracks, computes a {@link SoundSource} for each, and reconciles the
 * project's {@code RoomConfiguration.soundSources} list so the telemetry
 * canvas stays in sync with the arm/record workflow.</p>
 *
 * <h2>Behavior</h2>
 * <ul>
 *     <li>Arming a track inserts a matching source with the track's name,
 *     a deterministic in-room layout position, and
 *     {@value #DEFAULT_POWER_DB} dB.</li>
 *     <li>Disarming (or removing) a previously-armed track removes its
 *     source. Sources added by the user that do <em>not</em> correspond to
 *     any tracked trackId are treated as <em>free</em> and preserved.</li>
 *     <li>Renaming a track updates the managed source's name without
 *     clobbering the user's dragged position or power — matching is keyed
 *     by the stable {@link Track#getId() track id}, not the track name.</li>
 *     <li>When the user drags an auto-added source, call
 *     {@link #updateSourcePosition(String, Position3D)} so the new position
 *     survives subsequent sync passes.</li>
 *     <li>{@link #setAutoSyncEnabled(boolean)} toggles the reconciliation.
 *     When disabled the list is frozen; re-enabling performs a fresh
 *     reconcile without duplicating entries.</li>
 * </ul>
 *
 * <p>This class is not thread-safe. All mutating calls must happen on the
 * thread that owns the {@link DawProject}.</p>
 */
public final class ArmedTrackSourceProvider {

    /** Default power level applied to auto-added sources, in dB SPL. */
    public static final double DEFAULT_POWER_DB = 85.0;

    /** Default Z-coordinate (height above floor) for auto-placed sources. */
    private static final double DEFAULT_SOURCE_HEIGHT_M = 1.2;

    /**
     * Fraction of the smaller floor dimension used as the source-placement
     * circle radius. Keeps sources comfortably off the walls regardless of
     * room size.
     */
    private static final double LAYOUT_RADIUS_FRACTION = 0.3;

    /** Listener invoked after every successful sync. */
    @FunctionalInterface
    public interface Listener {
        /** Invoked after the provider has reconciled the source list. */
        void onSourcesChanged(ArmedTrackSourceProvider provider);
    }

    private DawProject project;
    private boolean autoSyncEnabled = true;

    /**
     * Sources this provider is currently managing, keyed by stable track id.
     * When a track is disarmed, its entry is removed (and the corresponding
     * {@link SoundSource} dropped from the {@link RoomConfiguration}).
     */
    private final Map<String, SoundSource> managedByTrackId = new LinkedHashMap<>();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Creates an unbound provider. Call {@link #setProject(DawProject)} before syncing. */
    public ArmedTrackSourceProvider() {
    }

    /** Creates a provider bound to the given project. Does not trigger a sync. */
    public ArmedTrackSourceProvider(DawProject project) {
        this.project = project;
    }

    /**
     * Binds the provider to a project. Rebinding clears the managed-source
     * map (managed sources in the previous project's room configuration are
     * left intact — callers should explicitly clear them if desired).
     *
     * @param project the project to observe (may be {@code null})
     */
    public void setProject(DawProject project) {
        this.project = project;
        managedByTrackId.clear();
    }

    /** Returns the bound project, or {@code null} if none. */
    public DawProject getProject() {
        return project;
    }

    /** Returns whether auto-sync is currently enabled. */
    public boolean isAutoSyncEnabled() {
        return autoSyncEnabled;
    }

    /**
     * Enables or disables reconciliation with armed tracks. Transitioning
     * from disabled to enabled triggers an immediate {@link #sync()} so the
     * list re-reconciles without duplicating entries.
     *
     * @param enabled {@code true} to enable auto-sync
     */
    public void setAutoSyncEnabled(boolean enabled) {
        boolean wasEnabled = this.autoSyncEnabled;
        this.autoSyncEnabled = enabled;
        if (enabled && !wasEnabled) {
            sync();
        }
    }

    /** Registers a listener. */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /** Unregisters a listener. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns an unmodifiable snapshot of the sources currently managed by
     * this provider (one per armed track). Free, user-added sources are
     * <em>not</em> included.
     */
    public List<SoundSource> getManagedSources() {
        return List.copyOf(managedByTrackId.values());
    }

    /**
     * Returns whether the source with the given name is currently managed
     * by this provider (i.e., backed by an armed track).
     */
    public boolean isManaged(String sourceName) {
        if (sourceName == null) {
            return false;
        }
        for (SoundSource s : managedByTrackId.values()) {
            if (sourceName.equals(s.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Updates the stored position for the source backing the given track.
     * Call this when the user drags a source on the telemetry canvas so
     * the new position survives subsequent sync passes.
     *
     * @return {@code true} if the track was managed and the position was updated
     */
    public boolean updateSourcePosition(String trackId, Position3D position) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        Objects.requireNonNull(position, "position must not be null");
        SoundSource existing = managedByTrackId.get(trackId);
        if (existing == null) {
            return false;
        }
        SoundSource updated = new SoundSource(existing.name(), position, existing.powerDb());
        managedByTrackId.put(trackId, updated);
        replaceInConfig(existing, updated);
        return true;
    }

    /**
     * Updates the stored power (dB) for the source backing the given track.
     *
     * @return {@code true} if the track was managed and the power was updated
     */
    public boolean updateSourcePower(String trackId, double powerDb) {
        Objects.requireNonNull(trackId, "trackId must not be null");
        SoundSource existing = managedByTrackId.get(trackId);
        if (existing == null) {
            return false;
        }
        SoundSource updated = new SoundSource(existing.name(), existing.position(), powerDb);
        managedByTrackId.put(trackId, updated);
        replaceInConfig(existing, updated);
        return true;
    }

    /**
     * Reconciles the bound project's {@link RoomConfiguration#getSoundSources()}
     * with its armed tracks: adds a source for each newly armed track,
     * removes sources for disarmed/removed tracks, and updates names for
     * renamed tracks. Position and power edits made by the user are
     * preserved, keyed by stable track id.
     *
     * <p>No-op when auto-sync is disabled, when no project is bound, or
     * when the project has no {@link RoomConfiguration}.</p>
     */
    public void sync() {
        if (!autoSyncEnabled || project == null) {
            return;
        }
        RoomConfiguration config = project.getRoomConfiguration();
        if (config == null) {
            return;
        }

        List<Track> armed = new ArrayList<>();
        for (Track t : project.getTracks()) {
            if (t.isArmed()) {
                armed.add(t);
            }
        }

        // 1. Drop managed sources whose track is no longer armed or was removed.
        Iterator<Map.Entry<String, SoundSource>> it = managedByTrackId.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SoundSource> entry = it.next();
            String trackId = entry.getKey();
            boolean stillArmed = armed.stream().anyMatch(t -> t.getId().equals(trackId));
            if (!stillArmed) {
                config.removeSoundSource(entry.getValue().name());
                it.remove();
            }
        }

        // 2. Reconcile each currently-armed track with its managed source.
        RoomDimensions dims = config.getDimensions();
        int layoutIndex = 0;
        int layoutSize = armed.size();
        for (Track track : armed) {
            SoundSource existing = managedByTrackId.get(track.getId());
            if (existing == null) {
                // Newly armed: compute deterministic layout position.
                Position3D pos = layoutPosition(layoutIndex, layoutSize, dims);
                SoundSource created = new SoundSource(track.getName(), pos, DEFAULT_POWER_DB);
                config.addSoundSource(created);
                managedByTrackId.put(track.getId(), created);
            } else if (!existing.name().equals(track.getName())) {
                // Renamed: preserve position & power, update the name.
                SoundSource renamed = new SoundSource(
                        track.getName(), existing.position(), existing.powerDb());
                replaceInConfig(existing, renamed);
                managedByTrackId.put(track.getId(), renamed);
            }
            layoutIndex++;
        }

        notifyListeners();
    }

    /** Removes all listeners and clears managed-source state. */
    public void dispose() {
        listeners.clear();
        managedByTrackId.clear();
        project = null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void notifyListeners() {
        for (Listener l : listeners) {
            l.onSourcesChanged(this);
        }
    }

    /**
     * Replaces {@code oldSource} with {@code newSource} in the bound
     * project's room configuration. If the old source has been removed
     * externally, {@code newSource} is simply added.
     */
    private void replaceInConfig(SoundSource oldSource, SoundSource newSource) {
        if (project == null) {
            return;
        }
        RoomConfiguration config = project.getRoomConfiguration();
        if (config == null) {
            return;
        }
        // Capture the ordinal position of the old source so the new source
        // slots into the same place (preserves user's visual ordering).
        List<SoundSource> current = new ArrayList<>(config.getSoundSources());
        int oldIndex = -1;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).name().equals(oldSource.name())) {
                oldIndex = i;
                break;
            }
        }
        if (oldIndex < 0) {
            config.addSoundSource(newSource);
            return;
        }
        // Rebuild the list preserving order.
        List<SoundSource> preserved = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            preserved.add(i == oldIndex ? newSource : current.get(i));
        }
        // Clear and re-add all sources in original order.
        for (SoundSource s : current) {
            config.removeSoundSource(s.name());
        }
        for (SoundSource s : preserved) {
            config.addSoundSource(s);
        }
    }

    /**
     * Computes a deterministic position inside the given room for the
     * {@code index}-th source out of {@code total}. Sources are arranged
     * on a circle around the room's floor-plane center at
     * {@link #DEFAULT_SOURCE_HEIGHT_M} above the floor. For {@code total == 1}
     * the source sits at the center.
     */
    static Position3D layoutPosition(int index, int total, RoomDimensions dims) {
        Objects.requireNonNull(dims, "dims must not be null");
        double cx = dims.width() / 2.0;
        double cy = dims.length() / 2.0;
        double z = Math.min(DEFAULT_SOURCE_HEIGHT_M, dims.height() * 0.5);
        if (total <= 1) {
            return new Position3D(cx, cy, z);
        }
        double radius = LAYOUT_RADIUS_FRACTION * Math.min(dims.width(), dims.length());
        double angle = (2.0 * Math.PI * index) / total;
        double x = clamp(cx + radius * Math.cos(angle), 0.0, dims.width());
        double y = clamp(cy + radius * Math.sin(angle), 0.0, dims.length());
        return new Position3D(x, y, z);
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** Returns an unmodifiable, ordered snapshot of the managed-source map entries. */
    Map<String, SoundSource> managedByTrackIdSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(managedByTrackId));
    }
}
