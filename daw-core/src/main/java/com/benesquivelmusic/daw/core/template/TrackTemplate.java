package com.benesquivelmusic.daw.core.template;

import com.benesquivelmusic.daw.core.audio.InputRouting;
import com.benesquivelmusic.daw.core.mixer.OutputRouting;
import com.benesquivelmusic.daw.core.track.TrackColor;
import com.benesquivelmusic.daw.core.track.TrackType;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A reusable template capturing the complete configuration of a track so
 * that identical tracks can be instantiated repeatedly with a single action.
 *
 * <p>A {@code TrackTemplate} captures:</p>
 * <ul>
 *     <li>The {@link TrackType track type} (audio or MIDI)</li>
 *     <li>A default name pattern used when creating a new track from this
 *         template</li>
 *     <li>The ordered chain of {@linkplain InsertEffectSpec insert effects},
 *         including their parameter values and bypass flags</li>
 *     <li>{@linkplain SendSpec Send routing} to return buses (looked up by
 *         name at apply-time)</li>
 *     <li>Default volume, pan, track color, and input/output routing</li>
 * </ul>
 *
 * <p>Templates are typically loaded from XML files under {@code ~/.daw/templates/}
 * by {@link TrackTemplateStore} and applied to a project via
 * {@link TrackTemplateService#createTrackFromTemplate}.</p>
 *
 * @param templateName  the display name of the template (for menus and
 *                      template pickers)
 * @param trackType     the track type to create
 * @param nameHint      the suggested name for tracks created from this template
 * @param inserts       the ordered insert effect specs (stored as an unmodifiable copy)
 * @param sends         the send specs (stored as an unmodifiable copy)
 * @param volume        the default volume in {@code [0.0, 1.0]}
 * @param pan           the default pan in {@code [-1.0, 1.0]}
 * @param color         the default track color (must not be {@code null})
 * @param inputRouting  the default input routing (must not be {@code null})
 * @param outputRouting the default output routing (must not be {@code null})
 */
public record TrackTemplate(String templateName,
                            TrackType trackType,
                            String nameHint,
                            List<InsertEffectSpec> inserts,
                            List<SendSpec> sends,
                            double volume,
                            double pan,
                            TrackColor color,
                            InputRouting inputRouting,
                            OutputRouting outputRouting) {

    public TrackTemplate {
        Objects.requireNonNull(templateName, "templateName must not be null");
        Objects.requireNonNull(trackType, "trackType must not be null");
        Objects.requireNonNull(nameHint, "nameHint must not be null");
        Objects.requireNonNull(inserts, "inserts must not be null");
        Objects.requireNonNull(sends, "sends must not be null");
        Objects.requireNonNull(color, "color must not be null");
        Objects.requireNonNull(inputRouting, "inputRouting must not be null");
        Objects.requireNonNull(outputRouting, "outputRouting must not be null");
        if (templateName.isBlank()) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        if (volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("volume must be between 0.0 and 1.0: " + volume);
        }
        if (pan < -1.0 || pan > 1.0) {
            throw new IllegalArgumentException("pan must be between -1.0 and 1.0: " + pan);
        }
        inserts = List.copyOf(inserts);
        sends = List.copyOf(sends);
    }

    /**
     * Creates a minimal template with no effects or sends, unity gain, centered
     * pan, default I/O routing, and the given color.
     *
     * @param templateName the template display name
     * @param trackType    the track type
     * @param nameHint     the default name for new tracks
     * @param color        the default track color
     * @return a new template with defaults for all other fields
     */
    public static TrackTemplate basic(String templateName,
                                      TrackType trackType,
                                      String nameHint,
                                      TrackColor color) {
        return new TrackTemplate(
                templateName,
                trackType,
                nameHint,
                Collections.emptyList(),
                Collections.emptyList(),
                1.0,
                0.0,
                color,
                InputRouting.DEFAULT_STEREO,
                OutputRouting.MASTER);
    }
}
