package com.benesquivelmusic.daw.core.audio.processing;

import com.benesquivelmusic.daw.core.undo.CompoundUndoableAction;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import com.benesquivelmusic.daw.core.undo.UndoableAction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipAssetHistoryTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultRetention_isFive() {
        assertThat(new ClipAssetHistory().retention()).isEqualTo(5);
    }

    @Test
    void customRetention_mustBePositive() {
        assertThatThrownBy(() -> new ClipAssetHistory(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordPriorAsset_isVisibleViaPriorAssets() {
        ClipAssetHistory h = new ClipAssetHistory();
        Path p = tempDir.resolve("a.wav");
        h.recordPriorAsset("clip-1", p);
        assertThat(h.priorAssets("clip-1")).containsExactly(p);
    }

    @Test
    void purgeUnused_deletesOnlyManagedFilesOutsideRetention() throws IOException {
        ClipAssetHistory h = new ClipAssetHistory(2);
        Path p1 = touch("p1.wav");
        Path p2 = touch("p2.wav");
        Path p3 = touch("p3.wav");
        h.recordPriorAsset("c", p1);
        h.recordPriorAsset("c", p2);
        h.recordPriorAsset("c", p3);
        h.markManaged(p1);
        h.markManaged(p2);
        h.markManaged(p3);

        List<Path> deleted = h.purgeUnused();

        assertThat(deleted).containsExactly(p1);
        assertThat(p1).doesNotExist();
        assertThat(p2).exists();
        assertThat(p3).exists();
        assertThat(h.priorAssets("c")).containsExactly(p2, p3);
    }

    @Test
    void purgeUnused_neverDeletesUnmanagedExternalFiles() throws IOException {
        // Simulates the first destructive op: the clip's original
        // user-imported file becomes a prior asset but is not managed
        // by the DAW and must never be deleted.
        ClipAssetHistory h = new ClipAssetHistory(1);
        Path external = touch("user-original.wav");
        Path managedLater = touch("Reversed-uuid.wav");
        h.recordPriorAsset("c", external);
        h.recordPriorAsset("c", managedLater);
        // Only the DAW-generated file is managed.
        h.markManaged(managedLater);

        List<Path> deleted = h.purgeUnused();

        assertThat(deleted).isEmpty();
        assertThat(external).exists();
        assertThat(managedLater).exists();
        // The external file is dropped from the manifest (outside
        // retention) but preserved on disk.
        assertThat(h.priorAssets("c")).containsExactly(managedLater);
    }

    @Test
    void syncPinsFromHistory_pinsAssetsReferencedByLiveActions() throws IOException {
        ClipAssetHistory h = new ClipAssetHistory(1);
        Path a = touch("a.wav");
        Path b = touch("b.wav");

        UndoManager um = new UndoManager();
        um.execute(new StubReferencingAction(List.of(a)));
        h.syncPinsFromHistory(um);
        assertThat(h.isPinned(a)).isTrue();
        assertThat(h.isPinned(b)).isFalse();

        um.execute(new StubReferencingAction(List.of(b)));
        h.syncPinsFromHistory(um);
        assertThat(h.pinnedAssets()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void syncPinsFromHistory_releasesAssetsOfDiscardedActions() {
        // When UndoManager trims the oldest action (maxHistory=1), the
        // asset it referenced should be released automatically — no leak.
        ClipAssetHistory h = new ClipAssetHistory();
        Path a = tempDir.resolve("a.wav");
        Path b = tempDir.resolve("b.wav");

        UndoManager um = new UndoManager(1);
        um.addHistoryListener(m -> h.syncPinsFromHistory(m));
        um.execute(new StubReferencingAction(List.of(a)));
        assertThat(h.isPinned(a)).isTrue();

        um.execute(new StubReferencingAction(List.of(b))); // trims the first action
        assertThat(h.isPinned(a)).isFalse();
        assertThat(h.isPinned(b)).isTrue();
    }

    @Test
    void syncPinsFromHistory_alsoWalksCompoundChildren() {
        ClipAssetHistory h = new ClipAssetHistory();
        Path a = tempDir.resolve("a.wav");
        Path b = tempDir.resolve("b.wav");

        UndoManager um = new UndoManager();
        um.execute(new CompoundUndoableAction("batch", List.of(
                new StubReferencingAction(List.of(a)),
                new StubReferencingAction(List.of(b)))));
        h.syncPinsFromHistory(um);

        assertThat(h.pinnedAssets()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void purgeUnused_retainsPinnedManagedAssetsEvenBeyondRetention() throws IOException {
        ClipAssetHistory h = new ClipAssetHistory(1);
        Path p1 = touch("p1.wav");
        Path p2 = touch("p2.wav");
        Path p3 = touch("p3.wav");
        h.recordPriorAsset("c", p1);
        h.recordPriorAsset("c", p2);
        h.recordPriorAsset("c", p3);
        h.markManaged(p1);
        h.markManaged(p2);
        h.markManaged(p3);

        UndoManager um = new UndoManager();
        um.execute(new StubReferencingAction(List.of(p1)));
        h.syncPinsFromHistory(um);

        List<Path> deleted = h.purgeUnused();

        assertThat(deleted).containsExactly(p2);
        assertThat(p1).exists();
        assertThat(p3).exists();
        assertThat(h.priorAssets("c")).containsExactly(p1, p3);
    }

    private Path touch(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "x");
        return p;
    }

    /** Test double implementing both {@link UndoableAction} and {@link ClipAssetReferencing}. */
    private static final class StubReferencingAction
            implements UndoableAction, ClipAssetReferencing {
        private final List<Path> paths;

        StubReferencingAction(List<Path> paths) {
            this.paths = List.copyOf(paths);
        }

        @Override public String description() { return "stub"; }
        @Override public void execute() { /* no-op */ }
        @Override public void undo() { /* no-op */ }
        @Override public List<Path> referencedAssets() { return paths; }
    }
}
