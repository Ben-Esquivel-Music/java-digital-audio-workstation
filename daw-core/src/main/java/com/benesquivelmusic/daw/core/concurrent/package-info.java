/**
 * Non-realtime concurrency primitives for the DAW core.
 *
 * <p>Audio callback threads are <strong>not</strong> managed here —
 * they remain dedicated platform threads owned by the audio host
 * (PortAudio / CoreAudio / WASAPI / JACK). Virtual threads are
 * unsuitable for deadline-critical work: a virtual thread can be
 * unmounted from its carrier at arbitrary safepoints, breaking the
 * sample-accurate timing the audio engine relies on.</p>
 *
 * <p>For everything else — file import / export, autosave,
 * background analysis, directory scans — use {@link
 * com.benesquivelmusic.daw.core.concurrent.DawTaskRunner} so the
 * runtime can route I/O-bound work to the virtual-thread-per-task
 * executor (JEP 444) and short CPU-bound work to a bounded platform
 * pool. Use {@link com.benesquivelmusic.daw.core.concurrent.DawScope}
 * for fan-out / fan-in patterns where the parent task should fail
 * fast on child failure.</p>
 *
 * @see com.benesquivelmusic.daw.core.concurrent.DawTaskRunner
 * @see com.benesquivelmusic.daw.core.concurrent.DawScope
 */
package com.benesquivelmusic.daw.core.concurrent;
