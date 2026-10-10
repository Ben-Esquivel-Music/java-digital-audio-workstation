package com.benesquivelmusic.daw.app.ui.recording;

import com.benesquivelmusic.daw.app.ui.SettingsModel;

import java.util.Objects;

/**
 * The production {@link SessionInputSelection}: the session input device is
 * {@link SettingsModel#getAudioInputDevice()}, read live on every call, so a
 * change is what the new-track and per-track input dialogs preselect next.
 * Every write goes through {@link SettingsModel#setAudioInputDevice}: the
 * Audio Settings session selector and the first-run wizard commit a chosen
 * device (and apply it through their own configuration path), and
 * {@link SettingsModel#resetToDefaults()} clears it back to the blank backend
 * default. No per-track surface is a writer.
 *
 * <p>The persisted value is a device NAME — the qualified name the session
 * selector stores, or a legacy bare name from before qualified names existed —
 * never an enumeration index; {@link #isSessionDevice} compares it by name.
 * Per-track input choices are separate stable {@code DeviceId} identities on
 * the track (story 326; a pre-identity project's per-track index survives only
 * as {@code Track.getLegacyInputDeviceIndexHint()}), and a per-track pick
 * never writes this selection, so nothing here persists, applies a
 * configuration or touches the engine.</p>
 */
public final class SettingsBackedSessionInputSelection implements SessionInputSelection {

    private final SettingsModel settings;

    /**
     * Creates the settings-backed selection.
     *
     * @param settings the persisted preferences the session device lives in; must not be {@code null}
     * @throws NullPointerException if {@code settings} is {@code null}
     */
    public SettingsBackedSessionInputSelection(SettingsModel settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public String currentDeviceName() {
        return settings.getAudioInputDevice();
    }
}
