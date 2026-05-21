package com.benesquivelmusic.daw.app.ui.motion;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The production {@link OsMotionHint} — a best-effort, per-platform
 * detector for the operating system's "reduce motion" accessibility
 * preference (story 279).
 *
 * <p>Detection is intentionally tolerant: every probe is wrapped so that
 * <em>any</em> failure (an unsupported platform, a missing native
 * library, a process-spawn error, a {@link Throwable} of any kind)
 * collapses to {@link Optional#empty()}. {@link MotionManager} then falls
 * back to {@code false} (motion allowed). A failure to detect the OS hint
 * must never break application startup.</p>
 *
 * <h2>Per-platform strategy</h2>
 *
 * <ul>
 *   <li><strong>Windows</strong> (the project's primary target) — a
 *       Foreign Function &amp; Memory API (FFM) downcall to
 *       {@code user32!SystemParametersInfoW} with
 *       {@code SPI_GETCLIENTAREAANIMATION}. The native call writes a
 *       {@code BOOL} into a caller-allocated 4-byte buffer:
 *       <em>non-zero</em> means client-area animations are <em>enabled</em>
 *       (so {@code reduceMotion = false}); <em>zero</em> means animations
 *       are off (so {@code reduceMotion = true}).</li>
 *   <li><strong>macOS</strong> — the equivalent preference
 *       ({@code NSWorkspace.accessibilityDisplayShouldReduceMotion}) is
 *       not reliably reachable without an Objective-C runtime message
 *       send, which this project does not currently bridge. macOS
 *       therefore returns {@link Optional#empty()}; the in-app setting
 *       still works, it simply is not OS-seeded on first launch. This is
 *       a documented, accepted limitation (story 279).</li>
 *   <li><strong>Linux</strong> — a best-effort {@code gsettings} process
 *       call reading {@code org.gnome.desktop.interface enable-animations}.
 *       Any failure (no GNOME, no {@code gsettings} binary, a timeout) is
 *       swallowed to {@link Optional#empty()}.</li>
 * </ul>
 */
final class PlatformMotionHint implements OsMotionHint {

    private static final Logger LOG =
            Logger.getLogger(PlatformMotionHint.class.getName());

    /**
     * {@code SPI_GETCLIENTAREAANIMATION} — the {@code SystemParametersInfo}
     * action that reports whether animation effects associated with
     * user-interface client areas are enabled (Win32 {@code winuser.h}).
     */
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;

    /** Bounded wait for the Linux {@code gsettings} probe. */
    private static final long LINUX_PROBE_TIMEOUT_SECONDS = 2;

    @Override
    public Optional<Boolean> reduceMotionPreferred() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return detectWindows();
            }
            if (os.contains("mac") || os.contains("darwin")) {
                // Not reliably detectable without an Objective-C runtime
                // message send — documented limitation (class Javadoc).
                return Optional.empty();
            }
            return detectLinux();
        } catch (Throwable t) {
            // Detection must never break MotionManager construction.
            LOG.log(Level.FINE,
                    "OS reduce-motion hint detection failed; defaulting to undetected",
                    t);
            return Optional.empty();
        }
    }

    /**
     * Windows: FFM downcall to
     * {@code SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &BOOL, 0)}.
     *
     * @return {@code Optional.of(true)} if client-area animations are
     *         disabled (reduce motion on), {@code Optional.of(false)} if
     *         they are enabled, or {@link Optional#empty()} on any failure
     */
    private static Optional<Boolean> detectWindows() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            MemorySegment fn = user32.find("SystemParametersInfoW").orElse(null);
            if (fn == null) {
                return Optional.empty();
            }
            // BOOL SystemParametersInfoW(UINT uiAction, UINT uiParam,
            //                            PVOID pvParam, UINT fWinIni)
            MethodHandle handle = linker.downcallHandle(fn, FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,    // BOOL return
                    ValueLayout.JAVA_INT,    // UINT  uiAction
                    ValueLayout.JAVA_INT,    // UINT  uiParam
                    ValueLayout.ADDRESS,     // PVOID pvParam
                    ValueLayout.JAVA_INT));  // UINT  fWinIni

            // pvParam receives a BOOL (a 4-byte int).
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) handle.invoke(
                    SPI_GETCLIENTAREAANIMATION, 0, out, 0);
            if (ok == 0) {
                // The call itself failed — treat as undetected.
                return Optional.empty();
            }
            int animationsEnabled = out.get(ValueLayout.JAVA_INT, 0);
            // animations enabled (non-zero) → reduceMotion = false.
            return Optional.of(animationsEnabled == 0);
        } catch (Throwable t) {
            LOG.log(Level.FINE,
                    "Windows SystemParametersInfo reduce-motion probe failed", t);
            return Optional.empty();
        }
    }

    /**
     * Linux: best-effort {@code gsettings} read of
     * {@code org.gnome.desktop.interface enable-animations}. Tolerates any
     * failure silently.
     *
     * @return {@code Optional.of(true)} if animations are disabled,
     *         {@code Optional.of(false)} if enabled, or
     *         {@link Optional#empty()} on any failure
     */
    private static Optional<Boolean> detectLinux() {
        try {
            Process process = new ProcessBuilder(
                    "gsettings", "get",
                    "org.gnome.desktop.interface", "enable-animations")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes()).trim()
                        .toLowerCase(Locale.ROOT);
            }
            if (!process.waitFor(LINUX_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (output.contains("true")) {
                // animations enabled → reduceMotion = false.
                return Optional.of(false);
            }
            if (output.contains("false")) {
                // animations disabled → reduceMotion = true.
                return Optional.of(true);
            }
            return Optional.empty();
        } catch (Exception e) {
            LOG.log(Level.FINE,
                    "Linux gsettings reduce-motion probe failed", e);
            return Optional.empty();
        }
    }
}
