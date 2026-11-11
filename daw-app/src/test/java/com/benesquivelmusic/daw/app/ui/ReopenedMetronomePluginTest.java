package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.plugin.AudioFormatPluginContext;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginGraph;
import com.benesquivelmusic.daw.core.plugin.MetronomePlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.recording.Metronome;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 334 (critique J3) — a first-party Metronome plugin insert reopens as
 * the plugin (studio ruling E11), and the read binds it to the project's own
 * metronome only so its slot can be built; the engine renders the
 * application's. Once installed, the plugin drives the engine's metronome —
 * its settings and its insert's stored bypass reach the click the engine
 * plays — and the project's own metronome is left alone. That every door
 * binds it right after it builds the engine's metronome is pinned by
 * {@code SourcePinnedWiringTest} ({@code MainController} is never
 * FXML-loaded in tests).
 */
class ReopenedMetronomePluginTest {

    @Test
    void aReopenedMetronomePluginDrivesTheEnginesMetronome() throws Exception {
        DawProject saved = new DawProject("Click", AudioFormat.CD_QUALITY);
        InsertSlot inserted = new BuiltInPluginGraph(ProcessorRegistry.getInstance()).createSlot(
                new MetronomePlugin(), new AudioFormatPluginContext(saved.getFormat()), saved.getMetronome());
        saved.getMixer().getMasterChannel().addInsert(inserted);
        saved.getMixer().getMasterChannel().setInsertBypassed(0, true);

        DawProject reopened = new ProjectDeserializer().deserialize(new ProjectSerializer().serialize(saved));
        InsertSlot slot = reopened.getMixer().getMasterChannel().getInsertSlots().getFirst();
        assertThat(slot.getPlugin()).as("E11: the plugin reopens as the plugin").isInstanceOf(MetronomePlugin.class);
        MetronomePlugin plugin = (MetronomePlugin) slot.getPlugin();
        assertThat(plugin.getMetronome()).as("fixture: the read bound the project's own metronome")
                .isSameAs(reopened.getMetronome());
        assertThat(slot.isBypassed()).as("fixture: the stored bypass reopened").isTrue();

        Metronome engine = new Metronome(reopened.getFormat().sampleRate(), reopened.getFormat().channels());
        engine.setEnabled(true);
        MainController.bindMetronomePlugins(reopened, engine);

        assertThat(plugin.getMetronome()).isSameAs(engine);
        assertThat(engine.isClickEnabled()).as("the insert's stored bypass silences the engine's click").isFalse();
        assertThat(reopened.getMetronome().isClickEnabled() == reopened.getMetronome().isEnabled())
                .as("the project's own metronome is released").isTrue();

        plugin.setVolume(0.25f);
        assertThat(engine.getVolume()).as("the plugin's settings drive the engine's metronome").isEqualTo(0.25f);
        reopened.getMixer().getMasterChannel().setInsertBypassed(0, false);
        assertThat(engine.isClickEnabled()).as("and so does its bypass, from now on").isTrue();

        MainController.bindMetronomePlugins(reopened, engine);
        assertThat(engine.isClickEnabled()).as("binding again is harmless").isTrue();
        reopened.disposeInsertsWhenQuiescent().join();
    }
}
