package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.runOnFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decisions D33, D39 and D40 — an open's audio run can never stay
 * loading for the rest of the session. When adding a drain's rows to the
 * report fails, the drain still updates the progress, ends the run (the
 * strip operation goes, the one follow-up is shown) and repaints once. When
 * applying one source's outcome fails, the failure is contained to that
 * source: the other outcomes of the same drain land and the run ends. When a
 * source's outcome is lost on its way to the drain, or its apply failed,
 * the run's end settles the source as an {@code UNREADABLE} row —
 * {@code loading did not complete} — instead of leaving its clips
 * {@code Loading…} and every export and freeze refused, and the drain that
 * settles it repaints, even when it applied nothing else.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class LoadingRunAlwaysEndsTest {

    @TempDir
    Path workspace;

    private OpenProjectFixture fixture;

    @BeforeEach
    void createTheController() throws Exception {
        fixture = new OpenProjectFixture(workspace);
    }

    @AfterEach
    void closeTheController() throws Exception {
        fixture.close();
    }

    @Test
    void aReportUpdateThatFailsStillEndsTheRunRepaintsOnceAndFollowsUp() throws Exception {
        Path good = OpenProjectFixture.wav(workspace.resolve("library").resolve("good.wav"), 48_000, 0.5, 220.0);
        Path missing = OpenProjectFixture.wav(workspace.resolve("library").resolve("missing.wav"), 48_000, 0.5, 330.0);
        Path broken = OpenProjectFixture.wav(workspace.resolve("library").resolve("broken.wav"), 48_000, 0.5, 440.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Failing View", good, missing, broken);
        Files.delete(missing);
        Files.write(broken, new byte[]{'n', 'o', 't', ' ', 'a', ' ', 'w', 'a', 'v', 'e'});
        AtomicInteger failedUpdates = new AtomicInteger();
        runOnFx(() -> fixture.lifecycle().setMissingAssetsPresenter((generation, report) ->
                new FailsItsFirstUpdate(failedUpdates)));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        fixture.releaseDecodesAndPulse();

        assertThat(failedUpdates).as("fixture: the drain's report update failed").hasValue(1);
        List<AudioClip> clips = fixture.liveClips();
        assertThat(clips.get(0).getAudioData()).as("the good clip landed").isNotNull();
        assertThat(onFx(() -> fixture.audio().report().rowsFor(new AssetReference.SingleFile(broken.toString()))))
                .as("the unreadable row is in the report").singleElement()
                .satisfies(row -> assertThat(row.reason()).isEqualTo(MissingAssetReason.UNREADABLE));
        assertThat(fixture.operationNames()).as("the run ended: the strip operation is gone").isEmpty();
        assertThat(fixture.repaints).as("the drain repainted once").hasValue(1);
        assertThat(fixture.notifications()).as("the announcement, then the run's one follow-up").hasSize(2);
        assertThat(fixture.notifications().getLast().message()).isEqualTo("1 asset could not be loaded as saved");
        for (AudioClip clip : clips) {
            assertThat(onFx(() -> fixture.audio().stateOf(clip))).isNotEqualTo(ClipAudioState.LOADING);
        }
        assertThat(onFx(() -> fixture.audio().readiness().loading())).isFalse();
    }

    @Test
    void aSourceWhoseOutcomeWasLostIsSettledUnreadableWhenTheRunEnds() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Lost Outcome",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("a.wav"), 48_000, 0.5, 220.0),
                OpenProjectFixture.wav(workspace.resolve("library").resolve("b.wav"), 48_000, 0.5, 330.0));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        fixture.decodes.release();
        fixture.decodes.awaitEveryTaskEnded();
        // One outcome is lost on its way to the drain — as when a listener fails
        // or memory runs out while it is reported; the run's end still arrives.
        runOnFx(() -> dropOneOutcome(fixture.audio()));

        fixture.pulse();

        List<AudioClip> clips = fixture.liveClips();
        List<AudioClip> landed = clips.stream().filter(clip -> clip.getAudioData() != null).toList();
        List<AudioClip> lost = clips.stream().filter(clip -> clip.getAudioData() == null).toList();
        assertThat(landed).as("the other outcome still landed").hasSize(1);
        assertThat(lost).hasSize(1);
        AudioClip lostClip = lost.getFirst();
        assertThat(onFx(() -> fixture.audio().stateOf(lostClip))).as("not loading forever")
                .isEqualTo(ClipAudioState.OFFLINE);
        MissingAssetsReport report = onFx(() -> fixture.audio().report());
        assertThat(report.assets()).singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo(AssetReference.of(lostClip).orElseThrow());
            assertThat(row.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
            assertThat(row.detail()).isEqualTo("loading did not complete");
            assertThat(row.silentHolders()).extracting(MissingAsset.Holder::clipId).containsExactly(lostClip.getId());
        });
        assertThat(fixture.operationNames()).as("the run ended").isEmpty();
        assertThat(onFx(() -> fixture.audio().readiness().loading())).as("an export may proceed").isFalse();
        assertThat(fixture.notifications().getLast().message()).isEqualTo("1 asset could not be loaded as saved");
    }

    @Test
    void anOutcomeWhoseApplyFailsIsContainedAndTheRestOfTheDrainStillLands() throws Exception {
        Path a = OpenProjectFixture.wav(workspace.resolve("library").resolve("a.wav"), 48_000, 0.5, 220.0);
        Path b = OpenProjectFixture.wav(workspace.resolve("library").resolve("b.wav"), 48_000, 0.5, 330.0);
        Path c = OpenProjectFixture.wav(workspace.resolve("library").resolve("c.wav"), 48_000, 0.5, 440.0);
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Failing Apply", a, b, c);
        AssetReference failing = new AssetReference.SingleFile(a.toString());
        AtomicInteger failedApplies = new AtomicInteger();
        fixture.applyOutcomesWith((session, outcome) -> {
            if (outcome.source().equals(failing)) {
                failedApplies.incrementAndGet();
                throw new IllegalStateException("fixture: applying A failed");
            }
            return session.apply(outcome).rows();
        });
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();

        // A, B and C, then the run's end, all drain in this one pulse.
        fixture.releaseDecodesAndPulse();

        assertThat(failedApplies).as("fixture: A's apply failed").hasValue(1);
        List<AudioClip> clips = fixture.liveClips();
        assertThat(clips.get(1).getAudioData()).as("the rest of the drain still landed: B").isNotNull();
        assertThat(clips.get(2).getAudioData()).as("and C").isNotNull();
        assertThat(clips.get(0).getAudioData()).isNull();
        assertThat(fixture.operationNames()).as("the run ended: the strip operation is gone").isEmpty();
        assertThat(fixture.repaints).as("the drain repainted once").hasValue(1);
        for (AudioClip clip : clips) {
            assertThat(onFx(() -> fixture.audio().stateOf(clip))).as("nothing is left loading")
                    .isNotEqualTo(ClipAudioState.LOADING);
        }
        assertThat(onFx(() -> fixture.audio().readiness().loading())).as("an export may proceed").isFalse();
        MissingAssetsReport report = onFx(() -> fixture.audio().report());
        assertThat(report.assets()).as("A's clips are silent, so A has a row").singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo(failing);
            assertThat(row.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
            assertThat(row.detail()).isEqualTo("loading did not complete");
            assertThat(row.silentHolders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(clips.get(0).getId());
        });
        assertThat(fixture.notifications().getLast().message()).isEqualTo("1 asset could not be loaded as saved");
    }

    @Test
    void aDrainThatOnlySettlesALostOutcomeStillRepaints() throws Exception {
        OpenProjectFixture.SavedProject saved = fixture.projectOnDisk("Lone Lost Outcome",
                OpenProjectFixture.wav(workspace.resolve("library").resolve("lone.wav"), 48_000, 0.5, 220.0));
        assertThat(onFx(() -> fixture.lifecycle().loadProjectFromPath(saved.directory()))).isTrue();
        fixture.decodes.release();
        fixture.decodes.awaitEveryTaskEnded();
        // The only outcome is lost: the drain holds the run's end and nothing else.
        runOnFx(() -> dropOneOutcome(fixture.audio()));

        fixture.pulse();

        AudioClip clip = fixture.liveClips().getFirst();
        assertThat(clip.getAudioData()).isNull();
        assertThat(onFx(() -> fixture.audio().stateOf(clip))).as("settled: no longer loading")
                .isEqualTo(ClipAudioState.OFFLINE);
        assertThat(fixture.repaints).as("the settling drain repainted, so the clip stops showing Loading…")
                .hasValue(1);
        assertThat(fixture.operationNames()).as("the run ended").isEmpty();
    }

    /** Removes one queued outcome from the current run's completion queue (reflection; FX thread). */
    private static void dropOneOutcome(OpenProjectAudio audio) {
        try {
            Field current = OpenProjectAudio.class.getDeclaredField("current");
            current.setAccessible(true);
            Object run = current.get(audio);
            Field completions = run.getClass().getDeclaredField("completions");
            completions.setAccessible(true);
            Queue<?> queue = (Queue<?>) completions.get(run);
            for (Iterator<?> completion = queue.iterator(); completion.hasNext(); ) {
                if (completion.next().getClass().getSimpleName().equals("Arrived")) {
                    completion.remove();
                    return;
                }
            }
            throw new AssertionError("fixture: no outcome was queued");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("fixture: the run keeps its queue in OpenProjectAudio.current.completions", e);
        }
    }

    /** A report surface whose first update fails, as a broken dialog would. */
    private static final class FailsItsFirstUpdate implements OpenProjectAudio.ReportView {

        private final AtomicInteger failedUpdates;

        FailsItsFirstUpdate(AtomicInteger failedUpdates) {
            this.failedUpdates = failedUpdates;
        }

        @Override
        public void present() {
        }

        @Override
        public void update(MissingAssetsReport report) {
            if (failedUpdates.getAndIncrement() == 0) {
                throw new IllegalStateException("the report surface failed to update");
            }
        }

        @Override
        public void dismiss() {
        }
    }
}
