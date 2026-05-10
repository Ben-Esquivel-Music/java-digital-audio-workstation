package com.benesquivelmusic.daw.app.ui.help;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * Wires the {@code F1} (open contextual help) and {@code Shift+F1} (toggle
 * Quick Help bar) keyboard shortcuts onto a {@link Scene}.
 *
 * <p>{@code F1} resolves a topic in the following order:</p>
 * <ol>
 *   <li>The slug attached (via {@link HelpControls}) to the keyboard-focused
 *       node, walking up its parent chain.</li>
 *   <li>The slug attached to the node currently under the mouse cursor.</li>
 *   <li>{@link HelpRegistry#INDEX_SLUG} — the help index.</li>
 * </ol>
 *
 * <p>Tests typically prefer to call {@link #resolveTopicSlug} and
 * {@link #openHelpFor} directly rather than synthesising key events.</p>
 */
public final class HelpKeyHandler {

    private final HelpRegistry registry;
    private final HelpOverlay overlay;
    private final QuickHelpBar quickBar;

    /** Latest node observed under the mouse — used as the F1 fallback. */
    private Node lastHovered;

    public HelpKeyHandler(HelpRegistry registry, HelpOverlay overlay, QuickHelpBar quickBar) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.quickBar = Objects.requireNonNull(quickBar, "quickBar");
    }

    /**
     * Installs key + mouse filters on {@code scene}. Safe to call once per
     * scene; subsequent calls are coalesced.
     */
    public void installOn(Scene scene) {
        Objects.requireNonNull(scene, "scene");
        if (Boolean.TRUE.equals(scene.getProperties().get("daw.help.keys.installed"))) {
            return;
        }
        scene.getProperties().put("daw.help.keys.installed", Boolean.TRUE);

        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (e.getTarget() instanceof Node n) {
                lastHovered = n;
            }
        });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
    }

    private void handleKey(KeyEvent event) {
        if (event.getCode() != KeyCode.F1) {
            return;
        }
        if (event.isShiftDown()) {
            quickBar.setEnabled(!quickBar.isEnabled());
            event.consume();
            return;
        }
        Node focused = event.getTarget() instanceof Node n
                ? n
                : (event.getSource() instanceof Scene s ? s.getFocusOwner() : null);
        openHelpFor(focused);
        event.consume();
    }

    /**
     * Resolves the topic that would be opened for {@code focused} given the
     * last mouse-hovered node. Useful in tests.
     */
    public String resolveTopicSlug(Node focused) {
        Optional<String> direct = HelpControls.findHelpTopic(focused);
        if (direct.isPresent()) {
            return registry.hasTopic(direct.get()) ? direct.get() : HelpRegistry.INDEX_SLUG;
        }
        Optional<String> hovered = HelpControls.findHelpTopic(lastHovered);
        if (hovered.isPresent()) {
            return registry.hasTopic(hovered.get()) ? hovered.get() : HelpRegistry.INDEX_SLUG;
        }
        return HelpRegistry.INDEX_SLUG;
    }

    /** Opens the overlay on the topic resolved for {@code focused}. */
    public void openHelpFor(Node focused) {
        overlay.showTopic(resolveTopicSlug(focused));
    }

    /** Test hook — set the "last hovered" node without firing real mouse events. */
    void setLastHovered(Node node) {
        this.lastHovered = node;
    }
}
