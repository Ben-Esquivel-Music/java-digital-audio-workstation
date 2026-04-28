/**
 * OMF&nbsp;2.0 fallback writer for older film-post workflows that do
 * not yet ingest AAF. The on-disk envelope mirrors the AAF&nbsp;1.2
 * format produced by {@link com.benesquivelmusic.daw.core.export.aaf}
 * but with {@code OMF20} magic and {@code OEND} trailer; the captured
 * timeline data model is identical (composition, source clips,
 * positions, fades, gains).
 */
package com.benesquivelmusic.daw.core.export.omf;
