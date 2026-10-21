package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.audio.GlueClipsAction;
import com.benesquivelmusic.daw.core.audioimport.AudioFileImporter;
import com.benesquivelmusic.daw.core.export.AdmBwfExporter;
import com.benesquivelmusic.daw.core.export.WavExporter;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.spatial.objectbased.AudioObject;
import com.benesquivelmusic.daw.core.spatial.objectbased.BedChannel;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.transport.TempoChangeEvent;
import com.benesquivelmusic.daw.sdk.export.AudioMetadata;
import com.benesquivelmusic.daw.sdk.export.DitherType;
import com.benesquivelmusic.daw.sdk.spatial.ObjectMetadata;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLabel;
import com.benesquivelmusic.daw.sdk.spatial.SpeakerLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The clip rehydrator against in-memory projects whose clips name real files
 * (Persistence Integrity book §4.3; decisions D3, D4, D5, D6, D10, D11, D24, D26, D30, D33, D37, D39, D43, D49).
 */
class ClipRehydratorTest {

    private static final int RATE = 48_000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 2, 16, 512);
    /** The tests' heap figure when the budget must not interfere. */
    private static final long UNLIMITED = Long.MAX_VALUE;
    private static final long TERMINATION_GUARD_SECONDS = 60;

    @TempDir
    Path tempDir;

    @Test
    void splitHalvesOfOneSourceShareOneDecodeAndOneArraySoTheyStillGlue() throws Exception {
        Path source = wav("shared.wav", RATE, RATE, 2); // 1 s = 2 beats at 120 BPM
        DawProject project = new DawProject("Shared", FORMAT);
        Track track = project.createAudioTrack("Drums");
        AudioClip first = clip(track, "shared", 0.0, 2.0, source);
        AudioClip second = first.splitAt(1.0);
        track.addClip(second);
        RecordingSourceDecoder decoder = new RecordingSourceDecoder();
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator(decoder, 4, UNLIMITED)
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        // A split made after the run started, before its audio landed, is a
        // live clip with the same source: it gets the same array.
        AudioClip late = second.splitAt(1.5);
        track.addClip(late);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(decoder.decodes()).containsExactly(new AssetReference.SingleFile(source.toString()));
        assertThat(first.getAudioData()).isNotNull();
        assertThat(second.getAudioData()).isSameAs(first.getAudioData());
        assertThat(late.getAudioData()).isSameAs(first.getAudioData());
        assertThat(result.attached()).containsExactly(first, second, late);
        assertThat(result.rows()).isEmpty();

        new GlueClipsAction(track, first, second).execute();
        assertThat(track.getClips()).hasSize(2);
        assertThat(track.getClips()).doesNotContain(first, second).contains(late);
    }

    @Test
    void aCorruptFileIsAnUnreadableRowWhileTheGoodClipLandsAndTheRunFinishesOnce() throws Exception {
        Path good = wav("good.wav", RATE, RATE / 2, 2);
        Path bad = tempDir.resolve("bad.wav");
        Files.writeString(bad, "not really a wav");
        DawProject project = new DawProject("Containment", FORMAT);
        Track track = project.createAudioTrack("Vox");
        AudioClip goodClip = clip(track, "good", 0.0, 1.0, good);
        AudioClip badClip = clip(track, "bad", 4.0, 1.0, bad);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        assertThat(session.awaitTermination(TERMINATION_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(goodClip.getAudioData()).isNotNull();
        assertThat(badClip.getAudioData()).isNull();
        assertThat(result.attached()).containsExactly(goodClip);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(bad.toString()));
            assertThat(row.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
            assertThat(row.detail()).isNotBlank();
            assertThat(row.holders()).containsExactly(new MissingAsset.Holder(badClip.getId(), "Vox", "bad"));
        });
        assertThat(listener.finishes()).containsExactly(new RehydrationListener.Summary(2, 1, 1));
        assertThat(session.pendingCount()).isZero();
    }

    @Test
    void aSourceDeletedAfterTheOpenIsANotFoundRow() throws Exception {
        Path gone = tempDir.resolve("gone.wav");
        DawProject project = new DawProject("Gone", FORMAT);
        AudioClip clip = clip(project.createAudioTrack("Bass"), "gone", 0.0, 1.0, gone);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(clip.getAudioData()).isNull();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.NOT_FOUND);
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId).containsExactly(clip.getId());
        });
    }

    @Test
    void aCancelledSessionReportsNothingMoreAndAppliesNothing() throws Exception {
        Path source = wav("held.wav", RATE, RATE / 2, 2);
        DawProject project = new DawProject("Cancel", FORMAT);
        AudioClip clip = clip(project.createAudioTrack("Keys"), "held", 0.0, 1.0, source);
        AssetReference reference = new AssetReference.SingleFile(source.toString());
        CountDownLatch release = new CountDownLatch(1);
        RecordingSourceDecoder decoder = new RecordingSourceDecoder().holdUntil(release);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator(decoder, 1, UNLIMITED)
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        decoder.awaitFirstDecode();
        assertThat(session.isPending(reference)).isTrue();
        session.cancel();
        release.countDown();

        assertThat(session.awaitTermination(TERMINATION_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(decoder.decodes()).containsExactly(reference);
        assertThat(listener.outcomes()).as("the held decode completed, but nothing is reported after cancel")
                .isEmpty();
        assertThat(listener.finishes()).isEmpty();
        assertThat(session.isCancelled()).isTrue();
        assertThat(session.isPending(reference)).isFalse();
        assertThat(session.pendingCount()).isZero();
        RehydrationOutcome decoded = new RehydrationOutcome.Decoded(session, reference,
                new DecodedAudio.FileAudio(new float[2][16], RATE));
        assertThat(session.apply(decoded)).isEqualTo(RehydrationSession.AttachResult.NONE);
        assertThat(clip.getAudioData()).isNull();
    }

    @Test
    void cancellingOnTheFirstOutcomeOfTheProductionDecoderSuppressesTheEndAndTheAttach() throws Exception {
        Path source = wav("real.wav", 44_100, 22_050, 2);
        DawProject project = new DawProject("Real", FORMAT);
        AudioClip clip = clip(project.createAudioTrack("Gtr"), "real", 0.0, 1.0, source);
        AtomicReference<RehydrationSession> started = new AtomicReference<>();
        CountDownLatch sessionKnown = new CountDownLatch(1);
        CollectingRehydrationListener collected = new CollectingRehydrationListener();
        RehydrationListener cancelling = new RehydrationListener() {
            @Override
            public void onOutcome(RehydrationOutcome outcome) {
                collected.onOutcome(outcome);
                awaitUninterruptibly(sessionKnown); // test only: the session is returned after start
                started.get().cancel();
            }

            @Override
            public void onFinished(Summary summary) {
                collected.onFinished(summary);
            }
        };

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, cancelling);
        started.set(session);
        sessionKnown.countDown();

        assertThat(session.awaitTermination(TERMINATION_GUARD_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(collected.outcomes()).singleElement().isInstanceOf(RehydrationOutcome.Decoded.class);
        assertThat(collected.finishes()).as("the end comes after the cancel and is not reported").isEmpty();
        assertThat(session.apply(collected.outcomes().getFirst()))
                .isEqualTo(RehydrationSession.AttachResult.NONE);
        assertThat(clip.getAudioData()).isNull();
    }

    @Test
    void applyTakesOnlyTheSessionsOwnOutcomesAndEachOnce() throws Exception {
        Path source = wav("once.wav", RATE, RATE / 2, 2);
        DawProject first = new DawProject("First", FORMAT);
        AudioClip firstClip = clip(first.createAudioTrack("A"), "once", 0.0, 1.0, source);
        DawProject second = new DawProject("Second", FORMAT);
        AudioClip secondClip = clip(second.createAudioTrack("A"), "once", 0.0, 1.0, source);
        CollectingRehydrationListener firstListener = new CollectingRehydrationListener();
        CollectingRehydrationListener secondListener = new CollectingRehydrationListener();
        ClipRehydrator rehydrator = new ClipRehydrator();

        RehydrationSession firstSession = rehydrator.start(first, MissingAssetsReport.EMPTY, 0.0, firstListener);
        RehydrationSession secondSession = rehydrator.start(second, MissingAssetsReport.EMPTY, 0.0, secondListener);
        firstListener.awaitFinished();
        secondListener.awaitFinished();
        RehydrationOutcome firstOutcome = firstListener.outcomes().getFirst();

        assertThat(secondSession.apply(firstOutcome)).isEqualTo(RehydrationSession.AttachResult.NONE);
        assertThat(secondClip.getAudioData()).isNull();
        assertThat(firstSession.apply(firstOutcome).attached()).containsExactly(firstClip);
        float[][] attached = firstClip.getAudioData();
        firstClip.setAudioData(null);
        assertThat(firstSession.apply(firstOutcome)).isEqualTo(RehydrationSession.AttachResult.NONE);
        assertThat(firstClip.getAudioData()).isNull();
        assertThat(attached).isNotNull();
    }

    @Test
    void onlyClipsWithoutAudioWithAnOpenableReferenceNotAlreadyReportedAreScheduled() throws Exception {
        Path loaded = wav("loaded.wav", RATE, 1_000, 1);
        Path reported = wav("reported.wav", RATE, 1_000, 1);
        Path wanted = wav("wanted.wav", RATE, 1_000, 1);
        DawProject project = new DawProject("Qualify", FORMAT);
        Track track = project.createAudioTrack("T");
        AudioClip hasAudio = clip(track, "loaded", 0.0, 1.0, loaded);
        float[][] existing = new float[1][8];
        hasAudio.setAudioData(existing);
        AudioClip relative = new AudioClip("relative", 1.0, 1.0, "audio/relative.wav");
        track.addClip(relative);
        AudioClip noReference = new AudioClip("buffer", 2.0, 1.0, null);
        track.addClip(noReference);
        AudioClip alreadyReported = clip(track, "reported", 3.0, 1.0, reported);
        clip(track, "wanted", 4.0, 1.0, wanted);
        MissingAssetsReport atOpen = MissingAssetsReport.EMPTY.with(new MissingAsset(
                new AssetReference.SingleFile(reported.toString()), MissingAssetReason.NOT_FOUND, "not found",
                List.of(MissingAsset.Holder.of(track, alreadyReported))));
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator().start(project, atOpen, 0.0, listener);
        listener.awaitFinished();
        listener.applyAll(session);

        assertThat(session.sources()).containsExactly(new AssetReference.SingleFile(wanted.toString()));
        assertThat(hasAudio.getAudioData()).as("rehydration never overwrites audio").isSameAs(existing);
        assertThat(relative.getAudioData()).isNull();
        assertThat(alreadyReported.getAudioData()).isNull();
    }

    @Test
    void admBedAndObjectClipsGetTheirOwnChannelsBackByNameAndARenamedClipIsReported() throws Exception {
        Path adm = exportAdm("scene.wav", 44_100);
        DawProject project = new DawProject("Atmos", FORMAT);
        new AudioFileImporter(project).importFile(adm, 0.0);
        AudioClip bed = onlyClipOf(project, "Bed (Stereo)");
        AudioClip objectOne = onlyClipOf(project, "Object 1");
        AudioClip objectTwo = onlyClipOf(project, "Object 2");
        Track objectTwoTrack = trackNamed(project, "Object 2");
        AudioClip objectTwoSplit = objectTwo.splitAt(objectTwo.getStartBeat() + objectTwo.getDurationBeats() / 2);
        objectTwoTrack.addClip(objectTwoSplit);
        objectOne.setName("Lead vocal");
        float[][] bedImported = bed.getAudioData();
        float[][] objectTwoImported = objectTwo.getAudioData();
        for (Track track : project.getTracks()) {
            for (AudioClip clip : track.getClips()) {
                clip.setAudioData(null); // what a reopened project holds
            }
        }
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(session.sources()).containsExactly(new AssetReference.SingleFile(adm.toString()));
        assertThat(bed.getAudioData()).hasNumberOfRows(2);
        assertThat(Arrays.deepEquals(bed.getAudioData(), bedImported)).isTrue();
        assertThat(objectTwo.getAudioData()).hasNumberOfRows(1);
        assertThat(Arrays.deepEquals(objectTwo.getAudioData(), objectTwoImported)).isTrue();
        assertThat(objectTwoSplit.getName()).isEqualTo("Object 2 (split)");
        assertThat(objectTwoSplit.getAudioData()).isSameAs(objectTwo.getAudioData());
        assertThat(objectOne.getAudioData()).as("a renamed clip is never guessed").isNull();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.ADM_OBJECT_UNKNOWN);
            assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(adm.toString()));
            assertThat(row.holders()).as("a relink re-points every clip of the file, so the row names them all")
                    .extracting(MissingAsset.Holder::clipId)
                    .containsExactly(bed.getId(), objectOne.getId(), objectTwo.getId(), objectTwoSplit.getId());
            assertThat(row.silentHolders()).as("only the renamed clip has no audio")
                    .containsExactly(MissingAsset.Holder.of(trackNamed(project, "Object 1"), objectOne));
            assertThat(row.detail()).as("the subset and what the file has, in one clause")
                    .isEqualTo("1 of them selects no bed or object of the file (it has: Bed (Stereo), Object 1,"
                            + " Object 2)");
        });
        MissingAssetsReport report = MissingAssetsReport.EMPTY.withAll(result.rows());
        assertThat(report.silentClipCount()).as("the bed and the objects that loaded are not silent").isEqualTo(1);
        assertThat(report.missingAssetCount()).isEqualTo(1);
        assertThat(report.shorterAssetCount()).isZero();
    }

    @Test
    void anAdmImportsClipsOnAFileWithoutAdmMetadataGetNoAudioAndAreAllReported() throws Exception {
        Path plain = wav("scene.wav", RATE, RATE / 2, 2); // the ADM master, its axml chunk stripped
        DawProject project = new DawProject("Stripped", FORMAT);
        AudioClip bed = clip(project.createAudioTrack("Bed (Stereo)"), "Bed (Stereo)", 0.0, 1.0, plain);
        Track objectTrack = project.createAudioTrack("Object 1");
        AudioClip object = clip(objectTrack, "Object 1", 0.0, 0.5, plain);
        AudioClip objectSplit = object.splitAt(0.25);
        objectTrack.addClip(objectSplit);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).as("every clip would play both channels: none is given the file").isEmpty();
        assertThat(List.of(bed, object, objectSplit)).allSatisfy(clip -> assertThat(clip.getAudioData()).isNull());
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.ADM_OBJECT_UNKNOWN);
            assertThat(row.detail()).as("a track renders only its own width of the file, so no channel count")
                    .isEqualTo("the file has no ADM metadata; each clip would play the same channels instead of"
                            + " its own bed or object");
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(bed.getId(), object.getId(), objectSplit.getId());
            assertThat(row.silentHolders()).isEqualTo(row.holders());
        });
        assertThat(session.pendingCount()).isZero();
    }

    @Test
    void anAdmImportsClipsOnAFileWithoutAdmMetadataAreAllReportedWhenAnotherProgramRenamedOne() throws Exception {
        Path plain = wav("master.wav", RATE, RATE / 2, 2); // the ADM master as a DAWproject file left it: no axml
        DawProject project = new DawProject("Interchanged", FORMAT);
        AudioClip bed = clip(project.createAudioTrack("Bed (Stereo)"), "Bed (Stereo)", 0.0, 1.0, plain);
        AudioClip object = clip(project.createAudioTrack("Object 1"), "Object 1", 0.0, 1.0, plain);
        AudioClip vocal = clip(project.createAudioTrack("Vocal"), "Vocal", 0.0, 1.0, plain);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).as("a clip of another name does not make the master a plain file").isEmpty();
        assertThat(List.of(bed, object, vocal)).allSatisfy(clip -> assertThat(clip.getAudioData()).isNull());
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.ADM_OBJECT_UNKNOWN);
            assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(plain.toString()));
            assertThat(row.detail())
                    .isEqualTo("the file has no ADM metadata; each clip would play the same channels instead of"
                            + " its own bed or object");
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(bed.getId(), object.getId(), vocal.getId());
            assertThat(row.silentHolders()).as("every holder, whatever its name").isEqualTo(row.holders());
        });
        assertThat(session.pendingCount()).isZero();
    }

    @Test
    void aSingleObjectNamedClipOnAPlainFileLoadsAsAPlainFile() throws Exception {
        Path plain = wav("Object 1.wav", RATE, RATE / 2, 2); // a user's own file, named like an ADM object
        DawProject project = new DawProject("Plain", FORMAT);
        Track track = project.createAudioTrack("Object 1");
        AudioClip object = clip(track, "Object 1", 0.0, 1.0, plain);
        AudioClip split = object.splitAt(0.5);
        track.addClip(split);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).containsExactly(object, split);
        assertThat(split.getAudioData()).isSameAs(object.getAudioData());
        assertThat(object.getAudioData()).hasNumberOfRows(2);
        assertThat(result.rows()).as("one distinct ADM-like name is a plain file").isEmpty();
    }

    @Test
    void oneObjectNamedClipAndAClipOfAnotherNameOnAPlainFileLoadAsAPlainFile() throws Exception {
        Path plain = wav("take.wav", RATE, RATE / 2, 2);
        DawProject project = new DawProject("Plain", FORMAT);
        AudioClip object = clip(project.createAudioTrack("Object 1"), "Object 1", 0.0, 1.0, plain);
        AudioClip vocal = clip(project.createAudioTrack("Vocal"), "Vocal", 0.0, 1.0, plain);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).containsExactly(object, vocal);
        assertThat(vocal.getAudioData()).isSameAs(object.getAudioData());
        assertThat(object.getAudioData()).hasNumberOfRows(2);
        assertThat(result.rows()).as("one distinct ADM-like name is a plain file, whatever the other clips are named")
                .isEmpty();
    }

    @Test
    void aBedNamedClipSplitTwiceOnAPlainFileLoadsAsAPlainFile() throws Exception {
        Path plain = wav("Bed (5.1).wav", RATE, RATE / 2, 2); // a user's own bounce, named like an ADM bed
        DawProject project = new DawProject("Plain", FORMAT);
        Track track = project.createAudioTrack("Bed (5.1)");
        AudioClip bed = clip(track, "Bed (5.1)", 0.0, 1.0, plain);
        AudioClip split = bed.splitAt(0.5);
        track.addClip(split);
        AudioClip splitAgain = split.splitAt(0.75);
        track.addClip(splitAgain);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(List.of(split.getName(), splitAgain.getName())).as("fixture: the names splitting gives")
                .containsExactly("Bed (5.1) (split)", "Bed (5.1) (split) (split)");
        assertThat(result.attached()).containsExactly(bed, split, splitAgain);
        assertThat(split.getAudioData()).isSameAs(bed.getAudioData());
        assertThat(splitAgain.getAudioData()).isSameAs(bed.getAudioData());
        assertThat(bed.getAudioData()).hasNumberOfRows(2);
        assertThat(result.rows()).as("with every trailing (split) removed, one distinct ADM-like name: a plain file")
                .isEmpty();
    }

    @Test
    void anAdmImportsObjectClipsOnAFileWithoutAdmMetadataAreReportedWhenOnlyTheirSplitHalvesRemain()
            throws Exception {
        Path plain = wav("master.wav", RATE, RATE / 2, 2); // the ADM master, its axml chunk stripped
        DawProject project = new DawProject("Stripped", FORMAT);
        // Each object clip was split and its first half deleted.
        AudioClip objectOne = clip(project.createAudioTrack("Object 1"), "Object 1 (split)", 0.5, 0.5, plain);
        AudioClip objectTwo = clip(project.createAudioTrack("Object 2"), "Object 2 (split)", 0.5, 0.5, plain);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).as("both objects would play the same channels: neither is given the file")
                .isEmpty();
        assertThat(List.of(objectOne, objectTwo)).allSatisfy(clip -> assertThat(clip.getAudioData()).isNull());
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.ADM_OBJECT_UNKNOWN);
            assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(plain.toString()));
            assertThat(row.detail())
                    .isEqualTo("the file has no ADM metadata; each clip would play the same channels instead of"
                            + " its own bed or object");
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(objectOne.getId(), objectTwo.getId());
            assertThat(row.silentHolders()).isEqualTo(row.holders());
        });
        assertThat(session.pendingCount()).isZero();
    }

    @Test
    void aSourceShorterThanTheClipUsesLoadsAsItIsAndIsAChangedOnDiskRow() throws Exception {
        Path shortFile = wav("short.wav", RATE, RATE / 2, 2); // 0.5 s = 1 beat at 120 BPM
        Path exact = wav("exact.wav", RATE, RATE / 2, 2);
        DawProject project = new DawProject("Short", FORMAT);
        Track track = project.createAudioTrack("Pad");
        AudioClip tooLong = clip(track, "too long", 0.0, 3.0, shortFile);
        AudioClip fits = clip(track, "fits", 4.0, 1.0, exact);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(tooLong.getAudioData()).as("loaded as it is").isNotNull();
        assertThat(fits.getAudioData()).isNotNull();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.CHANGED_ON_DISK);
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId).containsExactly(tooLong.getId());
            assertThat(row.silentHolders()).as("its clip plays what the file holds").isEmpty();
            assertThat(row.detail()).isEqualTo(shorterDetail(0.5, 1.5));
        });
        MissingAssetsReport report = MissingAssetsReport.EMPTY.withAll(result.rows());
        assertThat(report.assetCount()).isEqualTo(1);
        assertThat(report.silentClipCount()).as("a shorter file's clips still play").isZero();
        assertThat(report.missingAssetCount()).as("a shorter file is not missing").isZero();
        assertThat(report.shorterAssetCount()).isEqualTo(1);
    }

    @Test
    void aSourceUsedBySeveralClipsWhereOnlySomeRunPastItsEndListsEveryClipAndSaysHowManyRunPast()
            throws Exception {
        Path halfSecond = wav("vox comp.wav", RATE, RATE / 2, 2); // 0.5 s = 1 beat at 120 BPM
        DawProject project = new DawProject("Comp", FORMAT);
        Track lead = project.createAudioTrack("Lead");
        AudioClip fits = clip(lead, "vox comp", 0.0, 1.0, halfSecond);
        AudioClip runsPast = clip(lead, "vox comp", 4.0, 3.0, halfSecond); // spans 1.5 s
        AudioClip offsetPast = clip(project.createAudioTrack("Double"), "vox comp", 0.0, 0.5, halfSecond);
        offsetPast.setSourceOffsetBeats(1.5); // spans 2 beats of source: 1.0 s
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(result.attached()).containsExactly(fits, runsPast, offsetPast);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.CHANGED_ON_DISK);
            assertThat(row.holders()).as("a relink replaces the audio of all three, so the row names all three")
                    .extracting(MissingAsset.Holder::clipId)
                    .containsExactly(fits.getId(), runsPast.getId(), offsetPast.getId());
            assertThat(row.silentHolders()).isEmpty();
            assertThat(row.detail()).as("the subset that runs past the end, then the longest span")
                    .isEqualTo("2 of them run past its end; " + shorterDetail(0.5, 1.5));
        });
        MissingAssetsReport report = MissingAssetsReport.EMPTY.withAll(result.rows());
        assertThat(report.silentClipCount()).isZero();
        assertThat(report.missingAssetCount()).isZero();
        assertThat(report.shorterAssetCount()).isEqualTo(1);
    }

    @Test
    void aTempoSlowedAfterTheImportRaisesAShorterRowThatClaimsOnlyTheShortfall() throws Exception {
        Path halfSecond = wav("cut at 120.wav", RATE, RATE / 2, 2); // one beat at 120 BPM
        DawProject project = new DawProject("Slowed", FORMAT);
        AudioClip clip = clip(project.createAudioTrack("Loop"), "cut at 120", 0.0, 1.0, halfSecond);
        // The file never changed; the song was slowed to 100 BPM after the import.
        project.getTransport().setTempo(100.0);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(clip.getAudioData()).as("loaded as it is").isNotNull();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.CHANGED_ON_DISK);
            assertThat(row.detail()).as("nothing persisted can tell a slowed tempo from a shorter file")
                    .isEqualTo(shorterDetail(0.5, 0.6));
        });
    }

    @Test
    void aClipIsJudgedAtTheTempoTheEnginePlaysItAtNotAFasterTempoLaterInTheMap() throws Exception {
        Path halfSecond = wav("half.wav", RATE, RATE / 2, 2);
        DawProject project = new DawProject("Tempo", FORMAT);
        // Imported at 120 BPM — one beat, half a second — then the song
        // starts at 60 BPM and speeds up to 120 BPM later on. The engine
        // plays every audio clip at the transport's tempo, 60 BPM: this one
        // for a second, its second half past the end of the file.
        project.getTransport().setTempo(60.0);
        project.getTransport().getTempoMap().addTempoChange(TempoChangeEvent.instant(100.0, 120.0));
        AudioClip clip = clip(project.createAudioTrack("Click"), "half", 0.0, 1.0, halfSecond);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(clip.getAudioData()).as("loaded as it is").isNotNull();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.CHANGED_ON_DISK);
            assertThat(row.silentHolders()).isEmpty();
            // "holds 0.50 s; the clip spans 1.00 s at the current tempo; loaded as it is"
            assertThat(row.detail()).as("at 60 BPM, not at the map's 120 BPM, the clip spans a second")
                    .isEqualTo(shorterDetail(0.5, 1.0));
        });
    }

    @Test
    void aSourceAboveHalfTheHeapIsTooLargeAndNotAttemptedWhileOneAboveTheBudgetStillLoads() throws Exception {
        Path huge = wav("huge.wav", RATE, 1_000, 1);
        Path big = wav("big.wav", RATE, 1_000, 1);
        Path small = wav("small.wav", RATE, 1_000, 1);
        AssetReference hugeRef = new AssetReference.SingleFile(huge.toString());
        AssetReference bigRef = new AssetReference.SingleFile(big.toString());
        AssetReference smallRef = new AssetReference.SingleFile(small.toString());
        DawProject project = new DawProject("Budget", FORMAT);
        Track track = project.createAudioTrack("T");
        AudioClip hugeClip = clip(track, "huge", 0.0, 0.01, huge);
        AudioClip bigClip = clip(track, "big", 1.0, 0.01, big);
        AudioClip smallClip = clip(track, "small", 2.0, 0.01, small);
        // Heap 1000 bytes: budget 250, too-large limit 500.
        RecordingSourceDecoder decoder = new RecordingSourceDecoder()
                .cost(hugeRef, 501).cost(bigRef, 300).cost(smallRef, 100);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator(decoder, 4, 1_000)
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationSession.AttachResult result = listener.applyAll(session);

        assertThat(decoder.decodes()).containsExactlyInAnyOrder(bigRef, smallRef);
        assertThat(bigClip.getAudioData()).as("above the budget, it runs alone but runs").isNotNull();
        assertThat(smallClip.getAudioData()).isNotNull();
        assertThat(hugeClip.getAudioData()).isNull();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.TOO_LARGE);
            assertThat(row.reference()).isEqualTo(hugeRef);
            assertThat(row.detail()).isEqualTo(
                    "needs about 1 MiB of memory to load; at most 1 MiB is allowed (half the application's memory"
                            + " limit)");
        });
    }

    @Test
    void aSourceWhoseOutcomeWasLostIsSettledAsUnreadableOnceTheEndIsReported() throws Exception {
        Path lost = wav("lost.wav", RATE, 1_000, 1);
        Path landed = wav("landed.wav", RATE, 1_000, 1);
        AssetReference lostRef = new AssetReference.SingleFile(lost.toString());
        DawProject project = new DawProject("Lost", FORMAT);
        Track track = project.createAudioTrack("T");
        AudioClip lostClip = clip(track, "lost", 0.0, 0.01, lost);
        AudioClip lostCopy = clip(track, "lost copy", 1.0, 0.01, lost);
        float[][] relinkedMeanwhile = new float[1][8];
        lostCopy.setAudioData(relinkedMeanwhile);
        AudioClip landedClip = clip(track, "landed", 2.0, 0.01, landed);
        CollectingRehydrationListener collected = new CollectingRehydrationListener();
        // A listener failure is contained by the session, and that outcome is never seen again.
        RehydrationListener losingOne = new RehydrationListener() {
            @Override
            public void onOutcome(RehydrationOutcome outcome) {
                if (outcome.source().equals(lostRef)) {
                    throw new IllegalStateException("test: the outcome is lost on its way to the owner");
                }
                collected.onOutcome(outcome);
            }

            @Override
            public void onFinished(Summary summary) {
                collected.onFinished(summary);
            }
        };

        RehydrationSession session = new ClipRehydrator()
                .start(project, MissingAssetsReport.EMPTY, 0.0, losingOne);
        collected.awaitFinished();
        collected.applyAll(session);
        assertThat(landedClip.getAudioData()).isNotNull();
        assertThat(session.isPending(lostRef)).as("fixture: the lost outcome leaves its source pending").isTrue();

        List<MissingAsset> rows = session.settlePending();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo(lostRef);
            assertThat(row.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
            assertThat(row.detail()).isEqualTo("loading did not complete");
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(lostClip.getId(), lostCopy.getId());
            assertThat(row.silentHolders()).as("a clip that has audio is not silent")
                    .extracting(MissingAsset.Holder::clipId).containsExactly(lostClip.getId());
        });
        assertThat(session.isPending(lostRef)).isFalse();
        assertThat(session.pendingCount()).as("nothing is loading any more").isZero();
        assertThat(session.settlePending()).as("settled once").isEmpty();
        assertThat(lostCopy.getAudioData()).isSameAs(relinkedMeanwhile);
        assertThat(project.isDirty()).isFalse();
    }

    @Test
    void settlingBeforeTheRunsEndWasReportedLeavesADecodingSourcePending() throws Exception {
        Path source = wav("held.wav", RATE, RATE / 2, 2);
        DawProject project = new DawProject("Early", FORMAT);
        AudioClip clip = clip(project.createAudioTrack("Keys"), "held", 0.0, 1.0, source);
        AssetReference reference = new AssetReference.SingleFile(source.toString());
        CountDownLatch release = new CountDownLatch(1);
        RecordingSourceDecoder decoder = new RecordingSourceDecoder().holdUntil(release);
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator(decoder, 1, UNLIMITED)
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        decoder.awaitFirstDecode();

        assertThat(session.settlePending()).as("the source is still decoding").isEmpty();
        assertThat(session.isPending(reference)).isTrue();

        release.countDown();
        listener.awaitFinished();
        listener.applyAll(session);
        assertThat(clip.getAudioData()).as("its outcome still lands").isNotNull();
        assertThat(session.settlePending()).isEmpty();
    }

    @Test
    void aSourceWhoseApplyFailedStaysPendingAndIsSettledOverTheClipsStillWithoutAudio() throws Exception {
        Path source = wav("broken.wav", RATE, 1_000, 1);
        AssetReference reference = new AssetReference.SingleFile(source.toString());
        DawProject project = new DawProject("Broken", FORMAT);
        Track track = project.createAudioTrack("T");
        AudioClip first = clip(track, "broken", 0.0, 0.01, source);
        AudioClip second = clip(track, "broken", 1.0, 0.01, source);
        // A decode that hands back a channel without samples: attaching it
        // fails part-way, after the first clip got the array.
        SourceDecoder channelWithoutSamples = new SourceDecoder() {
            @Override
            public long estimateCost(AssetReference asset, int projectSampleRate) {
                return 0L;
            }

            @Override
            public DecodedAudio decode(AssetReference asset, int projectSampleRate) {
                return new DecodedAudio.FileAudio(new float[][] {null}, projectSampleRate);
            }
        };
        CollectingRehydrationListener listener = new CollectingRehydrationListener();
        RehydrationSession session = new ClipRehydrator(channelWithoutSamples, 1, UNLIMITED)
                .start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        listener.awaitFinished();
        RehydrationOutcome outcome = listener.outcomes().getFirst();

        assertThatThrownBy(() -> session.apply(outcome)).as("fixture: applying the outcome fails")
                .isInstanceOf(RuntimeException.class);
        assertThat(second.getAudioData()).as("fixture: the failure came before the second clip").isNull();
        assertThat(session.isPending(reference)).as("a source whose apply failed is still owed").isTrue();

        List<MissingAsset> rows = session.settlePending();

        assertThat(rows).as("its silent clip is never left without a row").singleElement().satisfies(row -> {
            assertThat(row.reference()).isEqualTo(reference);
            assertThat(row.reason()).isEqualTo(MissingAssetReason.UNREADABLE);
            assertThat(row.detail()).isEqualTo("loading did not complete");
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(first.getId(), second.getId());
            assertThat(row.silentHolders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactly(second.getId());
        });
        assertThat(session.pendingCount()).isZero();
        assertThat(session.apply(outcome)).as("settled: the outcome has no effect any more")
                .isEqualTo(RehydrationSession.AttachResult.NONE);
        assertThat(second.getAudioData()).isNull();
    }

    @Test
    void sourcesDecodeUnderThePlayheadFirstThenAheadOfItThenTheRest() throws Exception {
        DawProject project = new DawProject("Order", FORMAT);
        Track track = project.createAudioTrack("T");
        Path before = wav("before.wav", RATE, 1_000, 1);
        Path aheadFar = wav("ahead-far.wav", RATE, 1_000, 1);
        Path covering = wav("covering.wav", RATE, 1_000, 1);
        Path aheadNear = wav("ahead-near.wav", RATE, 1_000, 1);
        Path earlier = wav("earlier.wav", RATE, 1_000, 1);
        Path both = wav("both.wav", RATE, 1_000, 1);
        clip(track, "before", 0.0, 4.0, before);
        clip(track, "ahead far", 10.0, 2.0, aheadFar);
        clip(track, "covering", 6.0, 4.0, covering);
        clip(track, "ahead near", 9.0, 1.0, aheadNear);
        clip(track, "earlier", 2.0, 1.0, earlier);
        clip(track, "both early", 1.0, 1.0, both);
        clip(track, "both late", 20.0, 1.0, both);
        RecordingSourceDecoder decoder = new RecordingSourceDecoder();
        CollectingRehydrationListener listener = new CollectingRehydrationListener();

        RehydrationSession session = new ClipRehydrator(decoder, 1, UNLIMITED)
                .start(project, MissingAssetsReport.EMPTY, 8.0, listener);
        listener.awaitFinished();

        List<AssetReference> expected = List.of(
                new AssetReference.SingleFile(covering.toString()),
                new AssetReference.SingleFile(aheadNear.toString()),
                new AssetReference.SingleFile(aheadFar.toString()),
                new AssetReference.SingleFile(both.toString()),
                new AssetReference.SingleFile(before.toString()),
                new AssetReference.SingleFile(earlier.toString()));
        assertThat(session.sources()).containsExactlyElementsOf(expected);
        assertThat(decoder.decodes()).as("one permit: decodes run in the scheduled order")
                .containsExactlyElementsOf(expected);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** The detail of a shorter-than-the-clip row (D24), seconds printed as the application prints them. */
    static String shorterDetail(double heldSeconds, double spanSeconds) {
        return MessageFormat.format("holds {0,number,0.00} s; the clip spans {1,number,0.00} s"
                + " at the current tempo; loaded as it is", heldSeconds, spanSeconds);
    }

    private Path wav(String name, int sampleRate, int frames, int channels) throws IOException {
        float[][] audio = new float[channels][frames];
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < frames; i++) {
                audio[ch][i] = (float) (0.25 * Math.sin(2.0 * Math.PI * 220.0 * i / sampleRate + ch));
            }
        }
        Path file = tempDir.resolve(name);
        WavExporter.write(audio, sampleRate, 16, DitherType.NONE, AudioMetadata.EMPTY, file);
        return file;
    }

    private static AudioClip clip(Track track, String name, double startBeat, double beats, Path source) {
        AudioClip clip = new AudioClip(name, startBeat, beats, source.toString());
        track.addClip(clip);
        return clip;
    }

    private Path exportAdm(String name, int sampleRate) throws IOException {
        Path output = tempDir.resolve(name);
        List<BedChannel> beds = List.of(
                new BedChannel("bed-L", SpeakerLabel.L),
                new BedChannel("bed-R", SpeakerLabel.R));
        List<AudioObject> objects = List.of(
                new AudioObject("obj-1", new ObjectMetadata(0.5, 0.0, 0.2, 0.1, 0.9)),
                new AudioObject("obj-2", new ObjectMetadata(-0.5, 0.0, 0.2, 0.1, 0.9)));
        AdmBwfExporter.export(beds, List.of(tone(4_096, 110.0, sampleRate), tone(4_096, 220.0, sampleRate)),
                objects, List.of(tone(4_096, 330.0, sampleRate), tone(4_096, 440.0, sampleRate)),
                SpeakerLayout.LAYOUT_STEREO, sampleRate, 24, AudioMetadata.EMPTY, output);
        return output;
    }

    private static float[] tone(int frames, double hz, int sampleRate) {
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            samples[i] = (float) (0.3 * Math.sin(2.0 * Math.PI * hz * i / sampleRate));
        }
        return samples;
    }

    private static Track trackNamed(DawProject project, String name) {
        return project.getTracks().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    private static AudioClip onlyClipOf(DawProject project, String trackName) {
        List<AudioClip> clips = trackNamed(project, trackName).getClips();
        assertThat(clips).hasSize(1);
        return clips.getFirst();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // keep waiting: the test releases it right after start returns
            }
        }
    }
}
