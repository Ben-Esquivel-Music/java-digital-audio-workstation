package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.BackendStreamRung;
import com.benesquivelmusic.daw.core.audio.StreamingProvision;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioDeviceInfo;
import com.benesquivelmusic.daw.sdk.audio.DeviceId;
import com.benesquivelmusic.daw.sdk.audio.MockAudioBackend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round (S7) — arming a track from the arrangement strip must
 * not enumerate audio devices on the FX thread: {@code AudioBackend.listDevices()}
 * is a driver walk (on ASIO it waits on the driver control thread, for
 * seconds while a reopen is in flight) and the arm listener fires on every
 * arm gesture ({@code javafx-application-design} §11 — no blocking I/O in a
 * handler). The enumeration runs on a worker; the session-input mismatch
 * WARNING still lands, marshalled back onto the FX thread.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ArrangementArmInputCheckOffFxTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000, 2, 16, 256);

    @Test
    void armingFromTheStripEnumeratesDevicesOffTheFxThreadAndStillWarns() throws Exception {
        DawProject project = new DawProject("Arm", FORMAT);
        Track vox = project.createAudioTrack("Vox");
        AudioDeviceInfo mockDevice = new MockAudioBackend().listDevices().get(0);
        vox.setInputDeviceIndex(mockDevice.index());   // the enumerated device — not the session one
        EnumerationTrackingBackend backend = new EnumerationTrackingBackend();

        ArrangementStripFixture rig = ArrangementStripFixture.onFx(() -> new ArrangementStripFixture(
                project, true, new StubSessionInputSelection("Session In [ASIO]")));
        try {
            rig.audioEngine.setStreamingProvision(new StreamingProvision(backend.name(),
                    List.of(new BackendStreamRung(backend, DeviceId.defaultFor(backend.name())))));
            ArrangementStripFixture.onFx(() -> {
                rig.addStrip(vox);
                assertThat(rig.controller.pendingSessionInputCheck()).as("no check before an arm").isEmpty();
                vox.setArmed(true);   // TrackVM republishes inline on FX -> the strip's arm listener
                assertThat(backend.enumerationsOnFxThread.get())
                        .as("the arm listener must not enumerate synchronously on the FX thread").isZero();
            });

            Thread check = rig.controller.pendingSessionInputCheck().orElseThrow();
            check.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(check.isAlive()).as("the enumeration worker finished").isFalse();
            ArrangementStripFixture.onFx(() -> { });   // FX barrier: the worker's toast has landed

            assertThat(backend.enumerations.get()).as("the arm did enumerate, once, off-thread").isEqualTo(1);
            assertThat(backend.enumerationsOnFxThread.get()).isZero();
            ArrangementStripFixture.onFx(() -> {
                assertThat(rig.notificationBar.getCurrentLevel()).isEqualTo(NotificationLevel.WARNING);
                assertThat(rig.notificationBar.getMessage())
                        .contains("Recording uses the session input 'Session In [ASIO]'")
                        .contains("track(s) Vox chose '" + mockDevice.qualifiedName() + "'")
                        .contains("story 326");
            });
        } finally {
            ArrangementStripFixture.onFx(rig::close);
        }
    }
}
