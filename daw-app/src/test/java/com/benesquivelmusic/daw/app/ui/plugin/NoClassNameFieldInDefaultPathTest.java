package com.benesquivelmusic.daw.app.ui.plugin;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.PluginManagerDialog;
import com.benesquivelmusic.daw.app.ui.icons.DawIcon;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.sdk.editor.PluginCategory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;
import java.util.Arrays;

import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 303 — the re-skinned {@link PluginManagerDialog} shows the registry as a
 * list with descriptor-driven icons and <em>no</em> always-visible class-name /
 * JAR-path text fields, and the ~60-branch keyword-sniffing {@code pluginDetailIcon}
 * (and its {@code pluginTypeIcon} helper) are gone: the icon is now resolved by
 * {@link PluginIcons} from the descriptor category / hint.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class NoClassNameFieldInDefaultPathTest {

    @Test
    void managerDialogHasNoTextFieldAndKeywordSniffingIsGone() {
        PluginRegistry registry = new PluginRegistry();
        PluginManagerDialog dialog = runOnFxThread(() -> new PluginManagerDialog(registry));

        assertThat(PluginNodes.findTextFields(dialog.getDialogPane().getContent()))
                .as("the re-skinned manager exposes no class-name / JAR-path text field")
                .isEmpty();

        assertThat(Arrays.stream(PluginManagerDialog.class.getDeclaredMethods())
                .map(Method::getName).toList())
                .as("the keyword-sniffing pluginDetailIcon / pluginTypeIcon helpers are deleted")
                .doesNotContain("pluginDetailIcon", "pluginTypeIcon");
    }

    @Test
    void pluginIconsResolvesFromCategoryNotName() {
        DawIcon reverb = PluginIcons.resolve(PluginCategory.REVERB_AND_DELAY, "");
        DawIcon dynamics = PluginIcons.resolve(PluginCategory.DYNAMICS, "");

        assertThat(reverb)
                .as("a Reverb & Delay plugin resolves to the REVERB glyph via its category")
                .isEqualTo(DawIcon.REVERB);
        assertThat(dynamics)
                .as("a Dynamics plugin resolves to the COMPRESSOR glyph via its category")
                .isEqualTo(DawIcon.COMPRESSOR);
        assertThat(reverb)
                .as("different categories produce different glyphs — no name-keyword sniffing")
                .isNotEqualTo(dynamics);
    }
}
