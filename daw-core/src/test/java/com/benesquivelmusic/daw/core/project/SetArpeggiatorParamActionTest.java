package com.benesquivelmusic.daw.core.project;

import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Pattern;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin.Rate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SetArpeggiatorParamAction} — verifies the reflective
 * parameter binder updates the plugin and restores the prior value on
 * undo, for the full mix of parameter types (enum, int, double, boolean).
 */
class SetArpeggiatorParamActionTest {

    @Test
    void executeAndUndoRestoresEnumParameter() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        assertThat(arp.getRate()).isEqualTo(Rate.SIXTEENTH);

        var action = new SetArpeggiatorParamAction(arp, "rate", Rate.QUARTER);
        action.execute();
        assertThat(arp.getRate()).isEqualTo(Rate.QUARTER);

        action.undo();
        assertThat(arp.getRate()).isEqualTo(Rate.SIXTEENTH);
    }

    @Test
    void executeAndUndoRestoresIntParameter() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        var action = new SetArpeggiatorParamAction(arp, "octaveRange", 3);
        action.execute();
        assertThat(arp.getOctaveRange()).isEqualTo(3);
        action.undo();
        assertThat(arp.getOctaveRange()).isEqualTo(1);
    }

    @Test
    void executeAndUndoRestoresDoubleParameter() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        var action = new SetArpeggiatorParamAction(arp, "gate", 75.0);
        action.execute();
        assertThat(arp.getGate()).isEqualTo(75.0);
        action.undo();
        assertThat(arp.getGate()).isEqualTo(50.0);
    }

    @Test
    void executeAndUndoRestoresBooleanParameter() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        var action = new SetArpeggiatorParamAction(arp, "latch", true);
        action.execute();
        assertThat(arp.isLatch()).isTrue();
        action.undo();
        assertThat(arp.isLatch()).isFalse();
    }

    @Test
    void enumValueProvidedAsStringIsCoerced() {
        ArpeggiatorPlugin arp = new ArpeggiatorPlugin();
        var action = new SetArpeggiatorParamAction(arp, "pattern", "DOWN");
        action.execute();
        assertThat(arp.getPattern()).isEqualTo(Pattern.DOWN);
        action.undo();
        assertThat(arp.getPattern()).isEqualTo(Pattern.UP);
    }

    @Test
    void descriptionIncludesParamName() {
        var arp = new ArpeggiatorPlugin();
        var action = new SetArpeggiatorParamAction(arp, "swing", 25.0);
        assertThat(action.description()).contains("swing");
    }
}
