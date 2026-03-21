package com.benesquivelmusic.daw.app.ui.icons;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads SVG icon resources from the DAW icon pack and converts them into
 * JavaFX {@link Node} instances.
 *
 * <p>Each icon is returned as a {@link Group} containing the shapes
 * defined in the original SVG. The group preserves the 48&times;48
 * coordinate space of the source icons. Callers may scale or transform
 * the returned node as needed.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Node playIcon = IconNode.of(DawIcon.PLAY);
 * button.setGraphic(playIcon);
 * }</pre>
 */
public final class IconNode {

    private static final double DEFAULT_ICON_SIZE = 48.0;

    private IconNode() {
        // utility class
    }

    /**
     * Creates a new JavaFX {@link Node} for the given icon at its
     * native 48&times;48 size.
     *
     * @param icon the icon to render
     * @return a {@link Group} containing the icon shapes
     * @throws IllegalArgumentException if the SVG resource cannot be found
     * @throws IconLoadException        if the SVG cannot be parsed
     */
    public static Node of(DawIcon icon) {
        return of(icon, DEFAULT_ICON_SIZE);
    }

    /**
     * Creates a new JavaFX {@link Node} for the given icon, scaled to
     * fit within the specified {@code size} (both width and height).
     *
     * @param icon the icon to render
     * @param size the desired width and height in pixels
     * @return a {@link Group} containing the icon shapes, scaled to {@code size}
     * @throws IllegalArgumentException if the SVG resource cannot be found
     *                                  or {@code size} is not positive
     * @throws IconLoadException        if the SVG cannot be parsed
     */
    public static Node of(DawIcon icon, double size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive: " + size);
        }
        String resourcePath = icon.resourcePath();
        InputStream svgStream = IconNode.class.getResourceAsStream(resourcePath);
        if (svgStream == null) {
            throw new IllegalArgumentException(
                    "SVG resource not found: " + resourcePath);
        }
        try (svgStream) {
            Group group = parseSvg(svgStream);
            if (size != DEFAULT_ICON_SIZE) {
                double scale = size / DEFAULT_ICON_SIZE;
                group.setScaleX(scale);
                group.setScaleY(scale);
            }
            return group;
        } catch (IOException e) {
            throw new IconLoadException("Failed to read SVG: " + resourcePath, e);
        }
    }

    // ----------------------------------------------------------------
    // SVG parsing
    // ----------------------------------------------------------------

    static Group parseSvg(InputStream svgStream) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var builder = factory.newDocumentBuilder();
            Document doc = builder.parse(svgStream);
            Element root = doc.getDocumentElement();
            List<Node> children = parseChildren(root);
            return new Group(children.toArray(Node[]::new));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IconLoadException("Failed to parse SVG", e);
        }
    }

    private static List<Node> parseChildren(Element parent) {
        var nodes = new ArrayList<Node>();
        NodeList childNodes = parent.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            if (childNodes.item(i) instanceof Element child) {
                Node node = convertElement(child);
                if (node != null) {
                    nodes.add(node);
                }
            }
        }
        return nodes;
    }

    private static Node convertElement(Element el) {
        return switch (el.getTagName()) {
            case "line"     -> convertLine(el);
            case "circle"   -> convertCircle(el);
            case "ellipse"  -> convertEllipse(el);
            case "rect"     -> convertRect(el);
            case "path"     -> convertPath(el);
            case "polygon"  -> convertPolygon(el);
            case "polyline" -> convertPolyline(el);
            case "text"     -> convertText(el);
            case "g"        -> convertGroup(el);
            default         -> null;
        };
    }

    // ----------------------------------------------------------------
    // Shape converters
    // ----------------------------------------------------------------

    private static Line convertLine(Element el) {
        var line = new Line(
                doubleAttr(el, "x1"),
                doubleAttr(el, "y1"),
                doubleAttr(el, "x2"),
                doubleAttr(el, "y2"));
        applyStroke(el, line);
        applyFill(el, line);
        return line;
    }

    private static Circle convertCircle(Element el) {
        var circle = new Circle(
                doubleAttr(el, "cx"),
                doubleAttr(el, "cy"),
                doubleAttr(el, "r"));
        applyStroke(el, circle);
        applyFill(el, circle);
        return circle;
    }

    private static Ellipse convertEllipse(Element el) {
        var ellipse = new Ellipse(
                doubleAttr(el, "cx"),
                doubleAttr(el, "cy"),
                doubleAttr(el, "rx"),
                doubleAttr(el, "ry"));
        applyStroke(el, ellipse);
        applyFill(el, ellipse);
        return ellipse;
    }

    private static Rectangle convertRect(Element el) {
        var rect = new Rectangle(
                doubleAttr(el, "x"),
                doubleAttr(el, "y"),
                doubleAttr(el, "width"),
                doubleAttr(el, "height"));
        double rx = doubleAttr(el, "rx");
        double ry = doubleAttr(el, "ry");
        if (rx > 0) {
            rect.setArcWidth(rx * 2);
        }
        if (ry > 0) {
            rect.setArcHeight(ry * 2);
        } else if (rx > 0) {
            rect.setArcHeight(rx * 2);
        }
        applyStroke(el, rect);
        applyFill(el, rect);
        return rect;
    }

    private static SVGPath convertPath(Element el) {
        var path = new SVGPath();
        path.setContent(el.getAttribute("d"));
        applyStroke(el, path);
        applyFill(el, path);
        return path;
    }

    private static Polygon convertPolygon(Element el) {
        var polygon = new Polygon(parsePoints(el.getAttribute("points")));
        applyStroke(el, polygon);
        applyFill(el, polygon);
        return polygon;
    }

    private static Polyline convertPolyline(Element el) {
        var polyline = new Polyline(parsePoints(el.getAttribute("points")));
        applyStroke(el, polyline);
        applyFill(el, polyline);
        return polyline;
    }

    private static Text convertText(Element el) {
        var text = new Text(el.getTextContent().trim());
        text.setX(doubleAttr(el, "x"));
        text.setY(doubleAttr(el, "y"));

        String fill = el.getAttribute("fill");
        if (!fill.isEmpty()) {
            text.setFill(Color.web(fill));
        }

        double fontSize = 12;
        String fontSizeAttr = el.getAttribute("font-size");
        if (!fontSizeAttr.isEmpty()) {
            fontSize = Double.parseDouble(fontSizeAttr);
        }
        String fontWeight = el.getAttribute("font-weight");
        FontWeight weight = "bold".equalsIgnoreCase(fontWeight)
                ? FontWeight.BOLD : FontWeight.NORMAL;
        String fontFamily = el.getAttribute("font-family");
        if (fontFamily.isEmpty()) {
            fontFamily = "System";
        }
        text.setFont(Font.font(fontFamily, weight, fontSize));

        String anchor = el.getAttribute("text-anchor");
        if ("middle".equals(anchor)) {
            text.setTextAlignment(TextAlignment.CENTER);
            // Shift x so the text is centered at the specified coordinate
            text.setX(text.getX() - text.getLayoutBounds().getWidth() / 2);
        }

        return text;
    }

    private static Group convertGroup(Element el) {
        List<Node> children = parseChildren(el);
        return new Group(children.toArray(Node[]::new));
    }

    // ----------------------------------------------------------------
    // Stroke / fill helpers
    // ----------------------------------------------------------------

    private static void applyStroke(Element el, Shape shape) {
        String stroke = el.getAttribute("stroke");
        if (!stroke.isEmpty() && !"none".equals(stroke)) {
            shape.setStroke(Color.web(stroke));
        } else {
            shape.setStroke(null);
        }

        String strokeWidth = el.getAttribute("stroke-width");
        if (!strokeWidth.isEmpty()) {
            shape.setStrokeWidth(Double.parseDouble(strokeWidth));
        }

        String linecap = el.getAttribute("stroke-linecap");
        if (!linecap.isEmpty()) {
            shape.setStrokeLineCap(toStrokeLineCap(linecap));
        }

        String linejoin = el.getAttribute("stroke-linejoin");
        if (!linejoin.isEmpty()) {
            shape.setStrokeLineJoin(toStrokeLineJoin(linejoin));
        }
    }

    private static void applyFill(Element el, Shape shape) {
        String fill = el.getAttribute("fill");
        if (fill.isEmpty()) {
            return;
        }
        if ("none".equals(fill)) {
            shape.setFill(Paint.valueOf("transparent"));
        } else {
            shape.setFill(Color.web(fill));
        }
    }

    // ----------------------------------------------------------------
    // Parsing utilities
    // ----------------------------------------------------------------

    private static double doubleAttr(Element el, String name) {
        String value = el.getAttribute(name);
        if (value == null || value.isEmpty()) {
            return 0;
        }
        return Double.parseDouble(value);
    }

    private static double[] parsePoints(String points) {
        String[] tokens = points.trim().split("[,\\s]+");
        var result = new double[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            result[i] = Double.parseDouble(tokens[i]);
        }
        return result;
    }

    private static StrokeLineCap toStrokeLineCap(String value) {
        return switch (value) {
            case "round"  -> StrokeLineCap.ROUND;
            case "square" -> StrokeLineCap.SQUARE;
            default       -> StrokeLineCap.BUTT;
        };
    }

    private static StrokeLineJoin toStrokeLineJoin(String value) {
        return switch (value) {
            case "round" -> StrokeLineJoin.ROUND;
            case "bevel" -> StrokeLineJoin.BEVEL;
            default      -> StrokeLineJoin.MITER;
        };
    }
}
