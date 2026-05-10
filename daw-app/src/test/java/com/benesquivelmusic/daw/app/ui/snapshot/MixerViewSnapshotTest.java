package com.benesquivelmusic.daw.app.ui.snapshot;

import com.benesquivelmusic.daw.app.ui.MixerView;
import com.benesquivelmusic.daw.app.ui.theme.ThemeRegistry;
import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.project.DawProject;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Visual-regression snapshot tests for {@link MixerView}.
 *
 * <p>Renders the mixer for a fixed minimal {@link DawProject} (master
 * strip + one audio track + one MIDI track) so the snapshot is
 * deterministic.</p>
 */
class MixerViewSnapshotTest extends FxSnapshotTest {

    static Stream<String> bundledThemes() {
        return ThemeRegistry.BUNDLED_IDS.stream();
    }

    @Disabled("Goldens captured on Linux CI; JavaFX font-metric drift on other "
            + "platforms exceeds ImageDiff's default shift radius. Re-enable "
            + "once snapshot scenes use a bundled deterministic font, or "
            + "once per-OS goldens are introduced.")
    @ParameterizedTest(name = "[{index}] theme={0}")
    @MethodSource("bundledThemes")
    void mixerWithTwoTracks(String themeId) {
        MixerView view = runOnFxThread(() -> {
            DawProject project = new DawProject("Snapshot", AudioFormat.CD_QUALITY);
            project.createAudioTrack("Vocals");
            project.createMidiTrack("Piano");
            return new MixerView(project);
        });
        assertMatchesSnapshot(view, "mixerWithTwoTracks", themeId);
    }
}
