package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationException;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RecoveryTemps#inspect} reads a newer head temp before it judges it: complete (the
 * candidate), incomplete (left for a plain open to delete), or impossible to assess, which fails the
 * inspection with nothing deleted.
 */
class InterruptedSaveInspectionTest {
    private static final Instant SAVED = Instant.parse("2026-10-27T09:00:00Z");
    private static final String UNREADABLE = " could not be read (";
    private static final String UNREADABLE_END =
            "), so it was not checked for recovery; nothing was deleted. Make it readable and open the project again";

    @TempDir
    Path folder;

    @Test
    void aNewerHeadTempThatCannotBeReadFailsInspectionAndIsKept() throws IOException {
        head();
        Path temp = temp("0123456789abcdef", document("Interrupted"), SAVED.plusSeconds(60));
        byte[] written = Files.readAllBytes(temp);

        try (Access _ = denyReading(temp)) {
            assertThatThrownBy(() -> RecoveryTemps.inspect(folder))
                    .isInstanceOf(IOException.class)
                    .hasMessageStartingWith("The interrupted save " + temp + UNREADABLE)
                    .hasMessageEndingWith(UNREADABLE_END)
                    .hasCauseInstanceOf(IOException.class)
                    .satisfies(failure -> {
                        String message = failure.getMessage();
                        String reason = message.substring(("The interrupted save " + temp + UNREADABLE).length(),
                                message.length() - UNREADABLE_END.length());
                        assertThat(reason).as("the reason never repeats the path").isNotBlank()
                                .doesNotContain(temp.getFileName().toString());
                    });
        }

        assertThat(Files.readAllBytes(temp)).as("nothing was deleted").isEqualTo(written);
    }

    @Test
    void unreadableTempsOlderThanTheHeadOrOfAnotherFileAreNeverReadAndStayCleanable() throws IOException {
        head();
        Path older = temp("1111111111111111", document("Older"), SAVED.minusSeconds(60));
        Path otherFile = Files.writeString(folder.resolve(".notes.json.2222222222222222.tmp"), "{}");
        Files.setLastModifiedTime(otherFile, FileTime.from(SAVED.plusSeconds(60)));

        try (Access _ = denyReading(older); Access _ = denyReading(otherFile)) {
            RecoveryTemps inspected = RecoveryTemps.inspect(folder);

            assertThat(inspected.newerHead()).isNull();
            assertThat(inspected.files()).containsExactlyInAnyOrder(older, otherFile);
        }
    }

    @Test
    void aNewerTempWhoseBytesAreNotUtf8IsIncompleteThoughALenientDecodeWouldReadIt() throws IOException {
        head();
        byte[] bytes = withInvalidUtf8(document("Song MARK"), "MARK");
        Path temp = Files.write(folder.resolve(".project.daw.0123456789abcdef.tmp"), bytes);
        Files.setLastModifiedTime(temp, FileTime.from(SAVED.plusSeconds(60)));
        assertThat(new ProjectDeserializer().deserialize(new String(bytes, StandardCharsets.UTF_8), folder).getName())
                .as("a lenient decode substitutes U+FFFD and reads the project").contains("\uFFFD");

        assertIncomplete(temp);
    }

    @Test
    void aNewerCompleteTempWithASchemaNewerThanThisBuildFailsInspectionAndIsKept() throws IOException {
        head();
        int newer = MigrationRegistry.CURRENT_VERSION + 1;
        String xml = document("From a newer build").replaceFirst(
                "version=\"" + MigrationRegistry.CURRENT_VERSION + "\"", "version=\"" + newer + "\"");
        assertThat(MigrationRegistry.readVersion(ProjectDeserializer.parseXml(xml))).isEqualTo(newer);
        Path temp = temp("0123456789abcdef", xml, SAVED.plusSeconds(60));

        assertThatThrownBy(() -> RecoveryTemps.inspect(folder))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("The interrupted save " + temp + " could not be checked for recovery (")
                .hasMessageContaining("newer than this build")
                .hasMessageEndingWith("); nothing was deleted")
                .hasCauseInstanceOf(MigrationException.class);
        assertThat(Files.readString(temp)).as("nothing was deleted").isEqualTo(xml);
    }

    @Test
    void aNewerTempCutShortBeforeItsEndMarkerIsIncomplete() throws IOException {
        head();
        String xml = document("Cut short");

        assertIncomplete(temp("0123456789abcdef", xml.substring(0, xml.length() / 2), SAVED.plusSeconds(60)));
    }

    @Test
    void aNewerTempWithBothMarkersThatIsNotWellFormedIsIncomplete() throws IOException {
        head();

        assertIncomplete(temp("0123456789abcdef",
                "<daw-project version=\"2\"><metadata><name>Torn</daw-project>", SAVED.plusSeconds(60)));
    }

    /**
     * Each case gives the newest time to a different temp, so no rule that takes the first or last
     * one listed passes all three while the temps are listed in the same order in each case, as
     * they are by name on Windows and by a per-filesystem hash on Linux ext4.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void theNewestCompleteNewerTempIsTheCandidate(int newest) throws IOException {
        head();
        List<Long> olderSeconds = new ArrayList<>(List.of(20L, 40L));
        List<Path> temps = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            long seconds = index == newest ? 60L : olderSeconds.removeFirst();
            temps.add(temp(String.valueOf(index).repeat(16), document(index == newest ? "Newest" : "Older"),
                    SAVED.plusSeconds(seconds)));
        }

        RecoveryTemps inspected = RecoveryTemps.inspect(folder);

        assertThat(inspected.newerHead()).isEqualTo(temps.get(newest));
        assertThat(inspected.files()).containsExactlyInAnyOrderElementsOf(temps);
    }

    /**
     * Story 390 owns what an open should do with a complete interrupted save that has no
     * {@code project.daw} beside it. Until then the head's time is read outside every
     * classification, so the inspection fails, which fails the open, and nothing is deleted.
     */
    @Test
    void aCompleteHeadTempWithNoProjectFileBesideItFailsInspectionAndIsKept() throws IOException {
        Path temp = temp("0123456789abcdef", document("Interrupted"), SAVED.plusSeconds(60));
        byte[] written = Files.readAllBytes(temp);

        assertThatThrownBy(() -> RecoveryTemps.inspect(folder)).isInstanceOf(IOException.class);

        assertThat(Files.readAllBytes(temp)).as("nothing was deleted").isEqualTo(written);
    }

    private void assertIncomplete(Path temp) throws IOException {
        RecoveryTemps inspected = RecoveryTemps.inspect(folder);

        assertThat(inspected.newerHead()).as("no candidate").isNull();
        assertThat(inspected.files()).as("left for a plain open to delete").containsExactly(temp);
    }

    private void head() throws IOException {
        Path head = Files.writeString(folder.resolve(ProjectManager.PROJECT_FILE_NAME), document("Saved"));
        Files.setLastModifiedTime(head, FileTime.from(SAVED));
    }

    private Path temp(String id, String content, Instant writtenAt) throws IOException {
        Path temp = Files.writeString(folder.resolve(".project.daw." + id + ".tmp"), content);
        Files.setLastModifiedTime(temp, FileTime.from(writtenAt));
        return temp;
    }

    private static String document(String name) throws IOException {
        return new ProjectSerializer().serialize(new DawProject(name, AudioFormat.CD_QUALITY));
    }

    /** {@code xml} encoded as UTF-8, with {@code mark} replaced by a lead byte that lacks its continuation. */
    private static byte[] withInvalidUtf8(String xml, String mark) {
        int at = xml.indexOf(mark);
        assertThat(at).isPositive();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(xml.substring(0, at).getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(new byte[] {(byte) 0xC3, (byte) 0x28});
        bytes.writeBytes(xml.substring(at + mark.length()).getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    /** Read access taken from a file; closing gives it back, so {@code @TempDir} can clean up. */
    @FunctionalInterface
    private interface Access extends AutoCloseable {
        @Override
        void close() throws IOException;
    }

    /**
     * Makes {@code file} unreadable to its owner while its folder still lets it be deleted: a POSIX
     * mode without read, or, where the file system has no POSIX permissions (Windows), a DENY ACE for
     * READ_DATA. Skips the test where the file stays readable, as it does for a superuser.
     */
    private static Access denyReading(Path file) throws IOException {
        Access restore;
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            Set<PosixFilePermission> before = posix.readAttributes().permissions();
            Set<PosixFilePermission> withoutRead = EnumSet.noneOf(PosixFilePermission.class);
            withoutRead.addAll(before);
            withoutRead.removeAll(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ));
            posix.setPermissions(withoutRead);
            restore = () -> posix.setPermissions(before);
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            assumeTrue(acl != null, "the file system can deny reading a file");
            List<AclEntry> before = acl.getAcl();
            List<AclEntry> denyingReads = new ArrayList<>();
            denyingReads.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            denyingReads.addAll(before);
            acl.setAcl(denyingReads);
            restore = () -> acl.setAcl(before);
        }
        boolean readable = Files.isReadable(file);
        if (readable) restore.close();
        assumeFalse(readable, "a superuser may read a file its owner cannot");
        return restore;
    }
}
