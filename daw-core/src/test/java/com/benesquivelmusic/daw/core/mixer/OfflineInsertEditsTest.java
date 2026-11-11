package com.benesquivelmusic.daw.core.mixer;

import com.benesquivelmusic.daw.core.mixer.snapshot.MixerSnapshot;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.undo.UndoManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An offline insert is an ordinary member of its chain (story 334, studio
 * rulings E3 and E10.6): removing or moving it is an undoable edit that
 * carries its descriptor, and a mixer snapshot recalled after a reopen lands
 * its bypass states on the slots it captured them from, because the chain no
 * longer shifts on load.
 */
class OfflineInsertEditsTest {

    /** Compressor, an insert this build does not know, Delay — on the only track. */
    private static final String CHAIN = """
            <daw-project version="%d">
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Vox" type="AUDIO"/>
              </tracks>
              <mixer>
                <channels>
                  <channel>
                    <inserts>
                      <insert effect-type="COMPRESSOR" name="Compressor"/>
                      <insert effect-type="NOT_A_TYPE" name="Old FX"><parameter name="mix" value="0.5"/></insert>
                      <insert effect-type="DELAY" name="Delay"/>
                    </inserts>
                  </channel>
                </channels>
              </mixer>
            </daw-project>
            """.formatted(MigrationRegistry.CURRENT_VERSION);

    @Test
    void removeUndoRedoKeepsTheChainOrderAndTheDescriptor() throws Exception {
        MixerChannel channel = vox(new ProjectDeserializer().deserialize(CHAIN));
        InsertSlot offline = channel.getInsertSlots().get(1);
        OfflineInsert descriptor = offline.offline().orElseThrow();
        UndoManager undo = new UndoManager();

        undo.execute(new RemoveEffectAction(channel, 1));
        assertThat(names(channel)).containsExactly("Compressor", "Delay");

        undo.undo();
        assertThat(names(channel)).containsExactly("Compressor", "Old FX", "Delay");
        assertThat(channel.getInsertSlots().get(1)).as("the same offline insert").isSameAs(offline);
        assertThat(channel.getInsertSlots().get(1).offline()).contains(descriptor);

        undo.redo();
        assertThat(names(channel)).containsExactly("Compressor", "Delay");
    }

    @Test
    void aMoveIsUndoneWithItsDescriptor() throws Exception {
        MixerChannel channel = vox(new ProjectDeserializer().deserialize(CHAIN));
        OfflineInsert descriptor = channel.getInsertSlots().get(1).offline().orElseThrow();
        UndoManager undo = new UndoManager();

        undo.execute(new ReorderEffectAction(channel, 1, 2));
        assertThat(names(channel)).containsExactly("Compressor", "Delay", "Old FX");
        assertThat(channel.getInsertSlots().get(2).offline()).contains(descriptor);

        undo.undo();
        assertThat(names(channel)).containsExactly("Compressor", "Old FX", "Delay");
        assertThat(channel.getInsertSlots().get(1).offline()).contains(descriptor);
    }

    @Test
    void aSnapshotRecalledAfterReopenLandsOnTheSlotsItWasCapturedFrom() throws Exception {
        DawProject project = new ProjectDeserializer().deserialize(CHAIN);
        MixerChannel channel = vox(project);
        channel.setInsertBypassed(0, true);
        channel.setInsertBypassed(1, false);
        channel.setInsertBypassed(2, true);
        project.getMixerSnapshotManager().addSnapshot(MixerSnapshot.capture(project.getMixer(), "Verse"));

        DawProject reopened = new ProjectDeserializer().deserialize(new ProjectSerializer().serialize(project));
        MixerChannel reopenedChannel = vox(reopened);
        assertThat(names(reopenedChannel)).as("the chain did not shift").containsExactly("Compressor", "Old FX", "Delay");
        reopenedChannel.setInsertBypassed(0, false);
        reopenedChannel.setInsertBypassed(1, true);
        reopenedChannel.setInsertBypassed(2, false);

        reopened.getMixerSnapshotManager().getSnapshots().getFirst().applyTo(reopened.getMixer());

        assertThat(reopenedChannel.getInsertSlots()).extracting(InsertSlot::isBypassed)
                .containsExactly(true, false, true);
    }

    private static MixerChannel vox(DawProject project) {
        return project.getMixer().getChannels().getFirst();
    }

    private static List<String> names(MixerChannel channel) {
        return channel.getInsertSlots().stream().map(InsertSlot::getName).toList();
    }
}
