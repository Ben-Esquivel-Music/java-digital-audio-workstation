package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.recording.SessionInputSelection;

import java.util.Objects;

/**
 * A {@link SessionInputSelection} over a fixed in-memory device name for the
 * controller tests (story 322). The session input is read-only to every
 * per-track surface — only the Audio Settings session selector writes it,
 * through the preferences — so a test asserts on {@link #currentDeviceName()}
 * that a per-track pick left it untouched. The comparison logic is the
 * interface's own default implementation, so the stub and the settings-backed
 * production class answer identically.
 */
final class StubSessionInputSelection implements SessionInputSelection {

    private final String deviceName;

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
}
