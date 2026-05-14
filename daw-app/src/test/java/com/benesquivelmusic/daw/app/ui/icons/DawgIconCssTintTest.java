package com.benesquivelmusic.daw.app.ui.icons;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.benesquivelmusic.daw.app.ui.JavaFxToolkitExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link DawgIcon} re-tints from CSS via the
 * {@code -fx-icon-color} property (UI Design Book §2.4 / §3.6).
 *
 * <p>A parent stylesheet specifying {@code -fx-icon-color: red} on the
 * icon's style class must result in every shape in the icon being
 * stroked with {@link Color#RED} after {@code applyCss()}.</p>
 */
@ExtendWith(JavaFxToolkitExtension.class)
class DawgIconCssTintTest {

    @Test
    void shouldInheritIconColorFromCss() {
        DawgIcon icon = DawgIcon.of("play", DawgIcon.Size.SIZE_16);
        StackPane root = new StackPane(icon);
        Scene scene = new Scene(root);
        // Inline stylesheet: target the icon's auto-applied style class.
        String css = ".dawg-icon { -fx-icon-color: red; }";
        scene.getStylesheets().add("data:text/css;base64," +
                java.util.Base64.getEncoder().encodeToString(
                        css.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        root.applyCss();
        icon.applyCss();

        assertThat(icon.getIconColor()).isEqualTo(Color.RED);

        // Every painted shape should carry the new stroke.
        List<Shape> shapes = collectShapes(icon);
        assertThat(shapes).isNotEmpty();
        for (Shape s : shapes) {
            assertThat(s.getStroke()).isEqualTo(Color.RED);
        }
    }

    private static List<Shape> collectShapes(javafx.scene.Node node) {
        List<Shape> out = new ArrayList<>();
        if (node instanceof Shape s) {
            out.add(s);
        } else if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                out.addAll(collectShapes(child));
            }
        }
        return out;
    }
}
