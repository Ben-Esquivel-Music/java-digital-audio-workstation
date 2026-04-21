package com.benesquivelmusic.daw.core.audio.processing;

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
    void purgeUnused_keepsMostRecentNAndDeletesOthers() throws IOException {
        ClipAssetHistory h = new ClipAssetHistory(2);
        Path p1 = touch("p1.wav");
        Path p2 = touch("p2.wav");
        Path p3 = touch("p3.wav");
        h.recordPriorAsset("c", p1);
        h.recordPriorAsset("c", p2);
        h.recordPriorAsset("c", p3);

        List<Path> deleted = h.purgeUnused();

        assertThat(deleted).containsExactly(p1);
        assertThat(p1).doesNotExist();
        assertThat(p2).exists();
        assertThat(p3).exists();
        assertThat(h.priorAssets("c")).containsExactly(p2, p3);
    }

    @Test
    void purgeUnused_retainsPinnedAssetsEvenIfBeyondRetention() throws IOException {
        ClipAssetHistory h = new ClipAssetHistory(1);
        Path p1 = touch("p1.wav");
        Path p2 = touch("p2.wav");
        Path p3 = touch("p3.wav");
        h.recordPriorAsset("c", p1);
        h.recordPriorAsset("c", p2);
        h.recordPriorAsset("c", p3);

        h.pin(p1); // simulate an undo/redo-stack reference

        List<Path> deleted = h.purgeUnused();

        assertThat(deleted).containsExactly(p2);
        assertThat(p1).exists();
        assertThat(p3).exists();
        assertThat(h.priorAssets("c")).containsExactly(p1, p3);
    }

    @Test
    void pinAndUnpin_areReferenceCounted() {
        ClipAssetHistory h = new ClipAssetHistory();
        Path p = tempDir.resolve("x.wav");
        h.pin(p);
        h.pin(p);
        h.unpin(p);
        assertThat(h.isPinned(p)).isTrue();
        h.unpin(p);
        assertThat(h.isPinned(p)).isFalse();
    }

    private Path touch(String name) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "x");
        return p;
    }
}
