package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;
import com.benesquivelmusic.daw.sdk.audio.performance.TrackCpuBudget;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.app.ui.DeviceLossRecordingFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-track CPU Budget dialog's note under its policy combo (story 338,
 * ruling E194): no policy is applied yet, so the note says what going over the
 * budget does, one text for the three policies and one for {@code DoNothing},
 * following the combo from the value it opens on; both labels stay as they
 * are; the note wraps inside the dialog's width. Built and read on the FX
 * thread, asserted off it.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ChannelCpuBudgetDialogTest {

    private static final String POLICY_NOT_APPLIED = "This policy is not applied: when the track goes over this"
            + " budget, or the master CPU budget in Audio Settings marks it, its processing is not reduced; it gets"
            + " a ⚠ on its mixer strip, a notification and a Budget entry in the Xrun Log.";
    private static final String DO_NOTHING = "With DoNothing, going over budget changes and shows nothing: the"
            + " track's processing is not reduced, and there is no ⚠ on its mixer strip, no notification and no"
            + " Xrun Log entry.";

    @Test
    void theNoteFollowsThePolicyFromTheValueTheDialogOpensOnAndTheLabelsStay() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        List<String> notes = new ArrayList<>();
        List<String> labels = onFx(() -> {
            ChannelCpuBudgetDialog dialog = new ChannelCpuBudgetDialog(channel, null, () -> true);
            @SuppressWarnings("unchecked")
            ComboBox<String> policy = (ComboBox<String>) only(dialog, ComboBox.class);
            Label note = note(dialog);
            notes.add(note.getText());
            for (String name : List.of("BypassExpensive", "ReduceOversampling", "SubstituteSimpleKernel",
                    "DoNothing")) {
                policy.setValue(name);
                notes.add(note.getText());
            }
            return descendants(dialog.getDialogPane().getContent()).filter(Label.class::isInstance)
                    .map(Label.class::cast).filter(label -> label != note).map(Label::getText).toList();
        });

        assertThat(notes).as("opened on DoNothing, then each other policy, then DoNothing again")
                .containsExactly(DO_NOTHING, POLICY_NOT_APPLIED, POLICY_NOT_APPLIED, POLICY_NOT_APPLIED, DO_NOTHING);
        assertThat(labels).as("the slider's and the policy's labels")
                .contains("Max CPU fraction per block:", "Degradation policy:");
    }

    @Test
    void aDialogOpenedOnAPolicyShowsThatPolicysNote() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        channel.setCpuBudget(new TrackCpuBudget(0.4, new DegradationPolicy.ReduceOversampling(2)));

        String note = onFx(() -> note(new ChannelCpuBudgetDialog(channel, null, () -> true)).getText());

        assertThat(note).isEqualTo(POLICY_NOT_APPLIED);
    }

    @Test
    void theNoteWrapsInsideTheDialogsWidth() throws Exception {
        MixerChannel channel = new MixerChannel("Voice");
        channel.setCpuBudget(new TrackCpuBudget(0.4, new DegradationPolicy.BypassExpensive()));

        double[] measured = onFx(() -> {
            ChannelCpuBudgetDialog dialog = new ChannelCpuBudgetDialog(channel, null, () -> true);
            DialogPane pane = dialog.getDialogPane();
            if (pane.getScene() == null) {
                new Scene(new StackPane(pane));
            }
            pane.applyCss();
            double width = pane.prefWidth(-1);
            pane.resize(width, pane.prefHeight(width));
            pane.layout();
            Label note = note(dialog);
            note.applyCss();
            return new double[] {pane.getWidth(), note.getWidth(), note.getHeight(), note.prefWidth(-1),
                    note.prefHeight(-1)};
        });

        assertThat(measured[1]).as("the note is no wider than the dialog").isPositive()
                .isLessThanOrEqualTo(measured[0]);
        assertThat(measured[1]).as("narrower than its text on one line").isLessThan(measured[3]);
        assertThat(measured[2]).as("so it takes more than one line's height").isGreaterThan(1.5 * measured[4]);
    }

    private static Label note(ChannelCpuBudgetDialog dialog) {
        List<Node> notes = descendants(dialog.getDialogPane().getContent())
                .filter(node -> node.getStyleClass().contains("dialog-hint")).toList();
        assertThat(notes).as("one note, styled as the stylesheet's dialog hint").singleElement()
                .isInstanceOf(Label.class);
        return (Label) notes.getFirst();
    }

    private static Node only(ChannelCpuBudgetDialog dialog, Class<?> type) {
        List<Node> found = descendants(dialog.getDialogPane().getContent()).filter(type::isInstance).toList();
        assertThat(found).as("one %s", type.getSimpleName()).hasSize(1);
        return found.getFirst();
    }

    private static Stream<Node> descendants(Node node) {
        Stream<Node> self = Stream.of(node);
        if (node instanceof Parent parent) {
            return Stream.concat(self, parent.getChildrenUnmodifiable().stream()
                    .flatMap(ChannelCpuBudgetDialogTest::descendants));
        }
        return self;
    }
}
