/**
 * OMF / AAF interchange export for film post-production handoff.
 *
 * <p>This package provides a pure-Java writer over the minimal subset of
 * the AAF (Advanced Authoring Format, version 1.2) data model required
 * for editorial-to-post handoff: a timeline composition referencing one
 * or more source clips, each with a position, length, source offset,
 * fade-in and fade-out (with curve type), and per-clip gain. The
 * companion {@code com.benesquivelmusic.daw.core.export.omf} package
 * provides an OMF 2.0 fallback writer for older workflows.</p>
 *
 * <h2>Why a custom binary format?</h2>
 *
 * <p>True AAF is a Microsoft Compound File Binary container with a
 * UUID-keyed object hierarchy. Producing a Pro&nbsp;Tools-readable AAF is
 * out of scope for a pure-Java, dependency-free writer; instead this
 * package emits a compact binary file that captures exactly the AAF data
 * model needed for round-trip verification. The {@link
 * com.benesquivelmusic.daw.core.export.aaf.AafReader} verifier in this
 * same package re-reads files written by {@link
 * com.benesquivelmusic.daw.core.export.aaf.AafWriter} so that tests can
 * confirm timeline, fade, gain, and timecode information survives the
 * round-trip within sample-accurate precision.</p>
 *
 * <h2>File layout</h2>
 *
 * <pre>
 *   [8]   magic              "AAF12\0\0\0"  (or "OMF20\0\0\0" for OMF)
 *   [2]   versionMajor       big-endian
 *   [2]   versionMinor       big-endian
 *   [4]   manifestLen        big-endian
 *   [N]   manifestJson       UTF-8, describes composition, tracks, clips
 *   [4]   embeddedCount      big-endian
 *   for each embedded source:
 *     [16] sourceMobId       UUID bytes
 *     [4]  nameLen           big-endian
 *     [N]  nameUtf8
 *     [4]  sampleRate        big-endian
 *     [2]  channels          big-endian
 *     [2]  bitsPerSample     big-endian
 *     [8]  frameCount        big-endian
 *     [4]  pcmDataLen        big-endian
 *     [N]  pcm               little-endian signed PCM, interleaved
 *   [4]   trailer            "AEND" (or "OEND")
 * </pre>
 */
package com.benesquivelmusic.daw.core.export.aaf;
