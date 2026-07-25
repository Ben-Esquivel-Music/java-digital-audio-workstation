package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.sdk.editor.PluginEditorFactory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 302 §8.3 item 7 — the Arpeggiator's orphaned view is folded in, not
 * stranded. {@code ArpeggiatorPluginView} shipped in daw-app but was never
 * referenced by the (now deleted) built-in {@code switch}; this test proves
 * the orphan class is gone from daw-app sources and that
 * {@code ArpeggiatorPlugin} now yields a contract-driven
 * {@link PluginEditorFactory.Panel} editor instead.
 */
final class ArpeggiatorOrphanViewRetiredTest {

    @Test
    void theOrphanedViewClassIsDeletedFromDawApp() throws IOException {
        Path mainSources = SourceScanSupport.locateDawAppModule().resolve("src/main/java");
        assertThat(Files.isDirectory(mainSources))
                .as("daw-app main sources must live under %s", mainSources).isTrue();

        List<Path> scanned = new ArrayList<>();
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSources)) {
            files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        scanned.add(p);
                        if (p.getFileName().toString().equals("ArpeggiatorPluginView.java")) {
                            offenders.add(p);
                        }
                    });
        }
        assertThat(scanned)
                .as("the daw-app source scan must visit a non-trivial number of files")
                .hasSizeGreaterThan(50);
        assertThat(offenders)
                .as("ArpeggiatorPluginView must be deleted (story 302 §8.3 item 7)")
                .isEmpty();
    }

    @Test
    void theArpeggiatorNowYieldsAPanelEditor() {
        assertThat(new ArpeggiatorPlugin().editorFactory())
                .isInstanceOf(PluginEditorFactory.Panel.class);
    }
}
