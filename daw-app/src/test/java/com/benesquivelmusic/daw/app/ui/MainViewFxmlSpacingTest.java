package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 of UI Design Book §6 — enforces the 4&nbsp;px grid contract on
 * the FXML side.
 *
 * <p>Every {@code <Insets>} attribute (top/right/bottom/left) and every
 * container {@code spacing="..."} attribute in {@code main-view.fxml} must
 * be a multiple of 4. We parse the FXML as XML rather than via
 * {@link javafx.fxml.FXMLLoader} so the test does not require a live
 * JavaFX toolkit / display — the structural numbers are what we care
 * about, not the runtime scene graph.
 *
 * <p>The companion CSS-side contract lives in {@link TokenValidationTest}.
 *
 * <p>See also
 * {@link com.benesquivelmusic.daw.app.ui.design.SpacingTokens} for the
 * Java-side constants that mirror the CSS token block.
 */
final class MainViewFxmlSpacingTest {

    private static final String FXML_RESOURCE =
            "/com/benesquivelmusic/daw/app/ui/main-view.fxml";

    @Test
    void mainViewFxmlInsetsAndSpacingsSnapToFourPxGrid() throws Exception {
        Document doc = loadFxml();
        List<String> offences = new ArrayList<>();

        // 1. Walk every <Insets ...> element and check top/right/bottom/left.
        NodeList insets = doc.getElementsByTagName("Insets");
        for (int i = 0; i < insets.getLength(); i++) {
            Element el = (Element) insets.item(i);
            checkAttribute(el, "top", offences);
            checkAttribute(el, "right", offences);
            checkAttribute(el, "bottom", offences);
            checkAttribute(el, "left", offences);
        }

        // 2. Walk every element with a spacing="..." attribute (typically
        //    HBox / VBox / TilePane). spacing must also snap to the grid.
        walk(doc.getDocumentElement(), el -> {
            String spacing = el.getAttribute("spacing");
            if (!spacing.isEmpty()) {
                checkNumber(el.getTagName() + "[spacing]", spacing, offences);
            }
        });

        assertThat(offences)
                .as("Every <Insets> value and every container spacing in main-view.fxml "
                        + "must snap to the 4 px grid (UI_DESIGN_BOOK.md §2.3, §3.3).%n"
                        + "Each offence below identifies the attribute and the offending value:%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    /**
     * Story 262 — UI Design Book §5.1 dropped the discrete Pause button
     * (Play now toggles pause). The FXML must no longer declare a
     * {@code pauseButton} fx:id or a {@code pause-button} style class.
     */
    @Test
    void mainViewFxmlMustNotContainPauseButton() throws Exception {
        Document doc = loadFxml();
        List<String> offences = new ArrayList<>();

        walk(doc.getDocumentElement(), el -> {
            String fxId = el.getAttributeNS("http://javafx.com/fxml", "id");
            if (fxId.isEmpty()) {
                fxId = el.getAttribute("fx:id");
            }
            if ("pauseButton".equals(fxId)) {
                offences.add(el.getTagName() + " fx:id=\"pauseButton\" is forbidden — "
                        + "the Pause button was removed in story 262 (UI Design Book §5.1).");
            }
            String styleClass = el.getAttribute("styleClass");
            if (!styleClass.isEmpty() && styleClass.contains("pause-button")) {
                offences.add(el.getTagName() + " styleClass contains 'pause-button' — "
                        + "the per-button rainbow hue was removed in story 262 "
                        + "(UI Design Book §2.1, §7.2).");
            }
        });

        assertThat(offences)
                .as("main-view.fxml must not reference the removed Pause button "
                        + "(UI Design Book §5.1, story 262):%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }

    /**
     * Story 263 — every {@code <Button>} in {@code main-view.fxml} must
     * carry the unified {@code dawg-button} style class along with
     * exactly one size variant ({@code size-compact}, {@code size-default},
     * {@code size-touch}, or {@code size-transport}).
     *
     * <p>UI Design Book §1.3, §3.3, §5.1.
     */
    @Test
    void everyButtonHasDawgButtonClassWithExactlyOneSizeVariant() throws Exception {
        Document doc = loadFxml();
        List<String> offences = new ArrayList<>();
        List<String> sizeVariants = List.of(
                "size-compact", "size-default", "size-touch", "size-transport");

        NodeList buttons = doc.getElementsByTagName("Button");
        assertThat(buttons.getLength())
                .as("main-view.fxml must contain at least one <Button>")
                .isGreaterThan(0);

        for (int i = 0; i < buttons.getLength(); i++) {
            Element btn = (Element) buttons.item(i);
            String fxId = btn.getAttribute("fx:id");
            String label = fxId.isEmpty() ? "Button[" + i + "]" : "Button#" + fxId;

            String styleClassAttr = btn.getAttribute("styleClass");
            // FXML styleClass attributes are comma-separated; tokens may
            // also be whitespace-separated. Split on either.
            List<String> classes = new ArrayList<>();
            for (String token : styleClassAttr.split("[,\\s]+")) {
                if (!token.isEmpty()) classes.add(token);
            }

            if (!classes.contains("dawg-button")) {
                offences.add(label + " is missing the 'dawg-button' style class "
                        + "(story 263 — UI Design Book §1.3).");
                continue;
            }

            long sizeMatches = classes.stream().filter(sizeVariants::contains).count();
            if (sizeMatches != 1) {
                offences.add(label + " has " + sizeMatches + " size variants "
                        + "(expected exactly one of " + sizeVariants + ") — actual styleClass='"
                        + styleClassAttr + "'.");
            }
        }

        assertThat(offences)
                .as("Every <Button> in main-view.fxml must carry 'dawg-button' "
                        + "and exactly one size variant (story 263):%n%s",
                        String.join(System.lineSeparator(), offences))
                .isEmpty();
    }


        String raw = el.getAttribute(attr);
        if (raw.isEmpty()) {
            return;
        }
        checkNumber("Insets@" + attr, raw, offences);
    }

    private static void checkNumber(String label, String raw, List<String> offences) {
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            offences.add(label + " has non-numeric value '" + raw + "'");
            return;
        }
        int n = (int) value;
        if (value != n) {
            offences.add(label + " value '" + raw + "' is non-integer — only integer multiples of 4 are allowed");
            return;
        }
        // Allowed inline exception: 2 (= -spacing-xxs). Spacing values in
        // main-view.fxml today do not use 2; but keeping the rule aligned
        // with TokenValidationTest avoids accidental drift.
        boolean ok = (n % 4 == 0) || n == 2;
        if (!ok) {
            offences.add(label + " value '" + n + "' is not a multiple of 4 "
                    + "(use a -spacing-* / -row-* token via SpacingTokens; "
                    + "see UI_DESIGN_BOOK.md §3.3)");
        }
    }

    @FunctionalInterface
    private interface ElementVisitor {
        void visit(Element el);
    }

    private static void walk(Element root, ElementVisitor visitor) {
        visitor.visit(root);
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element child) {
                walk(child, visitor);
            }
        }
    }

    private static Document loadFxml() throws Exception {
        try (InputStream in = MainViewFxmlSpacingTest.class.getResourceAsStream(FXML_RESOURCE)) {
            assertThat(in)
                    .as("main-view.fxml must be on the test classpath at %s", FXML_RESOURCE)
                    .isNotNull();
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Disable external entity resolution (defensive — FXML is local).
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }
}
