package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.PluginManagerDialog;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;

import javafx.scene.Node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334 (studio-engineer ruling E9) — the installed plugins persist
 * between sessions, so story 303's truthful no-persistence notice comes out
 * of the Manage Plugins dialog entirely, its style rule with it, and nothing
 * replaces it: a working state needs no notice.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class PluginManagerShowsNoSessionNoticeTest {

    private static final String STYLES_CSS = "/com/benesquivelmusic/daw/app/ui/styles.css";

    @Test
    void theNoPersistenceNoticeAndItsStyleAreGone() throws IOException {
        PluginRegistry registry = new PluginRegistry();
        try {
            Node content = runOnFxThread(() -> new PluginManagerDialog(registry).getDialogPane().getContent());

            assertThat(runOnFxThread(() -> content.lookupAll(".plugin-manager-notice"))).isEmpty();
            List<String> texts = runOnFxThread(() -> PluginNodes.labelTexts(content));
            assertThat(texts).as("non-vacuity: the dialog's labels were read").contains("Installed Plugins:");
            assertThat(texts).noneMatch(text -> text != null && text.contains("between sessions"));
        } finally {
            registry.disposeAll();
        }

        String css;
        try (InputStream in = PluginManagerShowsNoSessionNoticeTest.class.getResourceAsStream(STYLES_CSS)) {
            assertThat(in).as("styles.css is on the test path").isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(css).as("non-vacuity: the plugin manager's other rules are there").contains(".plugin-manager-info");
        assertThat(css).doesNotContain(".plugin-manager-notice");
    }
}
