package com.benesquivelmusic.daw.app.screenshot;

import com.benesquivelmusic.daw.core.telemetry.RoomConfiguration;
import com.benesquivelmusic.daw.core.telemetry.SoundWaveTelemetryEngine;
import com.benesquivelmusic.daw.sdk.telemetry.AudienceMember;
import com.benesquivelmusic.daw.sdk.telemetry.MicrophonePlacement;
import com.benesquivelmusic.daw.sdk.telemetry.Position3D;
import com.benesquivelmusic.daw.sdk.telemetry.RoomDimensions;
import com.benesquivelmusic.daw.sdk.telemetry.RoomTelemetryData;
import com.benesquivelmusic.daw.sdk.telemetry.SoundSource;
import com.benesquivelmusic.daw.sdk.telemetry.SoundWavePath;
import com.benesquivelmusic.daw.sdk.telemetry.TelemetrySuggestion;
import com.benesquivelmusic.daw.sdk.telemetry.WallMaterial;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Standalone screenshot generator for the Sound Wave Telemetry view.
 *
 * <p>Renders the full telemetry visualization to a high-definition 1920×1080 PNG using
 * Java2D, faithfully replicating the color palette and layout of
 * {@link com.benesquivelmusic.daw.app.ui.display.RoomTelemetryDisplay}.
 *
 * <p>The generated image showcases every telemetry capability:
 * <ul>
 *   <li>Pulsing, glowing sound sources with animated ripple rings</li>
 *   <li>Color-coded wave paths — cyan for direct, orange for reflected</li>
 *   <li>Animated energy particles along each path</li>
 *   <li>Microphone icons with glow effects</li>
 *   <li>Audience member silhouettes</li>
 *   <li>RT60 ambient glow on the room border</li>
 *   <li>Suggestion panel with actionable badges</li>
 *   <li>Distance and delay labels on direct paths</li>
 *   <li>Attenuation labels at reflection bounce points</li>
 * </ul>
 *
 * <p>Run this class's {@code main} method to generate
 * {@code docs/screenshots/sound-wave-telemetry-hd.png}.
 */
public final class TelemetryScreenshotGenerator {

    // ── Output dimensions ────────────────────────────────────────────
    private static final int WIDTH  = 1920;
    private static final int HEIGHT = 1080;

    // ── Color palette (exact match to RoomTelemetryDisplay) ──────────
    private static final Color BACKGROUND           = new Color(10, 10, 30);
    private static final Color ROOM_FILL            = new Color(17, 17, 42);
    private static final Color ROOM_BORDER          = new Color(42, 42, 90);
    private static final Color GRID_COLOR           = new Color(255, 255, 255, 10);
    private static final Color DIRECT_PATH_COLOR    = new Color(0, 229, 255);
    private static final Color REFLECTED_PATH_COLOR = new Color(255, 145, 0);
    private static final Color SOURCE_COLOR         = new Color(255, 64, 129);
    private static final Color SOURCE_GLOW          = new Color(255, 64, 129, 64);
    private static final Color MIC_COLOR            = new Color(105, 240, 174);
    private static final Color MIC_GLOW             = new Color(105, 240, 174, 77);
    private static final Color PARTICLE_DIRECT      = new Color(0, 229, 255);
    private static final Color PARTICLE_REFLECTED   = new Color(255, 171, 64);
    private static final Color RIPPLE_COLOR         = new Color(255, 64, 129, 89);
    private static final Color SUGGESTION_BG        = new Color(255, 234, 0, 38);
    private static final Color SUGGESTION_BORDER    = new Color(255, 234, 0, 153);
    private static final Color SUGGESTION_TEXT      = new Color(255, 234, 0);
    private static final Color TEXT_COLOR           = new Color(255, 255, 255, 179);
    private static final Color RT60_LOW             = new Color(0, 230, 118, 77);
    private static final Color RT60_MID             = new Color(255, 234, 0, 77);
    private static final Color RT60_HIGH            = new Color(255, 23, 68, 102);
    private static final Color AUDIENCE_COLOR       = new Color(179, 136, 255);
    private static final Color AUDIENCE_GLOW        = new Color(179, 136, 255, 51);

    // ── Layout constants (matching RoomTelemetryDisplay) ─────────────
    private static final double ROOM_MARGIN          = 50.0;
    private static final double LABEL_OFFSET         = 18.0;
    private static final double SOURCE_RADIUS        = 10.0;
    private static final double MIC_RADIUS           = 8.0;
    private static final double AUDIENCE_RADIUS      = 7.0;
    private static final double AUDIENCE_LABEL_STAGGER = 12.0;

    /** Fixed animation time used to produce a visually rich mid-pulse state. */
    private static final double ANIM_TIME = 1.2;

    private TelemetryScreenshotGenerator() {}

    /**
     * Entry point — builds telemetry data, renders the visualization, and writes the PNG.
     *
     * @param args ignored
     * @throws IOException if the output file cannot be written
     */
    public static void main(String[] args) throws IOException {
        RoomConfiguration config = buildRichRoomConfig();
        RoomTelemetryData data = SoundWaveTelemetryEngine.compute(config);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        applyRenderingHints(g2);

        render(g2, WIDTH, HEIGHT, data);
        drawHeaderBar(g2, WIDTH);

        g2.dispose();

        File outDir = new File("docs/screenshots");
        outDir.mkdirs();
        File outFile = new File(outDir, "sound-wave-telemetry-hd.png");
        ImageIO.write(image, "PNG", outFile);
        System.out.println("Screenshot saved: " + outFile.getCanonicalPath());
    }

    // ── Room configuration ────────────────────────────────────────────

    /**
     * Builds a rich recording-studio configuration that exercises every
     * rendering feature: multiple sources, microphones, audience members,
     * wall-proximity suggestions, and a high RT60.
     */
    private static RoomConfiguration buildRichRoomConfig() {
        // 12 m × 9 m × 3.5 m studio with hardwood surfaces → RT60 ≈ 1.7 s (red glow)
        RoomDimensions dims = new RoomDimensions(12.0, 9.0, 3.5);
        RoomConfiguration config = new RoomConfiguration(dims, WallMaterial.WOOD);

        // Three sound sources spread across the room
        config.addSoundSource(new SoundSource("Guitar Amp",
                new Position3D(2.5, 2.5, 1.2), 85.0));
        config.addSoundSource(new SoundSource("Drum Kit",
                new Position3D(9.5, 2.5, 1.5), 100.0));
        config.addSoundSource(new SoundSource("Vocal Booth",
                new Position3D(6.0, 4.5, 1.7), 75.0));

        // Three microphones: two overhead and one room mic close to the wall
        // (the wall-proximity on "Room Mic" generates a position suggestion)
        config.addMicrophone(new MicrophonePlacement(
                "Overhead L", new Position3D(3.0, 6.0, 2.5), 195.0, 30.0));
        config.addMicrophone(new MicrophonePlacement(
                "Overhead R", new Position3D(9.0, 6.0, 2.5), 200.0, 30.0));
        config.addMicrophone(new MicrophonePlacement(
                "Room Mic",   new Position3D(0.15, 7.0, 1.5), 90.0, 0.0));

        // Two audience members at the back of the room
        config.addAudienceMember(new AudienceMember(
                "Listener A", new Position3D(4.0, 8.0, 1.0)));
        config.addAudienceMember(new AudienceMember(
                "Listener B", new Position3D(8.0, 8.0, 1.0)));

        return config;
    }

    // ── Rendering ─────────────────────────────────────────────────────

    private static void applyRenderingHints(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,     RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,    RenderingHints.VALUE_STROKE_PURE);
    }

    private static void render(Graphics2D g2, int w, int h, RoomTelemetryData data) {
        // Background
        g2.setColor(BACKGROUND);
        g2.fillRect(0, 0, w, h);

        double roomW = data.roomDimensions().width();
        double roomL = data.roomDimensions().length();

        double availW = w - 2 * ROOM_MARGIN;
        double availH = h - 2 * ROOM_MARGIN - 40; // headroom for header
        double scale  = Math.min(availW / roomW, availH / roomL);
        double offsetX = (w - roomW * scale) / 2;
        double offsetY = (h - roomL * scale) / 2 + 20; // shifted down for header

        // Room fill and border
        drawRoom(g2, offsetX, offsetY, roomW * scale, roomL * scale);

        // 1-metre grid
        drawGrid(g2, offsetX, offsetY, roomW, roomL, scale);

        // Sonar ripples emanating from each source at three age snapshots
        drawSimulatedRipples(g2, data, offsetX, offsetY, scale);

        // Wave paths (direct + reflected) with particles
        for (SoundWavePath path : data.wavePaths()) {
            drawWavePath(g2, path, offsetX, offsetY, scale);
        }

        // Sound sources
        Set<String> drawnSources = new HashSet<>();
        for (SoundWavePath path : data.wavePaths()) {
            if (drawnSources.add(path.sourceName())) {
                Position3D sp = path.waypoints().getFirst();
                drawSource(g2, sp, path.sourceName(), offsetX, offsetY, scale);
            }
        }

        // Microphones
        Set<String> drawnMics = new HashSet<>();
        for (SoundWavePath path : data.wavePaths()) {
            if (drawnMics.add(path.microphoneName())) {
                Position3D mp = path.waypoints().getLast();
                drawMicrophone(g2, mp, path.microphoneName(), offsetX, offsetY, scale);
            }
        }

        // Audience members
        List<AudienceMember> audience = data.audienceMembers();
        for (int i = 0; i < audience.size(); i++) {
            AudienceMember member = audience.get(i);
            drawAudienceMember(g2, member.position(), member.name(), offsetX, offsetY, scale, i);
        }

        // RT60 pulsing border glow
        drawRt60Glow(g2, offsetX, offsetY, roomW * scale, roomL * scale, data.estimatedRt60Seconds());

        // Suggestions panel (bottom-left)
        drawSuggestions(g2, w, h, data.suggestions());
    }

    private static void drawRoom(Graphics2D g2, double x, double y, double w, double h) {
        g2.setColor(ROOM_FILL);
        g2.fill(new Rectangle2D.Double(x, y, w, h));
        g2.setColor(ROOM_BORDER);
        g2.setStroke(new BasicStroke(2.0f));
        g2.draw(new Rectangle2D.Double(x, y, w, h));
    }

    private static void drawGrid(Graphics2D g2, double ox, double oy,
                                  double roomW, double roomL, double scale) {
        g2.setColor(GRID_COLOR);
        g2.setStroke(new BasicStroke(0.5f));
        for (double m = 1; m < roomW; m++) {
            double x = ox + m * scale;
            g2.draw(new Line2D.Double(x, oy, x, oy + roomL * scale));
        }
        for (double m = 1; m < roomL; m++) {
            double y = oy + m * scale;
            g2.draw(new Line2D.Double(ox, y, ox + roomW * scale, y));
        }
    }

    /**
     * Draws three simulated sonar ripple rings from each sound source, at staggered
     * ages (0.3 s, 0.9 s, 1.5 s), to show the animated ripple effect in a still image.
     */
    private static void drawSimulatedRipples(Graphics2D g2, RoomTelemetryData data,
                                              double ox, double oy, double scale) {
        Set<String> seen = new HashSet<>();
        for (SoundWavePath path : data.wavePaths()) {
            if (!seen.add(path.sourceName())) continue;
            Position3D sp = path.waypoints().getFirst();
            double cx = ox + sp.x() * scale;
            double cy = oy + sp.y() * scale;
            for (double age : new double[]{0.3, 0.9, 1.5}) {
                double rippleSpeed = 80.0;
                double rippleMaxAge = 2.0;
                double radius  = age * rippleSpeed;
                double opacity = Math.max(0, 1.0 - age / rippleMaxAge);
                Color c = withAlpha(RIPPLE_COLOR, (int)(RIPPLE_COLOR.getAlpha() * opacity * 0.7));
                g2.setColor(c);
                g2.setStroke(new BasicStroke((float)(2.0 * opacity + 0.5)));
                g2.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));

                if (age > 0.2) {
                    double ir = (age - 0.2) * rippleSpeed;
                    double iOpacity = Math.max(0, 1.0 - (age - 0.2) / rippleMaxAge) * 0.3;
                    g2.setColor(withAlpha(RIPPLE_COLOR, (int)(RIPPLE_COLOR.getAlpha() * iOpacity)));
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.draw(new Ellipse2D.Double(cx - ir, cy - ir, ir * 2, ir * 2));
                }
            }
        }
    }

    private static void drawWavePath(Graphics2D g2, SoundWavePath path,
                                      double ox, double oy, double scale) {
        List<Position3D> wp = path.waypoints();
        boolean reflected = path.reflected();
        Color pathColor = reflected ? REFLECTED_PATH_COLOR : DIRECT_PATH_COLOR;

        // Path line
        float lineW = reflected ? 1.0f : 1.8f;
        Color lineColor = withAlpha(pathColor, (int)(255 * 0.35));
        g2.setColor(lineColor);
        if (reflected) {
            g2.setStroke(new BasicStroke(lineW, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0));
        } else {
            g2.setStroke(new BasicStroke(lineW));
        }

        GeneralPath gp = new GeneralPath();
        for (int i = 0; i < wp.size(); i++) {
            double px = ox + wp.get(i).x() * scale;
            double py = oy + wp.get(i).y() * scale;
            if (i == 0) gp.moveTo(px, py);
            else        gp.lineTo(px, py);
        }
        g2.draw(gp);
        g2.setStroke(new BasicStroke(1.0f)); // reset

        // Reflection diamond + attenuation label
        if (reflected && wp.size() >= 3) {
            Position3D rp = wp.get(1);
            double rx = ox + rp.x() * scale;
            double ry = oy + rp.y() * scale;
            Color diamondColor = withAlpha(REFLECTED_PATH_COLOR, (int)(255 * 0.6));
            g2.setColor(diamondColor);
            int d = 4;
            int[] xs = {(int)rx,       (int)(rx + d), (int)rx,       (int)(rx - d)};
            int[] ys = {(int)(ry - d), (int)ry,       (int)(ry + d), (int)ry};
            g2.fillPolygon(xs, ys, 4);

            g2.setColor(withAlpha(REFLECTED_PATH_COLOR, (int)(255 * 0.7)));
            g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
            g2.drawString("%.1f dB".formatted(path.attenuationDb()), (float)(rx + d + 2), (float)(ry - 2));
        }

        // Direct path mid-point label
        if (!reflected && wp.size() >= 2) {
            double[] mid = interpolateAlongPath(wp, 0.5, ox, oy, scale);
            String label = "%.1fm  %.1fms".formatted(path.totalDistance(), path.delayMs());
            g2.setColor(withAlpha(DIRECT_PATH_COLOR, (int)(255 * 0.6)));
            g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(label, (float)(mid[0] - fm.stringWidth(label) / 2.0), (float)(mid[1] - 6));
        }

        // Traveling particles (three per path at evenly-spaced offsets)
        Color particleColor = reflected ? PARTICLE_REFLECTED : PARTICLE_DIRECT;
        double[] particleOffsets = {0.15, 0.45, 0.75};
        for (double t : particleOffsets) {
            double progress = (t + ANIM_TIME * (reflected ? 0.8 : 1.2)) % 1.0;
            double[] pos = interpolateAlongPath(wp, progress, ox, oy, scale);
            double opacity = 0.75 + 0.25 * Math.sin(ANIM_TIME * 6 + progress * Math.PI);
            double size    = 3.0 + 2.0 * Math.sin(ANIM_TIME * 6 + progress * Math.PI);

            // Glow halo
            g2.setColor(withAlpha(particleColor, (int)(255 * opacity * 0.3)));
            g2.fill(new Ellipse2D.Double(pos[0] - size * 1.5, pos[1] - size * 1.5, size * 3, size * 3));

            // Core dot
            g2.setColor(withAlpha(particleColor, (int)(255 * opacity)));
            g2.fill(new Ellipse2D.Double(pos[0] - size / 2, pos[1] - size / 2, size, size));
        }
    }

    private static void drawSource(Graphics2D g2, Position3D pos, String name,
                                    double ox, double oy, double scale) {
        double cx = ox + pos.x() * scale;
        double cy = oy + pos.y() * scale;

        double pulse      = 0.5 + 0.5 * Math.sin(ANIM_TIME * 3.0);
        double glowRadius = SOURCE_RADIUS + 8 * pulse;

        // Radial glow halo
        float[] dist   = {0f, 1f};
        Color[] colors = {SOURCE_GLOW, new Color(SOURCE_GLOW.getRed(), SOURCE_GLOW.getGreen(), SOURCE_GLOW.getBlue(), 0)};
        g2.setPaint(new RadialGradientPaint(
                (float)cx, (float)cy, (float)(glowRadius * 2), dist, colors));
        g2.fill(new Ellipse2D.Double(cx - glowRadius * 2, cy - glowRadius * 2,
                glowRadius * 4, glowRadius * 4));

        // Core circle
        g2.setPaint(null);
        g2.setColor(SOURCE_COLOR);
        g2.fill(new Ellipse2D.Double(cx - SOURCE_RADIUS, cy - SOURCE_RADIUS,
                SOURCE_RADIUS * 2, SOURCE_RADIUS * 2));

        // Inner highlight ring
        int ringAlpha = (int)(255 * (0.3 + 0.2 * pulse));
        g2.setColor(new Color(255, 255, 255, ringAlpha));
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(new Ellipse2D.Double(cx - SOURCE_RADIUS * 0.5, cy - SOURCE_RADIUS * 0.5,
                SOURCE_RADIUS, SOURCE_RADIUS));

        // Speaker arc lines
        g2.setColor(new Color(255, 255, 255, 128));
        g2.setStroke(new BasicStroke(1.0f));
        double arcR = SOURCE_RADIUS + 3 + 3 * pulse;
        g2.draw(new Arc2D.Double(cx - arcR, cy - arcR, arcR * 2, arcR * 2,
                -30 - 90, 60, Arc2D.OPEN)); // AWT angles from +X axis
        if (pulse > 0.3) {
            double arcR2 = SOURCE_RADIUS + 8 + 3 * pulse;
            g2.draw(new Arc2D.Double(cx - arcR2, cy - arcR2, arcR2 * 2, arcR2 * 2,
                    -20 - 90, 40, Arc2D.OPEN));
        }

        // Label
        g2.setColor(TEXT_COLOR);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
        drawCenteredString(g2, "\uD83D\uDD0A " + name, (float)cx, (float)(cy - SOURCE_RADIUS - LABEL_OFFSET));
    }

    private static void drawMicrophone(Graphics2D g2, Position3D pos, String name,
                                        double ox, double oy, double scale) {
        double cx = ox + pos.x() * scale;
        double cy = oy + pos.y() * scale;

        double pulse      = 0.5 + 0.5 * Math.sin(ANIM_TIME * 2.5 + 1.0);
        double glowRadius = MIC_RADIUS + 6 * pulse;

        // Radial glow halo
        float[] dist   = {0f, 1f};
        Color[] colors = {MIC_GLOW, new Color(MIC_GLOW.getRed(), MIC_GLOW.getGreen(), MIC_GLOW.getBlue(), 0)};
        g2.setPaint(new RadialGradientPaint(
                (float)cx, (float)cy, (float)(glowRadius * 2), dist, colors));
        g2.fill(new Ellipse2D.Double(cx - glowRadius * 2, cy - glowRadius * 2,
                glowRadius * 4, glowRadius * 4));
        g2.setPaint(null);

        // Diamond body
        g2.setColor(MIC_COLOR);
        double r = MIC_RADIUS;
        int[] micXs = {(int)cx,            (int)(cx + r),       (int)cx,            (int)(cx - r)};
        int[] micYs = {(int)(cy - r * 1.3), (int)cy, (int)(cy + r * 1.3), (int)cy};
        g2.fillPolygon(micXs, micYs, 4);

        // Inner shine
        int shineAlpha = (int)(255 * (0.2 + 0.15 * pulse));
        g2.setColor(new Color(255, 255, 255, shineAlpha));
        g2.fill(new Ellipse2D.Double(cx - 2.5, cy - 2.5, 5, 5));

        // Label
        g2.setColor(TEXT_COLOR);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
        drawCenteredString(g2, "\uD83C\uDF99 " + name, (float)cx,
                (float)(cy + MIC_RADIUS + LABEL_OFFSET + 4));
    }

    private static void drawAudienceMember(Graphics2D g2, Position3D pos, String name,
                                            double ox, double oy, double scale, int index) {
        double cx = ox + pos.x() * scale;
        double cy = oy + pos.y() * scale;

        double pulse      = 0.5 + 0.5 * Math.sin(ANIM_TIME * 1.8 + pos.x() * 0.5);
        double glowRadius = AUDIENCE_RADIUS + 4 * pulse;

        // Glow
        float[] dist   = {0f, 1f};
        Color[] colors = {AUDIENCE_GLOW, new Color(AUDIENCE_GLOW.getRed(), AUDIENCE_GLOW.getGreen(), AUDIENCE_GLOW.getBlue(), 0)};
        g2.setPaint(new RadialGradientPaint(
                (float)cx, (float)cy, (float)(glowRadius * 2), dist, colors));
        g2.fill(new Ellipse2D.Double(cx - glowRadius * 2, cy - glowRadius * 2,
                glowRadius * 4, glowRadius * 4));
        g2.setPaint(null);

        // Head
        double headR = AUDIENCE_RADIUS * 0.5;
        g2.setColor(AUDIENCE_COLOR);
        g2.fill(new Ellipse2D.Double(cx - headR, cy - AUDIENCE_RADIUS * 0.8 - headR,
                headR * 2, headR * 2));

        // Shoulders arc (AWT arc: start from +X, counter-clockwise)
        g2.fill(new Arc2D.Double(cx - AUDIENCE_RADIUS, cy - AUDIENCE_RADIUS * 0.2,
                AUDIENCE_RADIUS * 2, AUDIENCE_RADIUS * 1.4, 0, 180, Arc2D.PIE));

        // Label
        g2.setColor(TEXT_COLOR);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        double labelY = (index % 2 == 0)
                ? cy + AUDIENCE_RADIUS + LABEL_OFFSET
                : cy - AUDIENCE_RADIUS - AUDIENCE_LABEL_STAGGER;
        drawCenteredString(g2, name, (float)cx, (float)labelY);
    }

    private static void drawRt60Glow(Graphics2D g2, double x, double y, double w, double h,
                                      double rt60) {
        Color glowColor;
        if (rt60 < 0.3) {
            glowColor = RT60_LOW;
        } else if (rt60 < 0.8) {
            glowColor = RT60_MID;
        } else {
            glowColor = RT60_HIGH;
        }

        double pulse = 0.6 + 0.4 * Math.sin(ANIM_TIME * 1.5);
        g2.setColor(withAlpha(glowColor, (int)(glowColor.getAlpha() * pulse)));
        g2.setStroke(new BasicStroke(4.0f));
        g2.draw(new Rectangle2D.Double(x - 2, y - 2, w + 4, h + 4));

        // Extra outer glow passes for neon effect
        for (int pass = 1; pass <= 3; pass++) {
            double expand = pass * 3.0;
            int alpha = (int)(glowColor.getAlpha() * pulse * 0.5 / pass);
            g2.setColor(withAlpha(glowColor, alpha));
            g2.setStroke(new BasicStroke((float)(3.0 / pass)));
            g2.draw(new Rectangle2D.Double(x - 2 - expand, y - 2 - expand,
                    w + 4 + expand * 2, h + 4 + expand * 2));
        }

        // RT60 label
        g2.setColor(withAlpha(glowColor, Math.min(255, glowColor.getAlpha() * 2)));
        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        g2.drawString("RT60: %.2fs".formatted(rt60), (float)(x + 4), (float)(y - 8));
    }

    private static void drawSuggestions(Graphics2D g2, int canvasW, int canvasH,
                                         List<TelemetrySuggestion> suggestions) {
        if (suggestions.isEmpty()) return;

        double panelX = 10;
        double panelH = suggestions.size() * 22 + 26;
        double panelY = canvasH - panelH - 10;
        double panelW = Math.min(canvasW - 20, 500);

        // Panel background
        g2.setColor(SUGGESTION_BG);
        g2.fill(new Rectangle2D.Double(panelX, panelY, panelW, panelH));
        g2.setColor(SUGGESTION_BORDER);
        g2.setStroke(new BasicStroke(1.0f));
        g2.draw(new Rectangle2D.Double(panelX, panelY, panelW, panelH));

        // Title
        g2.setColor(SUGGESTION_TEXT);
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        g2.drawString("\uD83D\uDCA1 Suggestions", (float)(panelX + 8), (float)(panelY + 16));

        // Each suggestion row
        g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g2.setColor(TEXT_COLOR);
        for (int i = 0; i < suggestions.size(); i++) {
            String icon = switch (suggestions.get(i)) {
                case TelemetrySuggestion.AdjustMicPosition _ -> "\uD83D\uDCCD";
                case TelemetrySuggestion.AdjustMicAngle    _ -> "\uD83D\uDD04";
                case TelemetrySuggestion.AddDampening       _ -> "\uD83E\uDDF1";
                case TelemetrySuggestion.RemoveDampening    _ -> "\uD83E\uDE9F";
            };
            String text = suggestions.get(i).description();
            if (text.length() > 72) text = text.substring(0, 69) + "...";
            g2.drawString(icon + " " + text,
                    (float)(panelX + 10), (float)(panelY + 34 + i * 22));
        }
    }

    /**
     * Draws the "Sound Wave Telemetry" header bar at the top of the image,
     * matching the styling in {@link com.benesquivelmusic.daw.app.ui.TelemetryView}.
     */
    private static void drawHeaderBar(Graphics2D g2, int w) {
        int barH = 36;
        g2.setColor(new Color(20, 20, 45));
        g2.fillRect(0, 0, w, barH);
        g2.setColor(new Color(58, 58, 106));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawLine(0, barH, w, barH);

        g2.setColor(new Color(224, 224, 224));
        g2.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g2.drawString("\uD83C\uDF00  Sound Wave Telemetry", 12, 23);

        // Reconfigure button mock (right side)
        int btnW = 110;
        int btnX = w - btnW - 12;
        g2.setColor(new Color(58, 58, 106));
        g2.fill(new Rectangle2D.Double(btnX, 7, btnW, 22));
        g2.setColor(new Color(90, 90, 138));
        g2.draw(new Rectangle2D.Double(btnX, 7, btnW, 22));
        g2.setColor(new Color(224, 224, 224));
        g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
        drawCenteredString(g2, "Reconfigure", (float)(btnX + btnW / 2.0), 22);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Draws a string centered horizontally on the given x coordinate.
     *
     * @param g2   the graphics context
     * @param text the text to draw
     * @param cx   center x in canvas pixels
     * @param y    baseline y in canvas pixels
     */
    private static void drawCenteredString(Graphics2D g2, String text, float cx, float y) {
        FontMetrics fm = g2.getFontMetrics();
        float x = cx - fm.stringWidth(text) / 2.0f;
        g2.drawString(text, x, y);
    }

    /**
     * Returns a copy of {@code base} with the given alpha, clamped to [0, 255].
     *
     * @param base  the source color
     * @param alpha new alpha value in [0, 255]
     * @return the color with the new alpha
     */
    static Color withAlpha(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.max(0, Math.min(255, alpha)));
    }

    /**
     * Interpolates a canvas position along a multi-segment room-coordinate path.
     *
     * @param waypoints room-coordinate waypoints
     * @param t         progress in [0, 1]
     * @param ox        canvas x offset
     * @param oy        canvas y offset
     * @param scale     room-to-canvas scale factor
     * @return {@code {canvasX, canvasY}}
     */
    private static double[] interpolateAlongPath(List<Position3D> waypoints,
                                                  double t,
                                                  double ox, double oy, double scale) {
        if (waypoints.size() < 2) {
            Position3D p = waypoints.getFirst();
            return new double[]{ox + p.x() * scale, oy + p.y() * scale};
        }

        double totalLen = 0;
        double[] segLens = new double[waypoints.size() - 1];
        for (int i = 0; i < segLens.length; i++) {
            segLens[i] = waypoints.get(i).distanceTo(waypoints.get(i + 1));
            totalLen += segLens[i];
        }

        double target      = t * totalLen;
        double accumulated = 0;
        for (int i = 0; i < segLens.length; i++) {
            if (accumulated + segLens[i] >= target || i == segLens.length - 1) {
                double segT = (segLens[i] > 0) ? (target - accumulated) / segLens[i] : 0;
                segT = Math.max(0, Math.min(1, segT));
                Position3D a = waypoints.get(i);
                Position3D b = waypoints.get(i + 1);
                double rx = a.x() + segT * (b.x() - a.x());
                double ry = a.y() + segT * (b.y() - a.y());
                return new double[]{ox + rx * scale, oy + ry * scale};
            }
            accumulated += segLens[i];
        }

        Position3D last = waypoints.getLast();
        return new double[]{ox + last.x() * scale, oy + last.y() * scale};
    }
}
