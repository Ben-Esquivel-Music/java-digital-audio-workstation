package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.Send;
import com.benesquivelmusic.daw.core.mixer.SendTap;
import com.benesquivelmusic.daw.core.mixer.snapshot.SendSnapshot;
import com.benesquivelmusic.daw.core.plugin.MidiEffectPlugin;
import com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.sdk.transport.PreRollPostRoll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The write/read parity contract of {@code project.daw} (Persistence
 * Integrity book §2.9, §4.4): every element, attribute and text the
 * {@link ProjectSerializer} emits is read by the {@link ProjectDeserializer},
 * and every name the deserializer asks for is emitted — or is a reasoned
 * legacy read. Write-only persistence is never waived (book §9.3): there is
 * no exemption for it, only a reader or a writer that stops writing.
 *
 * <p>Mechanism: the {@link RoundTripInventoryProjects} family is serialized,
 * parsed, and deserialized through a {@link DomReadRecorder} view of the very
 * document that was written, via the deserializer's document seam (migration
 * included, so {@code daw-project@version} is read through the view too);
 * {@link XmlSchemaParity} then judges every emitted item at element-path
 * granularity. A source-derived guard proves the family emits every name the
 * writer sources hold ({@link PersistenceSourceScan}) and that no writer or
 * reader hides a name from that proof. {@code ProjectMetadataReader} reads
 * the same file for the project hub's cards; it is a separate consumer of
 * {@code <metadata>} and outside this contract.</p>
 *
 * <p>When the serializer gains an item: add its reader in the same change,
 * extend the fixture family until the guard stops naming the literal, and
 * declare any new writer or reader file below.</p>
 */
class ProjectSchemaParityContractTest {

    /**
     * Every file under {@code persistence/} that writes names into a project
     * document: the serializer, its analyzer-insert helper, and the
     * migrations, which rewrite an older document into the current schema —
     * so their names must be current-schema names the family emits.
     */
    static final List<String> WRITER_FILES = List.of(
            "ProjectSerializer.java",
            "AnalyzerInsertPersistence.java",
            "migration/MigrationRegistry.java",
            "migration/MidiEffectPluginIdMigration.java");

    /** Every file under {@code persistence/} that reads a project document for the deserializer. */
    static final List<String> READER_FILES = List.of(
            "ProjectDeserializer.java",
            "AnalyzerInsertPersistence.java",
            "migration/MigrationRegistry.java",
            "migration/MidiEffectPluginIdMigration.java");

    /** The other {@code org.w3c.dom} users under {@code persistence/}, and why the contract does not scan them. */
    static final Map<String, String> OTHER_DOM_FILES = Map.of(
            "ProjectMetadataReader.java",
            "reads <metadata> of project.daw for the project hub's cards — a separate consumer, out of scope",
            "migration/ProjectMigration.java",
            "carries the Document type of a migration step's function; reads and writes nothing itself",
            "ProjectDocument.java",
            "renders the document ProjectSerializer.capture built and rewrites the values of the asset-reference"
            + " attributes the serializer wrote and recorded (Attr.setValue through a remap); it creates no"
            + " element and names no attribute, so every name the document holds is the serializer's, which"
            + " the writer guard scans");

    /** Writes whose names the source guard cannot read, each with why the contract still covers them. */
    static final List<PersistenceSourceScan.DynamicWriteSite> DYNAMIC_WRITE_SITES = List.of(
            new PersistenceSourceScan.DynamicWriteSite("ProjectSerializer.java",
                    "unresolved.attributes().forEach(pluginElem::setAttribute)",
                    "writes an UnresolvedMidiEffect back with the attribute names it was read with: the names"
                    + " come from the file, not from this build, and the deserializer reads all of them"
                    + " in bulk (getAttributes() in ProjectDeserializer.unresolvedMidiEffect), which handles"
                    + " the names no writer holds as literals; plugin-id is read by name"));

    /**
     * The only methods allowed to create an element whose tag is a parameter.
     * The guard reads their tags from their calls, so every call passes a
     * literal; any other {@code createElement} on a non-literal is a
     * violation.
     */
    static final List<PersistenceSourceScan.TagParameterBuilder> TAG_PARAMETER_BUILDERS = List.of(
            new PersistenceSourceScan.TagParameterBuilder("ProjectSerializer.java", "buildMixerChannelElement",
                    "one body writes the master, a track channel and a return bus (master, channel,"
                    + " return-bus): the channel kinds share their strip, insert, send and budget children"),
            new PersistenceSourceScan.TagParameterBuilder("ProjectSerializer.java", "buildSnapshotElement",
                    "one body writes a saved snapshot and the A/B slots (snapshot, slot-a, slot-b): they hold"
                    + " the same captured mixer"),
            new PersistenceSourceScan.TagParameterBuilder("ProjectSerializer.java", "buildChannelSnapshotElement",
                    "one body writes a snapshot's master, track channels and return buses (master, channel,"
                    + " return-bus): the captured channel kinds share their children"));

    /** The node-name reads in the reader files that look up no element, each with what it reads. */
    static final List<PersistenceSourceScan.DeclaredNameRead> DECLARED_NAME_READS = List.of(
            new PersistenceSourceScan.DeclaredNameRead("ProjectDeserializer.java",
                    "attributes.put(attribute.getNodeName(), attribute.getNodeValue())",
                    "copies an unrecognised MIDI effect's attributes into its placeholder: getNodeName() of"
                    + " an Attr is an attribute name, not an element lookup"));

    /**
     * Names the deserializer asks for that no current file carries.
     * {@code applyMixerChannelAttrs} (mixer channels) and
     * {@code parseChannelSnapshot} (snapshot channels) serve the master, the
     * track channels and the return buses alike, so they ask each kind for
     * its output routing; but only a track channel can be routed off the
     * master (MixerView offers the routing selector on track strips only, and
     * a snapshot recalls the routing it captured), so the serializer, which
     * writes the routing only when it is not {@code OutputRouting.MASTER},
     * never writes it on the master or a return bus.
     */
    static final List<ToleratedRead> TOLERATED_READS = List.of(
            new ToleratedRead("master@output-routing-channel",
                    "shared channel readers ask every channel kind; the master always routes to MASTER,"
                    + " which buildMixerChannelElement/buildChannelSnapshotElement omit"),
            new ToleratedRead("master@output-routing-name",
                    "applyMixerChannelAttrs asks every channel kind; the master always routes to MASTER,"
                    + " which buildMixerChannelElement omits with its display name"),
            new ToleratedRead("return-bus@output-routing-channel",
                    "shared channel readers ask every channel kind; a return bus always routes to MASTER"
                    + " (only track strips offer a routing selector), which the builders omit"),
            new ToleratedRead("return-bus@output-routing-name",
                    "applyMixerChannelAttrs asks every channel kind; a return bus always routes to MASTER,"
                    + " which buildMixerChannelElement omits with its display name"));

    /**
     * Names only an older file carries: the current writer never emits them,
     * and the deserializer (or a migration) still reads them when present.
     *
     * @param item   the path-level item ({@code a/b/c@attr})
     * @param reason why the reader keeps it
     */
    record LegacyInput(String item, String reason) {
    }

    static final List<LegacyInput> LEGACY_INPUTS = List.of(
            new LegacyInput("daw-project/mixer/channels/channel/sends/send@mode",
                    "files written before per-send tap points carry the lossy mode; parseSendTap derives the"
                    + " tap from it when tap is absent"),
            new LegacyInput("daw-project/mixer-snapshots/snapshot/channels/channel/sends/send@mode",
                    "snapshot sends of the same era carry mode only; parseSendTap serves them too"),
            new LegacyInput("daw-project/tracks/track/midi-effect-chain/midi-effect@class",
                    "schema 1 keyed a MIDI effect by class name; MidiEffectPluginIdMigration rewrites the"
                    + " known one to plugin-id"));

    /** A schema-1 document holding every {@link #LEGACY_INPUTS} item, as builds of that schema wrote them. */
    static final String SCHEMA_1_DOCUMENT = """
            <daw-project version="1">
              <metadata><name>Legacy Session</name></metadata>
              <transport tempo="120.0" time-sig-numerator="4" time-sig-denominator="4" loop-enabled="false" \
            loop-start="0.0" loop-end="16.0" position="0.0" pre-roll-bars="2" post-roll-bars="1" \
            pre-roll-enabled="true"/>
              <tracks>
                <track id="00000000-0000-0000-0000-0000000000a1" name="Keys" type="MIDI">
                  <midi-effect-chain>
                    <midi-effect class="com.benesquivelmusic.daw.core.plugin.builtin.midi.ArpeggiatorPlugin" \
            rate="EIGHTH" pattern="DOWN" octave-range="2" gate="80.0" swing="10.0" latch="true"/>
                  </midi-effect-chain>
                </track>
              </tracks>
              <mixer>
                <master volume="1.0"/>
                <channels>
                  <channel name="Keys" volume="0.8">
                    <sends><send level="0.5" mode="PRE_FADER" target-index="0"/></sends>
                  </channel>
                </channels>
                <return-buses><return-bus name="Reverb Return" volume="1.0"/></return-buses>
              </mixer>
              <mixer-snapshots active-slot="A">
                <snapshot name="Old" timestamp="2026-01-01T00:00:00Z">
                  <master volume="1.0"/>
                  <channels>
                    <channel volume="0.8"><sends><send target-index="0" level="0.4" mode="PRE_FADER"/></sends></channel>
                  </channels>
                  <return-buses><return-bus volume="1.0"/></return-buses>
                </snapshot>
              </mixer-snapshots>
            </daw-project>
            """;

    /*
     * Floors set from the first green run: 76 element and 176 attribute names
     * in the writer sources, 598 path-level items emitted by the family, and
     * 75 element names the reader sources look up.
     * A count below its floor means the scan or the family lost coverage;
     * lower a floor only in the change that removes items from the schema.
     */
    private static final int WRITER_ELEMENT_NAME_FLOOR = 76;
    private static final int WRITER_ATTRIBUTE_NAME_FLOOR = 176;
    private static final int EMITTED_ITEM_FLOOR = 598;
    private static final int READER_ELEMENT_LOOKUP_FLOOR = 75;

    private static final Path ASSET_ROOT = Path.of("").toAbsolutePath().resolve("inventory-assets");

    /** One written document of the family, read back through a recorder. */
    record Recorded(Document written, Map<SchemaItem, List<Element>> emitted, DomReadRecorder reads,
                    DawProject reopened) {
    }

    private static XmlSchemaParity family;
    private static Recorded full;

    @BeforeAll
    static void recordTheFamily() throws IOException {
        family = new XmlSchemaParity().requireNamedReads(writerAttributeNames());
        for (RoundTripInventoryProjects.Variant variant : RoundTripInventoryProjects.variants(ASSET_ROOT)) {
            Recorded recorded = record(variant.project(), Set.of());
            family.record(variant.name(), recorded.emitted(), recorded.reads());
            if (variant.name().equals("full")) {
                full = recorded;
            }
        }
    }

    /**
     * Serializes {@code project}, parses the text, takes the emitted items, and
     * deserializes the same document through a recorder hiding {@code hidden}.
     */
    static Recorded record(DawProject project, Set<SchemaItem> hidden) throws IOException {
        Document written = ProjectDeserializer.parseXml(new ProjectSerializer().serialize(project));
        Map<SchemaItem, List<Element>> emitted = XmlSchemaParity.emitted(written);
        DomReadRecorder reads = new DomReadRecorder(hidden);
        DawProject reopened = new ProjectDeserializer().deserialize(reads.wrap(written), null);
        return new Recorded(written, emitted, reads, reopened);
    }

    /**
     * Every attribute name the writer sources hold as a literal: a reader
     * must ask for each by name, so a bulk {@code getAttributes()} copy does
     * not count as reading it ({@link XmlSchemaParity#requireNamedReads}).
     */
    static Set<String> writerAttributeNames() {
        Map<String, String> sources = persistenceSources();
        Set<String> names = new TreeSet<>();
        for (String file : WRITER_FILES) {
            PersistenceSourceScan.writerLiterals(file, sources.get(file), DYNAMIC_WRITE_SITES, TAG_PARAMETER_BUILDERS)
                    .attributes().forEach(literal -> names.add(literal.name()));
        }
        return names;
    }

    @Test
    void everyItemTheSerializerEmitsIsReadByTheDeserializer() {
        String report = family.writeOnlyReport();

        assertThat(family.writeOnly()).withFailMessage("%s", report).isEmpty();
    }

    @Test
    void everyNameTheDeserializerAsksForIsEmittedOrATolerableLegacyRead() {
        String report = family.readWithoutWriterReport(TOLERATED_READS);

        assertThat(family.readWithoutWriter(TOLERATED_READS)).withFailMessage("%s", report).isEmpty();
    }

    @Test
    void noToleratedReadIsStale() {
        assertThat(family.staleToleratedReads(TOLERATED_READS))
                .as("a tolerated read the writer now emits, or the reader no longer asks for, must be deleted")
                .isEmpty();
    }

    @Test
    void theWriterAndReaderFilesAreExactlyTheDeclaredOnes() {
        Map<String, String> sources = persistenceSources();

        Set<String> writers = sources.entrySet().stream()
                .filter(source -> PersistenceSourceScan.writesDocumentNames(source.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(writers)
                .as("files under persistence/ that create elements or set attributes; declare a new writer"
                        + " helper in WRITER_FILES so the coverage guard scans it")
                .containsExactlyInAnyOrderElementsOf(WRITER_FILES);

        Set<String> declared = new TreeSet<>(WRITER_FILES);
        declared.addAll(READER_FILES);
        declared.addAll(OTHER_DOM_FILES.keySet());
        Set<String> domUsers = sources.entrySet().stream()
                .filter(source -> PersistenceSourceScan.usesDom(source.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(domUsers)
                .as("files under persistence/ that use org.w3c.dom; declare each as a writer, a reader, or"
                        + " an out-of-scope DOM user with its reason")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void noWriterHidesANameFromTheCoverageGuard() {
        Map<String, String> sources = persistenceSources();
        List<String> violations = new ArrayList<>();
        for (String file : WRITER_FILES) {
            violations.addAll(PersistenceSourceScan.writerLiterals(
                    file, sources.get(file), DYNAMIC_WRITE_SITES, TAG_PARAMETER_BUILDERS).violations());
        }

        assertThat(violations)
                .as("every element and attribute name a writer can emit must be a literal the guard reads"
                        + " (or the tag a declared tag-parameter builder's call passes as a literal, or a"
                        + " declared dynamic write site)")
                .isEmpty();
        for (PersistenceSourceScan.DynamicWriteSite site : DYNAMIC_WRITE_SITES) {
            assertThat(sources.get(site.file()))
                    .as("stale dynamic write site in %s — delete it", site.file())
                    .contains(site.code());
        }
        assertThat(TAG_PARAMETER_BUILDERS).extracting(PersistenceSourceScan.TagParameterBuilder::file)
                .as("a tag-parameter builder lives in a declared writer file, which the guard scans")
                .allMatch(WRITER_FILES::contains);
    }

    @Test
    void theFixtureFamilyEmitsEveryNameTheWriterSourcesHold() {
        Map<String, String> sources = persistenceSources();
        List<String> missing = new ArrayList<>();
        for (String file : WRITER_FILES) {
            PersistenceSourceScan.WriterLiterals literals = PersistenceSourceScan.writerLiterals(
                    file, sources.get(file), DYNAMIC_WRITE_SITES, TAG_PARAMETER_BUILDERS);
            for (PersistenceSourceScan.Literal element : literals.elements()) {
                if (!family.emittedTags().contains(element.name())) {
                    missing.add(neverEmitted("element", element));
                }
            }
            for (PersistenceSourceScan.Literal attribute : literals.attributes()) {
                if (!family.emittedAttributeNames().contains(attribute.name())) {
                    missing.add(neverEmitted("attribute", attribute));
                }
            }
        }

        assertThat(missing).as("writer literals the parity contract cannot judge").isEmpty();
    }

    private static String neverEmitted(String kind, PersistenceSourceScan.Literal literal) {
        return "the fixture never emits " + kind + " \"" + literal.name() + "\" (" + literal.location()
                + ") — extend RoundTripInventoryProjects";
    }

    @Test
    void everyElementTheReaderLooksUpIsEmittedOrATolerableLegacyRead() {
        Map<String, String> sources = persistenceSources();
        Set<String> toleratedElements = TOLERATED_READS.stream()
                .filter(read -> !read.isAttribute())
                .map(ToleratedRead::item)
                .collect(Collectors.toSet());
        List<String> problems = new ArrayList<>();
        Set<String> lookedUp = new TreeSet<>();
        for (String file : READER_FILES) {
            PersistenceSourceScan.ReaderLookups lookups =
                    PersistenceSourceScan.readerLookups(file, sources.get(file), DECLARED_NAME_READS);
            problems.addAll(lookups.violations());
            for (PersistenceSourceScan.Literal element : lookups.elements()) {
                lookedUp.add(element.name());
                if (!family.emittedTags().contains(element.name()) && !toleratedElements.contains(element.name())) {
                    problems.add("Reader without a writer (book §4.4 \"vice versa\"): <" + element.name()
                            + "> is looked up at " + element.location() + " but never emitted");
                }
            }
        }
        for (String tolerated : toleratedElements) {
            if (family.emittedTags().contains(tolerated) || !lookedUp.contains(tolerated)) {
                problems.add("stale ToleratedRead '" + tolerated + "' — delete it");
            }
        }
        for (PersistenceSourceScan.DeclaredNameRead read : DECLARED_NAME_READS) {
            if (!READER_FILES.contains(read.file())) {
                problems.add("declared name read in " + read.file() + ", which is not a declared reader file");
            }
        }

        assertThat(problems).isEmpty();
    }

    /**
     * The shared builders write the same children under several parents; the
     * name-level guard cannot see whether each parent got them, so this pins
     * the family's path-level population of every reuse site. (The mixer
     * insert's id-keyed {@code parameter@id} form is not among them: the
     * serializer writes it only for a built-in processor without
     * {@code @ProcessorParam} accessors, and the one such processor,
     * {@code ParametricEqProcessor}, has no id-keyed parameters to write.)
     */
    @Test
    void everyReuseSiteOfTheSharedBuildersIsPopulated() {
        List<String> required = new ArrayList<>();
        for (String channel : List.of("daw-project/mixer/master", "daw-project/mixer/channels/channel",
                "daw-project/mixer/return-buses/return-bus")) {
            required.add(channel + "/inserts/insert@effect-type");
            required.add(channel + "/inserts/insert@analyzer-plugin-id");
            required.add(channel + "/inserts/insert@sidechain-source");
            required.add(channel + "/inserts/insert/parameter@name");
            required.add(channel + "/sends/send@tap");
            required.add(channel + "/cpu-budget@policy");
            required.add(channel + "/cpu-budget@fallback-factor");
            required.add(channel + "/cpu-budget@kernel-id");
        }
        for (String snapshot : List.of("snapshot", "slot-a", "slot-b")) {
            for (String channel : List.of("master", "channels/channel", "return-buses/return-bus")) {
                String path = "daw-project/mixer-snapshots/" + snapshot + "/" + channel;
                required.add(path + "/inserts/insert@effect-type");
                required.add(path + "/inserts/insert/parameter@id");
                required.add(path + "/sends/send@target");
                required.add(path + "/sends/send@target-index");
                required.add(path + "/cpu-budget@policy");
                required.add(path + "/cpu-budget@fallback-factor");
                required.add(path + "/cpu-budget@kernel-id");
            }
        }
        Set<String> emitted = family.emittedItems().stream()
                .map(SchemaItem::display)
                .collect(Collectors.toSet());

        assertThat(required.stream().filter(item -> !emitted.contains(item)).toList())
                .as("reuse sites the fixture family leaves empty").isEmpty();
    }

    @Test
    void theContractIsNotVacuous() {
        Map<String, String> sources = persistenceSources();
        Set<String> elementNames = new TreeSet<>();
        Set<String> attributeNames = new TreeSet<>();
        for (String file : WRITER_FILES) {
            assertThat(sources.get(file)).as("declared writer %s exists and is not blank", file).isNotBlank();
            PersistenceSourceScan.WriterLiterals literals = PersistenceSourceScan.writerLiterals(
                    file, sources.get(file), DYNAMIC_WRITE_SITES, TAG_PARAMETER_BUILDERS);
            assertThat(literals.elements().size() + literals.attributes().size())
                    .as("names the guard reads from writer %s", file).isPositive();
            literals.elements().forEach(literal -> elementNames.add(literal.name()));
            literals.attributes().forEach(literal -> attributeNames.add(literal.name()));
        }
        Set<String> lookedUpElements = new TreeSet<>();
        for (String file : READER_FILES) {
            assertThat(sources.get(file)).as("declared reader %s exists and is not blank", file).isNotBlank();
            PersistenceSourceScan.readerLookups(file, sources.get(file), DECLARED_NAME_READS).elements()
                    .forEach(literal -> lookedUpElements.add(literal.name()));
        }
        assertThat(lookedUpElements).as("element names the reader sources look up")
                .hasSizeGreaterThanOrEqualTo(READER_ELEMENT_LOOKUP_FLOOR)
                .contains("midi-clip", "midi-effect-chain", "transport");
        assertThat(elementNames).as("writer element names").hasSizeGreaterThanOrEqualTo(WRITER_ELEMENT_NAME_FLOOR)
                .contains("daw-project", "midi-clip", "midi-effect-chain");
        assertThat(attributeNames).as("writer attribute names")
                .hasSizeGreaterThanOrEqualTo(WRITER_ATTRIBUTE_NAME_FLOOR)
                .contains("pre-roll-bars", "plugin-id", "id");
        assertThat(family.emittedItems()).as("path-level items the family emitted")
                .hasSizeGreaterThanOrEqualTo(EMITTED_ITEM_FLOOR)
                .contains(SchemaItem.attribute("daw-project/tracks/track", "id"),
                        SchemaItem.element("daw-project/tracks/track/midi-clip"),
                        SchemaItem.element("daw-project/tracks/track/midi-effect-chain"),
                        SchemaItem.attribute("daw-project/transport", "pre-roll-bars"));

        Element root = full.written().getDocumentElement();
        assertThat(full.reads().consumed(root)).as("the reader consumed the root through the view").isTrue();
        assertThat(full.reads().attributeReadCount()).as("attribute reads recorded").isPositive();
        assertThat(full.reads().attributeRead(root, "version"))
                .as("daw-project@version was read through the view: the seam ran the migration registry")
                .isTrue();
        assertThat(full.reopened().getTracks()).as("the recorded parse rebuilt the project").hasSize(4);
    }

    @Test
    void everyLegacyInputIsReadWhenPresentAndNeverWritten() throws IOException {
        Document schema1 = ProjectDeserializer.parseXml(SCHEMA_1_DOCUMENT);
        Map<SchemaItem, List<Element>> present = XmlSchemaParity.emitted(schema1);
        DomReadRecorder reads = new DomReadRecorder();
        DawProject project = new ProjectDeserializer().deserialize(reads.wrap(schema1), null);

        List<String> problems = new ArrayList<>();
        for (LegacyInput legacy : LEGACY_INPUTS) {
            SchemaItem item = SchemaItem.parse(legacy.item());
            List<Element> instances = present.getOrDefault(item, List.of());
            if (instances.isEmpty()) {
                problems.add(legacy.item() + ": the schema-1 document does not hold it");
            } else if (instances.stream().noneMatch(element -> reads.attributeRead(element, item.name()))) {
                problems.add(legacy.item() + ": present in a schema-1 file but never read — the legacy reader"
                        + " is gone; delete the LegacyInput only if such files no longer need it");
            }
            String tagLevel = item.tag() + "@" + item.name();
            if (family.emittedTagAttributes().contains(tagLevel)) {
                problems.add(tagLevel + ": the current writer emits it — it is not a legacy input any more;"
                        + " give it a current reader and delete the LegacyInput");
            }
        }
        assertThat(problems).isEmpty();

        // The reads did what they are for: the class alias, the send mode and the pre-roll restored.
        List<MidiEffectPlugin> chain = project.getTracks().getFirst().getMidiEffectChain();
        assertThat(chain).singleElement().isInstanceOfSatisfying(ArpeggiatorPlugin.class, arp -> {
            assertThat(arp.getPattern()).isEqualTo(ArpeggiatorPlugin.Pattern.DOWN);
            assertThat(arp.isLatch()).isTrue();
        });
        MixerChannel keys = project.getMixer().getChannels().getFirst();
        assertThat(keys.getSends()).extracting(Send::getTap).containsExactly(SendTap.PRE_FADER);
        assertThat(project.getMixerSnapshotManager().getSnapshots().getFirst().channels().getFirst().sends())
                .extracting(SendSnapshot::tap).containsExactly(SendTap.PRE_FADER);
        assertThat(project.getTransport().getPreRollPostRoll()).isEqualTo(new PreRollPostRoll(2, 1, true));
    }

    private static Map<String, String> persistenceSources() {
        Path root = PersistenceSourceScan.locatePersistenceSource();
        assertThat(root).as("run from the daw-core module directory").isDirectory();
        assertThat(root.resolve("ProjectSerializer.java")).as("the persistence package was located").isRegularFile();
        return PersistenceSourceScan.sources(root);
    }

    /**
     * The permanent demonstration that the gate goes red (book §4.4): hiding
     * one item from the deserializer behaves exactly like deleting or
     * renaming its reader, and the report then names that item — one case
     * per read idiom. Where the reader reads other items only because of the
     * hidden one, they are named too, and listed here.
     * {@code daw-project@version} is never hidden: the registry would read
     * schema 1 and run a real migration.
     */
    @Nested
    class FaultInjection {

        static Stream<Arguments> hiddenItems() {
            return Stream.of(
                    Arguments.of("direct-child lookup", "daw-project/tracks/track/midi-effect-chain", List.of()),
                    // Every entry then becomes a placeholder, whose bulk copy
                    // of its attributes reads none of the arpeggiator's by name.
                    Arguments.of("getAttribute",
                            "daw-project/tracks/track/midi-effect-chain/midi-effect@plugin-id", List.of(
                                    "daw-project/tracks/track/midi-effect-chain/midi-effect@rate",
                                    "daw-project/tracks/track/midi-effect-chain/midi-effect@pattern",
                                    "daw-project/tracks/track/midi-effect-chain/midi-effect@octave-range",
                                    "daw-project/tracks/track/midi-effect-chain/midi-effect@gate",
                                    "daw-project/tracks/track/midi-effect-chain/midi-effect@swing",
                                    "daw-project/tracks/track/midi-effect-chain/midi-effect@latch")),
                    Arguments.of("parse*Attr", "daw-project/transport@pre-roll-bars", List.of()),
                    Arguments.of("parseBooleanAttr", "daw-project/transport@pre-roll-enabled", List.of()),
                    // The region is read only when both bounds are present.
                    Arguments.of("hasAttribute", "daw-project/transport@punch-start-frames", List.of(
                            "daw-project/transport@punch-end-frames",
                            "daw-project/transport@punch-enabled")),
                    Arguments.of("child list", "daw-project/tracks/track/midi-clip", List.of()),
                    Arguments.of("child list", "daw-project/tracks/track/midi-clip/notes/note", List.of()),
                    // A reference to a track whose id is unknown is dropped before its values are read.
                    Arguments.of("getAttribute", "daw-project/tracks/track@id", List.of(
                            "daw-project/mixer/cue-buses/cue-bus/cue-send@gain",
                            "daw-project/mixer/cue-buses/cue-bus/cue-send@pan",
                            "daw-project/mixer/cue-buses/cue-bus/cue-send@pre-fader",
                            "daw-project/mixer/channel-links/channel-link@mode",
                            "daw-project/mixer/channel-links/channel-link@link-faders",
                            "daw-project/mixer/channel-links/channel-link@link-pans",
                            "daw-project/mixer/channel-links/channel-link@link-mute-solo",
                            "daw-project/mixer/channel-links/channel-link@link-sends",
                            "daw-project/bed-bus/bed-routings/bed-routing@format",
                            "daw-project/bed-bus/bed-routings/bed-routing@channel-gains-db")),
                    Arguments.of("getTextContent", "daw-project/metadata/name#text", List.of()));
        }

        @ParameterizedTest(name = "{0}: {1}")
        @MethodSource("hiddenItems")
        void hidingAnItemFromTheReaderNamesItInTheReport(String idiom, String hiddenItem,
                                                         List<String> readOnlyBecauseOfIt) throws IOException {
            SchemaItem hidden = SchemaItem.parse(hiddenItem);
            Recorded recorded = record(RoundTripInventoryProjects.full(ASSET_ROOT), Set.of(hidden));
            XmlSchemaParity gate = new XmlSchemaParity().requireNamedReads(writerAttributeNames());
            gate.record("full", recorded.emitted(), recorded.reads());

            List<String> expected = new ArrayList<>(List.of(hiddenItem));
            expected.addAll(readOnlyBecauseOfIt);
            assertThat(gate.writeOnlyTopLevel()).extracting(SchemaItem::display)
                    .as("the items the gate reports when %s is hidden", hiddenItem)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(gate.writeOnlyReport())
                    .startsWith("Write-only persistence (Persistence Integrity book §2.9, §9.3)")
                    .contains("\n  " + hiddenItem + "  emitted ")
                    .contains("[variant \"full\"]");
        }
    }

    /**
     * {@link XmlSchemaParity#requireEveryInstance}: the gate judges a path by
     * the union of its instances, which lets one read instance mask an unread
     * one; strict mode judges each instance — the hook story 334 needs for its
     * per-slot plugin descriptors.
     */
    @Nested
    class StrictPerInstanceMode {

        private static final String DOCUMENT = """
                <rack>
                  <slot descriptor="a"/>
                  <slot descriptor="b"/>
                </rack>
                """;

        @Test
        void strictModeNamesAnItemTheUnionOfInstancesMasks() throws IOException {
            assertThat(gateAfterReadingTheFirstSlot(new XmlSchemaParity()).writeOnly())
                    .as("the union of instances: one read instance handles the path")
                    .isEmpty();

            XmlSchemaParity strict = gateAfterReadingTheFirstSlot(
                    new XmlSchemaParity().requireEveryInstance("**/slot"));

            assertThat(strict.writeOnlyTopLevel()).extracting(SchemaItem::display)
                    .containsExactly("rack/slot@descriptor");
            assertThat(strict.writeOnlyReport())
                    .contains("rack/slot@descriptor  emitted 2x [variant \"rack\"]: read on 1 of 2 instances"
                            + " (strict per-instance mode)");
        }

        private static XmlSchemaParity gateAfterReadingTheFirstSlot(XmlSchemaParity gate) throws IOException {
            Document written = ProjectDeserializer.parseXml(DOCUMENT);
            Map<SchemaItem, List<Element>> emitted = XmlSchemaParity.emitted(written);
            DomReadRecorder reads = new DomReadRecorder();
            Element rack = reads.wrap(written).getDocumentElement();
            List<Element> slots = new ArrayList<>();
            for (int i = 0; i < rack.getChildNodes().getLength(); i++) {
                if (rack.getChildNodes().item(i) instanceof Element slot) {
                    slots.add(slot);
                }
            }
            // Both slots are consumed; only the first one's descriptor is read.
            slots.getFirst().getAttribute("descriptor");
            slots.getLast().getAttribute("unrelated");
            gate.record("rack", emitted, reads);
            return gate;
        }
    }
}
