package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.app.ui.vm.ChannelVM;
import com.benesquivelmusic.daw.core.mixer.MixerChannel.ChangeKind;

import org.junit.jupiter.api.Test;

import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.CONTINUOUS_DOUBLE_CHANNEL_PUBLISH;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.SWITCH_MAP_PREFIX;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.named;
import static com.benesquivelmusic.daw.app.ui.RtPathBytecodeWalk.walk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 322 fix round (B2) — the bytecode sentinel over the one view-model
 * path the audio thread executes: {@code ChannelVM.onCoreChange} and every
 * same-class method it reaches. {@code RenderPipeline.applyAutomation} drives
 * {@code MixerChannel.setVolume/setPan} per block on the RT thread, and the
 * channel's change signal calls straight into this method, so on the non-FX
 * branch nothing reachable may allocate, take a monitor, or enter the
 * toolkit (Audio Engine Wiring Design Book §6.1). The walk and its rules —
 * no {@code invokedynamic}, no {@code new}, no monitor, no toolkit entry,
 * no {@code $SwitchMap$} or synthetic nested class (fix round 2) — are
 * {@link RtPathBytecodeWalk}, shared with the story 328 sentinel over the
 * count-in's record-start listeners.
 *
 * <p>The only permitted escape off the class is the wait-free
 * {@code FxDispatcher.ContinuousDoubleChannel.publish(double)}; the walk
 * asserts it actually saw that call and the {@code FxDispatcher.isFxThread()}
 * query, so the scan cannot pass vacuously. The FX-thread branch's property
 * writes and the discrete arms' {@code dispatcher.onFx} (an allocating
 * {@code Platform.runLater}, edge-triggered only — the setters notify on
 * change alone) are invocations into other classes and are not walked; the
 * class Javadoc "Threading" states that budget.</p>
 *
 * <p>Non-vacuity of the switch-map rule: {@link #switchOnFixtureKind} is an
 * enum {@code switch} over the real {@code ChangeKind} — the shape
 * {@code onCoreChange} must never compile back to;
 * {@link #theSwitchMapRuleFiresOnAnEnumSwitchFixture} runs the same
 * detector over it and requires the rule to report the {@code $SwitchMap$}
 * access.</p>
 */
final class ChannelVmRtPathBytecodeSentinelTest {

    private static final String ROOT_METHOD = "onCoreChange";
    private static final String FIXTURE_METHOD = "switchOnFixtureKind";

    @Test
    void nothingReachableFromOnCoreChangeAllocatesLocksOrEntersTheToolkit() throws Exception {
        RtPathBytecodeWalk.Walk walk = walk(ChannelVM.class, named(ROOT_METHOD));
        assertThat(walk.roots()).as("ChannelVM#" + ROOT_METHOD + " exists with a Code attribute").isEqualTo(1);

        // Non-vacuity: the walk followed the same-class helper and saw the two seam calls.
        assertThat(walk.reached()).as("the walk reached the same-class helper").anyMatch(k -> k.contains("#applyOnFx("));
        assertThat(walk.sawThreadQuery()).as("the FX-thread test is FxDispatcher.isFxThread()").isTrue();
        assertThat(walk.escapes()).as("the walk saw the permitted escape, ContinuousDoubleChannel.publish")
                .contains(CONTINUOUS_DOUBLE_CHANNEL_PUBLISH);
        assertThat(walk.findings())
                .as("allocation / monitor / toolkit / switch-map sites reachable from ChannelVM#%s on the "
                        + "audio thread (story 322 fix rounds 1 and 2); reached: %s", ROOT_METHOD, walk.reached())
                .isEmpty();
    }

    @Test
    void theSwitchMapRuleFiresOnAnEnumSwitchFixture() throws Exception {
        // The fixture really is an enum switch (and its synthetic class really initialises).
        assertThat(switchOnFixtureKind(ChangeKind.PAN)).isEqualTo(2);

        RtPathBytecodeWalk.Walk walk = walk(ChannelVmRtPathBytecodeSentinelTest.class, named(FIXTURE_METHOD));
        assertThat(walk.roots()).as("the fixture method exists with a Code attribute").isEqualTo(1);
        assertThat(walk.findings())
                .as("the detector must report the enum switch map the fixture compiles to")
                .anyMatch(finding -> finding.contains(SWITCH_MAP_PREFIX))
                .anyMatch(finding -> finding.contains("synthetic nested class"));
    }

    /**
     * Non-vacuity fixture: an arrow-form {@code switch} statement over the
     * real {@link ChangeKind}, the exact shape {@code onCoreChange} had before
     * fix round 2. Because {@code ChangeKind} is declared in another
     * compilation unit (daw-core), javac cannot rely on its ordinals and
     * lowers the switch to {@code getstatic ChannelVmRtPathBytecodeSentinelTest$1
     * .$SwitchMap$…MixerChannel$ChangeKind} plus {@code tableswitch} — the
     * access the sentinel must flag. An enum nested in this test would NOT
     * do: a switch over an enum from the same compilation unit is lowered to
     * a direct {@code ordinal()} switch with no synthetic class, and the
     * fixture would prove nothing.
     */
    private static int switchOnFixtureKind(ChangeKind kind) {
        switch (kind) {
            case VOLUME -> {
                return 1;
            }
            case PAN -> {
                return 2;
            }
        }
        return 0;
    }
}
