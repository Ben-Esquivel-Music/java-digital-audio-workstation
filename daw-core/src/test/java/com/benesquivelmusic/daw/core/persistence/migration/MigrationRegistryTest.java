package com.benesquivelmusic.daw.core.persistence.migration;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the migration registry framework.
 *
 * <p>Each migration the registry contains in production gets its own
 * golden-input/golden-output unit test (see the {@code Migration*Test}
 * neighbours of this class). The tests in this file exercise the
 * <em>framework</em>: ordering, version stamping, error paths, and
 * end-to-end multi-step chaining.</p>
 */
class MigrationRegistryTest {

    /** Build a minimal &lt;daw-project version="N"&gt; document for tests. */
    private static Document docAtVersion(int version) throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();
        Element root = doc.createElement("daw-project");
        root.setAttribute("version", Integer.toString(version));
        doc.appendChild(root);
        return doc;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String serialize(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }

    /** Adds an attribute to the root element and returns the same document. */
    private static UnaryOperator<Document> setRootAttr(String name, String value) {
        return d -> {
            d.getDocumentElement().setAttribute(name, value);
            return d;
        };
    }

    @Test
    void noOpWhenFileIsAlreadyAtCurrentVersion() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(1).build();
        Document doc = docAtVersion(1);

        MigrationRegistry.MigrationResult result = registry.migrate(doc);

        assertThat(result.report().wasMigrated()).isFalse();
        assertThat(result.report().fromVersion()).isEqualTo(1);
        assertThat(result.report().toVersion()).isEqualTo(1);
        assertThat(result.document()).isSameAs(doc);
    }

    @Test
    void appliesSingleStepMigration() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "add-tempo", setRootAttr("tempo", "120")))
                .build();

        Document doc = docAtVersion(1);
        MigrationRegistry.MigrationResult result = registry.migrate(doc);

        assertThat(result.report().wasMigrated()).isTrue();
        assertThat(result.report().fromVersion()).isEqualTo(1);
        assertThat(result.report().toVersion()).isEqualTo(2);
        assertThat(result.report().applied()).hasSize(1);
        assertThat(result.report().applied().getFirst().description()).isEqualTo("add-tempo");
        assertThat(result.document().getDocumentElement().getAttribute("tempo")).isEqualTo("120");
        // The migrated DOM is re-stamped with the new schema version so
        // round-trips of an in-flight load behave as no-ops.
        assertThat(result.document().getDocumentElement().getAttribute("version")).isEqualTo("2");
    }

    @Test
    void appliesChainOfMigrationsInOrder() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(4)
                .add(ProjectMigration.step(1, "v1→v2", setRootAttr("a", "1")))
                .add(ProjectMigration.step(2, "v2→v3", setRootAttr("b", "2")))
                .add(ProjectMigration.step(3, "v3→v4", setRootAttr("c", "3")))
                .build();

        MigrationRegistry.MigrationResult result = registry.migrate(docAtVersion(1));

        assertThat(result.report().applied())
                .extracting(MigrationReport.AppliedMigration::description)
                .containsExactly("v1→v2", "v2→v3", "v3→v4");
        Element root = result.document().getDocumentElement();
        assertThat(root.getAttribute("a")).isEqualTo("1");
        assertThat(root.getAttribute("b")).isEqualTo("2");
        assertThat(root.getAttribute("c")).isEqualTo("3");
        assertThat(root.getAttribute("version")).isEqualTo("4");
    }

    @Test
    void resumesPartwayThroughChain() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(4)
                .add(ProjectMigration.step(1, "v1→v2", setRootAttr("a", "1")))
                .add(ProjectMigration.step(2, "v2→v3", setRootAttr("b", "2")))
                .add(ProjectMigration.step(3, "v3→v4", setRootAttr("c", "3")))
                .build();

        // File already at v3 — only the last hop should run.
        MigrationRegistry.MigrationResult result = registry.migrate(docAtVersion(3));

        assertThat(result.report().applied())
                .extracting(MigrationReport.AppliedMigration::description)
                .containsExactly("v3→v4");
        Element root = result.document().getDocumentElement();
        assertThat(root.getAttribute("a")).isEmpty();
        assertThat(root.getAttribute("b")).isEmpty();
        assertThat(root.getAttribute("c")).isEqualTo("3");
    }

    @Test
    void treatsMissingVersionAttributeAsVersionOne() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "v1→v2", setRootAttr("touched", "yes")))
                .build();

        Document doc = parse("<daw-project/>");

        MigrationRegistry.MigrationResult result = registry.migrate(doc);

        assertThat(result.report().fromVersion()).isEqualTo(1);
        assertThat(result.report().wasMigrated()).isTrue();
        assertThat(result.document().getDocumentElement().getAttribute("touched")).isEqualTo("yes");
    }

    @Test
    void failsWhenFileVersionIsNewerThanCurrent() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "v1→v2", UnaryOperator.identity()))
                .build();

        Document doc = docAtVersion(99);

        assertThatThrownBy(() -> registry.migrate(doc))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("newer than this build");
    }

    @Test
    void failsWhenCurrentVersionIsAdvancedButRegistryIsEmpty() {
        // An empty registry targeting a version > 1 cannot service a load
        // of a legacy v1 file — the builder must reject this configuration
        // up front, not at deserialize time.
        assertThatThrownBy(() -> MigrationRegistry.builder(3).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no migrations are registered");
    }

    @Test
    void failsWhenChainDoesNotStartAtVersionOne() {
        // currentVersion is 3 but the chain only handles v2..v3, so a
        // file at v1 (or with a missing version attribute, normalised to
        // 1) would have nowhere to start.
        assertThatThrownBy(() -> MigrationRegistry.builder(3)
                .add(ProjectMigration.step(2, "v2→v3", UnaryOperator.identity()))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("starts at version 2");
    }

    @Test
    void failsWhenChainHasGap() throws Exception {
        // v1→v2 and v3→v4 but no v2→v3.
        assertThatThrownBy(() -> MigrationRegistry.builder(4)
                .add(ProjectMigration.step(1, "v1→v2", UnaryOperator.identity()))
                .add(ProjectMigration.step(3, "v3→v4", UnaryOperator.identity()))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not contiguous");
    }

    @Test
    void failsWhenChainOvershootsCurrentVersion() {
        assertThatThrownBy(() -> MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "v1→v2", UnaryOperator.identity()))
                .add(ProjectMigration.step(2, "v2→v3", UnaryOperator.identity()))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overshoots");
    }

    @Test
    void failsWhenChainIsTooShort() {
        // Has migrations but doesn't reach currentVersion.
        assertThatThrownBy(() -> MigrationRegistry.builder(5)
                .add(ProjectMigration.step(1, "v1→v2", UnaryOperator.identity()))
                .add(ProjectMigration.step(2, "v2→v3", UnaryOperator.identity()))
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ends at version 3");
    }

    @Test
    void supportsLegacyBatchMigration() throws Exception {
        // Consolidate v1..v3 into a single legacy-batch migration. This is
        // the deprecation strategy from the issue: migrations older than
        // ten versions can be folded into a batch.
        ProjectMigration legacyBatch = new ProjectMigration(
                1, 4, "legacy v1..v3 batch", setRootAttr("legacy", "applied"));
        MigrationRegistry registry = MigrationRegistry.builder(4)
                .add(legacyBatch)
                .build();

        MigrationRegistry.MigrationResult result = registry.migrate(docAtVersion(1));

        assertThat(legacyBatch.isLegacyBatch()).isTrue();
        assertThat(result.report().applied()).hasSize(1);
        assertThat(result.document().getDocumentElement().getAttribute("legacy"))
                .isEqualTo("applied");
        assertThat(result.document().getDocumentElement().getAttribute("version"))
                .isEqualTo("4");
    }

    @Test
    void rejectsMigrationThatReturnsNull() throws Exception {
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "broken", d -> null))
                .build();

        assertThatThrownBy(() -> registry.migrate(docAtVersion(1)))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("returned null");
    }

    @Test
    void projectMigrationValidatesArguments() {
        UnaryOperator<Document> id = UnaryOperator.identity();
        assertThatThrownBy(() -> new ProjectMigration(0, 1, "x", id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectMigration(2, 2, "x", id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectMigration(3, 2, "x", id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProjectMigration(1, 2, "  ", id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readVersionFallsBackToOneOnMissingOrInvalid() throws Exception {
        assertThat(MigrationRegistry.readVersion(parse("<daw-project/>"))).isEqualTo(1);
        assertThat(MigrationRegistry.readVersion(parse("<daw-project version=\"\"/>"))).isEqualTo(1);
        assertThat(MigrationRegistry.readVersion(parse("<daw-project version=\"junk\"/>"))).isEqualTo(1);
        assertThat(MigrationRegistry.readVersion(parse("<daw-project version=\"-3\"/>"))).isEqualTo(1);
        assertThat(MigrationRegistry.readVersion(parse("<daw-project version=\"7\"/>"))).isEqualTo(7);
    }

    @Test
    void defaultRegistryMatchesCurrentSerializerVersion() {
        // Until the schema evolves past version 1 the default registry has no
        // entries — but it must always exist and target the current version.
        MigrationRegistry registry = MigrationRegistry.defaultRegistry();
        assertThat(registry.currentVersion()).isEqualTo(MigrationRegistry.CURRENT_VERSION);
    }

    @Test
    void serializedRoundTripIsByteIdentical() throws Exception {
        // Belt-and-braces: a migration that returns the same document
        // produces equivalent XML when re-serialised.
        MigrationRegistry registry = MigrationRegistry.builder(2)
                .add(ProjectMigration.step(1, "noop", UnaryOperator.identity()))
                .build();
        Document migrated = registry.migrate(docAtVersion(1)).document();
        assertThat(serialize(migrated)).contains("version=\"2\"");
    }
}
