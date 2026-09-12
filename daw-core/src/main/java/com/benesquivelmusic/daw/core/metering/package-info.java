/**
 * The RT-safe metering tap bus (story 318; Audio Engine Wiring Design Book
 * &sect;3.3&ndash;3.5, &sect;4.3).
 *
 * <p>One tap on the render path, many consumers. The render thread touches
 * every sample anyway, so the mixer accumulates peak / sum-of-squares into a
 * preallocated {@link com.benesquivelmusic.daw.core.metering.LevelTapSlot}
 * per tap point (the <em>level lane</em>) and, only when an analyzer is
 * attached, copies the block into a bounded
 * {@link com.benesquivelmusic.daw.core.metering.SampleBlockRing} (the
 * <em>analysis lane</em>). Both lanes are latest-wins / drop-oldest: the RT
 * thread never blocks, never allocates, never takes a lock and never iterates
 * a mutable collection.</p>
 *
 * <h2>Thread ownership</h2>
 * <ul>
 *   <li><strong>RT render thread</strong> &mdash; reads
 *       {@link com.benesquivelmusic.daw.core.metering.MeteringTapBus#snapshot()}
 *       once per block, accumulates into slots, writes rings, calls
 *       {@link com.benesquivelmusic.daw.core.metering.MeteringTapBus#blockCompleted(TapSnapshot)}.
 *       Every method it may call carries
 *       {@link com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe @RealTimeSafe}.</li>
 *   <li><strong>Analysis thread</strong> ({@code daw-metering-analysis}, one
 *       daemon platform thread) &mdash; drains the rings into
 *       {@link com.benesquivelmusic.daw.core.metering.AnalysisConsumer}s.</li>
 *   <li><strong>FX / lifecycle threads</strong> &mdash; attach, dispose,
 *       rebind, refresh and {@code readInto}. Consumer-side reads are
 *       deliberately <em>not</em> annotated {@code @RealTimeSafe}: they spin
 *       on a seqlock and may retry, which is fine off-RT and wrong on it.</li>
 * </ul>
 *
 * <p>Publication follows the house idioms: preallocated mutable slots with
 * release-store / acquire-load
 * ({@link com.benesquivelmusic.daw.core.audio.performance.XrunEventRingBuffer}),
 * and a {@code volatile} immutable snapshot rebuilt off-RT under a lock and
 * read once per block
 * ({@link com.benesquivelmusic.daw.core.concurrent.ChangeNotifier}).</p>
 */
package com.benesquivelmusic.daw.core.metering;
