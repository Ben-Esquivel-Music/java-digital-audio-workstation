package com.benesquivelmusic.daw.core.persistence;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.mixer.InsertEffectFactory;
import com.benesquivelmusic.daw.core.mixer.InsertEffectType;
import com.benesquivelmusic.daw.core.mixer.InsertSlot;
import com.benesquivelmusic.daw.core.mixer.MixerChannel;
import com.benesquivelmusic.daw.core.mixer.OfflineCause;
import com.benesquivelmusic.daw.core.mixer.OfflineInsert;
import com.benesquivelmusic.daw.core.mixer.PassThroughProcessor;
import com.benesquivelmusic.daw.core.mixer.ProcessorRegistry;
import com.benesquivelmusic.daw.core.plugin.AudioFormatPluginContext;
import com.benesquivelmusic.daw.core.plugin.BuiltInPluginGraph;
import com.benesquivelmusic.daw.core.plugin.CompressorPlugin;
import com.benesquivelmusic.daw.core.plugin.DeEsserPlugin;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginEntry;
import com.benesquivelmusic.daw.core.plugin.ExternalPluginLoader;
import com.benesquivelmusic.daw.core.plugin.InstalledPlugin;
import com.benesquivelmusic.daw.core.plugin.LoadedPlugin;
import com.benesquivelmusic.daw.core.plugin.PluginLoadException;
import com.benesquivelmusic.daw.core.plugin.PluginRegistry;
import com.benesquivelmusic.daw.core.plugin.PluginResolution;
import com.benesquivelmusic.daw.core.plugin.PluginResolver;
import com.benesquivelmusic.daw.core.plugin.PluginSourceKind;
import com.benesquivelmusic.daw.core.plugin.UndeclaredThrowPlugins;
import com.benesquivelmusic.daw.core.preset.ReflectivePresetSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.sdk.audio.AudioProcessor;
import com.benesquivelmusic.daw.sdk.editor.PluginManifest;
import com.benesquivelmusic.daw.sdk.plugin.DawPlugin;
import com.benesquivelmusic.daw.sdk.plugin.PluginContext;
import com.benesquivelmusic.daw.sdk.plugin.PluginDescriptor;
import com.benesquivelmusic.daw.sdk.plugin.PluginParameter;
import com.benesquivelmusic.daw.sdk.plugin.PluginType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;

import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.PluginIdentityKey;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.VOX;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.answering;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.backupsIn;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.insertNamed;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.masterInserts;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.resolving;
import static com.benesquivelmusic.daw.core.persistence.InsertFixtures.voxInserts;
import static com.benesquivelmusic.daw.core.persistence.SaveTestSupport.saved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A plugin-backed insert persists through its descriptor (story 334, studio
 * rulings E4, E10, E11, E21; G3, G7, G8, G12, R1): a first-party plugin
 * reopens as the plugin, from this build's own providers; a JAR or CLAP
 * plugin through the resolver, a CLAP plugin with the state it saved —
 * never at its defaults: a state it does not take, or that the file damaged,
 * leaves the insert offline and written back as saved. Every cause logs its
 * exact line.
 */
class PluginInsertDescriptorPersistenceTest {

    private static final AudioFormat FORMAT = new AudioFormat(48_000.0, 2, 24, 256);

    /** The CLAP descriptor on the master, its state the bytes 0..7. */
    private static final String GLUE = "<insert bypassed=\"false\" expensive=\"false\" name=\"Glue\""
            + " plugin-id=\"com.example.glue\" plugin-source=\"clap\" plugin-version=\"1.4.4\">"
            + "<plugin-state>AAECAwQFBgc=</plugin-state></insert>";

    /** A JAR descriptor named and identified as studio ruling E6's examples are. */
    private static final String COMP_X = "<insert bypassed=\"false\" expensive=\"false\" name=\"Comp X\""
            + " plugin-id=\"com.example.comp\" plugin-source=\"jar\" plugin-version=\"1.2.0\"/>";

    private static final String NOT_LOADED = " not loaded (bypassed, kept as saved) — ";

    @TempDir
    Path tempDir;

    private ProjectManager manager;

    @BeforeEach
    void createTheManager() {
        manager = new ProjectManager(new CheckpointManager(new AutoSaveConfig(Duration.ofHours(1), 10, true)));
    }

    @AfterEach
    void releaseTheManager() {
        manager.closeProject().join();
    }

    @Test
    void firstPartyPluginsReopenAsThePluginInOrder() throws Exception {
        DawProject original = new DawProject("Song", FORMAT);
        Track vox = original.createAudioTrack("Vox");
        MixerChannel channel = original.getMixerChannelForTrack(vox);
        BuiltInPluginGraph graph = new BuiltInPluginGraph(ProcessorRegistry.getInstance());
        InsertSlot deEsser = graph.createSlot(new DeEsserPlugin(), new AudioFormatPluginContext(FORMAT),
                original.getMetronome());
        ReflectivePresetSerializer.restore(deEsser.getProcessor(), Map.of("Frequency", 8000.0));
        InsertSlot compressor = graph.createSlot(new CompressorPlugin(), new AudioFormatPluginContext(FORMAT),
                original.getMetronome());
        ReflectivePresetSerializer.restore(compressor.getProcessor(), Map.of("Threshold", -30.0));
        channel.addInsert(deEsser);
        channel.addInsert(compressor);
        channel.addInsert(InsertEffectFactory.createSlot(InsertEffectType.PARAMETRIC_EQ, 2, FORMAT.sampleRate()));

        String xml = new ProjectSerializer().serialize(original);
        assertThat(insertNamed(xml, "Compressor").hasAttribute("effect-type"))
                .as("a plugin saves its identity, not a built-in effect type").isFalse();
        assertThat(insertNamed(xml, "Compressor").getAttribute("plugin-source")).isEqualTo("built-in");
        ProjectDeserializer deserializer = new ProjectDeserializer();
        DawProject reopened = deserializer.deserialize(xml);

        List<InsertSlot> slots = reopened.getMixer().getChannels().getFirst().getInsertSlots();
        assertThat(slots).hasSize(3);
        assertThat(slots.get(0).getPlugin()).isInstanceOf(DeEsserPlugin.class);
        assertThat(slots.get(1).getPlugin()).isInstanceOf(CompressorPlugin.class);
        assertThat(slots.get(2).getPlugin()).isNull();
        assertThat(slots.get(2).getEffectType()).isEqualTo(InsertEffectType.PARAMETRIC_EQ);
        assertThat(ReflectivePresetSerializer.snapshot(slots.get(0).getProcessor()))
                .isEqualTo(ReflectivePresetSerializer.snapshot(deEsser.getProcessor()))
                .containsEntry("Frequency", 8000.0);
        assertThat(ReflectivePresetSerializer.snapshot(slots.get(1).getProcessor()))
                .isEqualTo(ReflectivePresetSerializer.snapshot(compressor.getProcessor()))
                .containsEntry("Threshold", -30.0);
        assertThat(deserializer.getUnreadItems()).isEmpty();
    }

    @Test
    void anUnknownFirstPartyIdOpensOfflineAndIsCounted() throws Exception {
        Opened opened = open(voxInserts("<insert name=\"X FX\" plugin-id=\"com.benesquivelmusic.daw.x\""
                + " plugin-source=\"built-in\" plugin-version=\"1.0.0\"/>"), PluginResolver.NONE);

        assertThat(opened.warnings()).containsExactly("Track 2 'Vox': insert 1 'X FX'" + NOT_LOADED
                + "built-in plugin 'com.benesquivelmusic.daw.x' is not a plugin this build has");
        assertThat(opened.deserializer().getUnreadItems()).containsExactly(UnreadProjectItem.atInsert(
                UnreadProjectItem.Kind.INSERT_NOT_LOADED, VOX, 0, "X FX",
                "built-in plugin 'com.benesquivelmusic.daw.x' is not a plugin this build has"));
        assertThat(opened.deserializer().losesSavedContent()).isFalse();
    }

    @Test
    void aClapPluginWhoseStateLoadThrowsOpensOfflineAndKeepsItsState() throws Exception {
        assertAClapPluginThatDoesNotTakeItsStateOpensOffline(FakeClapHost.Load.THROW, "IllegalArgumentException",
                "IllegalArgumentException");
    }

    @Test
    void aClapPluginThatReportsFailureOpensOfflineAndKeepsItsState() throws Exception {
        assertAClapPluginThatDoesNotTakeItsStateOpensOffline(FakeClapHost.Load.REPORT_FAILURE, "",
                "the plugin reported failure");
    }

    /**
     * @param failure the cause's failure: what the plugin threw, empty when it reported failure
     * @param inLine  what the line's parenthesis ends with
     */
    private void assertAClapPluginThatDoesNotTakeItsStateOpensOffline(FakeClapHost.Load load, String failure,
                                                                      String inLine) throws Exception {
        List<FakeClapHost> made = new CopyOnWriteArrayList<>();
        PluginResolver resolver = clap(() -> {
            FakeClapHost host = new FakeClapHost("1.4.4", load);
            made.add(host);
            return host;
        });
        Opened opened = open(masterInserts(GLUE), resolver);

        InsertSlot slot = opened.project().getMixer().getMasterChannel().getInsertSlots().getFirst();
        assertThat(slot.offline()).map(OfflineInsert::cause).contains(new OfflineCause.StateRejected(
                PluginSourceKind.CLAP, FakeClapHost.ID, "1.4.4", "1.4.4", failure));
        assertThat(opened.warnings()).containsExactly("Mixer master: insert 1 'Glue'" + NOT_LOADED
                + "CLAP plugin 'com.example.glue' did not accept its saved state (saved with version '1.4.4',"
                + " installed version 1.4.4; " + inLine + ")");
        assertThat(made).singleElement().satisfies(host ->
                assertThat(host.isDisposed()).as("the half-built instance is disposed").isTrue());
        assertThat(opened.deserializer().losesSavedContent()).isFalse();

        assertWrittenBackWithoutABackup(masterInserts(GLUE), resolver, "<plugin-state>AAECAwQFBgc=</plugin-state>");
    }

    @Test
    void aClapSlotOpensOfflineWhenItsIdIsInstalledAsAJar() throws Exception {
        PluginResolver resolver = answering(new PluginResolution.InstalledAsOtherKind(PluginSourceKind.JAR));
        Opened opened = open(masterInserts(GLUE), resolver);

        assertThat(opened.warnings()).containsExactly("Mixer master: insert 1 'Glue'" + NOT_LOADED
                + "CLAP plugin 'com.example.glue' version '1.4.4' is not installed; 'com.example.glue' is installed"
                + " as a JAR plugin");
        assertWrittenBackWithoutABackup(masterInserts(GLUE), resolver, "<plugin-state>AAECAwQFBgc=</plugin-state>");
    }

    @Test
    void aStateThatIsNotBase64OpensOfflineAndIsWrittenBackUnchanged() throws Exception {
        String damaged = GLUE.replace("AAECAwQFBgc=", "!!not base64");
        List<FakeClapHost> made = new CopyOnWriteArrayList<>();
        PluginResolver resolver = clap(() -> {
            FakeClapHost host = new FakeClapHost();
            made.add(host);
            return host;
        });
        String firstPartyCompressor = "<insert name=\"Compressor\" plugin-id=\"" + CompressorPlugin.PLUGIN_ID
                + "\" plugin-source=\"built-in\" plugin-version=\"1.0.0\"/>";
        Opened opened = open(masterInserts(firstPartyCompressor + damaged), resolver);

        List<InsertSlot> slots = opened.project().getMixer().getMasterChannel().getInsertSlots();
        assertThat(slots).hasSize(2);
        assertThat(slots.get(1).offline()).as("offline at its index").map(OfflineInsert::cause).contains(
                new OfflineCause.StateUnreadable(PluginSourceKind.CLAP, FakeClapHost.ID, "1.4.4"));
        assertThat(opened.warnings()).containsExactly("Mixer master: insert 2 'Glue'" + NOT_LOADED
                + "CLAP plugin 'com.example.glue' version '1.4.4' has a saved state that is not valid Base64;"
                + " the plugin was not given it");
        assertThat(made).allSatisfy(host -> assertThat(host.isDisposed()).isTrue());
        assertThat(opened.deserializer().losesSavedContent()).isFalse();

        assertWrittenBackWithoutABackup(masterInserts(damaged), resolver, "<plugin-state>!!not base64</plugin-state>");
    }

    @Test
    void aClapPluginThatFailsToSaveKeepsTheStateItLastSaved() throws Exception {
        List<FakeClapHost> made = new CopyOnWriteArrayList<>();
        DawProject project = open(masterInserts(GLUE), clap(() -> {
            FakeClapHost host = new FakeClapHost();
            made.add(host);
            return host;
        })).project();
        assertThat(made).singleElement().satisfies(host ->
                assertThat(host.state()).as("fixture: the state was loaded").containsExactly(0, 1, 2, 3, 4, 5, 6, 7));
        made.getFirst().failSaves();

        String written;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectSerializer.class)) {
            written = new ProjectSerializer().serialize(project);
            warnings = log.at(Level.WARNING);
        }
        assertThat(warnings).containsExactly("Mixer master: insert 1 'Glue' CLAP plugin 'com.example.glue' did not"
                + " return its state; the state it last saved is kept");
        assertThat(insertNamed(written, "Glue").getElementsByTagName("plugin-state").item(0).getTextContent())
                .isEqualTo("AAECAwQFBgc=");

        // Studio ruling G8: no earlier state, so none is written and the line says so.
        DawProject fresh = new DawProject("Fresh", FORMAT);
        FakeClapHost host = new FakeClapHost();
        host.failSaves();
        fresh.getMixer().getMasterChannel().addInsert(InsertEffectFactory.createSlotFromPlugin(host).orElseThrow());
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectSerializer.class)) {
            written = new ProjectSerializer().serialize(fresh);
            warnings = log.at(Level.WARNING);
        }
        assertThat(warnings).containsExactly("Mixer master: insert 1 'Glue' CLAP plugin 'com.example.glue' did not"
                + " return its state, and it has no earlier saved state; it will reopen at its defaults");
        assertThat(written).doesNotContain("plugin-state");
    }

    @Test
    void aStoreParameterReachesThePluginBeforeItsFirstBlockAndARefusedOneIsReported() throws Exception {
        List<StoreParameterJarTestPlugin> made = new CopyOnWriteArrayList<>();
        PluginResolver resolver = resolving(Map.of(
                new PluginIdentityKey(PluginSourceKind.JAR, StoreParameterJarTestPlugin.ID), () -> {
                    StoreParameterJarTestPlugin plugin = new StoreParameterJarTestPlugin();
                    made.add(plugin);
                    return plugin;
                }));
        Opened opened = open(voxInserts("<insert name=\"Knob Box\" plugin-id=\"" + StoreParameterJarTestPlugin.ID
                + "\" plugin-source=\"jar\" plugin-version=\"2.0\">"
                + "<parameter id=\"3\" value=\"7.5\"/><parameter id=\"0\" value=\"0.75\"/></insert>"), resolver);

        MixerChannel vox = opened.project().getMixer().getChannels().get(1);
        InsertSlot slot = vox.getInsertSlots().getFirst();
        StoreParameterJarTestPlugin plugin = made.getFirst();
        assertThat(plugin.value(StoreParameterJarTestPlugin.DRIVE)).as("held before any block renders")
                .isEqualTo(7.5);
        assertThat(slot.getParameterStore().valueById(StoreParameterJarTestPlugin.DRIVE)).isEqualTo(7.5);
        assertThat(slot.getParameterStore().snapshotPendingUiValues()).as("nothing left for the audio thread")
                .isEmpty();
        assertThat(plugin.value(StoreParameterJarTestPlugin.LEVEL)).isEqualTo(0.5);
        assertThat(opened.warnings()).containsExactly("Track 2 'Vox': insert 1 'Knob Box' parameter adjusted —"
                + " parameter id 0 value '0.75' is rejected by the insert; 0.5 is used");

        vox.prepareEffectsChain(2, FORMAT.bufferSize());
        float[][] input = new float[2][FORMAT.bufferSize()];
        float[][] output = new float[2][FORMAT.bufferSize()];
        for (int block = 0; block < 10; block++) {
            vox.drainInsertParameters();
            vox.getEffectsChain().process(input, output, FORMAT.bufferSize());
        }
        assertThat(slot.isBypassed()).as("the slot stays working").isFalse();
    }

    @Test
    void aPluginSavedWithAnotherVersionOpensTheInstalledOneWithOneInfoLine() throws Exception {
        PluginResolver resolver = resolving(Map.of(new PluginIdentityKey(PluginSourceKind.JAR, AnnotatedJarTestPlugin.ID),
                () -> new AnnotatedJarTestPlugin("2.0")));
        String xml = voxInserts("<insert name=\"Annotated Gain\" plugin-id=\"" + AnnotatedJarTestPlugin.ID
                + "\" plugin-source=\"jar\" plugin-version=\"1.0\"><parameter name=\"Gain\" value=\"0.5\"/></insert>");
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.setPluginResolver(resolver);
        DawProject project;
        List<String> infos;
        List<String> warnings;
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            project = deserializer.deserialize(xml);
            infos = log.at(Level.INFO);
            warnings = log.at(Level.WARNING);
        }

        InsertSlot slot = project.getMixer().getChannels().get(1).getInsertSlots().getFirst();
        assertThat(slot.getPlugin()).isInstanceOfSatisfying(AnnotatedJarTestPlugin.class, plugin ->
                assertThat(plugin.processor().getGain()).as("the saved state is applied").isEqualTo(0.5));
        assertThat(infos).containsExactly("Track 2 'Vox': insert 1 'Annotated Gain' JAR plugin"
                + " 'com.example.annotated.gain' was saved with version '1.0' and opens with installed version 2.0");
        assertThat(warnings).isEmpty();
        assertThat(deserializer.getUnreadItems()).isEmpty();
        assertThat(insertNamed(new ProjectSerializer().serialize(project), "Annotated Gain")
                .getAttribute("plugin-version")).as("the next save writes the installed version").isEqualTo("2.0");

        assertWrittenBackWithoutABackup(xml, resolver, "plugin-version=\"2.0\"");
    }

    /**
     * A read that fails after it built inserts releases their plugins before
     * the failure leaves: nobody installs the half-built project, and a JAR
     * plugin's loader would keep its file open (critique J11). The one
     * failure an insert's open does not absorb is the virtual machine's own
     * (ruling-3 C3).
     */
    @Test
    void aReadThatFailsAfterBuildingInsertsReleasesTheirPlugins() throws Exception {
        List<AnnotatedJarTestPlugin> made = new CopyOnWriteArrayList<>();
        PluginResolver resolver = (identity, context) -> {
            if (!identity.id().equals(AnnotatedJarTestPlugin.ID)) {
                throw new VirtualMachineFailure();
            }
            AnnotatedJarTestPlugin plugin = new AnnotatedJarTestPlugin();
            plugin.initialize(context);
            made.add(plugin);
            return new PluginResolution.Resolved(new LoadedPlugin(plugin, plugin::dispose));
        };
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.setPluginResolver(resolver);
        String xml = voxInserts("<insert name=\"Annotated Gain\" plugin-id=\"" + AnnotatedJarTestPlugin.ID
                + "\" plugin-source=\"jar\" plugin-version=\"1.0.0\"/>" + COMP_X);

        assertThatThrownBy(() -> deserializer.deserialize(xml)).isInstanceOf(VirtualMachineFailure.class);

        assertThat(made).hasSize(1);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!made.getFirst().isDisposed() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(made.getFirst().isDisposed()).as("the built plugin is released").isTrue();
    }

    /** A failure no reader handles: the virtual machine's own, which escapes every insert's open. */
    private static final class VirtualMachineFailure extends VirtualMachineError {
    }

    /**
     * Ruling-3 C3: plugin code that throws while its insert opens — even an
     * error that is not a {@link LinkageError}, an {@link AssertionError} from
     * {@code activate()} or from its parameter setter as the saved parameters
     * are applied — is the plugin failing to load: the plugin is disposed,
     * the insert opens offline as failed to load, and the project opens (the
     * open never fails because of an insert).
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(FailingStep.class)
    void aPluginThatThrowsAnErrorWhileItsInsertOpensIsDisposedAndOpensOffline(FailingStep step) throws Exception {
        assertAnErrorOfThePluginsOwnOpensItsInsertOffline(step, () -> new AssertionError("the plugin failed"),
                "AssertionError");
    }

    /**
     * The manager's decision on finding FX2-7: a {@link StackOverflowError}
     * the plugin's own code throws is the plugin failing, as any error but
     * the virtual machine's own: the plugin is disposed, the insert opens
     * offline as failed to load, and the project opens.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(FailingStep.class)
    void aStackOverflowInThePluginsOwnCodeIsThePluginFailingToLoad(FailingStep step) throws Exception {
        assertAnErrorOfThePluginsOwnOpensItsInsertOffline(step, StackOverflowError::new, "StackOverflowError");
    }

    private static void assertAnErrorOfThePluginsOwnOpensItsInsertOffline(FailingStep step,
                                                                          Supplier<? extends Error> error,
                                                                          String failure) throws IOException {
        List<FailingPlugin> made = new CopyOnWriteArrayList<>();
        Opened opened = open(voxInserts(COMP_X.replace("/>", "><parameter id=\"0\" value=\"0.3\"/></insert>")),
                jar(() -> {
                    FailingPlugin plugin = new FailingPlugin(step, error);
                    made.add(plugin);
                    return plugin;
                }));

        InsertSlot insert = opened.project().getMixer().getChannels().get(1).getInsertSlots().getFirst();
        assertThat(insert.offline()).map(OfflineInsert::cause).contains(new OfflineCause.FailedToLoad(
                PluginSourceKind.JAR, "com.example.comp", "1.2.0", failure));
        assertThat(made).singleElement().satisfies(plugin ->
                assertThat(plugin.disposals()).as("the plugin the open built is disposed once").isEqualTo(1));
        assertThat(opened.warnings()).containsExactly("Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED
                + "JAR plugin 'com.example.comp' version '1.2.0' failed to load (" + failure + ")");
        assertThat(opened.deserializer().getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind).isEqualTo(UnreadProjectItem.Kind.INSERT_NOT_LOADED);
    }

    /**
     * The manager's decision on finding FX2-7: an error of the virtual
     * machine's own still fails the read, but the plugin its insert was
     * opening is disposed before it leaves.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(FailingStep.class)
    void aVirtualMachineErrorFailsTheReadAfterTheOpeningPluginIsDisposed(FailingStep step) {
        List<FailingPlugin> made = new CopyOnWriteArrayList<>();
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.setPluginResolver(jar(() -> {
            FailingPlugin plugin = new FailingPlugin(step, VirtualMachineFailure::new);
            made.add(plugin);
            return plugin;
        }));

        assertThatThrownBy(() -> deserializer.deserialize(voxInserts(
                COMP_X.replace("/>", "><parameter id=\"0\" value=\"0.3\"/></insert>"))))
                .isInstanceOf(VirtualMachineFailure.class);
        assertThat(made).singleElement().satisfies(plugin ->
                assertThat(plugin.disposals()).as("disposed before the error leaves").isEqualTo(1));
    }

    /**
     * Finding FX2-2: a plugin whose {@code initialize} throws a checked
     * exception it does not declare is disposed with its class loader closed,
     * and its insert opens offline as failed to load. The registry resolves
     * it, as a project open does; its catalogue instance is the only other
     * one, so once the registry releases that, the JAR can be deleted — on
     * Windows, only if the open's loader is closed too.
     */
    @Test
    void aPluginWhoseInitializeThrowsAnUndeclaredCheckedExceptionOpensOfflineAndIsReleased() throws Exception {
        Path jar = UndeclaredThrowPlugins.buildJar(tempDir.resolve("Downloads"), "sneaky.jar");
        PluginManifest manifest = UndeclaredThrowPlugins.manifest();
        ExternalPluginEntry entry = new ExternalPluginEntry(jar, manifest.pluginClass());
        PluginRegistry registry = new PluginRegistry();
        registry.install(new InstalledPlugin(entry, manifest, "sneaky.jar"),
                ExternalPluginLoader.loadWithClassLoader(entry));
        UndeclaredThrowPlugins.Base.resetDisposals();
        String sneaky = "<insert bypassed=\"false\" expensive=\"false\" name=\"Sneaky Comp\" plugin-id=\""
                + UndeclaredThrowPlugins.ID + "\" plugin-source=\"jar\" plugin-version=\""
                + UndeclaredThrowPlugins.VERSION + "\"/>";

        Opened opened;
        int disposedByTheOpen;
        try {
            opened = open(voxInserts(sneaky), registry);
            disposedByTheOpen = UndeclaredThrowPlugins.Base.disposals();
        } finally {
            registry.disposeAll();
        }

        assertThat(disposedByTheOpen).as("the instance the open made is disposed").isEqualTo(1);
        InsertSlot insert = opened.project().getMixer().getChannels().get(1).getInsertSlots().getFirst();
        assertThat(insert.offline()).map(OfflineInsert::cause).contains(new OfflineCause.FailedToLoad(
                PluginSourceKind.JAR, UndeclaredThrowPlugins.ID, UndeclaredThrowPlugins.VERSION, "IOException"));
        Files.delete(jar);
        assertThat(jar).as("every loader of the JAR is closed").doesNotExist();
    }

    /** Where a {@link FailingPlugin}'s own code throws. */
    enum FailingStep {
        ACTIVATION,
        PARAMETER_SETTER
    }

    /** Each row: the cause, the resolver, the file, the one WARNING its open logs. */
    static Stream<Arguments> causes() {
        PluginResolver none = PluginResolver.NONE;
        String compressor = "<insert effect-type=\"COMPRESSOR\" name=\"Compressor\"/>";
        return Stream.of(
                Arguments.of("an unknown built-in type", none,
                        voxInserts(compressor + "<insert effect-type=\"NOT_A_TYPE\" name=\"Old FX\"/>"),
                        "Track 2 'Vox': insert 2 'Old FX'" + NOT_LOADED
                                + "effect-type 'NOT_A_TYPE' is not an effect this build knows"),
                Arguments.of("a built-in that cannot run in the format", none,
                        InsertFixtures.file(1, "", "<insert effect-type=\"STEREO_IMAGER\" name=\"Imager\"/>"),
                        "Track 2 'Vox': insert 1 'Imager'" + NOT_LOADED + "effect-type 'STEREO_IMAGER' cannot run in"
                                + " this project's audio format (channels 1, sample rate 48000.0 Hz)"),
                Arguments.of("an unknown first-party plugin", none,
                        voxInserts("<insert name=\"X\" plugin-id=\"com.benesquivelmusic.daw.x\""
                                + " plugin-source=\"built-in\" plugin-version=\"1.0.0\"/>"),
                        "Track 2 'Vox': insert 1 'X'" + NOT_LOADED
                                + "built-in plugin 'com.benesquivelmusic.daw.x' is not a plugin this build has"),
                Arguments.of("a JAR plugin that is not installed", none,
                        voxInserts(compressor + compressor + COMP_X),
                        "Track 2 'Vox': insert 3 'Comp X'" + NOT_LOADED
                                + "JAR plugin 'com.example.comp' version '1.2.0' is not installed"),
                Arguments.of("an id and a version that would split the line", none,
                        voxInserts("<insert name=\"Comp X\" plugin-id=\"com.example&#10;&lt;comp\""
                                + " plugin-source=\"jar\" plugin-version=\"1.&quot;2\"/>"),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED
                                + "JAR plugin 'com.example&#xA;&lt;comp' version '1.&quot;2' is not installed"),
                Arguments.of("a CLAP plugin that is not installed", none, masterInserts(GLUE),
                        "Mixer master: insert 1 'Glue'" + NOT_LOADED
                                + "CLAP plugin 'com.example.glue' version '1.4.4' is not installed"),
                Arguments.of("a JAR plugin installed as another kind",
                        answering(new PluginResolution.InstalledAsOtherKind(PluginSourceKind.CLAP)),
                        voxInserts(COMP_X),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED + "JAR plugin 'com.example.comp' version"
                                + " '1.2.0' is not installed; 'com.example.comp' is installed as a CLAP plugin"),
                Arguments.of("a JAR plugin whose file did not load at startup",
                        answering(new PluginResolution.MissingAtStartup()), voxInserts(COMP_X),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED + "JAR plugin 'com.example.comp' version"
                                + " '1.2.0' is installed, but its file did not load when the application started"),
                Arguments.of("a CLAP plugin whose state load throws",
                        clap(() -> new FakeClapHost("2.0", FakeClapHost.Load.THROW)),
                        masterInserts(GLUE.replace("plugin-version=\"1.4.4\"", "plugin-version=\"1.0\"")),
                        "Mixer master: insert 1 'Glue'" + NOT_LOADED + "CLAP plugin 'com.example.glue' did not"
                                + " accept its saved state (saved with version '1.0', installed version 2.0;"
                                + " IllegalArgumentException)"),
                Arguments.of("a CLAP plugin that reports it did not take its state",
                        clap(() -> new FakeClapHost("1.4.4", FakeClapHost.Load.REPORT_FAILURE)), masterInserts(GLUE),
                        "Mixer master: insert 1 'Glue'" + NOT_LOADED + "CLAP plugin 'com.example.glue' did not"
                                + " accept its saved state (saved with version '1.4.4', installed version 1.4.4;"
                                + " the plugin reported failure)"),
                Arguments.of("a CLAP state that is not Base64", clap(FakeClapHost::new),
                        masterInserts(GLUE.replace("AAECAwQFBgc=", "!!not base64")),
                        "Mixer master: insert 1 'Glue'" + NOT_LOADED + "CLAP plugin 'com.example.glue' version"
                                + " '1.4.4' has a saved state that is not valid Base64; the plugin was not given it"),
                Arguments.of("a snapshot restore, which loads no plugin",
                        answering(new PluginResolution.NotLoadedBySnapshotRestore()), voxInserts(COMP_X),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED + "JAR plugin 'com.example.comp' version"
                                + " '1.2.0' was not loaded, because restoring a snapshot does not load plugins"),
                Arguments.of("a JAR plugin whose constructor threw",
                        answering(PluginResolution.FailedToLoad.of(new PluginLoadException("failed",
                                new InvocationTargetException(new UnsatisfiedLinkError("no native library"))))),
                        voxInserts(COMP_X),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED
                                + "JAR plugin 'com.example.comp' version '1.2.0' failed to load (UnsatisfiedLinkError)"),
                Arguments.of("a JAR plugin with no audio processor", jar(() -> pluginWithoutAProcessor("2.0")),
                        voxInserts(COMP_X),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED + "JAR plugin 'com.example.comp' version"
                                + " '1.2.0' failed to load (the installed version 2.0 has no audio processor)"),
                Arguments.of("a JAR plugin whose activation throws", jar(PluginInsertDescriptorPersistenceTest::refusingActivation),
                        voxInserts(COMP_X),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED
                                + "JAR plugin 'com.example.comp' version '1.2.0' failed to load (IllegalStateException)"),
                Arguments.of("a resolver that throws", (PluginResolver) (identity, context) -> {
                            throw new UnsupportedOperationException("no registry");
                        }, voxInserts(COMP_X),
                        "Track 2 'Vox': insert 1 'Comp X'" + NOT_LOADED + "JAR plugin 'com.example.comp' version"
                                + " '1.2.0' failed to load (UnsupportedOperationException)"),
                Arguments.of("a stub with no identity", none,
                        voxInserts("<insert bypassed=\"true\" expensive=\"false\" name=\"Old Plugin\"/>"),
                        "Track 2 'Vox': insert 1 'Old Plugin'" + NOT_LOADED
                                + "the file records no plugin identity for it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("causes")
    void everyCauseLogsItsExactLine(String cause, PluginResolver resolver, String xml, String line) throws Exception {
        Opened opened = open(xml, resolver);

        assertThat(opened.warnings()).as(cause).containsExactly(line);
        assertThat(opened.deserializer().getUnreadItems()).singleElement()
                .extracting(UnreadProjectItem::kind).isEqualTo(UnreadProjectItem.Kind.INSERT_NOT_LOADED);
        assertThat(opened.deserializer().losesSavedContent()).as("written back as saved").isFalse();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A project read from a file, the deserializer that read it, and the WARNINGs it logged. */
    private record Opened(DawProject project, ProjectDeserializer deserializer, List<String> warnings) {
    }

    private static Opened open(String xml, PluginResolver resolver) throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        deserializer.setPluginResolver(resolver);
        try (InsertFixtures.CapturedLog log = InsertFixtures.CapturedLog.of(ProjectDeserializer.class)) {
            DawProject project = deserializer.deserialize(xml);
            return new Opened(project, deserializer, log.at(Level.WARNING));
        }
    }

    /** Opens {@code xml} through a manager resolving with {@code resolver}, saves it, and checks what it wrote. */
    private void assertWrittenBackWithoutABackup(String xml, PluginResolver resolver, String writtenBack)
            throws IOException {
        Path directory = manager.createProject("Song", tempDir.resolve("written-" + System.nanoTime()))
                .projectPath();
        manager.closeProject().join();
        Files.writeString(directory.resolve("project.daw"), xml);
        manager.setPluginResolver(resolver);
        manager.openProject(directory);
        saved(manager, manager.getCurrentDawProject());

        assertThat(backupsIn(directory)).as("nothing the save drops, so no backup").isEmpty();
        assertThat(Files.readString(directory.resolve("project.daw"))).contains(writtenBack);
    }

    private static PluginResolver clap(Supplier<DawPlugin> host) {
        return resolving(Map.of(new PluginIdentityKey(PluginSourceKind.CLAP, FakeClapHost.ID), host));
    }

    private static PluginResolver jar(Supplier<DawPlugin> plugin) {
        return resolving(Map.of(new PluginIdentityKey(PluginSourceKind.JAR, "com.example.comp"), plugin));
    }

    /** A JAR plugin at {@code version} that offers no audio processor. */
    private static DawPlugin pluginWithoutAProcessor(String version) {
        return new TestPlugin(version) {
        };
    }

    /** A JAR plugin whose activation throws. */
    private static DawPlugin refusingActivation() {
        return new TestPlugin("1.2.0") {
            @Override
            public void activate() {
                throw new IllegalStateException("licence check failed");
            }

            @Override
            public Optional<AudioProcessor> asAudioProcessor() {
                return Optional.of(new PassThroughProcessor(2));
            }
        };
    }

    /**
     * A JAR plugin with one parameter, id 0, whose own code throws the error
     * {@code error} makes — an {@link AssertionError}, say, an error that is
     * not a {@link LinkageError} — at {@code step}, and which counts its
     * disposals.
     */
    private static final class FailingPlugin extends TestPlugin {
        private final FailingStep step;
        private final Supplier<? extends Error> error;
        private final AtomicInteger disposals = new AtomicInteger();

        FailingPlugin(FailingStep step, Supplier<? extends Error> error) {
            super("1.2.0");
            this.step = step;
            this.error = error;
        }

        @Override
        public void activate() {
            if (step == FailingStep.ACTIVATION) {
                throw error.get();
            }
        }

        @Override
        public List<PluginParameter> getParameters() {
            return List.of(new PluginParameter(0, "Level", 0.0, 1.0, 0.5));
        }

        @Override
        public void setAutomatableParameter(int parameterId, double value) {
            if (step == FailingStep.PARAMETER_SETTER) {
                throw error.get();
            }
        }

        @Override
        public Optional<AudioProcessor> asAudioProcessor() {
            return Optional.of(new PassThroughProcessor(2));
        }

        @Override
        public void dispose() {
            disposals.incrementAndGet();
        }

        int disposals() {
            return disposals.get();
        }
    }

    /** A minimal JAR plugin with the id {@code com.example.comp}. */
    private abstract static class TestPlugin implements DawPlugin {
        private final String version;

        TestPlugin(String version) {
            this.version = version;
        }

        @Override
        public PluginDescriptor getDescriptor() {
            return new PluginDescriptor("com.example.comp", "Comp X", version, "Example Audio", PluginType.EFFECT);
        }

        @Override
        public void initialize(PluginContext context) {
        }

        @Override
        public void activate() {
        }

        @Override
        public void deactivate() {
        }

        @Override
        public void dispose() {
        }
    }
}
