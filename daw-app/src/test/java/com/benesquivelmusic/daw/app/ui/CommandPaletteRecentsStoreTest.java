package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandPaletteRecentsStoreTest {

    @Test
    void loadReturnsEmptyForMissingFile(@TempDir Path tmp) {
        var store = new CommandPaletteRecentsStore(tmp.resolve("missing.json"));
        assertThat(store.load()).isEmpty();
    }

    @Test
    void recordExecutionPersistsAndDeduplicates(@TempDir Path tmp) {
        var file = tmp.resolve("recents.json");
        var store = new CommandPaletteRecentsStore(file);
        store.recordExecution("PLAY_STOP");
        store.recordExecution("NEW_PROJECT");
        store.recordExecution("PLAY_STOP"); // duplicate — should bubble to front

        List<String> result = store.load();
        assertThat(result).containsExactly("PLAY_STOP", "NEW_PROJECT");

        // Reload via a fresh instance to confirm persistence.
        var reloaded = new CommandPaletteRecentsStore(file);
        assertThat(reloaded.load()).containsExactly("PLAY_STOP", "NEW_PROJECT");
    }

    @Test
    void capsAtMaxRecents(@TempDir Path tmp) {
        var store = new CommandPaletteRecentsStore(tmp.resolve("recents.json"));
        for (int i = 0; i < CommandPaletteRecentsStore.MAX_RECENTS + 3; i++) {
            store.recordExecution("ACTION_" + i);
        }
        List<String> result = store.load();
        assertThat(result).hasSize(CommandPaletteRecentsStore.MAX_RECENTS);
        // Most recent first.
        assertThat(result.get(0)).isEqualTo("ACTION_" + (CommandPaletteRecentsStore.MAX_RECENTS + 2));
    }

    @Test
    void corruptFileTreatedAsEmpty(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("recents.json");
        java.nio.file.Files.writeString(file, "this is not json");
        var store = new CommandPaletteRecentsStore(file);
        assertThat(store.load()).isEmpty();
    }

    @Test
    void escapesQuotesAndBackslashes() {
        // White-box test of the JSON helpers used by the store.
        String json = CommandPaletteRecentsStore.writeJsonArray(
                List.of("a\"b", "back\\slash", "tab\there"));
        List<String> roundTripped = CommandPaletteRecentsStore.parseJsonArray(json);
        assertThat(roundTripped).containsExactly("a\"b", "back\\slash", "tab\there");
    }
}
