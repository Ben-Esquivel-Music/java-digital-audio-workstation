/**
 * Real-time audio engine, render pipeline, and multi-core graph scheduling.
 *
 * <h2>Real-time safety contract</h2>
 *
 * <p>Every method annotated
 * {@link com.benesquivelmusic.daw.sdk.annotation.RealTimeSafe @RealTimeSafe}
 * in this package is reachable from the audio callback thread and from the
 * worker threads spawned by {@link
 * com.benesquivelmusic.daw.core.audio.AudioWorkerPool}. Such methods must:</p>
 * <ul>
 *   <li><strong>Allocate no Java heap memory.</strong> All scratch buffers
 *       are pre-allocated by {@link
 *       com.benesquivelmusic.daw.core.audio.AudioEngine#start()} or by
 *       constructors and reused for the lifetime of the engine. Per-branch
 *       buffers used by parallel inserts come from
 *       {@link com.benesquivelmusic.daw.core.audio.AudioBufferPool}.</li>
 *   <li><strong>Acquire no blocking locks.</strong> Use lock-free
 *       constructs (atomic CAS, volatile publication) or carefully-bounded
 *       primitives. {@link java.util.concurrent.locks.LockSupport#parkNanos
 *       LockSupport.parkNanos} is preferred over {@code Object.wait()} or
 *       {@code synchronized} blocks because the latter pin the carrier
 *       thread and risk priority inversion against the audio callback.</li>
 *   <li><strong>Use platform threads, never virtual threads.</strong>
 *       {@link com.benesquivelmusic.daw.core.audio.AudioWorkerPool} spawns
 *       {@code Thread.ofPlatform().daemon(true).priority(MAX_PRIORITY)}
 *       workers. Virtual threads share carrier threads with non-realtime
 *       work and therefore break audio-callback timing — they must never
 *       be used on the render path.</li>
 *   <li><strong>Tolerate concurrent reads of immutable engine state.</strong>
 *       UI-thread writes to {@code transport}, {@code mixer}, {@code tracks}
 *       and similar references are published via {@code volatile}; the
 *       audio thread snapshots them once per block and uses the snapshot
 *       for the duration of the call.</li>
 * </ul>
 *
 * <h2>Multi-core graph processing (story 125)</h2>
 *
 * <p>{@link com.benesquivelmusic.daw.core.audio.AudioEngine} owns an
 * optional {@link com.benesquivelmusic.daw.core.audio.AudioWorkerPool} and
 * {@link com.benesquivelmusic.daw.core.audio.AudioGraphScheduler}. When the
 * configured {@link com.benesquivelmusic.daw.core.audio.AudioEngineSettings}
 * requests a worker-pool size greater than one, the engine installs the
 * scheduler on the {@link com.benesquivelmusic.daw.core.mixer.Mixer} so
 * that independent per-channel insert chains are dispatched across worker
 * threads. The audio callback thread acts as the coordinator: it submits
 * tasks, participates as one worker, and returns once the master is
 * computed. Summing, send-routing, and delay-compensation phases stay
 * sequential to preserve bit-exact summation order with the
 * single-threaded path.</p>
 *
 * <h3>Single-threaded fallback</h3>
 *
 * <p>The engine falls back to inline single-threaded rendering when:</p>
 * <ul>
 *   <li>The configured worker-pool size is {@code 1}, or</li>
 *   <li>The block size is smaller than
 *       {@link com.benesquivelmusic.daw.core.audio.AudioGraphScheduler#DEFAULT_MIN_PARALLEL_BLOCK_SIZE}
 *       (default {@code 64} sample frames) — coordination overhead exceeds
 *       the parallelism gain at very small blocks, or</li>
 *   <li>Fewer than two parallelizable tasks would be dispatched for the
 *       current graph.</li>
 * </ul>
 *
 * <p>The pool size is locked at engine start; changing
 * {@link com.benesquivelmusic.daw.core.audio.AudioEngineSettings#workerPoolSize()}
 * requires stopping and restarting the engine.</p>
 */
package com.benesquivelmusic.daw.core.audio;
