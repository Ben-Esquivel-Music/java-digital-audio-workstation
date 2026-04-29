package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.sdk.ui.PanelState;
import com.benesquivelmusic.daw.sdk.ui.Rectangle2D;
import com.benesquivelmusic.daw.sdk.ui.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceStoreTest {

    private static Workspace sample(String name) {
        Map<String, PanelState> states = new LinkedHashMap<>();
        states.put("arrangement", new PanelState(true, 1.5, 100, 200));
        states.put("mixer", new PanelState(false, 1.0, 0, 0));
        Map<String, Rectangle2D> bounds = new LinkedHashMap<>();
        bounds.put("arrangement", new Rectangle2D(0, 0, 800, 600));
        return new Workspace(name, states, List.of("audio-settings"), bounds);
    }

    @Test
    void saveThenListRoundTrips(@TempDir Path tmp) {
        var store = new WorkspaceStore(tmp);
        Workspace ws = sample("Mixing");
        store.save(ws);

        List<Workspace> all = store.listAll();
        assertThat(all).hasSize(1);

        Workspace loaded = all.getFirst();
        assertThat(loaded.name()).isEqualTo("Mixing");
        assertThat(loaded.panelStates()).containsKey("arrangement");
        assertThat(loaded.panelStates().get("arrangement").zoom()).isEqualTo(1.5);
        assertThat(loaded.panelStates().get("arrangement").scrollX()).isEqualTo(100.0);
        assertThat(loaded.panelStates().get("mixer").visible()).isFalse();
        assertThat(loaded.openDialogs()).containsExactly("audio-settings");
        assertThat(loaded.panelBounds().get("arrangement"))
                .isEqualTo(new Rectangle2D(0, 0, 800, 600));
    }

    @Test
    void saveOverwritesByName(@TempDir Path tmp) {
        var store = new WorkspaceStore(tmp);
        store.save(sample("Mix"));

        Map<String, PanelState> updated = new LinkedHashMap<>();
        updated.put("mixer", new PanelState(true, 2.0, 0, 0));
        store.save(new Workspace("Mix", updated, List.of(), Map.of()));

        List<Workspace> all = store.listAll();
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().panelStates()).containsOnlyKeys("mixer");
        assertThat(all.getFirst().panelStates().get("mixer").zoom()).isEqualTo(2.0);
    }

    @Test
    void deleteRemovesFile(@TempDir Path tmp) {
        var store = new WorkspaceStore(tmp);
        store.save(sample("Editing"));
        assertThat(store.delete("Editing")).isTrue();
        assertThat(store.listAll()).isEmpty();
    }

    @Test
    void exportImportRoundTrips(@TempDir Path tmp) throws Exception {
        var store = new WorkspaceStore(tmp);
        Path target = tmp.resolve("share/exported.json");
        Workspace original = sample("Spatial");

        store.exportTo(original, target);
        assertThat(Files.exists(target)).isTrue();

        Workspace imported = store.importFrom(target);
        assertThat(imported.name()).isEqualTo("Spatial");
        assertThat(imported.panelStates()).isEqualTo(original.panelStates());
        assertThat(imported.openDialogs()).isEqualTo(original.openDialogs());
        assertThat(imported.panelBounds()).isEqualTo(original.panelBounds());
    }

    @Test
    void importRejectsCorruptFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("corrupt.json");
        Files.writeString(file, "not json at all");
        var store = new WorkspaceStore(tmp);
        assertThatThrownBy(() -> store.importFrom(file)).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void listAllEmptyForMissingDirectory(@TempDir Path tmp) {
        var store = new WorkspaceStore(tmp.resolve("nonexistent"));
        assertThat(store.listAll()).isEmpty();
    }

    @Test
    void slugifyHandlesSpacesAndPunctuation() {
        assertThat(WorkspaceStore.slugify("My Mix!")).isEqualTo("my-mix");
        assertThat(WorkspaceStore.slugify("Mastering / Final"))
                .isEqualTo("mastering-final");
        assertThat(WorkspaceStore.slugify("/../etc/passwd"))
                .doesNotContain("/")
                .doesNotContain("..");
        assertThat(WorkspaceStore.slugify("---")).isEqualTo("workspace");
    }

    @Test
    void parseTolerantOfMalformedJson() {
        // The parser returns null on garbage input rather than throwing.
        assertThat(WorkspaceStore.parseJson("not even close")).isNull();
        assertThat(WorkspaceStore.parseJson("")).isNull();
        assertThat(WorkspaceStore.parseJson("{")).isNull();
    }

    @Test
    void parseAcceptsHandWrittenJson() {
        String json = """
                {"name":"Hand",
                 "panelStates":{"mixer":{"visible":true,"zoom":1.25,"scrollX":10,"scrollY":20}},
                 "openDialogs":["x","y"],
                 "panelBounds":{"mixer":{"x":1,"y":2,"width":3,"height":4}}}
                """;
        Workspace ws = WorkspaceStore.parseJson(json);
        assertThat(ws).isNotNull();
        assertThat(ws.name()).isEqualTo("Hand");
        assertThat(ws.panelStates().get("mixer").zoom()).isEqualTo(1.25);
        assertThat(ws.openDialogs()).containsExactly("x", "y");
        assertThat(ws.panelBounds().get("mixer"))
                .isEqualTo(new Rectangle2D(1, 2, 3, 4));
    }
}
