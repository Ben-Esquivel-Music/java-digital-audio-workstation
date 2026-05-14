package com.benesquivelmusic.daw.app.ui.icons;

import javafx.beans.property.ObjectProperty;
import javafx.css.CssMetaData;
import javafx.css.PseudoClass;
import javafx.css.Styleable;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.converter.PaintConverter;
import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The DAW's single iconography entry point.
 *
 * <p>{@code DawgIcon} resolves an icon by Lucide name into a JavaFX
 * {@link Region} that renders the icon's vector paths and takes its
 * colour from CSS {@code -fx-icon-color} (defaulting to {@code -text-hi}
 * so the same icon flips with the theme). The icon participates in
 * layout cleanly at a fixed nominal size (16 / 20 / 24 px).</p>
 *
 * <p>Adopted in Phase 2 of the UI Design Book §6 migration
 * roadmap; see §3.6 (Iconography) for the design rationale and
 * §2.4 ("an icon is a replacement for a label, not a decoration
 * on it") for the placement rules.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Sidebar tab: icon + label
 * tab.setGraphic(DawgIcon.of("folder", DawgIcon.Size.SIZE_16));
 *
 * // Toolbar mode toggle: icon-only with tooltip
 * toggle.setGraphic(DawgIcon.of("repeat", DawgIcon.Size.SIZE_16));
 * toggle.setTooltip(new Tooltip("Loop"));
 *
 * // Re-tint via CSS:
 * //   .my-toggle:selected { -fx-icon-color: -accent; }
 * }</pre>
 *
 * <h2>Why not {@code IconNode}?</h2>
 *
 * <p>The legacy {@code IconNode} loader supports the project's hand-rolled
 * 48&times;48 icon pack with mixed strokes and fills. Lucide is the
 * single approved family going forward (UI Design Book §3.6); use
 * {@code DawgIcon} for every new icon.</p>
 */
public final class DawgIcon extends Region {

    /** Discrete icon sizes permitted by the design book. */
    public enum Size {
        SIZE_16(16),
        SIZE_20(20),
        SIZE_24(24);

        private final double px;
        Size(double px) { this.px = px; }
        public double pixels() { return px; }
    }

    /** Lucide ships icons at a 24&times;24 view box. */
    private static final double LUCIDE_VIEW_BOX = 24.0;
    private static final String RESOURCE_ROOT =
            "/com/benesquivelmusic/daw/app/ui/icons/lucide/";

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");

    private final String iconName;
    private final Size size;
    private final List<Shape> shapes;

    // ── CSS-styleable icon colour ────────────────────────────────────────────

    private static final CssMetaData<DawgIcon, Paint> ICON_COLOR_META =
            new CssMetaData<>("-fx-icon-color", PaintConverter.getInstance(), Color.web("#ECEEF2")) {
                @Override public boolean isSettable(DawgIcon node) {
                    return node.iconColor == null || !node.iconColor.isBound();
                }
                @Override @SuppressWarnings("unchecked")
                public StyleableProperty<Paint> getStyleableProperty(DawgIcon node) {
                    return (StyleableProperty<Paint>) node.iconColorProperty();
                }
            };

    private static final List<CssMetaData<? extends Styleable, ?>> STYLEABLES;
    static {
        List<CssMetaData<? extends Styleable, ?>> meta =
                new ArrayList<>(Region.getClassCssMetaData());
        meta.add(ICON_COLOR_META);
        STYLEABLES = Collections.unmodifiableList(meta);
    }

    private final StyleableObjectProperty<Paint> iconColor =
            new StyleableObjectProperty<>(Color.web("#ECEEF2")) {
                @Override public Object getBean() { return DawgIcon.this; }
                @Override public String getName() { return "iconColor"; }
                @Override public CssMetaData<DawgIcon, Paint> getCssMetaData() {
                    return ICON_COLOR_META;
                }
                @Override protected void invalidated() {
                    applyIconColor(get());
                }
            };

    // ── Construction ─────────────────────────────────────────────────────────

    /**
     * Resolves a Lucide icon by name at the given size.
     *
     * @param name the Lucide icon name (e.g. {@code "play"}, {@code "skip-back"})
     * @param size the discrete render size
     * @return a new {@code DawgIcon} region
     * @throws IllegalArgumentException if the icon is not bundled
     */
    public static DawgIcon of(String name, Size size) {
        return new DawgIcon(name, size);
    }

    private DawgIcon(String name, Size size) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("icon name must not be null or blank");
        }
        if (size == null) {
            throw new IllegalArgumentException("size must not be null");
        }
        this.iconName = name;
        this.size = size;
        this.shapes = loadShapes(name);

        // The wrapping Group lets us scale the 24x24 source path data down
        // to the requested nominal size while keeping a clean layout box.
        Group group = new Group();
        group.getChildren().addAll(shapes);
        double scale = size.pixels() / LUCIDE_VIEW_BOX;
        group.getTransforms().add(new Scale(scale, scale));
        group.setManaged(false);
        getChildren().add(group);

        double px = size.pixels();
        setPrefSize(px, px);
        setMinSize(px, px);
        setMaxSize(px, px);

        getStyleClass().add("dawg-icon");
        getStyleClass().add("dawg-icon-" + name);

        applyIconColor(iconColor.get());
    }

    /** The Lucide icon name this region renders. */
    public String iconName() { return iconName; }

    /** The discrete size this region was created at. */
    public Size size() { return size; }

    /**
     * Toggles the {@code :active} pseudo-class — used by toggle buttons so
     * the icon can re-tint via {@code .my-toggle .dawg-icon:active}.
     */
    public void setActive(boolean active) {
        pseudoClassStateChanged(ACTIVE, active);
    }

    // ── Styleable icon colour ────────────────────────────────────────────────

    /** Styleable paint backing {@code -fx-icon-color}. */
    public ObjectProperty<Paint> iconColorProperty() { return iconColor; }
    public Paint getIconColor() { return iconColor.get(); }
    public void setIconColor(Paint p) { iconColor.set(p); }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return STYLEABLES;
    }

    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return STYLEABLES;
    }

    private void applyIconColor(Paint paint) {
        for (Shape s : shapes) {
            // Lucide strokes the path — fill stays transparent unless the
            // source SVG explicitly set fill (we honour that on parse).
            s.setStroke(paint);
            if (s.getUserData() == Boolean.TRUE) {
                // Marker set during parse: this shape *was* originally filled
                // (e.g. circle-dot's inner dot uses fill="currentColor").
                s.setFill(paint);
            }
        }
    }

    // ── SVG loading + parsing ────────────────────────────────────────────────

    private static List<Shape> loadShapes(String name) {
        String path = RESOURCE_ROOT + name + ".svg";
        InputStream in = DawgIcon.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalArgumentException(
                    "Lucide icon not bundled: '" + name + "' (looked under " + path + ")");
        }
        try (in) {
            return parseLucideSvg(in);
        } catch (IOException e) {
            throw new IconLoadException("Failed to read Lucide icon: " + name, e);
        }
    }

    static List<Shape> parseLucideSvg(InputStream in) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);
            Element root = doc.getDocumentElement();

            // Lucide default stroke width is 2 px at 24px viewBox; the design
            // book asks for a 1.5 px optical stroke. We render at the source
            // width and let the scale transform give us the 1.5 px appearance
            // (2 * 16/24 ≈ 1.33 at SIZE_16; 2 * 20/24 ≈ 1.67 at SIZE_20; 2
            // at SIZE_24 — all within the §3.6 1.5 px optical band).
            double strokeWidth = parseDouble(root.getAttribute("stroke-width"), 2.0);
            String linecap = root.getAttribute("stroke-linecap");
            String linejoin = root.getAttribute("stroke-linejoin");

            List<Shape> shapes = new ArrayList<>();
            collect(root, shapes, strokeWidth, linecap, linejoin);
            return shapes;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IconLoadException("Failed to parse Lucide SVG", e);
        }
    }

    private static void collect(Element parent, List<Shape> out,
                                double strokeWidth, String linecap, String linejoin) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element el)) continue;
            Shape shape = switch (el.getTagName()) {
                case "path"    -> toPath(el);
                case "circle"  -> toCircle(el);
                case "rect"    -> toRect(el);
                case "line"    -> toLine(el);
                case "polygon", "polyline" -> null; // not used by current subset
                case "g"       -> { collect(el, out, strokeWidth, linecap, linejoin); yield null; }
                default        -> null;
            };
            if (shape == null) continue;
            shape.setStrokeWidth(strokeWidth);
            shape.setStrokeLineCap(toLineCap(linecap));
            shape.setStrokeLineJoin(toLineJoin(linejoin));
            // Default Lucide elements: stroke="currentColor", fill="none".
            // An element with an explicit fill that is *not* "none" is rare
            // in the subset (e.g. circle-dot uses fill="currentColor" on the
            // inner dot via attribute inheritance); honour it.
            String explicitFill = el.getAttribute("fill");
            if (!explicitFill.isEmpty() && !"none".equalsIgnoreCase(explicitFill)) {
                shape.setUserData(Boolean.TRUE);
            } else {
                shape.setFill(Color.TRANSPARENT);
            }
            out.add(shape);
        }
    }

    private static Shape toPath(Element el) {
        SVGPath p = new SVGPath();
        p.setContent(el.getAttribute("d"));
        return p;
    }

    private static Shape toCircle(Element el) {
        return new Circle(
                parseDouble(el.getAttribute("cx"), 0),
                parseDouble(el.getAttribute("cy"), 0),
                parseDouble(el.getAttribute("r"),  0));
    }

    private static Shape toRect(Element el) {
        Rectangle r = new Rectangle(
                parseDouble(el.getAttribute("x"), 0),
                parseDouble(el.getAttribute("y"), 0),
                parseDouble(el.getAttribute("width"),  0),
                parseDouble(el.getAttribute("height"), 0));
        double rx = parseDouble(el.getAttribute("rx"), 0);
        double ry = parseDouble(el.getAttribute("ry"), rx);
        if (rx > 0) r.setArcWidth(rx * 2);
        if (ry > 0) r.setArcHeight(ry * 2);
        return r;
    }

    private static Shape toLine(Element el) {
        return new Line(
                parseDouble(el.getAttribute("x1"), 0),
                parseDouble(el.getAttribute("y1"), 0),
                parseDouble(el.getAttribute("x2"), 0),
                parseDouble(el.getAttribute("y2"), 0));
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try { return Double.parseDouble(value); } catch (NumberFormatException e) { return fallback; }
    }

    private static StrokeLineCap toLineCap(String value) {
        if (value == null) return StrokeLineCap.ROUND;
        return switch (value) {
            case "square" -> StrokeLineCap.SQUARE;
            case "butt"   -> StrokeLineCap.BUTT;
            default       -> StrokeLineCap.ROUND;
        };
    }

    private static StrokeLineJoin toLineJoin(String value) {
        if (value == null) return StrokeLineJoin.ROUND;
        return switch (value) {
            case "miter" -> StrokeLineJoin.MITER;
            case "bevel" -> StrokeLineJoin.BEVEL;
            default      -> StrokeLineJoin.ROUND;
        };
    }
}
