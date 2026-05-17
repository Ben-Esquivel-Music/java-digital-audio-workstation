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
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

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

    private static final String MESSAGES_BUNDLE =
            "com.benesquivelmusic.daw.app.i18n.Messages";

    /**
     * The direct children of the {@code .status-bar} HBox, in order, as
     * authored in {@code main-view.fxml}. {@code LockStatusIndicator} is
     * NOT here — it is inserted at runtime by
     * {@link MainController#mountLockStatusIndicator()} (HBox index + 1
     * after {@code projectInfoLabel}), not declared in the FXML.
     *
     * <p>9 cells + 1 spacer {@code <Region>} = 10 direct children:
     * <ol>
     *   <li>projectInfoLabel  (project name + format — first cell, no dot)
     *   <li>monitoringLabel   (mono/stereo/surround prose)
     *   <li>ioRoutingLabel    (kHz I/O — numeric, keeps click handler)
     *   <li>cpuLabel          (static placeholder, numeric)
     *   <li>memLabel          (static placeholder, numeric)
     *   <li>dskLabel          (static placeholder, numeric)
     *   <li><Region hgrow=ALWAYS>  (the single spacer)
     *   <li>checkpointLabel   (live last-save / autosave cell —
     *       {@code <StatusCellLabel>}: written from ~30 dynamic sites, so
     *       the type is the seam that guarantees its leading "· ")
     *   <li>rippleBannerLabel (plain Label; visible/managed false — a
     *       special banner, NOT a dot cell)
     *   <li>statusBarLabel    (transport status — {@code <StatusCellLabel>}
     *       for the same reason as checkpointLabel)
     * </ol>
     * A "cell" is any direct child that is not the {@code <Region>} spacer:
     * {@code <Label>} or {@code <StatusCellLabel>}. Exactly two cells are
     * {@code <StatusCellLabel>} — locking in the story-274 dot seam so a
     * regression back to plain {@code <Label>} (which would drop the
     * separator on the first runtime status update) fails this test.
     */
    private static final int EXPECTED_CELL_CHILDREN = 9;
    private static final int EXPECTED_SPACER_CHILDREN = 1;
    private static final int EXPECTED_STATUS_CELL_LABELS = 2;
    private static final int EXPECTED_TOTAL_CHILDREN =
            EXPECTED_CELL_CHILDREN + EXPECTED_SPACER_CHILDREN;

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

        // 2. Direct children: cells (<Label>|<StatusCellLabel>) + 1 <Region>.
        List<Element> directChildren = directElementChildren(statusBar);
        long regions = directChildren.stream()
                .filter(e -> "Region".equals(e.getTagName())).count();
        long cells = directChildren.stream()
                .filter(e -> !"Region".equals(e.getTagName())).count();
        long statusCellLabels = directChildren.stream()
                .filter(e -> "StatusCellLabel".equals(e.getTagName())).count();

        assertThat(regions)
                .as("The status bar must have exactly one <Region hgrow=ALWAYS> "
                        + "spacer separating the left/centre cells from the "
                        + "right-aligned trailing group (story 274 §5.11).")
                .isEqualTo(EXPECTED_SPACER_CHILDREN);

        assertThat(cells)
                .as("The status bar must declare exactly %d cells "
                        + "(<Label> or <StatusCellLabel> — every existing fx:id "
                        + "preserved + the new static placeholder cells, "
                        + "story 274).", EXPECTED_CELL_CHILDREN)
                .isEqualTo(EXPECTED_CELL_CHILDREN);

        assertThat(statusCellLabels)
                .as("checkpointLabel and statusBarLabel must be "
                        + "<StatusCellLabel> (not plain <Label>): they are "
                        + "written from ~30 dynamic sites, so the type is the "
                        + "single seam that keeps their leading \"· \" "
                        + "separator (story 274 S1). A regression to <Label> "
                        + "would silently drop the dot on the first runtime "
                        + "status update.")
                .isEqualTo(EXPECTED_STATUS_CELL_LABELS);

        assertThat(directChildren.size())
                .as("The status bar must have exactly %d direct children "
                        + "(%d cells + %d spacer) — no Separator; the "
                        + "<padding> property element is not counted.",
                        EXPECTED_TOTAL_CHILDREN, EXPECTED_CELL_CHILDREN,
                        EXPECTED_SPACER_CHILDREN)
                .isEqualTo(EXPECTED_TOTAL_CHILDREN);
    }

    /**
     * Story 274 also removed the {@code .project-info-label} purple class
     * from the FXML (§5.11 / §7.6 project-info-purple veto). Scans every
     * element (any tag, including {@code <StatusCellLabel>}) so the guard
     * cannot be evaded by a cell-type change.
     */
    @Test
    void noStatusBarCellReferencesTheRemovedProjectInfoLabelClass() throws Exception {
        Document doc = loadFxml();
        List<String> offences = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            if (el.getAttribute("styleClass").contains("project-info-label")) {
                offences.add("<" + el.getTagName() + " fx:id=\""
                        + el.getAttribute("fx:id") + "\"> still carries the "
                        + "removed 'project-info-label' purple class "
                        + "(story 274 — UI Design Book §7.6).");
            }
        }
        assertThat(offences)
                .as("The purple project-info-label class must be gone:%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    /**
     * Story 274 S2 — the CPU/MEM/DSK placeholder cells have their
     * authoritative text in {@code Messages.properties} (the controller
     * overwrites from the bundle at init), but also a design-time
     * {@code text="…"} in the FXML. Pin the two to the same value so a
     * maintainer editing one cannot let them silently drift.
     */
    @Test
    void cpuMemDskDesignTimeTextMatchesMessagesBundle() throws Exception {
        Document doc = loadFxml();
        ResourceBundle bundle = ResourceBundle.getBundle(MESSAGES_BUNDLE, Locale.ROOT);

        Map<String, String> fxIdToKey = Map.of(
                "cpuLabel", "statusbar.cpu",
                "memLabel", "statusbar.mem",
                "dskLabel", "statusbar.dsk");

        List<String> offences = new ArrayList<>();
        for (Map.Entry<String, String> e : fxIdToKey.entrySet()) {
            Element cell = findElementByFxId(doc, e.getKey());
            if (cell == null) {
                offences.add("Placeholder cell fx:id=\"" + e.getKey()
                        + "\" is missing from main-view.fxml (story 274).");
                continue;
            }
            String fxmlText = cell.getAttribute("text");
            String bundleText = bundle.getString(e.getValue());
            if (!bundleText.equals(fxmlText)) {
                offences.add("fx:id=\"" + e.getKey() + "\" design-time text \""
                        + fxmlText + "\" != Messages key '" + e.getValue()
                        + "' = \"" + bundleText + "\" — the two sources of "
                        + "truth have drifted (story 274 S2).");
            }
        }
        assertThat(offences)
                .as("FXML design-time text and Messages.properties must "
                        + "agree for the CPU/MEM/DSK placeholders:%n%s",
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

    private static Element findElementByFxId(Document doc, String fxId) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            if (fxId.equals(el.getAttribute("fx:id"))) {
                return el;
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
