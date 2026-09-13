package com.benesquivelmusic.daw.app.ui.inspector;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;
import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.ArrayList;
import java.util.List;
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

    private static List<String> names(InspectorDrawer drawer) {
        return drawer.getInsertsSection().getRowsContainer().getChildren().stream()
                .map(node -> (HBox) node).map(row -> ((Label) row.getChildren().get(1)).getText()).toList();
    }
}
