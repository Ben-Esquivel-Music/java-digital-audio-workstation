package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.Objects;

/**
 * One rung of a {@link StreamingProvision} fallback ladder (story 316): an
 * {@link AudioBackend} instance paired with the {@link DeviceId} the engine
 * must resolve and open on it.
 *
 * <p>Device identity is a stable {@code (backend, name)} pair, never a bare
 * index — the backend resolves the name against a fresh enumeration snapshot
 * on <em>every</em> open (design book &sect;3.2), so a stale name is a
 * visible {@code AudioBackendException} rather than a silent index-0
 * open.</p>
 *
 * @param backend the backend instance to open on; must not be null. The
 *                provisioning caller (the controller) owns the instance's
 *                lifecycle
 * @param device  the device to open; {@link DeviceId#isDefault() default}
 *                asks the backend for its own default device; must not be null
 */
public record BackendStreamRung(AudioBackend backend, DeviceId device) {

    public BackendStreamRung {
        Objects.requireNonNull(backend, "backend must not be null");
        Objects.requireNonNull(device, "device must not be null");
    }
}
