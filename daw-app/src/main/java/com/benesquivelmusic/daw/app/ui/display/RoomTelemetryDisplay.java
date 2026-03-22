package com.benesquivelmusic.daw.app.ui.display;

import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SoundWavePath;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creative, animated room sound wave telemetry visualizer.
 *
 * <p>Renders a top-down (bird's-eye) view of the recording room with:
 * <ul>
 *   <li><b>Pulsing, glowing sound sources</b> with animated expanding ripple rings</li>
 *   <li><b>Animated microphone icons</b> that flash when "hit" by a wave</li>
 *   <li><b>Color-coded wave paths</b> — cool cyan for direct, warm orange for reflected</li>
 *   <li><b>Traveling energy particles</b> flowing along each path for a fun, lively feel</li>
 *   <li><b>Expanding sonar ripples</b> emanating from each source at regular intervals</li>
 *   <li><b>RT60 ambient glow</b> — the room border pulses with reverb intensity</li>
 *   <li><b>Suggestion badges</b> rendered next to the relevant element</li>
 * </ul>
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

    // ── Animation constants ────────────────────────────────────────
    private static final double RIPPLE_INTERVAL = 0.8;
    private static final double RIPPLE_MAX_AGE = 2.0;
    private static final double RIPPLE_SPEED = 80.0;
    private static final double SOURCE_PULSE_SPEED = 3.0;
    private static final double MIC_PULSE_SPEED = 2.5;

    private static final double ROOM_MARGIN = 50.0;
    private static final double LABEL_OFFSET = 18.0;
    private static final double SOURCE_RADIUS = 10.0;
    private static final double MIC_RADIUS = 8.0;

    private final Canvas canvas;
    private RoomTelemetryData telemetryData;
    private double animationTime;

    // Per-path particle animators (keyed by "sourceName→micName:reflected")
    private final Map<String, WaveParticleAnimator> pathAnimators = new HashMap<>();

    // Sonar ripples from sources
    private final List<Ripple> ripples = new ArrayList<>();
    private double timeSinceLastRipple;

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
            var seen = new java.util.HashSet<String>();
            for (SoundWavePath path : telemetryData.wavePaths()) {
                if (seen.add(path.sourceName())) {
                    Position3D sp = path.waypoints().getFirst();
                    ripples.add(new Ripple(sp.x(), sp.y(), 0));
                }
            }
        }

        // Age ripples
        ripples.replaceAll(r -> new Ripple(r.roomX, r.roomY, r.age + deltaSeconds));
        ripples.removeIf(r -> r.age >= RIPPLE_MAX_AGE);

        render();
    }

    /** Returns the current telemetry data. */
    public RoomTelemetryData getTelemetryData() {
        return telemetryData;
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

        // Compute scale to fit the room in the canvas with margins
        double availW = w - 2 * ROOM_MARGIN;
        double availH = h - 2 * ROOM_MARGIN;
        double scale = Math.min(availW / roomW, availH / roomL);
        double offsetX = (w - roomW * scale) / 2;
        double offsetY = (h - roomL * scale) / 2;

        // ── Draw room ──
        drawRoom(gc, offsetX, offsetY, roomW * scale, roomL * scale);

        // ── Draw grid ──
        drawGrid(gc, offsetX, offsetY, roomW, roomL, scale);

        // ── Draw sonar ripples ──
        for (Ripple ripple : ripples) {
            drawRipple(gc, ripple, offsetX, offsetY, scale);
        }

        // ── Draw wave paths with particles ──
        for (SoundWavePath path : telemetryData.wavePaths()) {
            drawWavePath(gc, path, offsetX, offsetY, scale);
        }

        // ── Draw sound sources (with glow + pulse) ──
        var drawnSources = new java.util.HashSet<String>();
        for (SoundWavePath path : telemetryData.wavePaths()) {
            if (drawnSources.add(path.sourceName())) {
                Position3D sp = path.waypoints().getFirst();
                drawSource(gc, sp, path.sourceName(), offsetX, offsetY, scale);
            }
        }

        // ── Draw microphones (with glow + pulse) ──
        var drawnMics = new java.util.HashSet<String>();
        for (SoundWavePath path : telemetryData.wavePaths()) {
            if (drawnMics.add(path.microphoneName())) {
                Position3D mp = path.waypoints().getLast();
                drawMicrophone(gc, mp, path.microphoneName(), offsetX, offsetY, scale);
            }
        }

        // ── Draw RT60 glow on room border ──
        drawRt60Glow(gc, offsetX, offsetY, roomW * scale, roomL * scale);

        // ── Draw suggestions panel ──
        drawSuggestions(gc, w, h);

        gc.restore();
    }

    private void drawPlaceholder(GraphicsContext gc, double w, double h) {
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 14));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("🎙  Configure a room to see Sound Wave Telemetry  🎙", w / 2, h / 2);
    }

    private void drawRoom(GraphicsContext gc, double x, double y, double w, double h) {
        // Room fill
        gc.setFill(ROOM_FILL);
        gc.fillRect(x, y, w, h);

        // Room border with rounded feel
        gc.setStroke(ROOM_BORDER);
        gc.setLineWidth(2.0);
        gc.strokeRect(x, y, w, h);
    }

    private void drawGrid(GraphicsContext gc, double offsetX, double offsetY,
                           double roomW, double roomL, double scale) {
        gc.setStroke(GRID_COLOR);
        gc.setLineWidth(0.5);
        // 1-meter grid
        for (double m = 1; m < roomW; m += 1) {
            double x = offsetX + m * scale;
            gc.strokeLine(x, offsetY, x, offsetY + roomL * scale);
        }
        for (double m = 1; m < roomL; m += 1) {
            double y = offsetY + m * scale;
            gc.strokeLine(offsetX, y, offsetX + roomW * scale, y);
        }
    }

    private void drawRipple(GraphicsContext gc, Ripple ripple,
                             double offsetX, double offsetY, double scale) {
        double cx = offsetX + ripple.roomX * scale;
        double cy = offsetY + ripple.roomY * scale;
        double radius = ripple.age * RIPPLE_SPEED;
        double opacity = Math.max(0, 1.0 - ripple.age / RIPPLE_MAX_AGE);

        gc.setStroke(RIPPLE_COLOR.deriveColor(0, 1, 1, opacity * 0.6));
        gc.setLineWidth(2.0 * opacity + 0.5);
        gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);

        // Inner ghost ring (fun layered effect)
        if (ripple.age > 0.2) {
            double innerRadius = (ripple.age - 0.2) * RIPPLE_SPEED;
            double innerOpacity = Math.max(0, 1.0 - (ripple.age - 0.2) / RIPPLE_MAX_AGE) * 0.3;
            gc.setStroke(RIPPLE_COLOR.deriveColor(0, 1, 1, innerOpacity));
            gc.setLineWidth(1.0);
            gc.strokeOval(cx - innerRadius, cy - innerRadius, innerRadius * 2, innerRadius * 2);
        }
    }

    private void drawWavePath(GraphicsContext gc, SoundWavePath path,
                               double offsetX, double offsetY, double scale) {
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
            double px = offsetX + waypoints.get(i).x() * scale;
            double py = offsetY + waypoints.get(i).y() * scale;
            if (i == 0) gc.moveTo(px, py);
            else gc.lineTo(px, py);
        }
        gc.stroke();
        gc.setLineDashes();

        // Draw reflection point marker (small diamond)
        if (reflected && waypoints.size() >= 3) {
            Position3D rp = waypoints.get(1);
            double rx = offsetX + rp.x() * scale;
            double ry = offsetY + rp.y() * scale;
            gc.setFill(REFLECTED_PATH_COLOR.deriveColor(0, 1, 1, 0.6));
            double d = 4;
            gc.fillPolygon(
                    new double[]{rx, rx + d, rx, rx - d},
                    new double[]{ry - d, ry, ry + d, ry},
                    4
            );
        }

        // Draw traveling particles 🎵
        String key = pathKey(path);
        WaveParticleAnimator animator = pathAnimators.get(key);
        if (animator != null) {
            Color particleColor = reflected ? PARTICLE_REFLECTED : PARTICLE_DIRECT;
            for (WaveParticleAnimator.Particle particle : animator.getParticles()) {
                double[] pos = interpolateAlongPath(waypoints, particle.progress(), offsetX, offsetY, scale);
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

    private void drawSource(GraphicsContext gc, Position3D pos, String name,
                             double offsetX, double offsetY, double scale) {
        double cx = offsetX + pos.x() * scale;
        double cy = offsetY + pos.y() * scale;

        // Animated pulse
        double pulse = 0.5 + 0.5 * Math.sin(animationTime * SOURCE_PULSE_SPEED);
        double glowRadius = SOURCE_RADIUS + 8 * pulse;

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

        // Label
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("🔊 " + name, cx, cy - SOURCE_RADIUS - LABEL_OFFSET);
    }

    private void drawMicrophone(GraphicsContext gc, Position3D pos, String name,
                                 double offsetX, double offsetY, double scale) {
        double cx = offsetX + pos.x() * scale;
        double cy = offsetY + pos.y() * scale;

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

        // Label
        gc.setFill(TEXT_COLOR);
        gc.setFont(Font.font("System", 10));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("🎙 " + name, cx, cy + MIC_RADIUS + LABEL_OFFSET + 4);
    }

    private void drawRt60Glow(GraphicsContext gc, double x, double y, double w, double h) {
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

        // Pulsing border glow intensity
        double pulse = 0.6 + 0.4 * Math.sin(animationTime * 1.5);
        gc.setStroke(glowColor.deriveColor(0, 1, 1, pulse));
        gc.setLineWidth(4.0);
        gc.strokeRect(x - 2, y - 2, w + 4, h + 4);

        // RT60 label
        gc.setFill(glowColor);
        gc.setFont(Font.font("System", 11));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("RT60: %.2fs".formatted(rt60), x + 4, y - 8);
    }

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
        gc.fillText("💡 Suggestions", panelX + 8, panelY + 16);

        // Each suggestion
        gc.setFont(Font.font("System", 9));
        gc.setFill(TEXT_COLOR);
        for (int i = 0; i < suggestions.size(); i++) {
            String icon = switch (suggestions.get(i)) {
                case TelemetrySuggestion.AdjustMicPosition _ -> "📍";
                case TelemetrySuggestion.AdjustMicAngle _ -> "🔄";
                case TelemetrySuggestion.AddDampening _ -> "🧱";
                case TelemetrySuggestion.RemoveDampening _ -> "🪟";
            };
            String text = suggestions.get(i).description();
            if (text.length() > 60) text = text.substring(0, 57) + "...";
            gc.fillText(icon + " " + text, panelX + 10, panelY + 34 + i * 22);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String pathKey(SoundWavePath path) {
        return path.sourceName() + "→" + path.microphoneName() + ":" + path.reflected();
    }

    /**
     * Interpolates a position along a multi-segment path.
     *
     * @param waypoints the path waypoints in room coordinates
     * @param t         progress along the path in [0.0, 1.0]
     * @return {canvasX, canvasY}
     */
    private static double[] interpolateAlongPath(List<Position3D> waypoints, double t,
                                                  double offsetX, double offsetY, double scale) {
        if (waypoints.size() < 2) {
            Position3D p = waypoints.getFirst();
            return new double[]{offsetX + p.x() * scale, offsetY + p.y() * scale};
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
                return new double[]{offsetX + rx * scale, offsetY + ry * scale};
            }
            accumulated += segLens[i];
        }

        Position3D last = waypoints.getLast();
        return new double[]{offsetX + last.x() * scale, offsetY + last.y() * scale};
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        render();
    }

    // ── Inner data carrier ─────────────────────────────────────────

    private record Ripple(double roomX, double roomY, double age) {}
}
