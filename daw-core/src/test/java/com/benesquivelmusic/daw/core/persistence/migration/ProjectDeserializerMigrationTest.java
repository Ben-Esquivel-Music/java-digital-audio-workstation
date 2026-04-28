package com.benesquivelmusic.daw.core.persistence.migration;

import com.benesquivelmusic.daw.core.audio.AudioFormat;
import com.benesquivelmusic.daw.core.persistence.ProjectDeserializer;
import com.benesquivelmusic.daw.core.persistence.ProjectSerializer;
import com.benesquivelmusic.daw.core.project.DawProject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ProjectDeserializer} consults the
 * {@link MigrationRegistry} on every load and that a legacy file
 * ends up in the same final state as a natively current-version file
 * after migration.
 */
class ProjectDeserializerMigrationTest {

    /**
     * Builds a registry whose target version is {@code CURRENT_VERSION + extraSteps},
     * filled with no-op step migrations from version 1 upward. This keeps
     * tests version-agnostic: when {@code CURRENT_VERSION} bumps in a
     * future schema change, the test still exercises the migration path
     * because the registry always sits one or more versions ahead of
     * production.
     */
    private static MigrationRegistry buildSyntheticChain(int extraSteps) {
        int target = MigrationRegistry.CURRENT_VERSION + extraSteps;
        MigrationRegistry.Builder builder = MigrationRegistry.builder(target);
        for (int v = 1; v < target; v++) {
            int from = v;
            builder.add(ProjectMigration.step(from, "v" + from + "→v" + (from + 1), d -> d));
        }
        return builder.build();
    }

    @Test
    void deserializerInvokesRegistryAndExposesReport() throws IOException {
        // Build a registry that targets one schema version above the
        // production current version with a no-op step at the top of
        // the chain. The serializer emits CURRENT_VERSION, so loading
        // through this registry triggers exactly one migration step
        // regardless of what CURRENT_VERSION happens to be.
        MigrationRegistry registry = buildSyntheticChain(1);
        int current = MigrationRegistry.CURRENT_VERSION;
        int target = current + 1;
        String topStepDescription = "v" + current + "→v" + target;

        ProjectDeserializer deserializer = new ProjectDeserializer(registry);

        String currentXml = new ProjectSerializer().serialize(
                new DawProject("Legacy", AudioFormat.CD_QUALITY));

        DawProject restored = deserializer.deserialize(currentXml);
        MigrationReport report = deserializer.getLastMigrationReport();

        assertThat(restored.getName()).isEqualTo("Legacy");
        assertThat(report.wasMigrated()).isTrue();
        assertThat(report.fromVersion()).isEqualTo(current);
        assertThat(report.toVersion()).isEqualTo(target);
        assertThat(report.applied())
                .extracting(MigrationReport.AppliedMigration::description)
                .containsExactly(topStepDescription);
    }

    @Test
    void deserializerProducesNoOpReportForCurrentVersionFile() throws IOException {
        ProjectDeserializer deserializer = new ProjectDeserializer();
        String xml = new ProjectSerializer().serialize(
                new DawProject("Native", AudioFormat.CD_QUALITY));

        deserializer.deserialize(xml);

        MigrationReport report = deserializer.getLastMigrationReport();
        assertThat(report.wasMigrated()).isFalse();
        assertThat(report.fromVersion()).isEqualTo(MigrationRegistry.CURRENT_VERSION);
        assertThat(report.toVersion()).isEqualTo(MigrationRegistry.CURRENT_VERSION);
    }

    @Test
    void migrationChainReachesSameFinalStateAsNativeCurrentFile() throws IOException {
        // Three synthetic no-op steps above the production current
        // version. The serializer emits CURRENT_VERSION, so exactly
        // three migration steps run regardless of what CURRENT_VERSION
        // happens to be.
        int extraSteps = 3;
        MigrationRegistry registry = buildSyntheticChain(extraSteps);

        ProjectSerializer serializer = new ProjectSerializer();
        DawProject template = new DawProject("Equivalence", AudioFormat.CD_QUALITY);
        template.getTransport().setTempo(101.0);
        template.createAudioTrack("Vocals");
        String currentXml = serializer.serialize(template);

        // Deserialize via the migrating registry…
        ProjectDeserializer migrating = new ProjectDeserializer(registry);
        DawProject migrated = migrating.deserialize(currentXml);

        // …and via the production no-migration path on the same XML.
        ProjectDeserializer native_ = new ProjectDeserializer();
        DawProject nativeLoaded = native_.deserialize(currentXml);

        // The migrating path's report says it ran the expected number
        // of synthetic no-op steps but the resulting state is identical
        // — proving the registry preserves equivalence.
        assertThat(migrating.getLastMigrationReport().applied()).hasSize(extraSteps);
        assertThat(migrated.getName()).isEqualTo(nativeLoaded.getName());
        assertThat(migrated.getTransport().getTempo())
                .isEqualTo(nativeLoaded.getTransport().getTempo());
        assertThat(migrated.getTracks()).hasSameSizeAs(nativeLoaded.getTracks());
        assertThat(migrated.getTracks().getFirst().getName())
                .isEqualTo(nativeLoaded.getTracks().getFirst().getName());
        // Confirm the migrated payload re-serialises into a current-version
        // XML — i.e. both deserialization paths land on the same schema.
        assertThat(serializer.serialize(migrated))
                .contains("<daw-project version=\""
                        + MigrationRegistry.CURRENT_VERSION + "\"");
    }
}
