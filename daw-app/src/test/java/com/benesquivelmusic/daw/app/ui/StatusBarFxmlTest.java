package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 274 — UI Design Book §5.11 / §7.7.
 *
 * <p>The status bar is a single row of dot-separated cells with
 * <strong>zero</strong> {@code <Separator>} nodes. We parse
 * {@code main-view.fxml} as XML rather than via {@link javafx.fxml.FXMLLoader}
 * — loading the FXML through FXMLLoader transitively constructs
 * {@link MainController}, whose {@code @FXML initialize()} spins up a real
 * {@code AudioEngine}, autosave timers, etc. None of that is needed (or
 * safe) for a structural check, and it would hang a headless test.
 *
 * <p>The secure {@link DocumentBuilderFactory} setup mirrors
 * {@link NumericClassAuditTest} (FEATURE_SECURE_PROCESSING + DOCTYPE
 * disabled, namespace-unaware so {@code fx:id} reads as a literal
 * attribute).
 */
final class StatusBarFxmlTest {

    private static final String FXML_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/main-view.fxml";

    /**
     * The exact direct children of the {@code .status-bar} HBox, in order,
     * as authored in {@code main-view.fxml}. The {@code LockStatusIndicator}
     * is NOT in this list — it is inserted at runtime by
     * {@link MainController#mountLockStatusIndicator()} (HBox index + 1
     * after {@code projectInfoLabel}), not declared in the FXML.
     *
     * <p>Cells (9) + 1 spacer {@code <Region>} = 10 direct children:
     * <ol>
     *   <li>projectInfoLabel  (project name + format — first cell, no dot)
     *   <li>monitoringLabel   (mono/stereo/surround prose)
     *   <li>ioRoutingLabel    (kHz I/O — numeric, keeps click handler)
     *   <li>cpuLabel          (static placeholder, numeric)
     *   <li>memLabel          (static placeholder, numeric)
     *   <li>dskLabel          (static placeholder, numeric)
     *   <li><Region hgrow=ALWAYS>  (the single spacer)
     *   <li>checkpointLabel   (live last-save / autosave cell — existing
     *       fx:id kept; §5.11 right group. No separate last-save /
     *       autosave placeholder cells: checkpointLabel already IS that
     *       data, a dead sibling would only duplicate it.)
     *   <li>rippleBannerLabel (visible/managed false)
     *   <li>statusBarLabel    (transport status prose)
     * </ol>
     * That is 9 {@code <Label>} + 1 {@code <Region>} = 10 children.
     */
    private static final int EXPECTED_LABEL_CHILDREN = 9;
    private static final int EXPECTED_SPACER_CHILDREN = 1;
    private static final int EXPECTED_TOTAL_CHILDREN =
            EXPECTED_LABEL_CHILDREN + EXPECTED_SPACER_CHILDREN;

    @Test
    void statusBarHasZeroSeparatorsAndExpectedChildCount() throws Exception {
        Document doc = loadFxml();

        Element statusBar = findStatusBarHBox(doc);
        assertThat(statusBar)
                .as("main-view.fxml must contain an <HBox styleClass=\"status-bar\">")
                .isNotNull();

        // 1. Zero <Separator> descendants anywhere inside the status bar.
        NodeList separators = statusBar.getElementsByTagName("Separator");
        assertThat(separators.getLength())
                .as("The status bar must have ZERO <Separator> nodes — grouping "
                        + "reads from the 16 px gap, not drawn lines "
                        + "(story 274, UI Design Book §5.11 / §7.7).")
                .isZero();

        // 2. Direct children: <Label>* + exactly one <Region> spacer.
        List<Element> directChildren = directElementChildren(statusBar);
        long labels = directChildren.stream()
                .filter(e -> "Label".equals(e.getTagName())).count();
        long regions = directChildren.stream()
                .filter(e -> "Region".equals(e.getTagName())).count();

        assertThat(regions)
                .as("The status bar must have exactly one <Region hgrow=ALWAYS> "
                        + "spacer separating the left/centre cells from the "
                        + "right-aligned trailing group (story 274 §5.11).")
                .isEqualTo(EXPECTED_SPACER_CHILDREN);

        assertThat(labels)
                .as("The status bar must declare exactly %d <Label> cells "
                        + "(every existing fx:id preserved + the new static "
                        + "placeholder cells — story 274).", EXPECTED_LABEL_CHILDREN)
                .isEqualTo(EXPECTED_LABEL_CHILDREN);

        assertThat(directChildren.size())
                .as("The status bar must have exactly %d direct children "
                        + "(%d cells + %d spacer) — no Separator, no padding "
                        + "<padding> element is counted (it is a property "
                        + "element, not a child node).",
                        EXPECTED_TOTAL_CHILDREN, EXPECTED_LABEL_CHILDREN,
                        EXPECTED_SPACER_CHILDREN)
                .isEqualTo(EXPECTED_TOTAL_CHILDREN);
    }

    /**
     * Story 274 also removed the {@code .project-info-label} purple class
     * from the FXML (§5.11 / §7.6 project-info-purple veto). Guard the
     * regression directly.
     */
    @Test
    void noStatusBarCellReferencesTheRemovedProjectInfoLabelClass() throws Exception {
        Document doc = loadFxml();
        List<String> offences = new ArrayList<>();
        NodeList labels = doc.getElementsByTagName("Label");
        for (int i = 0; i < labels.getLength(); i++) {
            Element label = (Element) labels.item(i);
            String styleClass = label.getAttribute("styleClass");
            if (styleClass.contains("project-info-label")) {
                offences.add("<Label fx:id=\"" + label.getAttribute("fx:id")
                        + "\"> still carries the removed 'project-info-label' "
                        + "purple class (story 274 — UI Design Book §7.6).");
            }
        }
        assertThat(offences)
                .as("The purple project-info-label class must be gone:%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    private static Element findStatusBarHBox(Document doc) {
        NodeList hboxes = doc.getElementsByTagName("HBox");
        for (int i = 0; i < hboxes.getLength(); i++) {
            Element hbox = (Element) hboxes.item(i);
            String styleClass = hbox.getAttribute("styleClass");
            for (String token : styleClass.split("[,\\s]+")) {
                if ("status-bar".equals(token)) {
                    return hbox;
                }
            }
        }
        return null;
    }

    private static List<Element> directElementChildren(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element child) {
                // <padding> is a JavaFX property element, not a scene-graph
                // child. The Separator/cell-count contract concerns scene
                // children only, so exclude property elements explicitly.
                if ("padding".equals(child.getTagName())) {
                    continue;
                }
                out.add(child);
            }
        }
        return out;
    }

    private static Document loadFxml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        try (InputStream in = StatusBarFxmlTest.class.getResourceAsStream(FXML_RESOURCE)) {
            assertThat(in)
                    .as("main-view.fxml must be on the test classpath at %s", FXML_RESOURCE)
                    .isNotNull();
            Document doc = factory.newDocumentBuilder().parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }
}
