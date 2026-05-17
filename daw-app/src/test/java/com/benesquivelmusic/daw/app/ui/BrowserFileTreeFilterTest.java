package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.BrowserPanel.FileNode;

import javafx.scene.control.TreeItem;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 275 (review S1) — the persistent search query is scoped to the
 * FILES tab too: {@link BrowserPanel#buildFilteredItem} prunes the
 * file-system model to matching leaves plus their ancestor directories,
 * keeps a directory whose own name matches, and expands survivors so
 * hits are visible; a blank query keeps the full, collapsed tree.
 *
 * <p>Pure model test — no FX scene and no disk I/O (a synthetic
 * {@link FileNode}), so none of the headless-JavaFX pitfalls apply and
 * the result is deterministic on every platform.</p>
 */
class BrowserFileTreeFilterTest {

    private static FileNode model() {
        return new FileNode("home", true, List.of(
                new FileNode("Samples", true, List.of(
                        new FileNode("kick.wav", false, List.of()),
                        new FileNode("snare.wav", false, List.of()))),
                new FileNode("Documents", true, List.of(
                        new FileNode("readme.wav", false, List.of()))),
                new FileNode("loop_kick.wav", false, List.of())));
    }

    @Test
    void blankQueryKeepsEverythingCollapsed() {
        TreeItem<String> item = BrowserPanel.buildFilteredItem(model(), "");

        assertThat(item).isNotNull();
        assertThat(item.getValue()).isEqualTo("home");
        assertThat(item.isExpanded())
                .as("blank query → collapsed, as before the search wiring")
                .isFalse();
        assertThat(item.getChildren()).extracting(TreeItem::getValue)
                .containsExactly("Samples", "Documents", "loop_kick.wav");
        assertThat(item.getChildren().get(0).getChildren())
                .extracting(TreeItem::getValue)
                .containsExactly("kick.wav", "snare.wav");
    }

    @Test
    void nonBlankQueryPrunesToMatchesAndExpands() {
        TreeItem<String> item = BrowserPanel.buildFilteredItem(model(), "kick");

        assertThat(item).as("home retained — it has a matching descendant").isNotNull();
        assertThat(item.isExpanded())
                .as("surviving directories expand so hits are visible")
                .isTrue();
        // Documents is pruned (no descendant contains "kick"); Samples
        // is kept but only its matching leaf; the top-level
        // loop_kick.wav leaf is kept.
        assertThat(item.getChildren()).extracting(TreeItem::getValue)
                .containsExactly("Samples", "loop_kick.wav");
        TreeItem<String> samples = item.getChildren().get(0);
        assertThat(samples.getChildren()).extracting(TreeItem::getValue)
                .containsExactly("kick.wav");
        assertThat(samples.isExpanded()).isTrue();
    }

    @Test
    void nonMatchingQueryPrunesEntireBranch() {
        assertThat(BrowserPanel.buildFilteredItem(model(), "zzz-no-match"))
                .as("nothing matches → whole subtree pruned")
                .isNull();
    }

    @Test
    void directoryNameMatchKeepsDirectoryEvenWithoutMatchingLeaves() {
        TreeItem<String> item = BrowserPanel.buildFilteredItem(model(), "sample");

        assertThat(item).isNotNull();
        // "Samples" dir name matches "sample" → kept even though its
        // leaves (kick.wav / snare.wav) do not contain "sample". This
        // also proves matching is case-insensitive w.r.t. node casing
        // (lower-case query vs. capitalised "Samples").
        assertThat(item.getChildren()).extracting(TreeItem::getValue)
                .containsExactly("Samples");
    }
}
