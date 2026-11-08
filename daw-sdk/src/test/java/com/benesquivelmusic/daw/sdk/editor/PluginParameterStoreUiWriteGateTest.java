package com.benesquivelmusic.daw.sdk.editor;

import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store's UI-write gates (PR #989 review 5711460812; Plugin View Design
 * Book §4.6): while a gate says no, {@link PluginParameterStore#writeFromUi}
 * changes nothing — not the value, not the pending set the audio thread
 * drains, not the capture a save takes — and calls every installed gate's
 * refusal callback once, in installation order, inside the write. Gates
 * combine with AND, a write refused while those callbacks run calls none,
 * each remover removes its own gate only, and the audio side never answers
 * to a gate.
 */
class PluginParameterStoreUiWriteGateTest {

    private static final int GAIN_ID = 7;
    private static final int MIX_ID = 9;

    private final PluginParameterStore store = new PluginParameterStore(List.of(
            new PluginParameter(GAIN_ID, "Gain", 0.0, 1.0, 0.5),
            new PluginParameter(MIX_ID, "Mix", 0.0, 1.0, 0.25)));

    private final List<String> calls = new ArrayList<>();

    @Test
    void aWriteOneGateRefusesChangesNothingAndCallsItsCallbackOnceWithTheIndex() {
        store.addUiWriteGate(() -> false, index -> calls.add("refused " + index));

        store.writeFromUi(0, 0.9);
        assertThat(calls).as("the callback ran once, with the index").containsExactly("refused 0");
        store.writeFromUiById(MIX_ID, 0.75);
        assertThat(calls).as("and once for the write by id").containsExactly("refused 0", "refused 1");

        assertNothingChanged();
        assertThat(store.uiWritesAdmitted()).isFalse();
    }

    @Test
    void gatesCombineWithAndAndEveryGatesCallbackRunsOnceInInstallationOrder() {
        AtomicBoolean firstAdmits = new AtomicBoolean(true);
        AtomicBoolean secondAdmits = new AtomicBoolean(false);
        store.addUiWriteGate(firstAdmits::get, index -> calls.add("first " + index));
        store.addUiWriteGate(secondAdmits::get, index -> calls.add("second " + index));

        store.writeFromUi(1, 0.75);
        assertThat(calls).as("one gate said no: both callbacks, in order").containsExactly("first 1", "second 1");
        assertNothingChanged();

        calls.clear();
        firstAdmits.set(false);
        secondAdmits.set(true);
        store.writeFromUi(1, 0.75);
        assertThat(calls).as("the other gate said no").containsExactly("first 1", "second 1");
        assertNothingChanged();

        calls.clear();
        firstAdmits.set(true);
        store.writeFromUi(1, 0.75);
        assertThat(calls).as("both say yes: no callback").isEmpty();
        assertThat(store.value(1)).as("and the write lands").isEqualTo(0.75);
        assertThat(store.snapshotPendingUiValues()).containsEntry(MIX_ID, 0.75);
        assertThat(store.drainToAudio(_ -> { })).isOne();
    }

    @Test
    void aWriteRefusedWhileTheCallbacksRunCallsNoneAndChangesNothing() {
        store.addUiWriteGate(() -> false, index -> {
            calls.add("refused " + index);
            if (calls.size() == 1) {
                store.writeFromUi(index, 0.9);
                store.writeFromUi(1, 0.75);
            }
        });

        store.writeFromUi(0, 0.9);

        assertThat(calls).as("the nested writes called no callback").containsExactly("refused 0");
        assertNothingChanged();
    }

    @Test
    void aWriteTheGatesAdmitDuringACallbackLands() {
        AtomicBoolean admits = new AtomicBoolean(false);
        store.addUiWriteGate(admits::get, index -> {
            calls.add("refused " + index);
            admits.set(true);
            store.writeFromUi(1, 0.75);
        });

        store.writeFromUi(0, 0.9);

        assertThat(calls).containsExactly("refused 0");
        assertThat(store.value(0)).as("the refused write changed nothing").isEqualTo(0.5);
        assertThat(store.value(1)).as("the write admitted inside the callback landed").isEqualTo(0.75);
        assertThat(store.snapshotPendingUiValues()).containsOnlyKeys(MIX_ID);
    }

    @Test
    void aGateAddedByACallbackIsNotCalledForTheWriteThatAddedIt() {
        store.addUiWriteGate(() -> false, index -> {
            calls.add("first " + index);
            if (calls.size() == 1) {
                store.addUiWriteGate(() -> false, added -> calls.add("added " + added));
            }
        });

        store.writeFromUi(0, 0.9);
        assertThat(calls).as("the added gate waits for the next write").containsExactly("first 0");

        store.writeFromUi(1, 0.75);
        assertThat(calls).containsExactly("first 0", "first 1", "added 1");
        assertNothingChanged();
    }

    @Test
    void aCallbackThatThrowsEndsThatWritesCallbacksAndTheNextRefusalCallsThemAgain() {
        store.addUiWriteGate(() -> false, index -> {
            calls.add("first " + index);
            if (calls.size() == 1) {
                throw new IllegalStateException("a refusal callback fault");
            }
        });
        store.addUiWriteGate(() -> false, index -> calls.add("second " + index));

        assertThatThrownBy(() -> store.writeFromUi(0, 0.9))
                .as("the throw goes back to the writer").isInstanceOf(IllegalStateException.class);
        assertThat(calls).as("it ended that write's callbacks").containsExactly("first 0");

        store.writeFromUi(0, 0.9);
        assertThat(calls).as("the bound was reset").containsExactly("first 0", "first 0", "second 0");
        assertNothingChanged();
    }

    @Test
    void eachRemoverRemovesItsOwnGateByIdentityOnceAndTheStoreAdmitsWhenNoneIsLeft() {
        BooleanSupplier no = () -> false;
        PluginParameterStore.IndexConsumer record = index -> calls.add("refused " + index);
        Runnable removeFirst = store.addUiWriteGate(no, record);
        Runnable removeSecond = store.addUiWriteGate(no, record);

        removeFirst.run();
        store.writeFromUi(0, 0.9);
        assertThat(calls).as("the twin gate, equal but not the same, still refuses").containsExactly("refused 0");
        assertThat(store.uiWritesAdmitted()).isFalse();

        removeFirst.run();
        store.writeFromUi(0, 0.9);
        assertThat(calls).as("a second run of a remover removes nothing").containsExactly("refused 0", "refused 0");
        assertNothingChanged();

        removeSecond.run();
        assertThat(store.uiWritesAdmitted()).as("the refusing gate's remover ran: the store admits").isTrue();
        store.writeFromUi(0, 0.9);
        assertThat(store.value(0)).isEqualTo(0.9);
        assertThat(calls).hasSize(2);
    }

    @Test
    void withNoGateTheStoreAdmitsAndWritesLand() {
        assertThat(store.uiWritesAdmitted()).isTrue();

        store.writeFromUiById(GAIN_ID, 0.9);

        assertThat(store.value(0)).isEqualTo(0.9);
        assertThat(store.snapshotPendingUiValues()).containsEntry(GAIN_ID, 0.9);
        assertThat(store.drainToAudio(_ -> { })).isOne();
    }

    @Test
    void theAudioSideNeverAnswersToAGate() {
        AtomicBoolean admits = new AtomicBoolean(true);
        store.addUiWriteGate(admits::get, index -> calls.add("refused " + index));
        store.writeFromUi(1, 0.75);
        admits.set(false);

        store.writeFromAudio(0, 0.9);
        assertThat(store.value(0)).as("an audio-side write lands while the gate says no").isEqualTo(0.9);
        assertThat(store.drainToUi(_ -> { })).isOne();

        List<Integer> drained = new ArrayList<>();
        assertThat(store.drainToAudio(drained::add)).as("a UI write admitted before the gate turned still drains")
                .isOne();
        assertThat(drained).containsExactly(1);
        assertThat(store.value(1)).isEqualTo(0.75);
        assertThat(calls).isEmpty();
    }

    @Test
    void aBadIndexThrowsOnTheRefusedBranchTooAndCallsNoCallback() {
        store.addUiWriteGate(() -> false, index -> calls.add("refused " + index));

        assertThatThrownBy(() -> store.writeFromUi(2, 0.9)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> store.writeFromUi(-1, 0.9)).isInstanceOf(IndexOutOfBoundsException.class);

        assertThat(calls).isEmpty();
        assertNothingChanged();
    }

    @Test
    void aGateNeedsBothItsAdmissionAndItsRefusalCallback() {
        assertThatThrownBy(() -> store.addUiWriteGate(null, _ -> { })).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.addUiWriteGate(() -> false, null)).isInstanceOf(NullPointerException.class);
        assertThat(store.uiWritesAdmitted()).as("neither was installed").isTrue();
    }

    /** The store as constructed: both defaults, nothing pending, nothing for the audio side to drain. */
    private void assertNothingChanged() {
        assertThat(store.value(0)).as("the value").isEqualTo(0.5);
        assertThat(store.value(1)).isEqualTo(0.25);
        assertThat(store.snapshotPendingUiValues()).as("the capture a save takes").isEmpty();
        assertThat(store.drainToAudio(_ -> { })).as("the audio side's pending set").isZero();
    }
}
