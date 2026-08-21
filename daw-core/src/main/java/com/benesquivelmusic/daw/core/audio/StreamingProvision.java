package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.List;
import java.util.Objects;

/**
 * The engine's one streaming slot (story 316): the backend the user
 * <em>requested</em> plus the explicit fallback ladder the engine walks when
 * that backend cannot open — for example
 * {@code ASIO → PortAudio → Java Sound} on Windows.
 *
 * <p>By convention the first rung is the requested backend/device
 * <em>when that backend can stream on this host</em>; when the builder's
 * availability/streaming gate rejects the request, the ladder starts at
 * the first viable fallback while {@link #requestedBackendName()} still
 * names the user's original request (nothing validates the two against
 * each other). Later rungs are the emergency fallbacks (each opened on its
 * own rung device — typically the rung backend's default device). The
 * engine publishes a {@code BackendFallbackEvent} — stamped with
 * {@code requestedBackendName} and {@code requestedDevice} — for every
 * failed hop so requested &ne; active is always a visible fact (design book
 * &sect;2.4), and when every rung fails it rethrows the <em>first</em>
 * rung's exception.</p>
 *
 * <p>{@link #requestedDevice()} carries exactly the same caveat as
 * {@link #requestedBackendName()}: it is the device the user's
 * configuration asked for, and it equals the first rung's device only when
 * the requested backend passed the builder's gate. A gate-rejected request
 * starts the ladder on a FALLBACK rung, whose device is that fallback
 * backend's — pairing it with the user's requested backend name in a
 * {@code BackendFallbackEvent} would report a device the user never chose
 * (story 316 review), which is why the requested device is carried here
 * rather than re-derived from {@link #firstRung()}.</p>
 *
 * <p>{@link #pendingFailedHopCauses()} closes the one hole that carriage
 * leaves open (story 316 review). A gate-rejected request never appears in
 * the ladder at all, so the engine's ladder walk records no failed hop for
 * it: when the fallback head then opens on the first try, {@code requested
 * != active} became a SILENT substitution on the {@code EventBus} seam —
 * the {@code NotificationManager} message the builder emits is a different
 * surface (a transient toast), not the durable fact other subscribers read.
 * The builder therefore hands the rejection forward as a pending failed hop
 * carrying its own cause, and the engine publishes it — ahead of the
 * ladder's own failed hops, because it happened first — once the actual
 * winner is known and the event can honestly name it.</p>
 *
 * <p>The controller that builds the provision owns every backend instance's
 * lifecycle; replacing the provision via
 * {@link AudioEngine#setStreamingProvision(StreamingProvision)} does not
 * close the outgoing instances.</p>
 *
 * @param requestedBackendName   display name of the backend the user's
 *                               configuration requested; must not be null or
 *                               blank
 * @param requestedDevice        the device the user's configuration asked
 *                               for; must not be null
 * @param ladder                 the ordered open ladder; must contain at
 *                               least one rung and no nulls; defensively
 *                               copied
 * @param pendingFailedHopCauses causes for hops the ladder never contains
 *                               because the app layer's availability /
 *                               streaming gate rejected them before the
 *                               engine ever saw them, carried here so the
 *                               engine can publish them once the actual
 *                               winner is known; must not be null and must
 *                               contain no null or blank elements;
 *                               defensively copied
 */
public record StreamingProvision(String requestedBackendName,
                                 DeviceId requestedDevice,
                                 List<BackendStreamRung> ladder,
                                 List<String> pendingFailedHopCauses) {

    public StreamingProvision {
        Objects.requireNonNull(requestedBackendName, "requestedBackendName must not be null");
        if (requestedBackendName.isBlank()) {
            throw new IllegalArgumentException("requestedBackendName must not be blank");
        }
        Objects.requireNonNull(requestedDevice, "requestedDevice must not be null");
        Objects.requireNonNull(ladder, "ladder must not be null");
        if (ladder.isEmpty()) {
            throw new IllegalArgumentException("ladder must contain at least one rung");
        }
        ladder = List.copyOf(ladder); // defensive copy; also rejects null elements
        Objects.requireNonNull(
                pendingFailedHopCauses, "pendingFailedHopCauses must not be null");
        // Defensive copy; also rejects null elements. A blank cause would
        // publish a BackendFallbackEvent that explains nothing, which is
        // worse than no event: the user sees a substitution with no reason.
        pendingFailedHopCauses = List.copyOf(pendingFailedHopCauses);
        for (String cause : pendingFailedHopCauses) {
            if (cause.isBlank()) {
                throw new IllegalArgumentException(
                        "pendingFailedHopCauses must not contain a blank cause");
            }
        }
    }

    /**
     * Convenience constructor for a provision with nothing pending — the
     * ladder itself is the whole story, because the app layer's gate
     * rejected nothing ahead of it. Delegates with an empty pending list.
     *
     * @param requestedBackendName display name of the backend the user's
     *                             configuration requested; must not be null
     *                             or blank
     * @param requestedDevice      the device the user's configuration asked
     *                             for; must not be null
     * @param ladder               the ordered open ladder; must contain at
     *                             least one rung and no nulls
     */
    public StreamingProvision(String requestedBackendName,
                              DeviceId requestedDevice,
                              List<BackendStreamRung> ladder) {
        this(requestedBackendName, requestedDevice, ladder, List.of());
    }

    /**
     * Convenience constructor for the common case where the requested
     * endpoint IS the first rung — i.e. the requested backend passed the
     * builder's availability/streaming gate, so nothing was skipped ahead of
     * it. Delegates with the first rung's device as the requested device.
     *
     * @param requestedBackendName display name of the backend the user's
     *                             configuration requested; must not be null
     *                             or blank
     * @param ladder               the ordered open ladder; must contain at
     *                             least one rung and no nulls
     */
    public StreamingProvision(String requestedBackendName, List<BackendStreamRung> ladder) {
        this(requestedBackendName, firstDeviceOf(ladder), ladder);
    }

    /**
     * Reads the first rung's device for the two-argument constructor, before
     * the canonical constructor's own validation can run.
     */
    private static DeviceId firstDeviceOf(List<BackendStreamRung> ladder) {
        Objects.requireNonNull(ladder, "ladder must not be null");
        if (ladder.isEmpty()) {
            throw new IllegalArgumentException("ladder must contain at least one rung");
        }
        return ladder.get(0).device();
    }

    /**
     * Returns the first rung — the backend/device the engine tries first,
     * which is the requested backend when it passed the builder's gate and
     * the first viable fallback otherwise (see the class javadoc).
     */
    public BackendStreamRung firstRung() {
        return ladder.get(0);
    }
}
