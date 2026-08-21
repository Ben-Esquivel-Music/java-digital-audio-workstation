package com.benesquivelmusic.daw.core.audio;

import com.benesquivelmusic.daw.sdk.audio.AudioBackend;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages audio device enumeration and provides a centralized interface for
 * querying available input and output hardware devices.
 *
 * <p>Enumerates through the engine's honest backend answer
 * ({@link AudioEngine#getBackend()} — the open stream's backend when a
 * stream is open, else the provisioned requested backend; story 316),
 * handling error recovery so that callers need not manage backend lifecycle
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
        AudioBackend backend = audioEngine.getBackend();
        if (backend == null) {
            return List.of();
        }
        try {
            return backend.listDevices();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to enumerate audio devices", e);
            return List.of();
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
