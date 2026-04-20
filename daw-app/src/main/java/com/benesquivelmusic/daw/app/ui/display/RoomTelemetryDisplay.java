package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.telemetry.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Creative, animated 3D room sound wave telemetry visualizer.
 *
 * <p>Renders an isometric 3D view of the recording room with:
 * <ul>
 *   <li><b>3D room box</b> showing floor, back wall, and left wall with wireframe edges</li>
 *   <li><b>Pulsing, glowing sound sources</b> positioned in 3D space with animated ripple rings</li>
 *   <li><b>Animated microphone icons</b> with directional aim indicators in 3D</li>
 *   <li><b>Color-coded wave paths</b> — cool cyan for direct, warm orange for reflected</li>
 *   <li><b>Traveling energy particles</b> flowing along each path in 3D</li>
 *   <li><b>Expanding sonar ripples</b> projected as ellipses on the floor (z=0) plane</li>
 *   <li><b>RT60 ambient glow</b> — the room edges pulse with reverb intensity</li>
 *   <li><b>Suggestion badges</b> rendered in an overlay panel</li>
 * </ul>
 *
 * <p>The isometric projection maps 3D room coordinates (X = width, Y = length,
 * Z = height) to 2D screen space using a 30° dimetric projection, creating a
 * natural 3D perspective that shows the full recording space geometry.</p>
 *
 * <p>All animations are driven by a time-accumulator so the display can be
 * updated at any frame rate. Call {@link #updateAnimation(double)} each
 * frame to advance the animation state, then the canvas repaints.</p>
 */
public final class RoomTelemetryDisplay extends Region {

    // ── Color palette ──────────────────────────────────────────────
    private static final Color BACKGROUND = Color.web("#0a0a1e");
    private static final Color ROOM_FILL = Color.web("#11112a");
    private static final Color ROOM_BORDER = Color.web("#2a2a5a");
    private static final Color WALL_BACK_FILL = Color.web("#0f0f26");
    private static final Color WALL_LEFT_FILL = Color.web("#131332");
    private static final Color GRID_COLOR = Color.web("#ffffff", 0.04);
    private static final Color DIRECT_PATH_COLOR = Color.web("#00e5ff");
    private static final Color REFLECTED_PATH_COLOR = Color.web("#ff9100");
    private static final Color SOURCE_COLOR = Color.web("#ff4081");
    private static final Color SOURCE_GLOW = Color.web("#ff4081", 0.25);
    private static final Color MIC_COLOR = Color.web("#69f0ae");
    private static final Color MIC_GLOW = Color.web("#69f0ae", 0.30);
    private static final Color PARTICLE_DIRECT = Color.web("#00e5ff");
    private static final Color PARTICLE_REFLECTED = Color.web("#ffab40");
    private static final Color RIPPLE_COLOR = Color.web("#ff4081", 0.35);
    private static final Color SUGGESTION_BG = Color.web("#ffea00", 0.15);
    private static final Color SUGGESTION_BORDER = Color.web("#ffea00", 0.6);
    private static final Color SUGGESTION_TEXT = Color.web("#ffea00");
    private static final Color TEXT_COLOR = Color.web("#ffffff", 0.7);
    private static final Color RT60_LOW = Color.web("#00e676", 0.3);
    private static final Color RT60_MID = Color.web("#ffea00", 0.3);
    private static final Color RT60_HIGH = Color.web("#ff1744", 0.4);
    private static final Color AUDIENCE_COLOR = Color.web("#b388ff");
    private static final Color AUDIENCE_GLOW = Color.web("#b388ff", 0.20);
    private static final Color WALL_LABEL_COLOR = Color.web("#ffffff", 0.25);
    private static final Color CRITICAL_DISTANCE_COLOR = Color.web("#ffffff", 0.12);
    private static final Color LEGEND_BG = Color.web("#0a0a1e", 0.85);
    private static final Color LEGEND_BORDER = Color.web("#2a2a5a", 0.8);
    private static final Color STATS_BG = Color.web("#0a0a1e", 0.85);
    private static final Color STATS_BORDER = Color.web("#2a2a5a", 0.8);

    // ── Isometric projection constants ─────────────────────────────
    private static final double ISO_ANGLE = Math.toRadians(30.0);
    private static final double COS_ISO = Math.cos(ISO_ANGLE);
    private static final double SIN_ISO = Math.sin(ISO_ANGLE);
    private static final int PROJECTED_CIRCLE_SEGMENTS = 36;

    // ── Animation constants ────────────────────────────────────────
    private static final double RIPPLE_INTERVAL = 0.8;
    private static final double RIPPLE_MAX_AGE = 2.0;
    private static final double RIPPLE_SPEED_MPS = 2.0;
    private static final double SOURCE_PULSE_SPEED = 3.0;
    private static final double MIC_PULSE_SPEED = 2.5;

    private static final double ROOM_MARGIN = 50.0;
    private static final double LABEL_OFFSET = 18.0;
    private static final double SOURCE_RADIUS = 10.0;
    private static final double MIC_RADIUS = 8.0;
    private static final double AUDIENCE_RADIUS = 7.0;
    private static final double AUDIENCE_PULSE_SPEED = 1.8;
    private static final double AUDIENCE_LABEL_STAGGER = 12.0;
    private static final double MIC_AIM_LENGTH = 20.0;

    private final Canvas canvas;
    private RoomTelemetryData telemetryData;
    private double animationTime;

    // Per-path particle animators (keyed by "sourceName→micName:reflected")
    private final Map<String, WaveParticleAnimator> pathAnimators = new HashMap<>();

    // Sonar ripples from sources
    private final List<Ripple> ripples = new ArrayList<>();
    private double timeSinceLastRipple;

    // Drag-and-drop state
    private String draggedSourceName;
    private String draggedMicName;
    private double draggedZ;
    private BiConsumer<String, Position3D> onSourceDragged;
    private BiConsumer<String, Position3D> onMicDragged;

    // Cached isometric projection transform (updated in render)
    private double cachedCenterX;
    private double cachedCenterY;
    private double cachedScale;

    // Cached ceiling overlay (regenerated only when telemetry data changes)
    private javafx.scene.image.WritableImage cachedCeilingOverlay;
    private RoomDimensions cachedCeilingDims;

    // Treatment overlay — acoustic treatments marked as applied in the
    // room configuration, rendered as small icons on top of the 2D room
    // view so the user can see at a glance what is already installed.
    private final List<AcousticTreatment> treatmentOverlays = new ArrayList<>();

    /**
     * Sets the list of acoustic treatments to overlay on the 2D room view.
     * Typically populated from {@code RoomConfiguration.getAppliedTreatments()}
     * plus any current {@code TreatmentAdvisor} suggestions being previewed.
     * The list is defensively copied; pass an empty list to clear.
     *
     * @param treatments the treatments to display (must not be {@code null})
     */
    public void setTreatmentOverlays(List<AcousticTreatment> treatments) {
        Objects.requireNonNull(treatments, "treatments must not be null");
        this.treatmentOverlays.clear();
        this.treatmentOverlays.addAll(treatments);
        render();
    }

    /** Returns an unmodifiable snapshot of the current treatment overlays. */
    public List<AcousticTreatment> getTreatmentOverlays() {
        return java.util.Collections.unmodifiableList(
                new ArrayList<>(treatmentOverlays));
    }

    /**
     * Creates a new room telemetry display.
     */
    public RoomTelemetryDisplay() {
        canvas = new Canvas();
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((_, _, _) -> render());
        heightProperty().addListener((_, _, _) -> render());

        // Drag-and-drop mouse handlers
        canvas.setOnMousePressed(this::handleMousePressed);
        canvas.setOnMouseDragged(this::handleMouseDragged);
        canvas.setOnMouseReleased(this::handleMouseReleased);
    }

    /**
     * Updates the telemetry data and reinitializes animators.
     *
     * @param data the latest telemetry snapshot
     */
    public void setTelemetryData(RoomTelemetryData data) {
        this.telemetryData = data;
        pathAnimators.clear();
        ripples.clear();
        animationTime = 0;
        timeSinceLastRipple = 0;
        cachedCeilingOverlay = null;
        cachedCeilingDims = null;

        if (data != null) {
            for (SoundWavePath path : data.wavePaths()) {
                String key = pathKey(path);
                double speed = path.reflected() ? 0.8 : 1.2;
                pathAnimators.put(key, new WaveParticleAnimator(0.2, speed, 1.8));
            }
        }
        render();
    }

    /**
     * Advances all animations by the given delta and repaints.
     *
     * <p>Call this from an {@link javafx.animation.AnimationTimer} on each
     * frame for smooth, creative animations.</p>
     *
     * @param deltaSeconds seconds since last frame
     */
    public void updateAnimation(double deltaSeconds) {
        animationTime += deltaSeconds;

        // Update path particles
        for (WaveParticleAnimator animator : pathAnimators.values()) {
            animator.update(deltaSeconds);
        }

        // Spawn sonar ripples
        timeSinceLastRipple += deltaSeconds;
        if (timeSinceLastRipple >= RIPPLE_INTERVAL && telemetryData != null) {
            timeSinceLastRipple -= RIPPLE_INTERVAL;
            // One ripple from each source position
            HashSet<String> seen = new HashSet<>();
            for (SoundWavePath path : telemetryData.wavePaths()) {
                if (seen.add(path.sourceName())) {
                    Position3D sp = path.waypoints().getFirst();
                    ripples.add(new Ripple(sp.x(), sp.y(), 0, 0));
                }
            }
        }

        // Age ripples
        ripples.replaceAll(r -> new Ripple(r.roomX, r.roomY, r.roomZ, r.age + deltaSeconds));
        ripples.removeIf(r -> r.age >= RIPPLE_MAX_AGE);

        render();
    }

    /** Returns the current telemetry data. */
    public RoomTelemetryData getTelemetryData() {
        return telemetryData;
    }

    /**
     * Sets a callback invoked when a sound source is dragged to a new position.
     *
     * @param listener callback receiving (sourceName, newPosition)
     */
    public void setOnSourceDragged(BiConsumer<String, Position3D> listener) {
        this.onSourceDragged = listener;
    }

    /**
     * Sets a callback invoked when a microphone is dragged to a new position.
     *
     * @param listener callback receiving (micName, newPosition)
     */
    public void setOnMicDragged(BiConsumer<String, Position3D> listener) {
        this.onMicDragged = listener;
    }

    /**
     * Returns the source drag listener.
     *
     * @return the source drag callback, or {@code null}
     */
    public BiConsumer<String, Position3D> getOnSourceDragged() {
        return onSourceDragged;
    }

    /**
     * Returns the mic drag listener.
     *
     * @return the mic drag callback, or {@code null}
     */
    public BiConsumer<String, Position3D> getOnMicDragged() {
        return onMicDragged;
    }

    // ── Isometric 3D Projection ────────────────────────────────────

    /**
     * Projects a 3D room coordinate to 2D screen space using isometric projection.
     *
     * @param x the X coordinate in meters (room width axis)
     * @param y the Y coordinate in meters (room length axis)
     * @param z the Z coordinate in meters (room height axis)
     * @return a two-element array {screenX, screenY}
     */
    double[] projectToScreen(double x, double y, double z) {
        double rawX = (x - y) * COS_ISO;
        double rawY = (x + y) * SIN_ISO - z;
        return new double[]{
                cachedCenterX + rawX * cachedScale,
                cachedCenterY + rawY * cachedScale
        };
    }

    /**
     * Projects a 3D room coordinate into a caller-provided output array.
     * This avoids allocation in tight loops (e.g., projected circle polygons).
     *
     * @param x   the X coordinate in meters (room width axis)
     * @param y   the Y coordinate in meters (room length axis)
     * @param z   the Z coordinate in meters (room height axis)
     * @param out a two-element array to receive {screenX, screenY}
     */
    private void projectInto(double x, double y, double z, double[] out) {
        double rawX = (x - y) * COS_ISO;
        double rawY = (x + y) * SIN_ISO - z;
        out[0] = cachedCenterX + rawX * cachedScale;
        out[1] = cachedCenterY + rawY * cachedScale;
    }

    /**
     * Reverse-projects a 2D screen coordinate back to 3D room space on a
     * horizontal plane at the given Z height.
     *
     * @param screenX the screen X coordinate
     * @param screenY the screen Y coordinate
     * @param z       the known Z height (room height axis)
     * @return the corresponding 3D room position
     */
    Position3D unprojectFromScreen(double screenX, double screenY, double z) {
        double rawX = (screenX - cachedCenterX) / cachedScale;
        double rawY = (screenY - cachedCenterY) / cachedScale;
        double xMinusY = rawX / COS_ISO;
        double xPlusY = (rawY + z) / SIN_ISO;
        double x = (xMinusY + xPlusY) / 2;
        double y = (xPlusY - xMinusY) / 2;
        return new Position3D(x, y, z);
    }

    // ── Drag-and-drop ──────────────────────────────────────────────

    private void handleMousePressed(MouseEvent event) {
        if (telemetryData == null || cachedScale <= 0) return;

        double mx = event.getX();
        double my = event.getY();

        // Check sources
        HashSet<String> checkedSources = new HashSet<>();
        for (SoundWavePath path : telemetryData.wavePaths()) {
            if (checkedSources.add(path.sourceName())) {
                Position3D sp = path.waypoints().getFirst();
                double[] screen = projectToScreen(sp.x(), sp.y(), sp.z());
                if (Math.hypot(mx - screen[0], my - screen[1]) <= SOURCE_RADIUS + 6) {
                    draggedSourceName = path.sourceName();
                    draggedZ = sp.z();
                    return;
                }
            }
        }

        // Check microphones
        HashSet<String> checkedMics = new HashSet<>();
        for (SoundWavePath path : telemetryData.wavePaths()) {
            if (checkedMics.add(path.microphoneName())) {
                Position3D mp = path.waypoints().getLast();
                double[] screen = projectToScreen(mp.x(), mp.y(), mp.z());
                if (Math.hypot(mx - screen[0], my - screen[1]) <= MIC_RADIUS + 6) {
                    draggedMicName = path.microphoneName();
                    draggedZ = mp.z();
                    return;
                }
            }
        }
    }

    private void handleMouseDragged(MouseEvent event) {
        if (telemetryData == null || cachedScale <= 0) return;

        Position3D roomPos = unprojectFromScreen(event.getX(), event.getY(), draggedZ);

        // Clamp to room bounds
        double w = telemetryData.roomDimensions().width();
        double l = telemetryData.roomDimensions().length();
        double clampedX = Math.max(0.01, Math.min(w - 0.01, roomPos.x()));
        double clampedY = Math.max(0.01, Math.min(l - 0.01, roomPos.y()));

        Position3D newPos = new Position3D(clampedX, clampedY, draggedZ);

        if (draggedSourceName != null && onSourceDragged != null) {
            onSourceDragged.accept(draggedSourceName, newPos);
        } else if (draggedMicName != null && onMicDragged != null) {
            onMicDragged.accept(draggedMicName, newPos);
        }
    }

    private void handleMouseReleased(MouseEvent event) {
        draggedSourceName = null;
        draggedMicName = null;
    }

    // ── Rendering ──────────────────────────────────────────────────

    private void render() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.save();

        // Background
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, w, h);

        if (telemetryData == null) {
            drawPlaceholder(gc, w, h);
            gc.restore();
            return;
        }

        double roomW = telemetryData.roomDimensions().width();
        double roomL = telemetryData.roomDimensions().length();
        double roomH = telemetryData.roomDimensions().height();

        // Compute isometric bounding box and scale to fit canvas
        double projWidth = (roomW + roomL) * COS_ISO;
        double projHeight = (roomW + roomL) * SIN_ISO + roomH;
        double availW = w - 2 * ROOM_MARGIN;
        double availH = h - 2 * ROOM_MARGIN;
        double scale = Math.min(availW / projWidth, availH / projHeight);

        double centroidRawX = (roomW - roomL) * COS_ISO / 2;
        double centroidRawY = ((roomW + roomL) * SIN_ISO - roomH) / 2;

        cachedCenterX = w / 2 - centroidRawX * scale;
        cachedCenterY = h / 2 - centroidRawY * scale;
        cachedScale = scale;

        // ── Draw 3D room ──
        drawRoom3D(gc, roomW, roomL, roomH);

        // ── Draw floor grid ──
        drawFloorGrid(gc, roomW, roomL);

        // ── Draw wall labels ──
        drawWallLabels(gc, roomW, roomL, roomH);

        // ── Draw dimension labels ──
        drawDimensionLabels(gc, roomW, roomL, roomH);

        // ── Draw sonar ripples ──
        for (Ripple ripple : ripples) {
            drawRipple(gc, ripple);
        }

        // ── Draw critical distance circles ──
        drawCriticalDistance(gc);

        // ── Draw wave paths with particles ──
        for (SoundWavePath path : telemetryData.wavePaths()) {
            drawWavePath(gc, path);
        }

        // ── Draw sound sources (with glow + pulse + power) ──
        HashSet<String> drawnSources = new HashSet<>();
        for (SoundWavePath path : telemetryData.wavePaths()) {
            if (drawnSources.add(path.sourceName())) {
                Position3D sp = path.waypoints().getFirst();
                SoundSource source = findSourceByName(path.sourceName());
                double powerDb = (source != null) ? source.powerDb() : 80.0;
                drawSource(gc, sp, path.sourceName(), powerDb);
            }
        }

        // ── Draw microphones (with glow + pulse + aim) ──
        HashSet<String> drawnMics = new HashSet<>();
        for (SoundWavePath path : telemetryData.wavePaths()) {
            if (drawnMics.add(path.microphoneName())) {
                Position3D mp = path.waypoints().getLast();
                MicrophonePlacement mic = findMicByName(path.microphoneName());
                drawMicrophone(gc, mp, path.microphoneName(), mic);
            }
        }

        // ── Draw audience members ──
        List<AudienceMember> audience = telemetryData.audienceMembers();
        for (int i = 0; i < audience.size(); i++) {
            AudienceMember member = audience.get(i);
            drawAudienceMember(gc, member.position(), member.name(), i);
        }

        // ── Draw RT60 glow on room edges ──
        drawRt60Glow(gc, roomW, roomL, roomH);

        // ── Draw applied/suggested treatment icons ──
        drawTreatmentOverlays(gc, roomW, roomL, roomH);

        // ── Draw suggestions panel ──
        drawSuggestions(gc, w, h);

        // ── Draw room statistics panel ──
        drawRoomStats(gc);

        // ── Draw ceiling-shape overlay (iso-contour + side silhouette) ──
        drawCeilingOverlay(gc, w, h, roomW, roomL);

        // ── Draw color legend ──
        drawLegend(gc, w);

        // ── Draw scale bar ──
        drawScaleBar(gc, roomW);

        gc.restore();
    }

    private void drawPlaceholder(GraphicsContext gc, double w, double h) {
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 14));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("\uD83C\uDF99  Configure a room to see 3D Sound Wave Telemetry  \uD83C\uDF99", w / 2, h / 2);
    }

    // ── 3D Room Drawing ────────────────────────────────────────────

    private void drawRoom3D(GraphicsContext gc, double roomW, double roomL, double roomH) {
        // Floor corners (z = 0)
        double[] f0 = projectToScreen(0, 0, 0);
        double[] f1 = projectToScreen(roomW, 0, 0);
        double[] f2 = projectToScreen(roomW, roomL, 0);
        double[] f3 = projectToScreen(0, roomL, 0);

        // Ceiling corners (z = roomH)
        double[] c0 = projectToScreen(0, 0, roomH);
        double[] c1 = projectToScreen(roomW, 0, roomH);
        double[] c2 = projectToScreen(roomW, roomL, roomH);
        double[] c3 = projectToScreen(0, roomL, roomH);

        // Fill back wall (y = roomL): f3 → f2 → c2 → c3
        gc.setFill(WALL_BACK_FILL);
        gc.fillPolygon(
                new double[]{f3[0], f2[0], c2[0], c3[0]},
                new double[]{f3[1], f2[1], c2[1], c3[1]}, 4);

        // Fill left wall (x = 0): f0 → f3 → c3 → c0
        gc.setFill(WALL_LEFT_FILL);
        gc.fillPolygon(
                new double[]{f0[0], f3[0], c3[0], c0[0]},
                new double[]{f0[1], f3[1], c3[1], c0[1]}, 4);

        // Fill floor (z = 0): f0 → f1 → f2 → f3
        gc.setFill(ROOM_FILL);
        gc.fillPolygon(
                new double[]{f0[0], f1[0], f2[0], f3[0]},
                new double[]{f0[1], f1[1], f2[1], f3[1]}, 4);

        // Draw all 12 box edges as wireframe
        gc.setStroke(ROOM_BORDER);
        gc.setLineWidth(2.0);
        gc.setLineDashes();

        // Floor edges
        strokeScreenLine(gc, f0, f1);
        strokeScreenLine(gc, f1, f2);
        strokeScreenLine(gc, f2, f3);
        strokeScreenLine(gc, f3, f0);
        // Ceiling edges
        strokeScreenLine(gc, c0, c1);
        strokeScreenLine(gc, c1, c2);
        strokeScreenLine(gc, c2, c3);
        strokeScreenLine(gc, c3, c0);
        // Vertical pillar edges
        strokeScreenLine(gc, f0, c0);
        strokeScreenLine(gc, f1, c1);
        strokeScreenLine(gc, f2, c2);
        strokeScreenLine(gc, f3, c3);
    }

    // ── Floor Grid ─────────────────────────────────────────────────

    private void drawFloorGrid(GraphicsContext gc, double roomW, double roomL) {
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);
        gc.setLineDashes();

        double[] s = new double[2];
        double[] e = new double[2];

        // 1-meter grid lines along the X axis (constant x, varying y at z=0)
        for (double m = 1; m < roomW; m += 1) {
            projectInto(m, 0, 0, s);
            projectInto(m, roomL, 0, e);
            gc.strokeLine(s[0], s[1], e[0], e[1]);
        }
        // 1-meter grid lines along the Y axis (constant y, varying x at z=0)
        for (double m = 1; m < roomL; m += 1) {
            projectInto(0, m, 0, s);
            projectInto(roomW, m, 0, e);
            gc.strokeLine(s[0], s[1], e[0], e[1]);
        }
    }

    // ── Ripples (projected ellipses) ───────────────────────────────

    private void drawRipple(GraphicsContext gc, Ripple ripple) {
        double radiusM = ripple.age * RIPPLE_SPEED_MPS;
        double opacity = Math.max(0, 1.0 - ripple.age / RIPPLE_MAX_AGE);

        gc.setStroke(RIPPLE_COLOR.deriveColor(0, 1, 1, opacity * 0.6));
        gc.setLineWidth(2.0 * opacity + 0.5);
        gc.setLineDashes();
        strokeProjectedCircle(gc, ripple.roomX, ripple.roomY, ripple.roomZ, radiusM);

        // Inner ghost ring (fun layered effect)
        if (ripple.age > 0.2) {
            double innerRadiusM = (ripple.age - 0.2) * RIPPLE_SPEED_MPS;
            double innerOpacity = Math.max(0, 1.0 - (ripple.age - 0.2) / RIPPLE_MAX_AGE) * 0.3;
            gc.setStroke(RIPPLE_COLOR.deriveColor(0, 1, 1, innerOpacity));
            gc.setLineWidth(1.0);
            strokeProjectedCircle(gc, ripple.roomX, ripple.roomY, ripple.roomZ, innerRadiusM);
        }
    }

    // ── Wave Paths ─────────────────────────────────────────────────

    private void drawWavePath(GraphicsContext gc, SoundWavePath path) {
        List<Position3D> waypoints = path.waypoints();
        boolean reflected = path.reflected();
        Color pathColor = reflected ? REFLECTED_PATH_COLOR : DIRECT_PATH_COLOR;

        // Draw the path line (dashed for reflected, solid for direct)
        gc.setStroke(pathColor.deriveColor(0, 1, 1, 0.35));
        gc.setLineWidth(reflected ? 1.0 : 1.8);
        if (reflected) {
            gc.setLineDashes(6, 4);
        } else {
            gc.setLineDashes();
        }

        gc.beginPath();
        for (int i = 0; i < waypoints.size(); i++) {
            Position3D wp = waypoints.get(i);
            double[] screen = projectToScreen(wp.x(), wp.y(), wp.z());
            if (i == 0) gc.moveTo(screen[0], screen[1]);
            else gc.lineTo(screen[0], screen[1]);
        }
        gc.stroke();
        gc.setLineDashes();

        // Draw reflection point marker (small diamond) with level label
        if (reflected && waypoints.size() >= 3) {
            Position3D rp = waypoints.get(1);
            double[] screen = projectToScreen(rp.x(), rp.y(), rp.z());
            double rx = screen[0];
            double ry = screen[1];
            gc.setFill(REFLECTED_PATH_COLOR.deriveColor(0, 1, 1, 0.6));
            double d = 4;
            gc.fillPolygon(
                    new double[]{rx, rx + d, rx, rx - d},
                    new double[]{ry - d, ry, ry + d, ry},
                    4
            );

            // Show reflection level label
            gc.setFill(REFLECTED_PATH_COLOR.deriveColor(0, 1, 1, 0.7));
            gc.setFont(Font.font("System", 8));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("%.1f dB".formatted(path.attenuationDb()), rx + d + 2, ry - 2);
        }

        // Show direct path distance and delay at midpoint
        if (!reflected && waypoints.size() >= 2) {
            double[] midPos = interpolateAlongPath(waypoints, 0.5);
            gc.setFill(DIRECT_PATH_COLOR.deriveColor(0, 1, 1, 0.6));
            gc.setFont(Font.font("System", 8));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("%.1fm  %.1fms".formatted(path.totalDistance(), path.delayMs()),
                    midPos[0], midPos[1] - 6);
        }

        // Draw traveling particles
        String key = pathKey(path);
        WaveParticleAnimator animator = pathAnimators.get(key);
        if (animator != null) {
            Color particleColor = reflected ? PARTICLE_REFLECTED : PARTICLE_DIRECT;
            for (WaveParticleAnimator.Particle particle : animator.getParticles()) {
                double[] pos = interpolateAlongPath(waypoints, particle.progress());
                double opacity = particle.opacity();
                double size = 3.0 + 2.0 * Math.sin(animationTime * 6 + particle.progress() * Math.PI);

                // Glow halo
                gc.setFill(particleColor.deriveColor(0, 1, 1, opacity * 0.3));
                gc.fillOval(pos[0] - size * 1.5, pos[1] - size * 1.5, size * 3, size * 3);

                // Core particle
                gc.setFill(particleColor.deriveColor(0, 1, 1, opacity));
                gc.fillOval(pos[0] - size / 2, pos[1] - size / 2, size, size);
            }
        }
    }

    // ── Sound Sources ──────────────────────────────────────────────

    private void drawSource(GraphicsContext gc, Position3D pos, String name,
                             double powerDb) {
        double[] screen = projectToScreen(pos.x(), pos.y(), pos.z());
        double cx = screen[0];
        double cy = screen[1];

        // Animated pulse
        double pulse = 0.5 + 0.5 * Math.sin(animationTime * SOURCE_PULSE_SPEED);

        // Scale glow radius by powerDb (75 dB → baseline, 100 dB → larger)
        double powerScale = 0.6 + 0.4 * Math.max(0, Math.min(1, (powerDb - 60) / 50.0));
        double glowRadius = (SOURCE_RADIUS + 8 * pulse) * powerScale;

        // Outer glow
        gc.setFill(new RadialGradient(0, 0, cx, cy, glowRadius * 2, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, SOURCE_GLOW),
                new Stop(1.0, Color.TRANSPARENT)
        ));
        gc.fillOval(cx - glowRadius * 2, cy - glowRadius * 2, glowRadius * 4, glowRadius * 4);

        // Core circle
        gc.setFill(SOURCE_COLOR);
        gc.fillOval(cx - SOURCE_RADIUS, cy - SOURCE_RADIUS, SOURCE_RADIUS * 2, SOURCE_RADIUS * 2);

        // Inner highlight (fun concentric ring)
        gc.setStroke(Color.web("#ffffff", 0.3 + 0.2 * pulse));
        gc.setLineWidth(1.5);
        gc.strokeOval(cx - SOURCE_RADIUS * 0.5, cy - SOURCE_RADIUS * 0.5,
                SOURCE_RADIUS, SOURCE_RADIUS);

        // Speaker icon lines (fun visual)
        gc.setStroke(Color.web("#ffffff", 0.5));
        gc.setLineWidth(1.0);
        double arcR = SOURCE_RADIUS + 3 + 3 * pulse;
        gc.strokeArc(cx - arcR, cy - arcR, arcR * 2, arcR * 2, -30, 60,
                javafx.scene.shape.ArcType.OPEN);
        if (pulse > 0.3) {
            double arcR2 = SOURCE_RADIUS + 8 + 3 * pulse;
            gc.strokeArc(cx - arcR2, cy - arcR2, arcR2 * 2, arcR2 * 2, -20, 40,
                    javafx.scene.shape.ArcType.OPEN);
        }

        // Draw a thin vertical "drop line" from source to floor for depth cue
        double[] floorScreen = projectToScreen(pos.x(), pos.y(), 0);
        if (Math.abs(cy - floorScreen[1]) > 2) {
            gc.setStroke(SOURCE_COLOR.deriveColor(0, 1, 1, 0.15));
            gc.setLineWidth(1.0);
            gc.setLineDashes(3, 3);
            gc.strokeLine(cx, cy, floorScreen[0], floorScreen[1]);
            gc.setLineDashes();
        }

        // Label with name
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("\uD83D\uDD0A " + name, cx, cy - SOURCE_RADIUS - LABEL_OFFSET);

        // Power dB label below the source name
        gc.setFont(Font.font("System", 8));
        gc.fillText("%.0f dB".formatted(powerDb), cx, cy - SOURCE_RADIUS - LABEL_OFFSET + 12);
    }

    // ── Microphones ────────────────────────────────────────────────

    private void drawMicrophone(GraphicsContext gc, Position3D pos, String name,
                                 MicrophonePlacement mic) {
        double[] screen = projectToScreen(pos.x(), pos.y(), pos.z());
        double cx = screen[0];
        double cy = screen[1];

        // Animated pulse
        double pulse = 0.5 + 0.5 * Math.sin(animationTime * MIC_PULSE_SPEED + 1.0);

        // Outer glow
        double glowRadius = MIC_RADIUS + 6 * pulse;
        gc.setFill(new RadialGradient(0, 0, cx, cy, glowRadius * 2, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, MIC_GLOW),
                new Stop(1.0, Color.TRANSPARENT)
        ));
        gc.fillOval(cx - glowRadius * 2, cy - glowRadius * 2, glowRadius * 4, glowRadius * 4);

        // Microphone body (rounded rectangle feel as a diamond)
        gc.setFill(MIC_COLOR);
        double r = MIC_RADIUS;
        gc.fillPolygon(
                new double[]{cx, cx + r, cx, cx - r},
                new double[]{cy - r * 1.3, cy, cy + r * 1.3, cy},
                4
        );

        // Inner shine
        gc.setFill(Color.web("#ffffff", 0.2 + 0.15 * pulse));
        gc.fillOval(cx - 2.5, cy - 2.5, 5, 5);

        // Aim direction indicator (azimuth line)
        if (mic != null) {
            double azRad = Math.toRadians(mic.azimuth());
            // Azimuth: 0 = +Y direction, 90 = +X direction (top-down view)
            double aimDx = Math.sin(azRad) * MIC_AIM_LENGTH;
            double aimDy = Math.cos(azRad) * MIC_AIM_LENGTH;
            gc.setStroke(MIC_COLOR.deriveColor(0, 1, 1, 0.5));
            gc.setLineWidth(2.0);
            gc.setLineDashes();
            gc.strokeLine(cx, cy, cx + aimDx, cy + aimDy);

            // Draw suggested aim direction if an AdjustMicAngle suggestion exists
            if (telemetryData != null) {
                for (TelemetrySuggestion suggestion : telemetryData.suggestions()) {
                    if (suggestion instanceof TelemetrySuggestion.AdjustMicAngle adjust
                            && adjust.microphoneName().equals(name)) {
                        double sugAzRad = Math.toRadians(adjust.suggestedAzimuth());
                        double sugDx = Math.sin(sugAzRad) * MIC_AIM_LENGTH;
                        double sugDy = Math.cos(sugAzRad) * MIC_AIM_LENGTH;
                        gc.setStroke(MIC_COLOR.deriveColor(0, 1, 1, 0.3));
                        gc.setLineWidth(1.5);
                        gc.setLineDashes(4, 3);
                        gc.strokeLine(cx, cy, cx + sugDx, cy + sugDy);
                        gc.setLineDashes();
                        break;
                    }
                }
            }
        }

        // Draw a thin vertical "drop line" from mic to floor for depth cue
        double[] floorScreen = projectToScreen(pos.x(), pos.y(), 0);
        if (Math.abs(cy - floorScreen[1]) > 2) {
            gc.setStroke(MIC_COLOR.deriveColor(0, 1, 1, 0.15));
            gc.setLineWidth(1.0);
            gc.setLineDashes(3, 3);
            gc.strokeLine(cx, cy, floorScreen[0], floorScreen[1]);
            gc.setLineDashes();
        }

        // Label
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("\uD83C\uDF99 " + name, cx, cy + MIC_RADIUS + LABEL_OFFSET + 4);
    }

    // ── Audience Members ───────────────────────────────────────────

    private void drawAudienceMember(GraphicsContext gc, Position3D pos, String name,
                                     int index) {
        double[] screen = projectToScreen(pos.x(), pos.y(), pos.z());
        double cx = screen[0];
        double cy = screen[1];

        // Subtle pulse for audience members (slower than performers)
        double pulse = 0.5 + 0.5 * Math.sin(animationTime * AUDIENCE_PULSE_SPEED + pos.x() * 0.5);

        // Outer glow
        double glowRadius = AUDIENCE_RADIUS + 4 * pulse;
        gc.setFill(new RadialGradient(0, 0, cx, cy, glowRadius * 2, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, AUDIENCE_GLOW),
                new Stop(1.0, Color.TRANSPARENT)
        ));
        gc.fillOval(cx - glowRadius * 2, cy - glowRadius * 2, glowRadius * 4, glowRadius * 4);

        // Person silhouette: head (circle) + shoulders (arc)
        double headR = AUDIENCE_RADIUS * 0.5;
        gc.setFill(AUDIENCE_COLOR);
        gc.fillOval(cx - headR, cy - AUDIENCE_RADIUS * 0.8 - headR, headR * 2, headR * 2);
        gc.fillArc(cx - AUDIENCE_RADIUS, cy - AUDIENCE_RADIUS * 0.2,
                AUDIENCE_RADIUS * 2, AUDIENCE_RADIUS * 1.4, 0, 180,
                javafx.scene.shape.ArcType.ROUND);

        // Label — alternate above/below silhouette for dense groups
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 9));
        gc.setTextAlign(TextAlignment.CENTER);
        double labelY = computeAudienceLabelY(cy, index);
        gc.fillText(name, cx, labelY);
    }

    // ── RT60 Glow ──────────────────────────────────────────────────

    private void drawRt60Glow(GraphicsContext gc, double roomW, double roomL, double roomH) {
        if (telemetryData == null) return;

        double rt60 = telemetryData.estimatedRt60Seconds();
        Color glowColor;
        if (rt60 < 0.3) {
            glowColor = RT60_LOW;
        } else if (rt60 < 0.8) {
            glowColor = RT60_MID;
        } else {
            glowColor = RT60_HIGH;
        }

        // Pulsing glow on all visible edges
        double pulse = 0.6 + 0.4 * Math.sin(animationTime * 1.5);
        gc.setStroke(glowColor.deriveColor(0, 1, 1, pulse));
        gc.setLineWidth(4.0);
        gc.setLineDashes();

        // Floor edges
        double[] f0 = projectToScreen(0, 0, 0);
        double[] f1 = projectToScreen(roomW, 0, 0);
        double[] f2 = projectToScreen(roomW, roomL, 0);
        double[] f3 = projectToScreen(0, roomL, 0);
        strokeScreenLine(gc, f0, f1);
        strokeScreenLine(gc, f1, f2);
        strokeScreenLine(gc, f2, f3);
        strokeScreenLine(gc, f3, f0);

        // Front vertical edges (most prominent to viewer)
        double[] c0 = projectToScreen(0, 0, roomH);
        double[] c1 = projectToScreen(roomW, 0, roomH);
        strokeScreenLine(gc, f0, c0);
        strokeScreenLine(gc, f1, c1);
        strokeScreenLine(gc, c0, c1);

        // RT60 label near the front-top edge
        gc.setFill(glowColor);
        gc.setFont(Font.font("System", 11));
        gc.setTextAlign(TextAlignment.CENTER);
        double labelX = (c0[0] + c1[0]) / 2;
        double labelY = Math.min(c0[1], c1[1]) - 10;
        gc.fillText("RT60: %.2fs".formatted(rt60), labelX, labelY);
    }

    // ── Suggestions Panel (screen-space overlay) ───────────────────

    private void drawSuggestions(GraphicsContext gc, double canvasW, double canvasH) {
        if (telemetryData == null || telemetryData.suggestions().isEmpty()) return;

        List<TelemetrySuggestion> suggestions = telemetryData.suggestions();
        double panelX = 10;
        double panelY = canvasH - (suggestions.size() * 22 + 30);
        double panelW = Math.min(canvasW - 20, 420);

        // Panel background
        gc.setFill(SUGGESTION_BG);
        gc.fillRect(panelX, panelY, panelW, suggestions.size() * 22 + 26);
        gc.setStroke(SUGGESTION_BORDER);
        gc.setLineWidth(1);
        gc.strokeRect(panelX, panelY, panelW, suggestions.size() * 22 + 26);

        // Title
        gc.setFill(SUGGESTION_TEXT);
        gc.setFont(Font.font("System", 11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("\uD83D\uDCA1 Suggestions", panelX + 8, panelY + 16);

        // Each suggestion
        gc.setFont(Font.font("System", 9));
        gc.setFill(TEXT_COLOR);
        for (int i = 0; i < suggestions.size(); i++) {
            String icon = switch (suggestions.get(i)) {
                case TelemetrySuggestion.AdjustMicPosition _ -> "\uD83D\uDCCD";
                case TelemetrySuggestion.AdjustMicAngle _ -> "\uD83D\uDD04";
                case TelemetrySuggestion.AddDampening _ -> "\uD83E\uDDF1";
                case TelemetrySuggestion.RemoveDampening _ -> "\uD83E\uDE9F";
            };
            String text = suggestions.get(i).description();
            if (text.length() > 60) text = text.substring(0, 57) + "...";
            gc.fillText(icon + " " + text, panelX + 10, panelY + 34 + i * 22);
        }
    }

    // ── Dimension Labels (3D) ──────────────────────────────────────

    private void drawDimensionLabels(GraphicsContext gc, double roomW, double roomL,
                                      double roomH) {
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 10));
        gc.setTextAlign(TextAlignment.CENTER);

        // Width label along front floor edge (y=0, from x=0 to x=W)
        double[] wMid = projectToScreen(roomW / 2, 0, 0);
        gc.fillText("%.1f m".formatted(roomW), wMid[0], wMid[1] + 16);

        // Length label along left floor edge (x=0, from y=0 to y=L)
        double[] lMid = projectToScreen(0, roomL / 2, 0);
        gc.fillText("%.1f m".formatted(roomL), lMid[0] - 16, lMid[1]);

        // Height label along front-left vertical edge (x=0, y=0, from z=0 to z=H)
        double[] hMid = projectToScreen(0, 0, roomH / 2);
        gc.fillText("%.1f m".formatted(roomH), hMid[0] - 16, hMid[1]);
    }

    // ── Wall Labels (3D) ───────────────────────────────────────────

    private void drawWallLabels(GraphicsContext gc, double roomW, double roomL,
                                 double roomH) {
        gc.setFill(WALL_LABEL_COLOR);
        gc.setFont(Font.font("System", 11));
        gc.setTextAlign(TextAlignment.CENTER);

        // Front wall label (y=0 face, centered at mid-width, mid-height)
        double[] frontMid = projectToScreen(roomW / 2, 0, roomH / 2);
        gc.fillText("Front", frontMid[0], frontMid[1]);

        // Back wall label (y=L face, centered)
        double[] backMid = projectToScreen(roomW / 2, roomL, roomH / 2);
        gc.fillText("Back", backMid[0], backMid[1]);

        // Left wall label (x=0 face, centered)
        double[] leftMid = projectToScreen(0, roomL / 2, roomH / 2);
        gc.fillText("Left", leftMid[0] - 8, leftMid[1]);

        // Right wall label (x=W face, centered)
        double[] rightMid = projectToScreen(roomW, roomL / 2, roomH / 2);
        gc.fillText("Right", rightMid[0] + 8, rightMid[1]);
    }

    // ── Color Legend (screen-space overlay) ─────────────────────────

    private void drawLegend(GraphicsContext gc, double canvasW) {
        if (telemetryData == null) return;

        boolean hasAudience = !telemetryData.audienceMembers().isEmpty();
        int entryCount = hasAudience ? 5 : 4;
        double lineHeight = 18;
        double panelW = 140;
        double panelH = entryCount * lineHeight + 20;
        double panelX = canvasW - panelW - 10;
        double panelY = 10;

        // Background
        gc.setFill(LEGEND_BG);
        gc.fillRect(panelX, panelY, panelW, panelH);
        gc.setStroke(LEGEND_BORDER);
        gc.setLineWidth(1);
        gc.strokeRect(panelX, panelY, panelW, panelH);

        double entryX = panelX + 8;
        double entryY = panelY + 16;

        gc.setFont(Font.font("System", 9));
        gc.setTextAlign(TextAlignment.LEFT);

        // Direct path — cyan solid line
        gc.setStroke(DIRECT_PATH_COLOR);
        gc.setLineWidth(1.8);
        gc.setLineDashes();
        gc.strokeLine(entryX, entryY - 3, entryX + 16, entryY - 3);
        gc.setFill(TEXT_COLOR);
        gc.fillText("Direct path", entryX + 22, entryY);
        entryY += lineHeight;

        // Reflected path — orange dashed line
        gc.setStroke(REFLECTED_PATH_COLOR);
        gc.setLineWidth(1.0);
        gc.setLineDashes(6, 4);
        gc.strokeLine(entryX, entryY - 3, entryX + 16, entryY - 3);
        gc.setLineDashes();
        gc.setFill(TEXT_COLOR);
        gc.fillText("Reflected path", entryX + 22, entryY);
        entryY += lineHeight;

        // Sound source — pink circle
        gc.setFill(SOURCE_COLOR);
        gc.fillOval(entryX + 4, entryY - 8, 8, 8);
        gc.setFill(TEXT_COLOR);
        gc.fillText("Sound source", entryX + 22, entryY);
        entryY += lineHeight;

        // Microphone — green diamond
        gc.setFill(MIC_COLOR);
        double dx = entryX + 8;
        double dy = entryY - 4;
        gc.fillPolygon(
                new double[]{dx, dx + 4, dx, dx - 4},
                new double[]{dy - 5, dy, dy + 5, dy},
                4
        );
        gc.setFill(TEXT_COLOR);
        gc.fillText("Microphone", entryX + 22, entryY);
        entryY += lineHeight;

        // Audience — purple silhouette (only when present)
        if (hasAudience) {
            gc.setFill(AUDIENCE_COLOR);
            double ax = entryX + 8;
            double ay = entryY - 4;
            gc.fillOval(ax - 3, ay - 6, 6, 6);
            gc.fillArc(ax - 5, ay - 1, 10, 7, 0, 180, javafx.scene.shape.ArcType.ROUND);
            gc.setFill(TEXT_COLOR);
            gc.fillText("Audience", entryX + 22, entryY);
        }
    }

    // ── Room Statistics Panel (screen-space overlay) ────────────────

    private void drawRoomStats(GraphicsContext gc) {
        if (telemetryData == null) return;

        RoomDimensions dims = telemetryData.roomDimensions();
        double volume = dims.volume();
        double surfaceArea = dims.surfaceArea();
        double rt60 = telemetryData.estimatedRt60Seconds();

        // Critical distance: Dc = 0.057 * sqrt(V / RT60)
        double criticalDistance = (rt60 > 0) ? 0.057 * Math.sqrt(volume / rt60) : 0;

        WallMaterial material = telemetryData.wallMaterial();
        int sourceCount = telemetryData.soundSources().size();
        int micCount = telemetryData.microphones().size();

        List<String> lines = new ArrayList<>();
        lines.add("Volume: %.1f m\u00B3".formatted(volume));
        lines.add("Surface: %.1f m\u00B2".formatted(surfaceArea));
        if (material != null) {
            lines.add("Material: %s".formatted(formatMaterialName(material)));
        }
        lines.add("Critical dist: %.2f m".formatted(criticalDistance));
        lines.add("Sources: %d  Mics: %d".formatted(sourceCount, micCount));

        double lineHeight = 16;
        double panelW = 170;
        double panelH = lines.size() * lineHeight + 14;
        double panelX = 10;
        double panelY = 10;

        // Background
        gc.setFill(STATS_BG);
        gc.fillRect(panelX, panelY, panelW, panelH);
        gc.setStroke(STATS_BORDER);
        gc.setLineWidth(1);
        gc.strokeRect(panelX, panelY, panelW, panelH);

        // Text
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 9));
        gc.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < lines.size(); i++) {
            gc.fillText(lines.get(i), panelX + 8, panelY + 14 + i * lineHeight);
        }
    }

    // ── Scale Bar (projected on floor) ─────────────────────────────

    private void drawScaleBar(GraphicsContext gc, double roomW) {
        // Draw scale bar along the right floor edge (y=0 edge, near x=W)
        double targetPixels = 100;
        double targetMeters = targetPixels / cachedScale;

        // Round to a nice value
        double scaleMeters;
        if (targetMeters < 0.75) scaleMeters = 0.5;
        else if (targetMeters < 1.5) scaleMeters = 1;
        else if (targetMeters < 3) scaleMeters = 2;
        else if (targetMeters < 7) scaleMeters = 5;
        else if (targetMeters < 15) scaleMeters = 10;
        else scaleMeters = 20;

        // Position scale bar along the front floor edge near the right corner
        double barEndX = roomW;
        double barStartX = Math.max(0, roomW - scaleMeters);
        double barY = 0; // front edge

        double[] start = projectToScreen(barStartX, barY, 0);
        double[] end = projectToScreen(barEndX, barY, 0);

        gc.setStroke(TEXT_COLOR);
        gc.setLineWidth(1.5);
        gc.setLineDashes();

        // Main bar line
        gc.strokeLine(start[0], start[1], end[0], end[1]);

        // Perpendicular ticks (perpendicular to the bar direction on screen)
        double barDx = end[0] - start[0];
        double barDy = end[1] - start[1];
        double barLen = Math.hypot(barDx, barDy);
        double tickH = 6;
        if (barLen > 0) {
            double perpX = -barDy / barLen * tickH;
            double perpY = barDx / barLen * tickH;
            gc.strokeLine(start[0] + perpX, start[1] + perpY, start[0] - perpX, start[1] - perpY);
            gc.strokeLine(end[0] + perpX, end[1] + perpY, end[0] - perpX, end[1] - perpY);
        }

        // Label
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 9));
        gc.setTextAlign(TextAlignment.CENTER);
        String label = (scaleMeters == (int) scaleMeters)
                ? "%d m".formatted((int) scaleMeters)
                : "%.1f m".formatted(scaleMeters);
        double midX = (start[0] + end[0]) / 2;
        double midY = (start[1] + end[1]) / 2;
        gc.fillText(label, midX, midY + 16);
    }

    // ── Critical Distance Circles (projected on source Z plane) ────

    private void drawCriticalDistance(GraphicsContext gc) {
        if (telemetryData == null) return;

        double volume = telemetryData.roomDimensions().volume();
        double rt60 = telemetryData.estimatedRt60Seconds();
        if (rt60 <= 0) return;

        double criticalDistMeters = 0.057 * Math.sqrt(volume / rt60);

        gc.setStroke(CRITICAL_DISTANCE_COLOR);
        gc.setLineWidth(1.0);
        gc.setLineDashes(6, 4);

        HashSet<String> drawnSources = new HashSet<>();
        for (SoundWavePath path : telemetryData.wavePaths()) {
            if (drawnSources.add(path.sourceName())) {
                Position3D sp = path.waypoints().getFirst();
                strokeProjectedCircle(gc, sp.x(), sp.y(), sp.z(), criticalDistMeters);

                // "Dc" label at the edge
                double[] labelPos = projectToScreen(sp.x() + criticalDistMeters, sp.y(), sp.z());
                gc.setFill(TEXT_COLOR.deriveColor(0, 1, 1, 0.5));
                gc.setFont(Font.font("System", 8));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.fillText("Dc", labelPos[0] + 2, labelPos[1] - 2);
            }
        }
        gc.setLineDashes();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Draws a line between two screen-space points.
     */
    private static void strokeScreenLine(GraphicsContext gc, double[] a, double[] b) {
        gc.strokeLine(a[0], a[1], b[0], b[1]);
    }

    /**
     * Draws a circle in 3D room space as a projected polygon on the canvas.
     * The circle lies on a horizontal plane at the given Z height.
     */
    private void strokeProjectedCircle(GraphicsContext gc,
                                        double cx, double cy, double cz,
                                        double radiusM) {
        double[] xs = new double[PROJECTED_CIRCLE_SEGMENTS];
        double[] ys = new double[PROJECTED_CIRCLE_SEGMENTS];
        double[] tmp = new double[2];
        for (int i = 0; i < PROJECTED_CIRCLE_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / PROJECTED_CIRCLE_SEGMENTS;
            double px = cx + radiusM * Math.cos(angle);
            double py = cy + radiusM * Math.sin(angle);
            projectInto(px, py, cz, tmp);
            xs[i] = tmp[0];
            ys[i] = tmp[1];
        }
        gc.strokePolygon(xs, ys, PROJECTED_CIRCLE_SEGMENTS);
    }

    private SoundSource findSourceByName(String name) {
        if (telemetryData == null) return null;
        for (SoundSource source : telemetryData.soundSources()) {
            if (source.name().equals(name)) return source;
        }
        return null;
    }

    private MicrophonePlacement findMicByName(String name) {
        if (telemetryData == null) return null;
        for (MicrophonePlacement mic : telemetryData.microphones()) {
            if (mic.name().equals(name)) return mic;
        }
        return null;
    }

    private static String formatMaterialName(WallMaterial material) {
        String raw = material.name();
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static String pathKey(SoundWavePath path) {
        return path.sourceName() + "\u2192" + path.microphoneName() + ":" + path.reflected();
    }

    /**
     * Computes the Y-coordinate for an audience member label.
     *
     * <p>Even-indexed members place the label below the silhouette;
     * odd-indexed members place it above. This staggers labels when
     * many audience members share similar positions, reducing overlap.</p>
     *
     * @param cy    the canvas Y center of the audience member silhouette
     * @param index the zero-based index of the audience member in the list
     * @return the Y-coordinate for the label text baseline
     */
    static double computeAudienceLabelY(double cy, int index) {
        if (index % 2 == 0) {
            return cy + AUDIENCE_RADIUS + LABEL_OFFSET;
        }
        return cy - AUDIENCE_RADIUS - AUDIENCE_LABEL_STAGGER;
    }

    /**
     * Interpolates a position along a multi-segment path using 3D projection.
     *
     * @param waypoints the path waypoints in room coordinates
     * @param t         progress along the path in [0.0, 1.0]
     * @return {screenX, screenY}
     */
    private double[] interpolateAlongPath(List<Position3D> waypoints, double t) {
        if (waypoints.size() < 2) {
            Position3D p = waypoints.getFirst();
            return projectToScreen(p.x(), p.y(), p.z());
        }

        // Compute total length and segment lengths
        double totalLen = 0;
        double[] segLens = new double[waypoints.size() - 1];
        for (int i = 0; i < segLens.length; i++) {
            segLens[i] = waypoints.get(i).distanceTo(waypoints.get(i + 1));
            totalLen += segLens[i];
        }

        double target = t * totalLen;
        double accumulated = 0;
        for (int i = 0; i < segLens.length; i++) {
            if (accumulated + segLens[i] >= target || i == segLens.length - 1) {
                double segT = (segLens[i] > 0) ? (target - accumulated) / segLens[i] : 0;
                segT = Math.max(0, Math.min(1, segT));
                Position3D a = waypoints.get(i);
                Position3D b = waypoints.get(i + 1);
                double rx = a.x() + segT * (b.x() - a.x());
                double ry = a.y() + segT * (b.y() - a.y());
                double rz = a.z() + segT * (b.z() - a.z());
                return projectToScreen(rx, ry, rz);
            }
            accumulated += segLens[i];
        }

        Position3D last = waypoints.getLast();
        return projectToScreen(last.x(), last.y(), last.z());
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }

    // ── Ceiling shape overlay (iso-contour + side silhouette) ──────────
    //
    // The contour grid and silhouette only depend on the room dimensions and
    // ceiling shape, which are static between telemetry updates. We cache
    // the overlay into a WritableImage and blit it each frame, regenerating
    // only when the room dimensions change.

    private void drawCeilingOverlay(GraphicsContext gc, double w, double h,
                                    double roomW, double roomL) {
        if (telemetryData == null) return;
        RoomDimensions dims = telemetryData.roomDimensions();
        CeilingShape ceiling = dims.ceiling();
        if (ceiling instanceof CeilingShape.Flat) {
            return; // nothing interesting to show
        }

        double panelSize = 140;
        double margin = 12;
        double x0 = w - panelSize - margin;
        double y0 = margin;

        // Regenerate the cached overlay only when needed.
        if (cachedCeilingOverlay == null || !dims.equals(cachedCeilingDims)) {
            cachedCeilingOverlay = renderCeilingOverlayImage(ceiling, roomW, roomL, panelSize);
            cachedCeilingDims = dims;
        }

        gc.drawImage(cachedCeilingOverlay, x0, y0);
    }

    private javafx.scene.image.WritableImage renderCeilingOverlayImage(
            CeilingShape ceiling, double roomW, double roomL, double panelSize) {

        int size = (int) Math.ceil(panelSize);
        Canvas offscreen = new Canvas(size, size);
        GraphicsContext gc = offscreen.getGraphicsContext2D();

        // Background card
        gc.setFill(Color.rgb(10, 15, 25, 0.72));
        gc.fillRect(0, 0, panelSize, panelSize);
        gc.setStroke(Color.rgb(90, 110, 160, 0.8));
        gc.setLineWidth(1.0);
        gc.strokeRect(0, 0, panelSize, panelSize);

        gc.setFill(Color.rgb(200, 210, 230));
        gc.setFont(Font.font("System", 11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("Ceiling: " + ceiling.kind(), 6, 14);

        // Iso-contour heatmap (top half)
        double contourTop = 20;
        double contourHeight = (panelSize - 22) * 0.55;
        double contourWidth = panelSize - 12;
        int cells = 20;
        double minZ = ceiling.minHeight();
        double maxZ = ceiling.maxHeight();
        double range = Math.max(1e-9, maxZ - minZ);
        for (int iy = 0; iy < cells; iy++) {
            for (int ix = 0; ix < cells; ix++) {
                double rx = roomW * (ix + 0.5) / cells;
                double ry = roomL * (iy + 0.5) / cells;
                double z = ceiling.heightAt(rx, ry, roomW, roomL);
                double t = (z - minZ) / range;
                Color col = Color.color(0.2 + 0.7 * t, 0.3 + 0.6 * t, 0.6 - 0.2 * t);
                gc.setFill(col);
                double cx = 6 + contourWidth * ix / cells;
                double cy = contourTop + contourHeight * iy / cells;
                gc.fillRect(cx, cy, contourWidth / cells + 0.5, contourHeight / cells + 0.5);
            }
        }

        // Side-view silhouette (bottom half): X-axis cross-section at y = length/2
        double sideTop = contourTop + contourHeight + 4;
        double sideHeight = (panelSize - 22) * 0.35;
        gc.setStroke(Color.rgb(160, 200, 255));
        gc.setLineWidth(1.2);
        int samples = 40;
        double prevPx = Double.NaN, prevPy = Double.NaN;
        for (int i = 0; i <= samples; i++) {
            double rx = roomW * i / samples;
            double z = ceiling.heightAt(rx, roomL / 2.0, roomW, roomL);
            double px = 6 + contourWidth * i / samples;
            double py = sideTop + sideHeight - sideHeight * (z / maxZ);
            if (!Double.isNaN(prevPx)) {
                gc.strokeLine(prevPx, prevPy, px, py);
            }
            prevPx = px;
            prevPy = py;
        }
        // Floor line
        gc.setStroke(Color.rgb(90, 110, 160, 0.6));
        gc.strokeLine(6, sideTop + sideHeight, 6 + contourWidth, sideTop + sideHeight);

        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return offscreen.snapshot(params, null);
    }

    // ── Treatment overlay drawing ──────────────────────────────────

    private void drawTreatmentOverlays(
            GraphicsContext gc, double roomW, double roomL, double roomH) {
        if (treatmentOverlays.isEmpty()) return;

        gc.save();
        for (AcousticTreatment treatment : treatmentOverlays) {
            Position3D p = treatmentAnchor3D(treatment, roomW, roomL, roomH);
            if (p == null) continue;
            double[] scr = projectToScreen(p.x(), p.y(), p.z());
            Color color = treatmentColor(treatment.kind());
            String glyph = treatmentGlyph(treatment.kind());

            gc.setFill(Color.color(0, 0, 0, 0.55));
            gc.fillOval(scr[0] - 9, scr[1] - 9, 18, 18);
            gc.setFill(color);
            gc.fillOval(scr[0] - 7, scr[1] - 7, 14, 14);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1.4);
            gc.strokeOval(scr[0] - 7, scr[1] - 7, 14, 14);

            gc.setFill(Color.WHITE);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setFont(Font.font("SansSerif", 10));
            gc.fillText(glyph, scr[0], scr[1] + 3.5);
        }
        gc.restore();
    }

    private static Position3D treatmentAnchor3D(
            AcousticTreatment t, double roomW, double roomL, double roomH) {
        return switch (t.location()) {
            case com.benesquivelmusic.daw.sdk.telemetry.WallAttachment.OnSurface on ->
                    switch (on.surface()) {
                        case LEFT_WALL   -> new Position3D(0, clamp(on.u(), 0, roomL),
                                clamp(on.v(), 0, roomH));
                        case RIGHT_WALL  -> new Position3D(roomW, clamp(on.u(), 0, roomL),
                                clamp(on.v(), 0, roomH));
                        case FRONT_WALL  -> new Position3D(clamp(on.u(), 0, roomW), 0,
                                clamp(on.v(), 0, roomH));
                        case BACK_WALL   -> new Position3D(clamp(on.u(), 0, roomW), roomL,
                                clamp(on.v(), 0, roomH));
                        case FLOOR       -> new Position3D(clamp(on.u(), 0, roomW),
                                clamp(on.v(), 0, roomL), 0);
                        case CEILING     -> new Position3D(clamp(on.u(), 0, roomW),
                                clamp(on.v(), 0, roomL), roomH);
                    };
            case com.benesquivelmusic.daw.sdk.telemetry.WallAttachment.InCorner in ->
                    cornerAnchor(in, roomW, roomL, in.z());
        };
    }

    private static Position3D cornerAnchor(
            com.benesquivelmusic.daw.sdk.telemetry.WallAttachment.InCorner in,
            double roomW, double roomL, double z) {
        double x = isSurface(in, RoomSurface.RIGHT_WALL) ? roomW
                : isSurface(in, RoomSurface.LEFT_WALL) ? 0.0
                : roomW / 2.0;
        double y = isSurface(in, RoomSurface.BACK_WALL) ? roomL
                : isSurface(in, RoomSurface.FRONT_WALL) ? 0.0
                : roomL / 2.0;
        return new Position3D(x, y, z);
    }

    private static boolean isSurface(
            com.benesquivelmusic.daw.sdk.telemetry.WallAttachment.InCorner in,
            RoomSurface s) {
        return in.surfaceA() == s || in.surfaceB() == s;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static Color treatmentColor(
            com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind kind) {
        return switch (kind) {
            case ABSORBER_BROADBAND -> Color.rgb(60, 150, 230);
            case ABSORBER_LF_TRAP   -> Color.rgb(220, 80, 90);
            case DIFFUSER_SKYLINE   -> Color.rgb(140, 200, 120);
            case DIFFUSER_QUADRATIC -> Color.rgb(240, 190, 70);
        };
    }

    private static String treatmentGlyph(
            com.benesquivelmusic.daw.sdk.telemetry.TreatmentKind kind) {
        return switch (kind) {
            case ABSORBER_BROADBAND -> "A";
            case ABSORBER_LF_TRAP   -> "L";
            case DIFFUSER_SKYLINE   -> "S";
            case DIFFUSER_QUADRATIC -> "Q";
        };
    }

    // ── Inner data carrier ─────────────────────────────────────────

    private record Ripple(double roomX, double roomY, double roomZ, double age) {}
}
