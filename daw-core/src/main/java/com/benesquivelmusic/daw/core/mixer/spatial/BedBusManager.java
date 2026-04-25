package com.benesquivelmusic.daw.core.mixer.spatial;

import com.benesquivelmusic.daw.sdk.spatial.ImmersiveFormat;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages a project's single {@link BedBus} together with all
 * {@link BedChannelRouting per-track routings} into that bus.
 *
 * <p>The manager owns the bed bus for a project and is responsible for two
 * key invariants:
 * <ul>
 *   <li>All {@link BedChannelRouting routings} target the manager's
 *       current {@link ImmersiveFormat} — when the format changes, every
 *       routing is rebuilt against the new layout.</li>
 *   <li>When the format changes, routings are <em>preserved</em> for
 *       channels whose {@link SpeakerLabel} exists in both the old and
 *       new layout, and zeroed out (set to
 *       {@link BedChannelRouting#SILENT_DB SILENT_DB}) for new channels
 *       that did not previously exist.</li>
 * </ul>
 *
 * <p>The manager also offers a small mixing helper
 * ({@link #mixMonoSource(UUID, float[], float[][])}) that the spatial
 * renderer chain can use to fan a mono source out into the bed bus
 * channels at the gains specified by the track's routing.</p>
 */
public final class BedBusManager {

    private BedBus bedBus;
    private final Map<UUID, BedChannelRouting> routings = new LinkedHashMap<>();

    /** Creates a manager with a unity-gain 7.1.4 bed bus and no routings. */
    public BedBusManager() {
        this(ImmersiveFormat.FORMAT_7_1_4);
    }

    /**
     * Creates a manager with a unity-gain bed bus in the given format.
     *
     * @param format the initial bed format
     */
    public BedBusManager(ImmersiveFormat format) {
        Objects.requireNonNull(format, "format must not be null");
        this.bedBus = BedBus.unityGain(UUID.randomUUID(), format);
    }

    /** Returns the current bed bus. */
    public BedBus getBedBus() {
        return bedBus;
    }

    /**
     * Replaces the bed bus.
     *
     * <p>This is the entry point used by the project deserializer and by
     * undoable actions; routings are <em>not</em> automatically rebuilt by
     * this method (that is the job of
     * {@link #setFormat(ImmersiveFormat)}). Callers must therefore ensure
     * the new bus's format matches the current routings, or call
     * {@link #setFormat(ImmersiveFormat)} themselves.</p>
     *
     * @param bedBus the new bed bus
     */
    public void setBedBus(BedBus bedBus) {
        this.bedBus = Objects.requireNonNull(bedBus, "bedBus must not be null");
    }

    /** Returns the current bed format. */
    public ImmersiveFormat getFormat() {
        return bedBus.format();
    }

    /**
     * Switches the bed bus to a new immersive format, preserving routings
     * for shared channels.
     *
     * <p>For every existing track routing, channels whose
     * {@link SpeakerLabel} appears in both the old and new format keep
     * their gain; channels that only exist in the new format are set to
     * {@link BedChannelRouting#SILENT_DB SILENT_DB} (silent).</p>
     *
     * @param newFormat the new bed format
     */
    public void setFormat(ImmersiveFormat newFormat) {
        Objects.requireNonNull(newFormat, "newFormat must not be null");
        if (newFormat == bedBus.format()) {
            return;
        }
        ImmersiveFormat oldFormat = bedBus.format();
        SpeakerLayout oldLayout = oldFormat.layout();
        SpeakerLayout newLayout = newFormat.layout();

        // Rebuild every routing on the new layout, copying over gains for
        // any speaker label that exists in both layouts.
        Map<UUID, BedChannelRouting> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<UUID, BedChannelRouting> entry : routings.entrySet()) {
            BedChannelRouting old = entry.getValue();
            double[] oldGains = old.channelGainsDb();
            double[] newGains = new double[newFormat.channelCount()];
            Arrays.fill(newGains, BedChannelRouting.SILENT_DB);
            for (int newIdx = 0; newIdx < newLayout.channelCount(); newIdx++) {
                SpeakerLabel label = newLayout.speakers().get(newIdx);
                int oldIdx = oldLayout.indexOf(label);
                if (oldIdx >= 0) {
                    newGains[newIdx] = oldGains[oldIdx];
                }
            }
            rebuilt.put(entry.getKey(), new BedChannelRouting(old.trackId(), newFormat, newGains));
        }
        routings.clear();
        routings.putAll(rebuilt);

        // Replace the bus, preserving the id but resetting gains to unity.
        this.bedBus = BedBus.unityGain(bedBus.id(), newFormat);
    }

    /**
     * Sets (or replaces) the routing for a single track.
     *
     * @param routing the routing
     * @throws IllegalArgumentException if the routing's format does not
     *                                  match the manager's current format
     */
    public void setRouting(BedChannelRouting routing) {
        Objects.requireNonNull(routing, "routing must not be null");
        if (routing.format() != bedBus.format()) {
            throw new IllegalArgumentException(
                    "routing format " + routing.format()
                            + " does not match bed format " + bedBus.format());
        }
        routings.put(routing.trackId(), routing);
    }

    /**
     * Removes any routing for the given track.
     *
     * @param trackId the source track id
     * @return the previous routing, or {@link Optional#empty()} if none
     */
    public Optional<BedChannelRouting> removeRouting(UUID trackId) {
        return Optional.ofNullable(routings.remove(trackId));
    }

    /**
     * Returns the routing for the given track, if any.
     *
     * @param trackId the source track id
     * @return the routing or {@link Optional#empty()}
     */
    public Optional<BedChannelRouting> getRouting(UUID trackId) {
        return Optional.ofNullable(routings.get(trackId));
    }

    /** Returns an unmodifiable view of all routings, keyed by track id. */
    public Map<UUID, BedChannelRouting> getRoutings() {
        return Collections.unmodifiableMap(routings);
    }

    /** Removes every routing. */
    public void clearRoutings() {
        routings.clear();
    }

    /**
     * Mixes a mono source into the bed bus output buffer using the
     * track's current routing.
     *
     * <p>This is the audio-side companion of
     * {@link #setRouting(BedChannelRouting)}: it implements the
     * "guitar routed to L only" behaviour required by the issue. If no
     * routing exists for the given track the output is left unchanged.</p>
     *
     * @param trackId   the source track id (used to look up the routing)
     * @param input     the mono input buffer of length {@code numSamples}
     * @param output    the bed bus output, indexed
     *                  {@code [channel][sample]} where the first
     *                  dimension equals
     *                  {@link ImmersiveFormat#channelCount()}
     */
    public void mixMonoSource(UUID trackId, float[] input, float[][] output) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(output, "output must not be null");
        BedChannelRouting routing = routings.get(trackId);
        if (routing == null) {
            return;
        }
        int channelCount = bedBus.format().channelCount();
        if (output.length != channelCount) {
            throw new IllegalArgumentException(
                    "output channel count " + output.length
                            + " does not match bed format " + channelCount);
        }
        double[] busGainsDb = bedBus.channelGainsDb();
        for (int ch = 0; ch < channelCount; ch++) {
            double linear = routing.linearGain(ch);
            if (linear == 0.0) {
                continue;
            }
            // Apply the bed-bus per-channel trim on top of the routing gain.
            double busTrim = busGainsDb[ch];
            double trimLinear = (busTrim == BedChannelRouting.SILENT_DB
                    || Double.isInfinite(busTrim))
                    ? 0.0 : Math.pow(10.0, busTrim / 20.0);
            double total = linear * trimLinear;
            if (total == 0.0) {
                continue;
            }
            float[] outCh = output[ch];
            int n = Math.min(input.length, outCh.length);
            for (int i = 0; i < n; i++) {
                outCh[i] += (float) (input[i] * total);
            }
        }
    }
}
