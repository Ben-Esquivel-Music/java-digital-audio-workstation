package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.TransportVM;
import com.benesquivelmusic.daw.core.transport.Transport.ChangeKind;

import org.junit.jupiter.api.Test;

import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.CONTINUOUS_CHANNEL_PUBLISH;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.CONTINUOUS_DOUBLE_CHANNEL_PUBLISH;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.SWITCH_MAP_PREFIX;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.accessingField;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.named;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.walk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 328 review round 2 — the bytecode sentinel over the two daw-app
 * listener bodies the render thread executes at a count-in's fire
 * (Recording Reliability book §5.6): {@code CountInSchedule.fire()} runs
 * {@code Transport.record()} on the render thread at the count-in's last
 * block boundary, whose {@code ChangeKind.STATE} notification calls
 * straight into
 *
 * <ul>
 *   <li>{@code TransportVM.onCoreChange} — the STATE arm publishes into the
 *       dispatcher's lock-free {@code ContinuousChannel} off the FX thread
 *       and the playhead into the {@code ContinuousDoubleChannel}; and</li>
 *   <li>{@code RecordCoordinator.countInObserver} — the lambda compiled as
 *       {@code lambda$new$N}, rooted here by what it does (it reads the
 *       {@code countInFireChannel} field) rather than by javac's number —
 *       which publishes the fire into that channel.</li>
 * </ul>
 *
 * <p>Both run on the block immediately before the first captured one, so
 * nothing reachable from either may allocate, take a monitor, enter the
 * toolkit, or dispatch through an enum switch map: {@link RtPathBytecodeWalk}
 * holds the rules; {@code RealTimeSafeContractTest}'s count-in fire walk in
 * daw-core stops at {@code Consumer#accept} and delegates the listener
 * bodies to this sentinel ({@code dawg-annotations-reflection} §4 "Adding a
 * new {@code @RealTimeSafe} method" — walk, deny by default, pin the root).
 * The behavioural pins ({@code TransportVMTest},
 * {@code CountInRecordStartTest}) only show the signal did not travel by
 * {@code runLater}; a {@code LOG.fine}, a string concatenation, a capturing
 * lambda or a {@code switch} added to either body would keep them green,
 * which is this sentinel's reason to exist.</p>
 *
 * <p>Budget: the FX-thread arms of {@code TransportVM.onCoreChange} call
 * {@code FxDispatcher.onFx} with a runnable built once in the constructor,
 * and the coordinator's observer falls back to {@code postFx}
 * ({@code FxDispatcher.runOnFx}) only when no dispatcher exists — the
 * pure-unit context. Both are invocations into {@code FxDispatcher}, which
 * the walk does not follow; {@code FxDispatcherFxThreadBytecodeSentinelTest}
 * covers that class. Non-vacuity: the walk must have seen the escapes each
 * body is built around — the VM's {@code isFxThread()} query and both
 * channel publishes, the observer's {@code ContinuousChannel.publish} —
 * and the switch-map rule is proven live on {@link #switchOnFixtureKind},
 * an enum switch over the real cross-module {@link ChangeKind}.</p>
 */
final class CountInRecordStartListenerBytecodeSentinelTest {

    private static final String VM_ROOT_METHOD = "onCoreChange";
    private static final String OBSERVER_CHANNEL_FIELD = "countInFireChannel";
    private static final String FIXTURE_METHOD = "switchOnFixtureKind";

    @Test
    void nothingReachableFromTransportVmOnCoreChangeAllocatesLocksSwitchesOrEntersTheToolkit() throws Exception {
        RtPathBytecodeWalk.Walk walk = walk(TransportVM.class, named(VM_ROOT_METHOD));
        assertThat(walk.roots()).as("TransportVM#" + VM_ROOT_METHOD + " exists with a Code attribute").isEqualTo(1);

        assertThat(walk.sawThreadQuery()).as("the FX-thread test is FxDispatcher.isFxThread()").isTrue();
        assertThat(walk.escapes()).as("the STATE arm publishes into the state channel and the playhead channel")
                .contains(CONTINUOUS_CHANNEL_PUBLISH, CONTINUOUS_DOUBLE_CHANNEL_PUBLISH);
        assertThat(walk.findings())
                .as("allocation / monitor / toolkit / switch-map / lambda sites reachable from TransportVM#%s "
                        + "on the render thread at the count-in's fire; reached: %s", VM_ROOT_METHOD, walk.reached())
                .isEmpty();
    }

    @Test
    void nothingReachableFromTheCoordinatorsCountInObserverAllocatesLocksSwitchesOrEntersTheToolkit()
            throws Exception {
        // The root is the one compiled lambda that reads the channel field;
        // the FX-side methods that open and close the channel are its
        // writers and are not render-thread code.
        RtPathBytecodeWalk.Walk walk = walk(RecordCoordinator.class,
                mm -> mm.methodName().stringValue().startsWith("lambda$")
                        && accessingField(OBSERVER_CHANNEL_FIELD).test(mm));
        assertThat(walk.roots())
                .as("exactly one lambda of RecordCoordinator reads %s; reached: %s", OBSERVER_CHANNEL_FIELD, walk.reached())
                .isEqualTo(1);
        assertThat(walk.escapes()).as("the observer publishes the fire into the lock-free channel")
                .contains(CONTINUOUS_CHANNEL_PUBLISH);
        assertThat(walk.findings())
                .as("allocation / monitor / toolkit / switch-map / lambda sites reachable from the count-in "
                        + "observer on the render thread at the count-in's fire; reached: %s", walk.reached())
                .isEmpty();
    }

    @Test
    void theSwitchMapRuleFiresOnAnEnumSwitchOverTheTransportsChangeKind() throws Exception {
        assertThat(switchOnFixtureKind(ChangeKind.STATE)).isEqualTo(1);

        RtPathBytecodeWalk.Walk walk = walk(CountInRecordStartListenerBytecodeSentinelTest.class, named(FIXTURE_METHOD));
        assertThat(walk.roots()).as("the fixture method exists with a Code attribute").isEqualTo(1);
        assertThat(walk.findings())
                .as("the detector must report the enum switch map the fixture compiles to")
                .anyMatch(finding -> finding.contains(SWITCH_MAP_PREFIX))
                .anyMatch(finding -> finding.contains("synthetic nested class"));
    }

    /**
     * Non-vacuity fixture: the shape {@code TransportVM.onCoreChange} had
     * before review round 2 — an arrow-form {@code switch} over the
     * cross-module {@link ChangeKind}, which javac lowers to a
     * {@code $SwitchMap$} table in a synthetic nested class.
     */
    private static int switchOnFixtureKind(ChangeKind kind) {
        switch (kind) {
            case STATE -> {
                return 1;
            }
            case POSITION -> {
                return 2;
            }
            default -> {
                return 0;
            }
        }
    }
}
