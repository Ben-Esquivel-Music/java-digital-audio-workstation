package com.benesquivelmusic.daw.app.ui.icons;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IconNodeTest {

    private Group parse(String svgBody) {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">
                  %s
                </svg>
                """.formatted(svgBody);
        return IconNode.parseSvg(
                new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldParseLineElement() {
        Group group = parse(
                """
                <line x1="8" y1="10" x2="40" y2="38" \
                stroke="#00E676" stroke-width="2.5" \
                stroke-linecap="round"/>""");

        assertThat(group.getChildren()).hasSize(1);
        Line line = (Line) group.getChildren().getFirst();
        assertThat(line.getStartX()).isEqualTo(8);
        assertThat(line.getStartY()).isEqualTo(10);
        assertThat(line.getEndX()).isEqualTo(40);
        assertThat(line.getEndY()).isEqualTo(38);
        assertThat(line.getStroke()).isEqualTo(Color.web("#00E676"));
        assertThat(line.getStrokeWidth()).isEqualTo(2.5);
        assertThat(line.getStrokeLineCap()).isEqualTo(StrokeLineCap.ROUND);
    }

    @Test
    void shouldParseCircleElement() {
        Group group = parse(
                """
                <circle cx="24" cy="24" r="14" \
                fill="none" stroke="#FF9100" stroke-width="2"/>""");

        assertThat(group.getChildren()).hasSize(1);
        Circle circle = (Circle) group.getChildren().getFirst();
        assertThat(circle.getCenterX()).isEqualTo(24);
        assertThat(circle.getCenterY()).isEqualTo(24);
        assertThat(circle.getRadius()).isEqualTo(14);
        assertThat(circle.getStroke()).isEqualTo(Color.web("#FF9100"));
        assertThat(circle.getStrokeWidth()).isEqualTo(2);
    }

    @Test
    void shouldParseEllipseElement() {
        Group group = parse(
                """
                <ellipse cx="16" cy="28" rx="8" ry="5" \
                fill="none" stroke="#FF1744" stroke-width="2"/>""");

        assertThat(group.getChildren()).hasSize(1);
        Ellipse ellipse = (Ellipse) group.getChildren().getFirst();
        assertThat(ellipse.getCenterX()).isEqualTo(16);
        assertThat(ellipse.getCenterY()).isEqualTo(28);
        assertThat(ellipse.getRadiusX()).isEqualTo(8);
        assertThat(ellipse.getRadiusY()).isEqualTo(5);
    }

    @Test
    void shouldParseRectElement() {
        Group group = parse(
                """
                <rect x="10" y="6" width="28" height="36" rx="2" \
                fill="none" stroke="#FF1744" stroke-width="2"/>""");

        assertThat(group.getChildren()).hasSize(1);
        Rectangle rect = (Rectangle) group.getChildren().getFirst();
        assertThat(rect.getX()).isEqualTo(10);
        assertThat(rect.getY()).isEqualTo(6);
        assertThat(rect.getWidth()).isEqualTo(28);
        assertThat(rect.getHeight()).isEqualTo(36);
        assertThat(rect.getArcWidth()).isEqualTo(4); // rx * 2
        assertThat(rect.getArcHeight()).isEqualTo(4); // defaults to rx * 2 when ry absent
    }

    @Test
    void shouldParsePathElement() {
        Group group = parse(
                """
                <path d="M10,26 Q24,16 38,26" fill="none" \
                stroke="#00E676" stroke-width="2.5" \
                stroke-linecap="round"/>""");

        assertThat(group.getChildren()).hasSize(1);
        SVGPath path = (SVGPath) group.getChildren().getFirst();
        assertThat(path.getContent()).isEqualTo("M10,26 Q24,16 38,26");
    }

    @Test
    void shouldParsePolygonElement() {
        Group group = parse(
                """
                <polygon points="16,10 16,38 40,24" \
                fill="#00E676" stroke="#00E676" \
                stroke-width="2" stroke-linejoin="round"/>""");

        assertThat(group.getChildren()).hasSize(1);
        Polygon polygon = (Polygon) group.getChildren().getFirst();
        assertThat(polygon.getPoints())
                .containsExactly(16.0, 10.0, 16.0, 38.0, 40.0, 24.0);
        assertThat(polygon.getStrokeLineJoin()).isEqualTo(StrokeLineJoin.ROUND);
    }

    @Test
    void shouldParsePolylineElement() {
        Group group = parse(
                """
                <polyline points="20,12 24,8 28,12" \
                fill="none" stroke="#FFFFFF" \
                stroke-width="2" stroke-linecap="round" \
                stroke-linejoin="round"/>""");

        assertThat(group.getChildren()).hasSize(1);
        Polyline polyline = (Polyline) group.getChildren().getFirst();
        assertThat(polyline.getPoints())
                .containsExactly(20.0, 12.0, 24.0, 8.0, 28.0, 12.0);
    }

    @Test
    void shouldParseMultipleElements() {
        Group group = parse("""
                <circle cx="24" cy="24" r="10" fill="#FF0000"/>
                <line x1="0" y1="0" x2="48" y2="48" stroke="#FFFFFF"/>
                <rect x="5" y="5" width="10" height="10" fill="#0000FF"/>
                """);

        assertThat(group.getChildren()).hasSize(3);
        assertThat(group.getChildren().get(0)).isInstanceOf(Circle.class);
        assertThat(group.getChildren().get(1)).isInstanceOf(Line.class);
        assertThat(group.getChildren().get(2)).isInstanceOf(Rectangle.class);
    }

    @Test
    void shouldParseGroupElement() {
        Group group = parse("""
                <g>
                  <circle cx="10" cy="10" r="5" fill="#FF0000"/>
                  <line x1="0" y1="0" x2="20" y2="20" stroke="#00FF00"/>
                </g>
                """);

        assertThat(group.getChildren()).hasSize(1);
        Group inner = (Group) group.getChildren().getFirst();
        assertThat(inner.getChildren()).hasSize(2);
    }

    @Test
    void shouldIgnoreUnknownElements() {
        Group group = parse("""
                <defs><style>/* skip */</style></defs>
                <circle cx="24" cy="24" r="10" fill="#FF0000"/>
                """);

        assertThat(group.getChildren()).hasSize(1);
        assertThat(group.getChildren().getFirst()).isInstanceOf(Circle.class);
    }

    @Test
    void shouldRejectNonPositiveSize() {
        assertThatThrownBy(() -> IconNode.of(DawIcon.PLAY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be positive");

        assertThatThrownBy(() -> IconNode.of(DawIcon.PLAY, -10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleFillNone() {
        Group group = parse(
                """
                <circle cx="24" cy="24" r="14" fill="none" \
                stroke="#FF9100" stroke-width="2"/>""");

        Circle circle = (Circle) group.getChildren().getFirst();
        assertThat(circle.getFill()).isEqualTo(Color.TRANSPARENT);
    }

    @Test
    void shouldHandleStrokeNone() {
        Group group = parse(
                """
                <circle cx="24" cy="24" r="14" fill="#FF0000" \
                stroke="none"/>""");

        Circle circle = (Circle) group.getChildren().getFirst();
        assertThat(circle.getStroke()).isNull();
    }

    @Test
    void shouldParseRectWithBothRxAndRy() {
        Group group = parse(
                """
                <rect x="0" y="0" width="20" height="20" \
                rx="3" ry="5" fill="#000000"/>""");

        Rectangle rect = (Rectangle) group.getChildren().getFirst();
        assertThat(rect.getArcWidth()).isEqualTo(6);  // rx * 2
        assertThat(rect.getArcHeight()).isEqualTo(10); // ry * 2
    }
}
