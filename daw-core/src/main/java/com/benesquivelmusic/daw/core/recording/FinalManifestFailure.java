package com.benesquivelmusic.daw.core.recording;

import com.benesquivelmusic.daw.core.recording.TakeManifest.SealedBy;

import java.util.Objects;

/**
 * The final manifest write of a sealed take failed on every attempt
 * (Recording Reliability book §2.5 crash-consistent by construction, §5.5;
 * story 327, PR #982 review): the value
 * {@link CaptureFlushService#finalManifestFailure()} and
 * {@link RecordingPipeline#finalManifestFailure()} report. The flush thread
 * seals every lane first and only then writes the manifest that says so;
 * when that write fails on each of its
 * {@link CaptureFlushService#MANIFEST_WRITE_ATTEMPTS} attempts — an
 * {@link java.io.IOException} from the staging file or the rename, or a
 * {@link RuntimeException} from building or writing it — the segments are
 * what the seal left them, but the manifest on disk is the last one written:
 * it reads {@code seal-status=streaming} (the take start's, or the last
 * rotation's), and says nothing of the seal. This value is how that reaches
 * the caller. A stop or a device-loss rescue that reports no
 * {@link StopSealFailure} is still no clean finalisation while this is
 * present: the take is not recorded as rescued on disk, and the recovery
 * scan ({@link TakeRecovery#scan}; story 332 runs it on project open) finds
 * a crash candidate and rebuilds its manifest with a reason and an instant of its
 * own — the reason the flush thread meant to write is lost there, which is
 * why this value carries it.
 *
 * @param failure        the throwable of the last attempt — an
 *                       {@link java.io.IOException} from the staging file
 *                       or the rename, or a {@link RuntimeException} from
 *                       building or writing the manifest
 * @param intendedReason the reason the manifest was to carry:
 *                       {@link SealedBy#STOP} for a stop whose every lane
 *                       sealed ({@code seal-status=sealed}),
 *                       {@link SealedBy#DEVICE_LOSS} for a device-loss
 *                       rescue whose every lane sealed,
 *                       {@link SealedBy#WRITE_FAILURE} when a lane threw in
 *                       the seal or a write failure sealed the take early,
 *                       {@link SealedBy#DISK_EXHAUSTION} for the headroom
 *                       floor
 */
public record FinalManifestFailure(Throwable failure, SealedBy intendedReason) {

    public FinalManifestFailure {
        Objects.requireNonNull(failure, "failure must not be null");
        Objects.requireNonNull(intendedReason, "intendedReason must not be null");
    }
}
