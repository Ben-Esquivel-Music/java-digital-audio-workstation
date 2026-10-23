package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.migration.MigrationRegistry;
import com.benesquivelmusic.daw.core.plugin.BuiltInDawPlugin;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginCategory;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiMessage;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.MidiProcessContext;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A track's MIDI-effect chain survives save → reopen in chain order: an
 * arpeggiator is keyed by its stable {@code plugin-id} and keeps all six
 * settings; an entry this build does not recognise stays in its position as
 * an {@link UnresolvedMidiEffect} that writes its attributes back unchanged;
 * an invalid arpeggiator setting is adjusted and reported; a schema 1 file
 * that keyed the arpeggiator by class name restores it through the 1 → 2
 * migration.
 */
class MidiEffectChainPersistenceTest {

    private static final String CHORD_GENERATOR = "com.example.chord-generator";

    private final ProjectDeserializer deserializer = new ProjectDeserializer();

    @Test
    void chainRoundTripsInOrderWithAnUnrecognisedEntryKeptAsSaved() throws Exception {
        DawProject project = new DawProject("Session", AudioFormat.CD_QUALITY);
        Track keys = project.createMidiTrack("Keys");
        ArpeggiatorPlugin first = new ArpeggiatorPlugin();
        first.setRate(ArpeggiatorPlugin.Rate.SIXTEENTH_TRIPLET);
        first.setPattern(ArpeggiatorPlugin.Pattern.DOWN_UP);
        first.setOctaveRange(3);
        first.setGate(137.5);
        first.setSwing(62.25);
        first.setLatch(true);
        ArpeggiatorPlugin second = new ArpeggiatorPlugin();
        second.setRate(ArpeggiatorPlugin.Rate.QUARTER);
        second.setPattern(ArpeggiatorPlugin.Pattern.CHORD);
        second.setOctaveRange(4);
        second.setGate(10.0);
        second.setSwing(75.0);
        keys.getMidiEffectChain().add(first);
        keys.getMidiEffectChain().add(chordGenerator());
        keys.getMidiEffectChain().add(second);

        String xml = new ProjectSerializer().serialize(project);
        DawProject reopened = deserializer.deserialize(xml);

        assertThat(xml).contains("plugin-id=\"" + ArpeggiatorPlugin.PLUGIN_ID + "\"");
        assertThat(xml).as("schema 2 keys MIDI effects by plugin id only").doesNotContain(" class=");
        List<MidiEffectPlugin> chain = reopened.getTracks().getFirst().getMidiEffectChain();
        assertThat(chain).hasSize(3);
        assertSameArpeggiator(chain.get(0), first);
        assertThat(chain.get(1)).isInstanceOf(UnresolvedMidiEffect.class);
        assertThat(((UnresolvedMidiEffect) chain.get(1)).attributes())
                .containsExactlyEntriesOf(chordGenerator().attributes());
        assertSameArpeggiator(chain.get(2), second);
        assertThat(deserializer.getUnreadItems()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED);
            assertThat(item.location()).isEqualTo(new UnreadProjectItem.OnTrack(0, "Keys"));
            assertThat(item.index()).isEqualTo(1);
            assertThat(item.detail()).contains(CHORD_GENERATOR);
        });

        String resaved = new ProjectSerializer().serialize(reopened);
        assertThat(resaved)
                .as("the unrecognised entry is written back with exactly its attributes")
                .contains("<midi-effect inversion=\"1\" plugin-id=\"" + CHORD_GENERATOR
                        + "\" voicing=\"drop2\"/>");
        List<MidiEffectPlugin> again = deserializer.deserialize(resaved).getTracks().getFirst()
                .getMidiEffectChain();
        assertThat(((UnresolvedMidiEffect) again.get(1)).attributes())
                .containsExactlyEntriesOf(chordGenerator().attributes());
    }

    @Test
    void invalidArpeggiatorSettingsAreAdjustedAndReported() throws Exception {
        DawProject project = deserializer.deserialize(fileWithChain("""
                <midi-effect plugin-id="%1$s" gate="NaN"/>
                <midi-effect plugin-id="%1$s" gate="250"/>
                <midi-effect plugin-id="%1$s" rate="WHOLE"/>
                <midi-effect plugin-id="%1$s" octave-range="9"/>
                <midi-effect plugin-id="%1$s" swing="-5" pattern="SIDEWAYS" octave-range="two"/>
                <midi-effect plugin-id="%1$s" swing="Infinity" gate="12.5" latch="true"/>
                """.formatted(ArpeggiatorPlugin.PLUGIN_ID)));

        List<MidiEffectPlugin> chain = project.getTracks().getFirst().getMidiEffectChain();
        assertThat(chain).hasSize(6).allMatch(ArpeggiatorPlugin.class::isInstance);
        assertThat(arp(chain, 0).getGate()).as("NaN gate → default").isEqualTo(50.0);
        assertThat(arp(chain, 1).getGate()).as("gate 250 → clamped").isEqualTo(ArpeggiatorPlugin.MAX_GATE);
        assertThat(arp(chain, 2).getRate()).as("unknown rate → default")
                .isEqualTo(ArpeggiatorPlugin.Rate.SIXTEENTH);
        assertThat(arp(chain, 3).getOctaveRange()).as("octave-range 9 → clamped")
                .isEqualTo(ArpeggiatorPlugin.MAX_OCTAVE);
        assertThat(arp(chain, 4).getSwing()).as("swing -5 → clamped").isEqualTo(ArpeggiatorPlugin.MIN_SWING);
        assertThat(arp(chain, 4).getPattern()).as("unknown pattern → default")
                .isEqualTo(ArpeggiatorPlugin.Pattern.UP);
        assertThat(arp(chain, 4).getOctaveRange()).as("non-integer octave-range → default").isEqualTo(1);
        assertThat(arp(chain, 5).getSwing()).as("Infinity swing → default").isEqualTo(0.0);
        assertThat(arp(chain, 5).getGate()).as("valid gate kept").isEqualTo(12.5);
        assertThat(arp(chain, 5).isLatch()).isTrue();

        assertThat(deserializer.getUnreadItems())
                .allMatch(item -> item.kind() == UnreadProjectItem.Kind.MIDI_EFFECT_SETTING_ADJUSTED)
                .extracting(UnreadProjectItem::index, UnreadProjectItem::detail)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0,
                                "arpeggiator gate 'NaN' is not a finite number; 50.0 is used"),
                        org.assertj.core.groups.Tuple.tuple(1,
                                "arpeggiator gate 250 is outside 10.0–200.0; 200.0 is used"),
                        org.assertj.core.groups.Tuple.tuple(2,
                                "arpeggiator rate 'WHOLE' is not a rate; SIXTEENTH is used"),
                        org.assertj.core.groups.Tuple.tuple(3,
                                "arpeggiator octave-range 9 is outside 1–4; 4 is used"),
                        org.assertj.core.groups.Tuple.tuple(4,
                                "arpeggiator pattern 'SIDEWAYS' is not a pattern; UP is used"),
                        org.assertj.core.groups.Tuple.tuple(4,
                                "arpeggiator octave-range 'two' is not an integer; 1 is used"),
                        org.assertj.core.groups.Tuple.tuple(4,
                                "arpeggiator swing -5 is outside 0.0–75.0; 0.0 is used"),
                        org.assertj.core.groups.Tuple.tuple(5,
                                "arpeggiator swing 'Infinity' is not a finite number; 0.0 is used"));
    }

    @Test
    void absentArpeggiatorSettingsKeepThePluginDefaultsUnreported() throws Exception {
        DawProject project = deserializer.deserialize(fileWithChain(
                "<midi-effect plugin-id=\"" + ArpeggiatorPlugin.PLUGIN_ID + "\"/>"));

        assertSameArpeggiator(project.getTracks().getFirst().getMidiEffectChain().getFirst(),
                new ArpeggiatorPlugin());
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void entryWithoutPluginIdIsKeptAsAPlaceholderAndItsChildElementsAreDropped() throws Exception {
        DawProject project = deserializer.deserialize(fileWithChain("""
                <midi-effect plugin-id="%s" rate="EIGHTH"/>
                <midi-effect flavour="unknown"><setting value="1"/></midi-effect>
                """.formatted(ArpeggiatorPlugin.PLUGIN_ID)));

        List<MidiEffectPlugin> chain = project.getTracks().getFirst().getMidiEffectChain();
        assertThat(arp(chain, 0).getRate()).isEqualTo(ArpeggiatorPlugin.Rate.EIGHTH);
        UnresolvedMidiEffect placeholder = (UnresolvedMidiEffect) chain.get(1);
        assertThat(placeholder.pluginId()).isEmpty();
        assertThat(placeholder.attributes()).containsExactly(java.util.Map.entry("flavour", "unknown"));
        assertThat(placeholder.getDescriptor().id()).isEqualTo("unresolved-midi-effect");
        assertThat(deserializer.getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind, UnreadProjectItem::index)
                .containsExactly(UnreadProjectItem.Kind.MIDI_EFFECT_NOT_RECOGNIZED, 1);

        String resaved = new ProjectSerializer().serialize(project);
        assertThat(resaved).contains("<midi-effect flavour=\"unknown\"/>").doesNotContain("<setting");
    }

    @Test
    void schemaOneClassNameRestoresTheArpeggiatorThroughTheMigration() throws Exception {
        String v1 = """
                <daw-project version="1">
                  <tracks>
                    <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI">
                      <midi-effect-chain>
                        <midi-effect class="com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin" gate="80.0" latch="true" octave-range="2" pattern="RANDOM" rate="EIGHTH" swing="20.0"/>
                      </midi-effect-chain>
                    </track>
                  </tracks>
                </daw-project>
                """;

        DawProject project = deserializer.deserialize(v1);

        ArpeggiatorPlugin arp = arp(project.getTracks().getFirst().getMidiEffectChain(), 0);
        assertThat(arp.getRate()).isEqualTo(ArpeggiatorPlugin.Rate.EIGHTH);
        assertThat(arp.getPattern()).isEqualTo(ArpeggiatorPlugin.Pattern.RANDOM);
        assertThat(arp.getOctaveRange()).isEqualTo(2);
        assertThat(arp.getGate()).isEqualTo(80.0);
        assertThat(arp.getSwing()).isEqualTo(20.0);
        assertThat(arp.isLatch()).isTrue();
        assertThat(deserializer.getLastMigrationReport().wasMigrated()).isTrue();
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void placeholderPassesMidiThroughAndIsNeverABuiltInPlugin() {
        UnresolvedMidiEffect placeholder = chordGenerator();
        MidiMessage[] input = new MidiMessage[0];

        assertThat(placeholder.process(input, 0, new MidiProcessContext(48_000, 512, 0, 24_000)))
                .isSameAs(input);
        assertThat(placeholder.getDescriptor().id()).isEqualTo(CHORD_GENERATOR);
        assertThat(placeholder.getMenuLabel()).contains(CHORD_GENERATOR);
        assertThat(placeholder.getCategory()).isEqualTo(BuiltInPluginCategory.MIDI_EFFECT);
        assertThat(placeholder.getMenuIcon()).isNotBlank();
        assertThat(BuiltInDawPlugin.discoverAll())
                .noneMatch(UnresolvedMidiEffect.class::isInstance);
        assertThat(BuiltInDawPlugin.menuEntries())
                .noneMatch(entry -> entry.pluginClass() == UnresolvedMidiEffect.class);
    }

    private static UnresolvedMidiEffect chordGenerator() {
        SequencedMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("inversion", "1");
        attributes.put("plugin-id", CHORD_GENERATOR);
        attributes.put("voicing", "drop2");
        return new UnresolvedMidiEffect(attributes);
    }

    private static String fileWithChain(String effects) {
        return """
                <daw-project version="%d">
                  <tracks>
                    <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI">
                      <midi-effect-chain>
                %s
                      </midi-effect-chain>
                    </track>
                  </tracks>
                </daw-project>
                """.formatted(MigrationRegistry.CURRENT_VERSION, effects);
    }

    private static ArpeggiatorPlugin arp(List<MidiEffectPlugin> chain, int index) {
        return (ArpeggiatorPlugin) chain.get(index);
    }

    private static void assertSameArpeggiator(MidiEffectPlugin actual, ArpeggiatorPlugin expected) {
        assertThat(actual).isInstanceOf(ArpeggiatorPlugin.class);
        ArpeggiatorPlugin arp = (ArpeggiatorPlugin) actual;
        assertThat(arp.getRate()).as("rate").isEqualTo(expected.getRate());
        assertThat(arp.getPattern()).as("pattern").isEqualTo(expected.getPattern());
        assertThat(arp.getOctaveRange()).as("octave-range").isEqualTo(expected.getOctaveRange());
        assertThat(arp.getGate()).as("gate").isEqualTo(expected.getGate());
        assertThat(arp.getSwing()).as("swing").isEqualTo(expected.getSwing());
        assertThat(arp.isLatch()).as("latch").isEqualTo(expected.isLatch());
    }
}
