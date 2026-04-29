/**
 * Persistent cache of rendered (frozen) track audio.
 *
 * <p>Story 035 introduces track freeze, but its rendered audio is
 * session-scoped: closing and reopening a project re-renders every
 * frozen track from scratch. The classes in this package extend that
 * feature with a persistent on-disk cache keyed by the track's
 * DSP-relevant state hash (see {@link
 * com.benesquivelmusic.daw.core.audio.cache.TrackDspHasher}). A cache
 * hit lets {@link
 * com.benesquivelmusic.daw.core.track.TrackFreezeService} skip
 * re-rendering and load the previously frozen audio directly from
 * disk.</p>
 *
 * <p>Layout on disk:</p>
 *
 * <pre>{@code
 * ~/.daw/render-cache/
 *     <projectUuid>/
 *         <hashPrefix>/             // first two hex chars of trackDspHash
 *             <renderKey>.pcm       // raw 32-bit float audio + small header
 * }</pre>
 *
 * <p>The cache enforces a per-project byte quota with LRU eviction
 * (default 5 GiB; see {@link
 * com.benesquivelmusic.daw.core.audio.cache.RenderCacheConfig}).
 * Invalidation is implicit: any DSP-affecting change alters the
 * {@link com.benesquivelmusic.daw.core.audio.cache.RenderKey} and
 * therefore produces a miss.</p>
 *
 * <p><b>Non-goals</b>: cross-machine sharing, partial-range caching,
 * or automatic freeze.</p>
 */
package com.benesquivelmusic.daw.core.audio.cache;
