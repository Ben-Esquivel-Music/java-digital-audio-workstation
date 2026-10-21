package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.persistence.AssetReference;
import com.benesquivelmusic.daw.core.persistence.ClipRehydrator;
import com.benesquivelmusic.daw.core.persistence.MissingAsset;
import com.benesquivelmusic.daw.core.persistence.MissingAssetReason;
import com.benesquivelmusic.daw.core.persistence.MissingAssetsReport;
import com.benesquivelmusic.daw.core.persistence.RehydrationListener;
import com.benesquivelmusic.daw.core.persistence.RehydrationOutcome;
import com.benesquivelmusic.daw.core.persistence.RehydrationSession;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.session.dawproject.DawProjectSessionImporter;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.session.SessionImportResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Import DAWproject Exchange keeps each clip's name and file path exactly as
 * the DAWproject file gives them, so an ADM master's clips can come back with
 * one of them renamed when the file, in this application's DAWproject layout,
 * was edited outside the application (Persistence Integrity book §4.3;
 * decisions D30, D49). Headless: the importer, the controller's
 * {@code applySessionData} and the clip rehydrator need no FX thread.
 */
class DawProjectImportOfRenamedAdmClipsTest {

    /** A guard against a hung run, not a tolerance: a healthy run ends in milliseconds. */
    private static final long FINISH_GUARD_SECONDS = 60;

    @TempDir
    Path tempDir;

    @Test
    void anAdmMastersClipsImportedWithOneRenamedLoadNothingFromAFileWithoutAdmMetadataUnderOneRow()
            throws Exception {
        Path master = OpenProjectFixture.wav(tempDir.resolve("audio").resolve("master.wav"), 48_000, 0.5, 220.0);
        // master.wav is a plain stereo WAV: the ADM master as it was left outside the application,
        // without its axml chunk.
        Path exchange = Files.writeString(tempDir.resolve("project.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project version="1.0" name="Atmos" sampleRate="48000.0">
                    <Transport>
                        <Tempo value="120.0"/>
                        <TimeSignature numerator="4" denominator="4"/>
                    </Transport>
                    <Structure>
                """ + audioTrack("Bed (Stereo)", master) + audioTrack("Object 1", master)
                + audioTrack("Vocal", master) + """
                    </Structure>
                </Project>
                """);

        SessionImportResult imported = new DawProjectSessionImporter().importSession(exchange);
        DawProject project = new DawProject("Untitled", OpenProjectFixture.PROJECT_FORMAT);
        new SessionInterchangeController().applySessionData(imported.sessionData(), project);
        List<AudioClip> clips = clipsOf(project);
        assertThat(clips).extracting(AudioClip::getName).containsExactly("Bed (Stereo)", "Object 1", "Vocal");
        assertThat(clips).allSatisfy(clip -> {
            assertThat(clip.getSourceFilePath()).as("the import keeps the master's path").isEqualTo(master.toString());
            assertThat(clip.getAudioData()).as("the import loads no audio").isNull();
        });

        CollectingListener listener = new CollectingListener();
        RehydrationSession session = new ClipRehydrator().start(project, MissingAssetsReport.EMPTY, 0.0, listener);
        assertThat(listener.finished.await(FINISH_GUARD_SECONDS, TimeUnit.SECONDS))
                .as("the rehydration run reports its end").isTrue();
        List<AudioClip> attached = new ArrayList<>();
        List<MissingAsset> rows = new ArrayList<>();
        for (RehydrationOutcome outcome : listener.outcomes) {
            RehydrationSession.AttachResult result = session.apply(outcome);
            attached.addAll(result.attached());
            rows.addAll(result.rows());
        }

        assertThat(attached).as("each clip would play the same channels of the master").isEmpty();
        assertThat(clips).allSatisfy(clip -> assertThat(clip.getAudioData()).isNull());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo(MissingAssetReason.ADM_OBJECT_UNKNOWN);
            assertThat(row.reference()).isEqualTo(new AssetReference.SingleFile(master.toString()));
            assertThat(row.detail())
                    .isEqualTo("the file has no ADM metadata; each clip would play the same channels instead of"
                            + " its own bed or object");
            assertThat(row.holders()).extracting(MissingAsset.Holder::clipId)
                    .containsExactlyElementsOf(clips.stream().map(AudioClip::getId).toList());
            assertThat(row.silentHolders()).as("the renamed clip too").isEqualTo(row.holders());
        });
    }

    /** One audio track of a DAWproject file whose one clip, named as the track, plays {@code file}. */
    private static String audioTrack(String name, Path file) {
        return """
                        <Track name="%1$s" contentType="audio">
                            <Lanes>
                                <Clips>
                                    <Clip name="%1$s" time="0.0" duration="1.0">
                                        <Audio>
                                            <File path="%2$s"/>
                                        </Audio>
                                    </Clip>
                                </Clips>
                            </Lanes>
                        </Track>
                """.formatted(name, xmlAttribute(file.toString()));
    }

    private static String xmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private static List<AudioClip> clipsOf(DawProject project) {
        List<AudioClip> clips = new ArrayList<>();
        for (Track track : project.getTracks()) {
            clips.addAll(track.getClips());
        }
        return clips;
    }

    /** Queues what the run reports, as the application's listener does; the test thread applies it. */
    private static final class CollectingListener implements RehydrationListener {

        private final List<RehydrationOutcome> outcomes = new CopyOnWriteArrayList<>();
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void onOutcome(RehydrationOutcome outcome) {
            outcomes.add(outcome);
        }

        @Override
        public void onFinished(Summary summary) {
            finished.countDown();
        }
    }
}
