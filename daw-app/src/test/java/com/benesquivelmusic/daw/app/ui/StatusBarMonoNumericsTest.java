package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 274 — every numeric status-bar cell carries {@code .numeric-value}
 * (mono tabular figures per story 266 / UI Design Book §3.2, §5.11).
 *
 * <p><b>Reconciliation note (story text vs. codebase contract):</b> the
 * existing status labels used {@code .numeric-caption} (11 px). Story §5.11
 * explicitly prescribes {@code .numeric-value} for the status-bar numeric
 * cells, so this test asserts {@code .numeric-value} (the switch is
 * documented in main-view.fxml). {@code NumericClassAuditTest} accepts any
 * of the four numeric classes, so it stays green either way.
 *
 * <p>Structural XML parse — no FXMLLoader (it would boot the AudioEngine
 * via {@code MainController.initialize()}).
 */
final class StatusBarMonoNumericsTest {

    private static final String FXML_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/main-view.fxml";

    /**
     * The status-bar cells whose displayed value is a number per §5.11
     * (sample-rate / bit-depth / channels live in projectInfoLabel; the
     * kHz I/O figure in ioRoutingLabel; the CPU/MEM/DSK placeholders).
     * The prose cells — monitoringLabel, checkpointLabel, statusBarLabel,
     * rippleBannerLabel — are NOT numeric and carry {@code .body} only.
     */
    private static final List<String> NUMERIC_CELL_IDS = List.of(
            "projectInfoLabel",
            "ioRoutingLabel",
            "cpuLabel",
            "memLabel",
            "dskLabel");

    @Test
    void everyNumericStatusBarCellCarriesNumericValueClass() throws Exception {
        Document doc = loadFxml();

        List<String> offences = new ArrayList<>();
        NodeList labels = doc.getElementsByTagName("Label");
        for (String id : NUMERIC_CELL_IDS) {
            Element label = findLabelByFxId(labels, id);
            if (label == null) {
                offences.add("Numeric status-bar cell fx:id=\"" + id
                        + "\" is missing from main-view.fxml (story 274).");
                continue;
            }
            String styleClass = label.getAttribute("styleClass");
            if (!containsClass(styleClass, "numeric-value")) {
                offences.add("<Label fx:id=\"" + id + "\" styleClass=\""
                        + styleClass + "\"> must carry 'numeric-value' "
                        + "(story 274 §5.11 / story 266 §3.2).");
            }
        }

        assertThat(offences)
                .as("Every numeric status-bar cell must carry the "
                        + "'numeric-value' mono class:%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    private static Element findLabelByFxId(NodeList labels, String fxId) {
        for (int i = 0; i < labels.getLength(); i++) {
            Element label = (Element) labels.item(i);
            if (fxId.equals(label.getAttribute("fx:id"))) {
                return label;
            }
        }
        return null;
    }

    private static boolean containsClass(String styleClassAttr, String className) {
        if (styleClassAttr == null || styleClassAttr.isBlank()) return false;
        for (String token : styleClassAttr.split("[,\\s]+")) {
            if (token.equals(className)) return true;
        }
        return false;
    }

    private static Document loadFxml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        try (InputStream in = StatusBarMonoNumericsTest.class.getResourceAsStream(FXML_RESOURCE)) {
            assertThat(in)
                    .as("main-view.fxml must be on the test classpath at %s", FXML_RESOURCE)
                    .isNotNull();
            Document doc = factory.newDocumentBuilder().parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }
}
