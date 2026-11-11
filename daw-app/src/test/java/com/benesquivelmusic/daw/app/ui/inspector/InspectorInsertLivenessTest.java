package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.mixer.OfflineInsert;
import com.benesquivelmusic.daw.core.mixer.SavedInsert;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.atomic.AtomicReference;
import static com.benesquivelmusic.daw.app.ui.snapshot.FxSnapshotTest.runOnFxThread;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(JavaFxToolkitExtension.class)
final class InspectorInsertLivenessTest {
    @Test
    void selectedChannelOrderBypassEditsAndAddFollowRealGraphChanges() {
        runOnFxThread(() -> {
            var dispatcher = new FxDispatcher();
            var drawer = new InspectorDrawer();
            var channel = new MixerChannel("Voice");
            var other = new MixerChannel("Bus");
            var first = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
            var second = InsertEffectFactory.createSlot(InsertEffectType.REVERB, 2, 48_000);
            channel.addInsert(first);
            channel.addInsert(second);
            var selected = new AtomicReference<>(channel);
            var opened = new ArrayList<InsertSlot>();
            var additions = new ArrayList<MixerChannel>();
            drawer.bindInserts(selected::get, (host, slot) -> opened.add(slot), additions::add, dispatcher);
            try {
                assertThat(names(drawer)).containsExactly(first.getName(), second.getName());
                channel.moveInsert(0, 1);
                channel.setInsertBypassed(0, true);
                dispatcher.pulse();
                assertThat(names(drawer)).containsExactly(second.getName(), first.getName());
                HBox row = (HBox) drawer.getInsertsSection().getRowsContainer().getChildren().getFirst();
                assertThat(row.getChildren().getFirst().getStyleClass()).contains("insert-dot-inactive");
                ((Button) row.getChildren().getLast()).fire();
                ((Button) row.getChildren().getLast()).fire();
                assertThat(opened).containsExactly(second, second);
                drawer.getInsertsSection().getAddButton().fire();
                assertThat(additions).containsExactly(channel);
                channel.removeInsert(0);
                dispatcher.pulse();
                assertThat(names(drawer)).containsExactly(first.getName());
                selected.set(other);
                dispatcher.pulse();
                assertThat(names(drawer)).isEmpty();
                other.addInsert(second);
                dispatcher.pulse();
                assertThat(names(drawer)).containsExactly(second.getName());
                selected.set(null);
                dispatcher.pulse();
                assertThat(names(drawer)).isEmpty();
                drawer.getInsertsSection().getAddButton().fire();
                assertThat(additions).containsExactly(channel, null);
            } finally { drawer.disposeInsertBinding(); dispatcher.dispose(); }
            return null;
        });
    }

    /**
     * An offline row's tooltip is formatted once per slot object and reused on
     * later pulses, also across a rebuild that a bypass change or a reorder
     * causes, while the bypass state is still read on every pulse; a fresh
     * binding formats it again, to the same text. The offline word's accessible
     * help is the row's tooltip String itself, so identity tells reuse from
     * reformatting.
     */
    @Test
    void offlineRowTooltipIsFormattedOncePerSlotWhileBypassStaysLive() {
        runOnFxThread(() -> {
            FxDispatcher dispatcher = new FxDispatcher();
            InspectorDrawer drawer = new InspectorDrawer();
            MixerChannel channel = new MixerChannel("Voice");
            InsertSlot online = InsertEffectFactory.createSlot(InsertEffectType.COMPRESSOR, 2, 48_000);
            InsertSlot offline = offlineSlot();
            channel.addInsert(online);
            channel.addInsert(offline);
            drawer.bindInserts(() -> channel, (host, slot) -> { }, host -> { }, dispatcher);
            try {
                List<Node> rows0 = List.copyOf(drawer.getInsertsSection().getRowsContainer().getChildren());
                String help0 = offlineWord(drawer).getAccessibleHelp();
                assertThat(help0).isNotBlank();

                dispatcher.pulse();
                List<Node> rows1 = drawer.getInsertsSection().getRowsContainer().getChildren();
                assertThat(rows1).hasSameSizeAs(rows0);
                for (int i = 0; i < rows0.size(); i++) {
                    assertThat(rows1.get(i)).isSameAs(rows0.get(i));
                }

                channel.setInsertBypassed(0, true);
                dispatcher.pulse();
                HBox onlineRow = (HBox) drawer.getInsertsSection().getRowsContainer().getChildren().getFirst();
                assertThat(onlineRow).isNotSameAs(rows0.getFirst());
                assertThat(onlineRow.getChildren().getFirst().getStyleClass()).contains("insert-dot-inactive");
                assertThat(offlineWord(drawer).getAccessibleHelp()).isSameAs(help0);

                channel.moveInsert(0, 1);
                dispatcher.pulse();
                assertThat(names(drawer)).containsExactly(offline.getName(), online.getName());
                assertThat(offlineWord(drawer).getAccessibleHelp()).isSameAs(help0);

                drawer.disposeInsertBinding();
                drawer.bindInserts(() -> channel, (host, slot) -> { }, host -> { }, dispatcher);
                assertThat(offlineWord(drawer).getAccessibleHelp()).isEqualTo(help0).isNotSameAs(help0);
            } finally { drawer.disposeInsertBinding(); dispatcher.dispose(); }
            return null;
        });
    }

    /** An insert an open kept offline because the build has no effect type {@code NOT_A_TYPE}. */
    private static InsertSlot offlineSlot() {
        SequencedMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("effect-type", "NOT_A_TYPE");
        attributes.put("name", "Old FX");
        return InsertSlot.ofOffline("Old FX", 2, new OfflineInsert(
                new OfflineCause.UnknownEffectType("NOT_A_TYPE"), new SavedInsert(attributes, List.of()), false));
    }

    private static Button offlineWord(InspectorDrawer drawer) {
        return drawer.getInsertsSection().getRowsContainer().getChildren().stream()
                .map(node -> (HBox) node).flatMap(row -> row.getChildren().stream())
                .filter(node -> node.getStyleClass().contains("inspector-row-offline-info"))
                .map(node -> (Button) node).findFirst().orElseThrow();
    }

    private static List<String> names(InspectorDrawer drawer) {
        return drawer.getInsertsSection().getRowsContainer().getChildren().stream()
                .map(node -> (HBox) node).map(row -> ((Label) row.getChildren().get(1)).getText()).toList();
    }
}
