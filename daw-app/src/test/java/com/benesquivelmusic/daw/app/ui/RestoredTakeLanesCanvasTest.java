package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.comping.CompRegion;
import com.benesquivelmusic.daw.core.comping.CompileCompAction;
import com.benesquivelmusic.daw.core.comping.TakeComping;
import com.benesquivelmusic.daw.core.comping.TakeLane;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comping surface after a reopen (story 334, studio ruling E18): the
 * take lanes and comp regions a project file restores are laid out and
 * drawn by the {@link ArrangementCanvas} exactly as the live ones are —
 * the track's slot grows by one lane height per restored lane, a Y position
 * resolves to the restored lane under it, and each restored region's
 * overlay is drawn on the lane it names by id in the file, and only there.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class RestoredTakeLanesCanvasTest {

    private static final double TRACK_HEIGHT = 80.0;
    private static final double PIXELS_PER_BEAT = 20.0;

    @Test
    void restoredLanesAndRegionOverlaysAreLaidOutAndDrawn() throws Exception {
        Track restored = reopenedCompedTrack();
        TakeComping comping = restored.getTakeComping();
        assertThat(comping.getTakeLaneCount()).as("fixture: three lanes reopened").isEqualTo(3);
        assertThat(comping.getCompRegions()).as("fixture: the regions reopened on lanes 0 and 2")
                .containsExactly(new CompRegion(0, 6.0, 2.0), new CompRegion(2, 12.0, 2.0));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ArrangementCanvas canvas = new ArrangementCanvas();
                new Scene(canvas, 400, 300);
                canvas.setTrackHeight(TRACK_HEIGHT);
                canvas.setPixelsPerBeat(PIXELS_PER_BEAT);
                canvas.setTracks(List.of(restored));
                canvas.applyCss();
                canvas.layout();

                double laneHeight = ArrangementCanvas.TAKE_LANE_HEIGHT;
                assertThat(canvas.takeLanesHeight(restored)).as("one lane height per restored lane")
                        .isEqualTo(3 * laneHeight);
                for (int lane = 0; lane < 3; lane++) {
                    assertThat(canvas.takeLaneIndexAtY(0, laneMiddle(lane)))
                            .as("Y resolves to restored lane %d", lane).isEqualTo(lane);
                }
                assertThat(canvas.trackIndexAtY(laneMiddle(2))).as("the lanes belong to their track").isZero();

                WritableImage image = canvas.snapshot(new SnapshotParameters(), null);
                PixelReader pixels = image.getPixelReader();
                int laneZeroInRegion = pixels.getArgb(x(7.15), (int) laneMiddle(0));
                int laneZeroPlain = pixels.getArgb(x(10.35), (int) laneMiddle(0));
                int laneZeroUnderTheOtherRegion = pixels.getArgb(x(13.15), (int) laneMiddle(0));
                int laneTwoInRegion = pixels.getArgb(x(13.15), (int) laneMiddle(2));
                int laneTwoPlain = pixels.getArgb(x(10.35), (int) laneMiddle(2));
                int laneTwoUnderTheOtherRegion = pixels.getArgb(x(7.15), (int) laneMiddle(2));

                assertThat(laneZeroInRegion).as("lane 0's region overlay is drawn").isNotEqualTo(laneZeroPlain);
                assertThat(laneZeroUnderTheOtherRegion).as("lane 2's region is not drawn on lane 0")
                        .isEqualTo(laneZeroPlain);
                assertThat(laneTwoInRegion).as("lane 2's region overlay is drawn").isNotEqualTo(laneTwoPlain);
                assertThat(laneTwoUnderTheOtherRegion).as("lane 0's region is not drawn on lane 2")
                        .isEqualTo(laneTwoPlain);
                assertThat(laneTwoPlain).as("the restored mute flag dims lane 2's clip (same lane fill as lane 0)")
                        .isNotEqualTo(laneZeroPlain);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) {
            throw new AssertionError("failed on the FX thread", failure.get());
        }
    }

    /**
     * Saves a project whose track "Vox" holds three take lanes — each a clip
     * over beats 0–20, the third muted — with comp regions on lanes 0 and 2,
     * compiled, and returns the track as a reopen of the saved file restores it.
     */
    private static Track reopenedCompedTrack() throws Exception {
        DawProject project = new DawProject("Comped", AudioFormat.CD_QUALITY);
        Track vox = project.createAudioTrack("Vox");
        TakeComping comping = vox.getTakeComping();
        for (int i = 1; i <= 3; i++) {
            TakeLane lane = new TakeLane("Take " + i);
            AudioClip take = new AudioClip("Take " + i, 0.0, 20.0, null);
            take.setSourceSegmentPaths(List.of("/takes/take-" + i + "/segment-000.wav"));
            lane.addClip(take);
            lane.setMuted(i == 3);
            comping.addTakeLane(lane);
        }
        comping.addCompRegion(new CompRegion(0, 6.0, 2.0));
        comping.addCompRegion(new CompRegion(2, 12.0, 2.0));
        new CompileCompAction(vox).execute();
        DawProject reopened = new ProjectDeserializer().deserialize(new ProjectSerializer().serialize(project));
        return reopened.getTracks().getFirst();
    }

    /** The Y of the middle of take lane {@code lane}, under the 80 px main lane. */
    private static double laneMiddle(int lane) {
        return TRACK_HEIGHT + lane * ArrangementCanvas.TAKE_LANE_HEIGHT + ArrangementCanvas.TAKE_LANE_HEIGHT / 2;
    }

    /** The X of {@code beat}, chosen off the beat and sub-beat grid lines. */
    private static int x(double beat) {
        return (int) Math.round(beat * PIXELS_PER_BEAT);
    }
}
