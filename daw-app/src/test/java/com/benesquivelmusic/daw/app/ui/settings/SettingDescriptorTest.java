package com.benesquivelmusic.daw.app.ui.settings;

import com.benesquivelmusic.daw.app.ui.SettingsModel;
import com.benesquivelmusic.daw.sdk.audio.MixPrecision;
import com.benesquivelmusic.daw.sdk.audio.SampleRateConverter.QualityTier;
import com.benesquivelmusic.daw.sdk.audio.performance.DegradationPolicy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 305 — proves each {@link SettingDescriptor}'s validator delegates
 * to the canonical {@code SettingsModel} setter guard rather than a
 * parallel re-implementation: for every {@code SettingsModel}-backed
 * descriptor and a table of accept/reject candidates,
 * {@code descriptor.accepts(v)} must equal "the real setter does not
 * throw for {@code v}". Also pins the record's nine-field §3.4 shape,
 * the defensive synonyms copy, and that descriptor defaults equal the
 * authority's defaults (a fresh model over an empty node).
 */
class SettingDescriptorTest {

    private static Preferences isolatedNode() {
        return Preferences.userRoot().node("settingDescriptorTest_" + System.nanoTime());
    }

    /**
     * One invoker per SettingsModel-backed descriptor id, calling the
     * REAL setter on the given model with an untyped candidate.
     */
    private static Map<String, Consumer<Object>> settersById(SettingsModel model) {
        Map<String, Consumer<Object>> setters = new LinkedHashMap<>();
        setters.put("audio.sampleRate", v -> model.setSampleRate((Double) v));
        setters.put("audio.bitDepth", v -> model.setBitDepth((Integer) v));
        setters.put("audio.bufferSize", v -> model.setBufferSize((Integer) v));
        setters.put("audio.mixPrecision", v -> model.setMixPrecision((MixPrecision) v));
        setters.put("audio.backend", v -> model.setAudioBackend((String) v));
        setters.put("audio.inputDevice", v -> model.setAudioInputDevice((String) v));
        setters.put("audio.outputDevice", v -> model.setAudioOutputDevice((String) v));
        setters.put("audio.applyLatencyCompensation",
                v -> model.setApplyLatencyCompensation((Boolean) v));
        setters.put("audio.srcQuality", v -> model.setSrcQuality((QualityTier) v));
        setters.put("audio.workerPoolSize", v -> model.setWorkerPoolSize((Integer) v));
        setters.put("audio.masterCpuBudgetFraction",
                v -> model.setMasterCpuBudgetFraction((Double) v));
        // The descriptor's value is the persisted policy short-name String.
        // SettingsModel.parsePolicy (package-private, unreachable here) maps
        // every non-null name to a policy object the setter accepts, so the
        // real guard chain sees null iff the name is null — mirror exactly
        // that: null name -> null policy (requireNonNull rejects), non-null
        // name -> a policy instance (accepted).
        setters.put("audio.masterCpuBudgetPolicy", v -> model.setMasterCpuBudgetPolicy(
                v == null ? null : new DegradationPolicy.DoNothing()));
        setters.put("project.autoSaveIntervalSeconds",
                v -> model.setAutoSaveIntervalSeconds((Integer) v));
        setters.put("project.defaultTempo", v -> model.setDefaultTempo((Double) v));
        setters.put("project.useJournaledPersistence",
                v -> model.setUseJournaledPersistence((Boolean) v));
        setters.put("appearance.uiScale", v -> model.setUiScale((Double) v));
        setters.put("plugins.scanPaths", v -> model.setPluginScanPaths((String) v));
        return setters;
    }

    /**
     * The accept/reject candidate table per setting. Arrays.asList (not
     * List.of) because null itself is a candidate for the reference-typed
     * settings.
     */
    private static Map<String, List<Object>> candidatesById() {
        Map<String, List<Object>> candidates = new LinkedHashMap<>();
        candidates.put("audio.sampleRate", Arrays.asList(44_100.0, 96_000.0, 0.0, -1.0));
        candidates.put("audio.bitDepth", Arrays.asList(16, 0));
        candidates.put("audio.bufferSize", Arrays.asList(256, -256));
        candidates.put("audio.mixPrecision", Arrays.asList(MixPrecision.FLOAT_32, null));
        candidates.put("audio.backend", Arrays.asList("ASIO", null));
        candidates.put("audio.inputDevice", Arrays.asList("USB Interface", null));
        candidates.put("audio.outputDevice", Arrays.asList("Main Out", null));
        candidates.put("audio.applyLatencyCompensation", Arrays.asList(true, false));
        candidates.put("audio.srcQuality", Arrays.asList(QualityTier.HIGH, null));
        candidates.put("audio.workerPoolSize", Arrays.asList(1, 0));
        candidates.put("audio.masterCpuBudgetFraction",
                Arrays.asList(0.5, 1.0, 0.0, 1.5, Double.NaN));
        candidates.put("audio.masterCpuBudgetPolicy", Arrays.asList("DoNothing", null));
        candidates.put("project.autoSaveIntervalSeconds", Arrays.asList(30, 0));
        candidates.put("project.defaultTempo",
                Arrays.asList(20.0, 999.0, 19.9, 999.1, 1000.0));
        candidates.put("project.useJournaledPersistence", Arrays.asList(true, false));
        candidates.put("appearance.uiScale", Arrays.asList(0.5, 3.0, 0.49, 3.01));
        candidates.put("plugins.scanPaths", Arrays.asList("C:\\plugins;D:\\vst", null));
        return candidates;
    }

    private static boolean setterAccepts(Consumer<Object> setter, Object candidate) {
        try {
            setter.accept(candidate);
            return true;
        } catch (IllegalArgumentException | NullPointerException rejected) {
            return false;
        }
    }

    @Test
    void validatorShouldMatchTheCanonicalSetterGuardForEveryCandidate() {
        SettingsCatalogue catalogue = SettingsCatalogue.create();
        SettingsModel model = new SettingsModel(isolatedNode());
        Map<String, Consumer<Object>> setters = settersById(model);
        Map<String, List<Object>> candidates = candidatesById();
        // Non-empty guard: the table must cover all 17 SettingsModel-backed
        // descriptors, so a silently shrunken table cannot green-wash this.
        assertThat(candidates).hasSize(17);
        assertThat(candidates.keySet()).isEqualTo(setters.keySet());

        for (Map.Entry<String, List<Object>> entry : candidates.entrySet()) {
            String id = entry.getKey();
            SettingDescriptor<?> descriptor = catalogue.byId(id).orElseThrow(
                    () -> new AssertionError("No descriptor for setting: " + id));
            Consumer<Object> setter = setters.get(id);
            for (Object candidate : entry.getValue()) {
                boolean expected = setterAccepts(setter, candidate);
                assertThat(descriptor.accepts(candidate))
                        .as("%s.accepts(%s) must equal the real setter guard (%s)",
                                id, candidate, expected)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void synonymsShouldBeDefensivelyCopiedAndImmutable() {
        List<String> source = new ArrayList<>(List.of("latency", "frames"));
        SettingDescriptor<Integer> descriptor = new SettingDescriptor<>(
                "test.bufferSize", "Buffer Size", "A test descriptor.", source,
                Scope.AUDIO_DEVICE, ApplyClass.ENGINE_RECONFIGURE, ControlKind.CHOICE,
                256, value -> value > 0);

        source.add("mutated-after-construction");

        assertThat(descriptor.synonyms())
                .as("mutating the source list must not reach the descriptor")
                .containsExactly("latency", "frames");
        assertThatThrownBy(() -> descriptor.synonyms().add("nope"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recordShouldHaveExactlyTheNineBookComponentsInOrder() {
        RecordComponent[] components = SettingDescriptor.class.getRecordComponents();
        assertThat(components).hasSize(9);
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .containsExactly("id", "label", "description", "synonyms",
                        "scope", "applyClass", "controlKind", "defaultValue", "validator");
    }

    @Test
    void descriptorDefaultsShouldEqualTheCanonicalModelDefaults() {
        SettingsCatalogue catalogue = SettingsCatalogue.create();
        Preferences emptyNode = isolatedNode();
        SettingsModel fresh = new SettingsModel(emptyNode);

        Map<String, Object> expectedById = new LinkedHashMap<>();
        expectedById.put("audio.sampleRate", fresh.getSampleRate());
        expectedById.put("audio.bitDepth", fresh.getBitDepth());
        expectedById.put("audio.bufferSize", fresh.getBufferSize());
        expectedById.put("audio.mixPrecision", fresh.getMixPrecision());
        expectedById.put("audio.backend", fresh.getAudioBackend());
        expectedById.put("audio.inputDevice", fresh.getAudioInputDevice());
        expectedById.put("audio.outputDevice", fresh.getAudioOutputDevice());
        expectedById.put("audio.applyLatencyCompensation",
                fresh.isApplyLatencyCompensation());
        expectedById.put("audio.srcQuality", fresh.getSrcQuality());
        // Machine-dependent (derived from availableProcessors()) — compare
        // against the getter, never a literal.
        expectedById.put("audio.workerPoolSize", fresh.getWorkerPoolSize());
        expectedById.put("audio.masterCpuBudgetFraction",
                fresh.getMasterCpuBudgetFraction());
        expectedById.put("audio.masterCpuBudgetPolicy",
                canonicalPolicyName(fresh, emptyNode));
        expectedById.put("project.autoSaveIntervalSeconds",
                fresh.getAutoSaveIntervalSeconds());
        expectedById.put("project.defaultTempo", fresh.getDefaultTempo());
        expectedById.put("project.useJournaledPersistence",
                fresh.isUseJournaledPersistence());
        expectedById.put("appearance.uiScale", fresh.getUiScale());
        expectedById.put("plugins.scanPaths", fresh.getPluginScanPaths());
        assertThat(expectedById).hasSize(17);

        for (Map.Entry<String, Object> expected : expectedById.entrySet()) {
            assertThat(catalogue.byId(expected.getKey()).orElseThrow().defaultValue())
                    .as("defaultValue of %s", expected.getKey())
                    .isEqualTo(expected.getValue());
        }
    }

    /**
     * Derives the canonical persisted policy short-name the same way the
     * catalogue does: round-trip the default policy through the real
     * setter (which persists {@code SettingsModel.policyName(...)},
     * package-private in {@code ..app.ui}) and read the persisted
     * representation back — no duplicated literal.
     */
    private static String canonicalPolicyName(SettingsModel fresh, Preferences node) {
        fresh.setMasterCpuBudgetPolicy(fresh.getMasterCpuBudgetPolicy());
        String name = node.get("audio.masterCpuBudgetPolicy", null);
        assertThat(name).as("setter persisted the default policy name").isNotNull();
        return name;
    }
}
