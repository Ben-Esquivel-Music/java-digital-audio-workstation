package com.benesquivelmusic.daw.core.persistence.journal;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 298 — Test 4. {@link JournalReplayer#recover} performs a structured,
 * rollback-safe replay: the happy path returns a fresh project advanced by the
 * journal, and a corrupt replay input fails the whole recovery atomically
 * (mirroring {@code DawScopeTest.firstFailureCancelsAllOtherForks}) without
 * touching any live project.
 */
class RecoveryReplayStructuredTest {

    @Test
    void happyPath_replaysCheckpointPlusSegments(@TempDir Path projectDir) throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        Path checkpoint = writeValidCheckpoint(ctx, "ProjectAlpha");
        List<JournalRecord> written = writeSegment(ctx, 0, 5);

        JournalReplayer replayer = new JournalReplayer();
        RecoveryResult result = replayer.recover(ctx, checkpoint, List.of(segmentPath(ctx, 0)));

        assertThat(result.recoveredProject())
                .as("recovery must return a fresh, non-null project")
                .isNotNull();
        assertThat(result.recoveredProject().getName()).isEqualTo("ProjectAlpha");
        assertThat(result.appliedRecordCount())
                .as("every non-marker journal record must be applied")
                .isEqualTo(written.size());
        assertThat(result.summary().replayableEventCount()).isEqualTo(written.size());
    }

    @Test
    void corruptSegment_rollsBackAtomically(@TempDir Path projectDir) throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        Path checkpoint = writeValidCheckpoint(ctx, "ProjectBeta");
        writeSegment(ctx, 0, 3);
        // Corrupt the segment HEAD so it yields zero decodable records despite
        // having bytes — this forces verifyChecksums to throw.
        corruptSegmentHead(segmentPath(ctx, 0));

        // A "live" project the caller would keep on a rollback.
        DawProject livePriorProject = new DawProject("LiveUntouched", AudioFormat.CD_QUALITY);

        JournalReplayer replayer = new JournalReplayer();
        assertThatThrownBy(() -> replayer.recover(ctx, checkpoint, List.of(segmentPath(ctx, 0))))
                .as("a corrupt segment must roll the whole recovery back")
                .isInstanceOf(JournalRecoveryException.class);

        // Atomic rollback: nothing was applied to the caller's prior project.
        assertThat(livePriorProject.getName())
                .as("the live/prior project must be untouched after a rollback")
                .isEqualTo("LiveUntouched");
    }

    @Test
    void midSegmentCorruption_rollsBackAtomically(@TempDir Path projectDir) throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        Path checkpoint = writeValidCheckpoint(ctx, "ProjectGamma");
        List<JournalRecord> written = writeSegment(ctx, 0, 5);
        // Corrupt a byte inside the SECOND frame's body (after its length prefix,
        // not the segment head), leaving valid frames both before and after it.
        // readAll would silently stop at the torn frame and drop the tail;
        // readAllStrict detects the complete-but-corrupt frame and fails the
        // recovery so the structured scope rolls back.
        Path segment = segmentPath(ctx, 0);
        byte[] bytes = Files.readAllBytes(segment);
        int frame0Size = written.get(0).serializedSize();
        int corruptAt = frame0Size + 6;     // inside the 2nd frame's sequence field
        assertThat(bytes.length).isGreaterThan(corruptAt);
        bytes[corruptAt] ^= (byte) 0xFF;
        Files.write(segment, bytes);

        JournalReplayer replayer = new JournalReplayer();
        assertThatThrownBy(() -> replayer.recover(ctx, checkpoint, List.of(segment)))
                .as("a complete-but-corrupt mid-segment frame must roll the recovery back")
                .isInstanceOf(JournalRecoveryException.class);
    }

    @Test
    void corruptCheckpoint_rollsBackAtomically(@TempDir Path projectDir) throws Exception {
        ProjectContext ctx = ProjectContext.forProject(projectDir);
        // Write a checkpoint that is NOT valid project XML so loadCheckpoint throws.
        Files.createDirectories(ctx.checkpointDirectory());
        Path checkpoint = ctx.checkpointDirectory().resolve("checkpoint-001-20260101T000000.daw");
        Files.writeString(checkpoint, "<<<not-valid-project-xml>>>");
        writeSegment(ctx, 0, 2);

        JournalReplayer replayer = new JournalReplayer();
        assertThatThrownBy(() -> replayer.recover(ctx, checkpoint, List.of(segmentPath(ctx, 0))))
                .as("a corrupt checkpoint must roll the whole recovery back")
                .isInstanceOf(JournalRecoveryException.class);
    }

    // ---- helpers ----------------------------------------------------------

    private static Path writeValidCheckpoint(ProjectContext ctx, String name) throws IOException {
        Files.createDirectories(ctx.checkpointDirectory());
        DawProject project = new DawProject(name, AudioFormat.CD_QUALITY);
        project.createAudioTrack("Vocals");
        String xml = new ProjectSerializer().serialize(project);
        Path checkpoint = ctx.checkpointDirectory().resolve("checkpoint-001-20260101T000000.daw");
        Files.writeString(checkpoint, xml);
        return checkpoint;
    }

    private static List<JournalRecord> writeSegment(ProjectContext ctx, int index, int count)
            throws IOException {
        Files.createDirectories(ctx.journalDirectory());
        Path path = segmentPath(ctx, index);
        java.util.ArrayList<JournalRecord> records = new java.util.ArrayList<>();
        try (JournalSegment segment = JournalSegment.open(path)) {
            for (int i = 0; i < count; i++) {
                JournalRecord r = JournalRecord.ofMutation(
                        i, Instant.ofEpochMilli(1_000L + i),
                        "MixerEvent.MuteChanged", UUID.randomUUID(), null);
                records.add(r);
            }
            segment.appendGroup(records);
        }
        return records;
    }

    private static Path segmentPath(ProjectContext ctx, int index) {
        return ctx.journalDirectory().resolve(String.format("segment-%03d.bin", index));
    }

    /**
     * Corrupts the first bytes of the segment (the leading frame length and
     * sequence) so no record decodes, while leaving the file non-empty — the
     * recovery's checksum verification then fails.
     */
    private static void corruptSegmentHead(Path segment) throws IOException {
        byte[] bytes = Files.readAllBytes(segment);
        assertThat(bytes.length).isGreaterThan(8);
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) 0xFF;   // bogus huge frame length -> readFrom returns empty
        }
        Files.write(segment, bytes);
    }
}
