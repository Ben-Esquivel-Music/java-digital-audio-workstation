package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardManagerTest {

    @Test
    void shouldStartEmpty() {
        var clipboard = new ClipboardManager();
        assertThat(clipboard.hasContent()).isFalse();
    }

    @Test
    void markCopiedShouldSetContentAvailable() {
        var clipboard = new ClipboardManager();
        clipboard.markCopied();
        assertThat(clipboard.hasContent()).isTrue();
    }

    @Test
    void clearShouldRemoveContent() {
        var clipboard = new ClipboardManager();
        clipboard.markCopied();
        clipboard.clear();
        assertThat(clipboard.hasContent()).isFalse();
    }

    @Test
    void multipleCopiesShouldKeepContentAvailable() {
        var clipboard = new ClipboardManager();
        clipboard.markCopied();
        clipboard.markCopied();
        assertThat(clipboard.hasContent()).isTrue();
    }

    @Test
    void clearOnEmptyClipboardShouldBeNoOp() {
        var clipboard = new ClipboardManager();
        clipboard.clear();
        assertThat(clipboard.hasContent()).isFalse();
    }
}
