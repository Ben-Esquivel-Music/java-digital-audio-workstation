package com.benesquivelmusic.daw.app.ui;

import com.benesquivelmusic.daw.core.audio.AudioClip;
import com.benesquivelmusic.daw.core.track.Track;
import com.benesquivelmusic.daw.core.track.TrackType;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.benesquivelmusic.daw.app.ui.OpenProjectFixture.onFx;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 329, decision D18 — a clip that reads from a file but has no audio
 * never draws an empty body: {@code Loading…} while the open's run owes its
 * source, {@code Offline} otherwise, each its own picture; a clip with no
 * source keeps the plain body it always had, and a clip with audio takes
 * the waveform branch whatever its owner says. A placeholder is drawn only
 * near the canvas (r1 M1: a clip at the highest zoom is millions of pixels
 * wide), on its whole pattern, so what shows is what the whole placeholder
 * would show, anchored to the clip while the arrangement scrolls.
 */
@ExtendWith(JavaFxToolkitExtension.class)
class ClipPlaceholderRenderingTest {

    private static final double HATCH_SPACING = 8.0;
    private static final int LANE_HEIGHT = 80;

    @Test
    void loadingAndOfflineAreTwoDistinctPlaceholdersAndNeitherIsThePlainBody() throws Exception {
        Track track = new Track("Vox", TrackType.AUDIO);
        track.addClip(new AudioClip("Verse", 0.0, 8.0, Path.of("library", "verse.wav").toAbsolutePath().toString()));

        int[] plain = onFx(() -> OpenProjectFixture.arrangementPixels(track, _ -> ClipAudioState.NO_SOURCE));
        int[] loading = onFx(() -> OpenProjectFixture.arrangementPixels(track, _ -> ClipAudioState.LOADING));
        int[] offline = onFx(() -> OpenProjectFixture.arrangementPixels(track, _ -> ClipAudioState.OFFLINE));

        assertThat(Arrays.equals(loading, plain)).as("Loading… is not an empty body").isFalse();
        assertThat(Arrays.equals(offline, plain)).as("Offline is not an empty body").isFalse();
        assertThat(Arrays.equals(loading, offline)).as("and the two say different things").isFalse();
        assertThat(onFx(() -> OpenProjectFixture.arrangementPixels(track, ClipAudioState::unmanaged)))
                .as("with nothing loading it, a clip that reads from a file is offline").isEqualTo(offline);
    }

    @Test
    void theHatchOfAClipFarWiderThanTheCanvasIsDrawnOnlyNearTheCanvasAndAnchoredToTheClip() {
        // Four minutes at 120 BPM, 480 beats, at the highest zoom (100 × 40 px per beat).
        double clipWidth = 480 * 4_000.0;
        double clipHeight = 76.0;
        double canvasWidth = 1_600.0;
        double clipX = -1_000_000.0;

        ClipOverlayRenderer.PatternSpan lines = ClipOverlayRenderer.hatchSpan(clipX, clipWidth, clipHeight, canvasWidth);

        assertThat(lineCount(lines, HATCH_SPACING)).as("about one line per 8 px of canvas, not per 8 px of clip")
                .isBetween(209L, 213L);
        assertThat(lines.first() + clipHeight).as("the first line reaches no further right than the canvas edge")
                .isLessThanOrEqualTo(0.0);
        assertThat(lines.end()).as("the lines reach past the right edge").isGreaterThanOrEqualTo(canvasWidth);
        assertThat(Math.floorMod((long) (lines.first() - (clipX - clipHeight)), (long) HATCH_SPACING))
                .as("each line is one of the whole hatch's: the pattern is anchored to the clip").isZero();

        ClipOverlayRenderer.PatternSpan scrolled =
                ClipOverlayRenderer.hatchSpan(clipX - 3.0, clipWidth, clipHeight, canvasWidth);
        assertThat(Math.floorMod((long) (scrolled.first() - (clipX - 3.0 - clipHeight)), (long) HATCH_SPACING))
                .as("scrolled 3 px, the lines move with the clip").isZero();

        assertThat(ClipOverlayRenderer.hatchSpan(40.0, 320.0, clipHeight, canvasWidth))
                .as("a clip on the canvas keeps its whole hatch")
                .isEqualTo(new ClipOverlayRenderer.PatternSpan(40.0 - clipHeight, 360.0));
        assertThat(ClipOverlayRenderer.hatchSpan(2_000.0, 320.0, clipHeight, canvasWidth).isEmpty())
                .as("a clip past the canvas draws none").isTrue();
    }

    @Test
    void theDashedLineOfAClipFarWiderThanTheCanvasIsDrawnOnlyNearTheCanvasOnItsOwnDashes() {
        double clipWidth = 480 * 4_000.0;
        double canvasWidth = 1_600.0;
        double clipX = -1_000_000.0;

        ClipOverlayRenderer.PatternSpan line = ClipOverlayRenderer.dashSpan(clipX, clipWidth, canvasWidth);

        assertThat(line.first()).as("it starts just left of the canvas").isBetween(-16.0, 0.0);
        assertThat(line.end()).as("and ends just right of it").isBetween(canvasWidth, canvasWidth + 8.0);
        assertThat(Math.floorMod((long) (line.first() - (clipX + 4.0)), 8L))
                .as("on a dash of the whole line, which starts 4 px inside the clip").isZero();
        assertThat(ClipOverlayRenderer.dashSpan(40.0, 320.0, canvasWidth))
                .as("a clip on the canvas keeps its whole line")
                .isEqualTo(new ClipOverlayRenderer.PatternSpan(44.0, 356.0));
    }

    @Test
    void aClipWiderThanTheCanvasPaintsTheVisiblePixelsOfItsWholePlaceholder() throws Exception {
        for (ClipAudioState state : List.of(ClipAudioState.OFFLINE, ClipAudioState.LOADING)) {
            // A 1600 px clip at x = -405 on a 480 px canvas, past both of its edges (an offset
            // that is no whole number of hatch or dash periods) …
            int[] window = onFx(() -> clipPixels(state, 480, 10.125));
            // … and the same clip whole, at x = 100 on a 2200 px canvas: window column x is column x + 505.
            int[] whole = onFx(() -> clipPixels(state, 2_200, -2.5));

            for (int y = 0; y < LANE_HEIGHT; y++) {
                for (int x = 0; x < 480; x++) {
                    int expected = whole[y * 2_200 + x + 505];
                    int actual = window[y * 480 + x];
                    if (actual != expected) {
                        throw new AssertionError(state + ": pixel (" + x + ", " + y + ") is "
                                + Integer.toHexString(actual) + ", the whole placeholder's is "
                                + Integer.toHexString(expected));
                    }
                }
            }
        }
    }

    /**
     * Decision D45 (r2 R12) — the pixel comparison above cannot tell a
     * clamped painter from one that strokes the whole clip again: the
     * visible pixels are the same. The painters' source pins that each
     * strokes only the span its pure function clamped.
     */
    @Test
    void thePlaceholderPaintersStrokeOnlyTheSpansTheirClampsReturn() throws IOException {
        Path file = SourceScanSupport.locateDawAppModule()
                .resolve("src/main/java/com/benesquivelmusic/daw/app/ui/ClipOverlayRenderer.java");
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(raw.length()).as("non-vacuity: ClipOverlayRenderer.java was read").isGreaterThan(2_000);
        String code = SourceScanSupport.stripStringLiterals(SourceScanSupport.stripComments(raw))
                .replaceAll("\\s+", "");

        assertThat(methodBody(code, "privatestaticvoiddrawOfflinePlaceholder("))
                .as("the Offline hatch is clamped by hatchSpan")
                .contains("PatternSpanlines=hatchSpan(clipX,clipWidth,clipHeight,canvasWidth);")
                .as("and strokes only its lines")
                .contains("for(doublex=lines.first();x<lines.end();x+=PLACEHOLDER_HATCH_SPACING)");
        assertThat(methodBody(code, "privatestaticvoiddrawLoadingPlaceholder("))
                .as("the Loading… line is clamped by dashSpan")
                .contains("PatternSpanline=dashSpan(clipX,clipWidth,canvasWidth);")
                .as("and strokes only its span")
                .contains("gc.strokeLine(line.first(),centreY,line.end(),centreY);");
    }

    @Test
    void aClipWithAudioTakesTheWaveformBranchWhateverItsStateSays() {
        AudioClip loaded = new AudioClip("Verse", 0.0, 8.0, Path.of("verse.wav").toAbsolutePath().toString());
        loaded.setAudioData(new float[][]{{0.5f, -0.5f}});
        AudioClip unsourced = new AudioClip("Bounce", 0.0, 8.0, null);

        assertThat(ClipOverlayRenderer.bodyFor(loaded, ClipAudioState.LOADING))
                .isEqualTo(ClipOverlayRenderer.ClipBody.WAVEFORM);
        assertThat(ClipAudioState.unmanaged(loaded)).isEqualTo(ClipAudioState.LOADED);
        assertThat(ClipAudioState.unmanaged(unsourced)).isEqualTo(ClipAudioState.NO_SOURCE);
        assertThat(ClipOverlayRenderer.bodyFor(unsourced, ClipAudioState.NO_SOURCE))
                .as("no source: the plain body, as before").isEqualTo(ClipOverlayRenderer.ClipBody.WAVEFORM);
    }

    /** The brace-balanced body of the method whose declaration starts with {@code signature}. */
    private static String methodBody(String code, String signature) {
        int declaration = code.indexOf(signature);
        assertThat(declaration).as("declares %s", signature).isNotNegative();
        int open = code.indexOf('{', declaration);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    private static long lineCount(ClipOverlayRenderer.PatternSpan span, double spacing) {
        return (long) Math.ceil((span.end() - span.first()) / spacing);
    }

    /**
     * Draws a 40-beat clip — 1600 px at 40 px per beat — filling an 80 px
     * lane, its body in {@code state}, on a canvas {@code width} wide scrolled
     * to {@code scrollXBeats}, and returns the canvas pixels. FX thread.
     */
    private static int[] clipPixels(ClipAudioState state, int width, double scrollXBeats) {
        Canvas canvas = new Canvas(width, LANE_HEIGHT);
        AudioClip clip = new AudioClip("Verse", 0.0, 40.0, Path.of("library", "verse.wav").toAbsolutePath().toString());
        ClipOverlayRenderer.drawAudioClip(canvas.getGraphicsContext2D(), clip, Color.STEELBLUE, 0.0, LANE_HEIGHT,
                40.0, scrollXBeats, width, LANE_HEIGHT, null, 0.0, 0, state);
        WritableImage image = canvas.snapshot(new SnapshotParameters(), null);
        assertThat((int) image.getWidth()).as("fixture: one snapshot pixel per canvas pixel").isEqualTo(width);
        int[] argb = new int[width * LANE_HEIGHT];
        image.getPixelReader().getPixels(0, 0, width, LANE_HEIGHT, PixelFormat.getIntArgbInstance(), argb, 0, width);
        return argb;
    }
}
