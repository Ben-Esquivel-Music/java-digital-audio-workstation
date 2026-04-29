/**
 * In-process implementation of the SDK event bus.
 *
 * <p>This package contains the engine-side implementation of
 * {@link com.benesquivelmusic.daw.sdk.event.EventBus}. The
 * authoritative type contract lives in
 * {@code com.benesquivelmusic.daw.sdk.event}; consumers should depend
 * only on the SDK interface so they can be tested in isolation and
 * swapped with alternative bus implementations.</p>
 *
 * <h2>Wiring</h2>
 *
 * <pre>{@code
 * EventBus bus = DefaultEventBus.builder()
 *         .bufferCapacity(256)
 *         .uiExecutor(javafx.application.Platform::runLater)
 *         .overflowStrategy(MeterEvent.class, OverflowStrategy.DROP_OLDEST)
 *         .overflowStrategy(ProjectEvent.class, OverflowStrategy.BLOCK)
 *         .build();
 * }</pre>
 *
 * <p>The bus owns a daemon {@code ExecutorService} for per-subscription
 * dispatch workers and shuts it down in {@link
 * com.benesquivelmusic.daw.core.event.DefaultEventBus#close()}.</p>
 */
package com.benesquivelmusic.daw.core.event;
