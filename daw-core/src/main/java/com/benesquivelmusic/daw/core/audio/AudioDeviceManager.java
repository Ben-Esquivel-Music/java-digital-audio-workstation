package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages audio device enumeration and provides a centralized interface for
 * querying available input and output hardware devices.
 *
 * <p>Enumerates through the engine's honest backend answer
 * ({@link AudioEngine#getBackend()} — the open stream's backend when a
 * stream is open, else the provisioned ladder head; story 316), handling
 * error recovery so that callers need not manage backend lifecycle
 * directly.</p>
 */
public final class AudioDeviceManager {

    private static final Logger LOG = Logger.getLogger(AudioDeviceManager.class.getName());

    private final AudioEngine audioEngine;

    public AudioDeviceManager(AudioEngine audioEngine) {
        this.audioEngine = audioEngine;
    }

    /**
     * Returns all available audio devices from the configured backend.
     * Returns an empty list if no backend is configured or enumeration fails.
     *
     * @return the list of available audio devices
     */
    public List<AudioDeviceInfo> getAvailableDevices() {
        return enumerate().devices();
    }

    /**
     * Enumerates the devices of ONE backend reference together with that
     * backend's name, so a device picked from the list can be stored as a
     * stable {@link DeviceId} naming the backend that actually listed it.
     * Returns an empty enumeration if no backend is configured or
     * enumeration fails.
     *
     * @return the backend name (empty when no backend is configured) and its devices
     */
    public Enumeration enumerate() {
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return new Enumeration(Optional.empty(), List.of());
        }
        try {
            return new Enumeration(Optional.of(backend.name()), backend.listDevices());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to enumerate audio devices", e);
            return new Enumeration(Optional.of(backend.name()), List.of());
        }
    }

    /**
     * Devices listed by one backend.
     *
     * @param backendName the listing backend's name; empty when no backend is configured
     * @param devices     the enumerated devices
     */
    public record Enumeration(Optional<String> backendName, List<AudioDeviceInfo> devices) {
        public Enumeration {
            Objects.requireNonNull(backendName, "backendName must not be null");
            devices = List.copyOf(devices);
        }

        /**
         * The stable identity of {@code device}: this enumeration's backend plus
         * the device's host-API-qualified name — never its enumeration index.
         *
         * @param device a device from {@link #devices()}; must not be {@code null}
         * @return the stable identity
         * @throws IllegalStateException when no backend listed the devices
         */
        public DeviceId identityOf(AudioDeviceInfo device) {
            Objects.requireNonNull(device, "device must not be null");
            return new DeviceId(backendName.orElseThrow(() -> new IllegalStateException(
                    "no audio backend listed device '" + device.qualifiedName() + "'")), device.qualifiedName());
        }
    }

    /**
     * Returns only devices that support audio input.
     *
     * @return the list of input-capable devices
     */
    public List<AudioDeviceInfo> getInputDevices() {
        return getAvailableDevices().stream()
                .filter(AudioDeviceInfo::supportsInput)
                .toList();
    }

    /**
     * Returns only devices that support audio output.
     *
     * @return the list of output-capable devices
     */
    public List<AudioDeviceInfo> getOutputDevices() {
        return getAvailableDevices().stream()
                .filter(AudioDeviceInfo::supportsOutput)
                .toList();
    }
}
