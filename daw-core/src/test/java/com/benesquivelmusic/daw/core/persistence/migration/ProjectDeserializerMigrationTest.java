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

    @Test
    void deserializerInvokesRegistryAndExposesReport() throws IOException {
        // Build a registry that targets v2 and registers a no-op v1→v2
        // migration. This drives ProjectDeserializer through the
        // migration code path even though the production schema is v1.
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "promote-to-v2", d -> d))
                .build();

        ProjectDeserializer deserializer = new ProjectDeserializer(registry);

        // A v1 document — the production serializer always emits v1.
        String v1Xml = new ProjectSerializer().serialize(
                new DawProject("Legacy", AudioFormat.CD_QUALITY));

        DawProject restored = deserializer.deserialize(v1Xml);
        MigrationReport report = deserializer.getLastMigrationReport();

        assertThat(restored.getName()).isEqualTo("Legacy");
        assertThat(report.wasMigrated()).isTrue();
        assertThat(report.fromVersion()).isEqualTo(1);
        assertThat(report.toVersion()).isEqualTo(2);
        assertThat(report.applied())
                .extracting(MigrationReport.AppliedMigration::description)
                .containsExactly("promote-to-v2");
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
        // Build a 3-step chain (v1 → v4) where every step is a no-op so
        // the final DawProject must be byte-equivalent to one
        // serialized/deserialized natively.
        MigrationRegistry registry = MigrationRegistry.builder(4)
                .add(ProjectMigration.step(1, "v1→v2", d -> d))
                .add(ProjectMigration.step(2, "v2→v3", d -> d))
                .add(ProjectMigration.step(3, "v3→v4", d -> d))
                .build();

        ProjectSerializer serializer = new ProjectSerializer();
        DawProject template = new DawProject("Equivalence", AudioFormat.CD_QUALITY);
        template.getTransport().setTempo(101.0);
        template.createAudioTrack("Vocals");
        String v1Xml = serializer.serialize(template);

        // Deserialize via the migrating registry…
        ProjectDeserializer migrating = new ProjectDeserializer(registry);
        DawProject migrated = migrating.deserialize(v1Xml);

        // …and via the production no-migration path on the same XML.
        ProjectDeserializer native_ = new ProjectDeserializer();
        DawProject nativeLoaded = native_.deserialize(v1Xml);

        // Re-serialise both. The migrating path's report says it ran 3
        // steps but the resulting state is identical because each step
        // is a no-op — proving the registry preserves equivalence.
        assertThat(migrating.getLastMigrationReport().applied()).hasSize(3);
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
