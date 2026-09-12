package com.benesquivelmusic.daw.app.ui.metering;

import com.benesquivelmusic.daw.core.metering.MeterTapPoint;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/** Owns a meter token only while its surface belongs to a showing window. FX thread only. */
public final class VisibleMeterBinding implements AutoCloseable {

    private final MeterFeed feed;
    private final MeterTapPoint point;
    private final Node surface;
    private final MeterSink sink;
    private final ChangeListener<Scene> sceneListener = (_, _, scene) -> observeScene(scene);
    private final ChangeListener<Window> windowListener = (_, _, window) -> observeWindow(window);
    private final ChangeListener<Boolean> visibilityListener = (_, _, _) -> reconcile();
    private final ChangeListener<Parent> parentListener = (_, _, _) -> observeAncestors();
    private final List<Node> ancestors = new ArrayList<>();
    private Scene scene;
    private Window window;
    private MeterSubscription subscription;
    private boolean closed;

    public VisibleMeterBinding(MeterFeed feed, MeterTapPoint point, Node surface, MeterSink sink) {
        this.feed = Objects.requireNonNull(feed);
        this.point = Objects.requireNonNull(point);
        this.surface = Objects.requireNonNull(surface);
        this.sink = Objects.requireNonNull(sink);
        surface.sceneProperty().addListener(sceneListener);
        observeAncestors();
        observeScene(surface.getScene());
    }

    private void observeAncestors() {
        for (Node ancestor : ancestors) {
            ancestor.visibleProperty().removeListener(visibilityListener);
            ancestor.parentProperty().removeListener(parentListener);
        }
        ancestors.clear();
        if (!closed) {
            for (Node ancestor = surface; ancestor != null; ancestor = ancestor.getParent()) {
                ancestors.add(ancestor);
                ancestor.visibleProperty().addListener(visibilityListener);
                ancestor.parentProperty().addListener(parentListener);
            }
        }
        reconcile();
    }

    private void observeScene(Scene next) {
        if (scene != null) {
            scene.windowProperty().removeListener(windowListener);
        }
        scene = next;
        if (scene != null) {
            scene.windowProperty().addListener(windowListener);
        }
        observeWindow(scene == null ? null : scene.getWindow());
    }

    private void observeWindow(Window next) {
        if (window != null) {
            window.showingProperty().removeListener(visibilityListener);
        }
        window = next;
        if (window != null) {
            window.showingProperty().addListener(visibilityListener);
        }
        reconcile();
    }

    private boolean isShowing() {
        if (closed || scene == null || window == null || !window.isShowing()) {
            return false;
        }
        for (Node ancestor : ancestors) {
            if (!ancestor.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void reconcile() {
        if (isShowing() && !feed.isDisposed()) {
            if (subscription == null || subscription.isDisposed()) {
                subscription = feed.subscribe(point, surface, this::isShowing, sink);
            }
        } else if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        surface.sceneProperty().removeListener(sceneListener);
        observeAncestors();
        observeScene(null);
    }
}
