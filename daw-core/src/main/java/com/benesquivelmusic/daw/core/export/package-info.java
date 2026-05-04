/**
 * Offline export utilities for rendering DAW projects to file.
 *
 * <h2>Unified Render Pipeline</h2>
 *
 * <p>As of story 102 — "Playback-Export Parity: Unified Render Pipeline for
 * Live and Offline Processing" — every offline render in this package is a
 * thin wrapper over
 * {@link com.benesquivelmusic.daw.core.audio.RenderPipeline#renderOffline(
 * com.benesquivelmusic.daw.core.transport.Transport,
 * com.benesquivelmusic.daw.core.mixer.Mixer, java.util.List,
 * com.benesquivelmusic.daw.core.audio.MidiTrackRenderer,
 * com.benesquivelmusic.daw.core.audio.EffectsChain,
 * float[][], int, int) RenderPipeline.renderOffline}, the same pipeline
 * driving live audio playback through
 * {@link com.benesquivelmusic.daw.core.audio.AudioEngine#processBlock
 * AudioEngine.processBlock}.</p>
 *
 * <p>This means {@link com.benesquivelmusic.daw.core.export.StemExporter},
 * {@link com.benesquivelmusic.daw.core.export.TrackBouncer}, and
 * {@link com.benesquivelmusic.daw.core.export.BundleExportService} no
 * longer carry their own clip-walking, accumulator-summing,
 * pan/volume/insert-applying logic — each delegates per-block rendering
 * to the unified pipeline. The architectural guarantee is "what you hear
 * is what you get" (WYHIWYG): exported audio is bit-identical to live
 * playback for the same project state, verified by
 * {@code RenderPipelineParityTest} (pipeline level) and
 * {@code StemExporterParityTest} (export level).</p>
 *
 * <h2>Authoring guideline for new export features</h2>
 *
 * <p>Any new export feature in this package <strong>must</strong> compose
 * with {@link com.benesquivelmusic.daw.core.audio.RenderPipeline} rather
 * than reimplement audio rendering. If a per-track stem buffer is
 * required, route through
 * {@link com.benesquivelmusic.daw.core.export.OfflineStemRenderer}, the
 * shared helper that owns the mute / master / transport orchestration.
 * Reimplementing rendering — even "just for one new format" — silently
 * reintroduces the divergence story 102 was created to eliminate.</p>
 *
 * <p>Convenience utilities that are not rendering paths
 * (e.g. {@link com.benesquivelmusic.daw.core.export.TrackBouncer#beatsToFrames(double, int, double)
 * TrackBouncer.beatsToFrames}, the file-format adapters in
 * {@link com.benesquivelmusic.daw.core.export.DefaultAudioExporter},
 * {@link com.benesquivelmusic.daw.core.export.WavExporter},
 * {@link com.benesquivelmusic.daw.core.export.FlacExporter}, etc.) remain
 * appropriate to live in this package.</p>
 */
package com.benesquivelmusic.daw.core.export;
