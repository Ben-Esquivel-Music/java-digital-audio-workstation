package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.SessionInputSelection;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link SessionInputSelection} over an in-memory device name for the
 * controller tests (story 322): {@link #select} records the device and
 * adopts its qualified name — unless that qualified name already IS the
 * session name, the interface's exact no-op rule (fix round 2: the production
 * class neither persists nor applies then; a legacy bare session name is not
 * a no-op — it upgrades, and a same-named device under another host API is
 * a real selection); the mismatch logic is the interface's own default
 * implementation, so the stub and the settings-backed production class
 * answer identically.
 */
final class StubSessionInputSelection implements SessionInputSelection {

    private String deviceName;
    final List<AudioDeviceInfo> selected = new ArrayList<>();

    /** A stub whose session device is the backend default (blank). */
    StubSessionInputSelection() {
        this("");
    }

    StubSessionInputSelection(String deviceName) {
        this.deviceName = Objects.requireNonNull(deviceName, "deviceName must not be null");
    }

    @Override
    public String currentDeviceName() {
        return deviceName;
    }

    @Override
    public void select(AudioDeviceInfo device) {
        Objects.requireNonNull(device, "device must not be null");
        String qualifiedName = device.qualifiedName();
        if (qualifiedName.equals(deviceName)) {
            return; // the production class's exact no-op rule, never isSessionDevice's tolerance
        }
        selected.add(device);
        deviceName = qualifiedName;
    }
}
