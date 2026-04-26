package com.benesquivelmusic.daw.core.snapshot;

import com.benesquivelmusic.daw.core.undo.UndoableAction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotBrowserServiceTest {

    @Test
    void createsAndReturnsUserCheckpoints() {
        SnapshotBrowserService svc = new SnapshotBrowserService();

        SnapshotEntry e = svc.createUserCheckpoint("Before mastering", "<project/>");

        assertThat(e.kind()).isEqualTo(SnapshotKind.USER_CHECKPOINT);
        assertThat(e.label()).isEqualTo("Before mastering");
        assertThat(e.loadContent()).isEqualTo("<project/>");
        assertThat(svc.getEntries()).containsExactly(e);
    }

    @Test
    void recordsAndClearsUndoSnapshots() {
        SnapshotBrowserService svc = new SnapshotBrowserService();
        UndoableAction action = new TestAction("Add track");

        SnapshotEntry e = svc.recordUndoSnapshot(action, "<project/>");

        assertThat(e.kind()).isEqualTo(SnapshotKind.UNDO_POINT);
        assertThat(e.label()).isEqualTo("Add track");
        assertThat(svc.getEntries()).contains(e);

        svc.clearSession();
        assertThat(svc.getEntries()).doesNotContain(e);
    }

    @Test
    void removesUserCheckpoints() {
        SnapshotBrowserService svc = new SnapshotBrowserService();
        SnapshotEntry e = svc.createUserCheckpoint("X", "data");

        assertThat(svc.removeUserCheckpoint(e)).isTrue();
        assertThat(svc.getEntries()).isEmpty();
        assertThat(svc.removeUserCheckpoint(e)).isFalse();
    }

    @Test
    void timelineIsSortedByTimestamp(@TempDir Path tmp) throws Exception {
        Clock fixed = Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"),
                ZoneId.of("UTC"));
        SnapshotBrowserService svc =
                new SnapshotBrowserService(Duration.ofDays(7), fixed);

        SnapshotEntry first = svc.createUserCheckpoint("first", "a");
        SnapshotEntry second = svc.recordUndoSnapshot(new TestAction("edit"), "b");

        Path autosaveDir = tmp.resolve("autosaves");
        Files.createDirectories(autosaveDir);
        Path file = autosaveDir.resolve("autosave-1.daw");
        Files.writeString(file, "auto");
        Files.setLastModifiedTime(file,
                FileTime.from(Instant.parse("2026-04-26T09:30:00Z")));
        svc.addAutosaveDirectory(autosaveDir);

        List<SnapshotEntry> entries = svc.getEntries();

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).kind()).isEqualTo(SnapshotKind.AUTOSAVE);
        assertThat(entries).contains(first, second);
    }

    @Test
    void filtersOutAutosavesOlderThanRetention(@TempDir Path tmp) throws Exception {
        Clock fixed = Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"),
                ZoneId.of("UTC"));
        SnapshotBrowserService svc =
                new SnapshotBrowserService(Duration.ofDays(7), fixed);

        Path stale = tmp.resolve("stale.daw");
        Files.writeString(stale, "old");
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.parse("2026-04-01T10:00:00Z")));

        Path fresh = tmp.resolve("fresh.daw");
        Files.writeString(fresh, "new");
        Files.setLastModifiedTime(fresh,
                FileTime.from(Instant.parse("2026-04-25T10:00:00Z")));

        svc.addAutosaveDirectory(tmp);

        List<SnapshotEntry> entries = svc.getEntries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).label()).isEqualTo("fresh.daw");
        assertThat(entries.get(0).loadContent()).isEqualTo("new");
    }

    @Test
    void purgeExpiredAutosavesDeletesOldFiles(@TempDir Path tmp) throws IOException {
        Clock fixed = Clock.fixed(Instant.parse("2026-04-26T10:00:00Z"),
                ZoneId.of("UTC"));
        SnapshotBrowserService svc =
                new SnapshotBrowserService(Duration.ofDays(7), fixed);

        Path stale = tmp.resolve("old.daw");
        Files.writeString(stale, "old");
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.parse("2026-04-01T10:00:00Z")));

        Path fresh = tmp.resolve("new.daw");
        Files.writeString(fresh, "new");
        Files.setLastModifiedTime(fresh,
                FileTime.from(Instant.parse("2026-04-25T10:00:00Z")));

        svc.addAutosaveDirectory(tmp);

        int deleted = svc.purgeExpiredAutosaves();
        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void purgeAllAutosavesDeletesEverything(@TempDir Path tmp) throws IOException {
        SnapshotBrowserService svc = new SnapshotBrowserService();
        Files.writeString(tmp.resolve("a.daw"), "a");
        Files.writeString(tmp.resolve("b.daw"), "b");
        svc.addAutosaveDirectory(tmp);

        int deleted = svc.purgeAllAutosaves();
        assertThat(deleted).isEqualTo(2);
        try (var stream = Files.list(tmp)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void restoringSnapshotProducesIdenticalContent() {
        SnapshotBrowserService svc = new SnapshotBrowserService();
        String content = "<project><track id=\"1\"/></project>";
        SnapshotEntry checkpoint = svc.createUserCheckpoint("A", content);

        // Loading the same snapshot twice yields bit-identical content
        // (the issue acceptance criterion: "restoring a known snapshot
        //  produces bit-identical project state vs loading that snapshot
        //  fresh").
        String loadedFirst = checkpoint.loadContent();
        String loadedAgain = svc.getEntries().get(0).loadContent();

        assertThat(loadedFirst).isEqualTo(content);
        assertThat(loadedAgain).isEqualTo(content);
        assertThat(loadedFirst).isEqualTo(loadedAgain);
    }

    private record TestAction(String desc) implements UndoableAction {
        @Override public void execute() {}
        @Override public void undo() {}
        @Override public String description() { return desc; }
    }
}
