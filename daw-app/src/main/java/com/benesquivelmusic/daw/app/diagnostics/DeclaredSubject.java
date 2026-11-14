package com.benesquivelmusic.daw.app.diagnostics;

import java.util.Objects;
import java.util.Optional;

/**
 * A subject the failing site declares to the funnel (story 336): the plugin
 * a dispose worker is disposing now (story 336, studio-engineer ruling E10), and the
 * palette command later (story 339). The
 * funnel never infers what failed from frames, focus or the last event; only
 * a site that knows says so, and the toast then names it.
 *
 * <p>The site catches and reports, rather than setting a per-thread subject
 * the default handler reads: a scoped binding is gone by the time the
 * exception reaches the top of the thread, and a thread-local never removed
 * would leak onto the FX thread where a test's plugin-menu worker is
 * {@code Runnable::run}.</p>
 *
 * @param category the failure's category slug, for example {@link #PLUGIN_DISPOSE}
 * @param subject  what the site was working on, as the user knows it
 */
public record DeclaredSubject(String category, String subject) {

    /** A plugin's {@code dispose()} failed (story 336, studio-engineer ruling E10). */
    public static final String PLUGIN_DISPOSE = "plugin.dispose";

    public DeclaredSubject {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
    }

    /**
     * The subject of a plugin's disposal.
     *
     * @param pluginName the plugin's display name, as its insert slot shows it
     * @return the {@link #PLUGIN_DISPOSE} subject naming it
     */
    public static DeclaredSubject pluginDisposal(String pluginName) {
        return new DeclaredSubject(PLUGIN_DISPOSE, pluginName);
    }

    /**
     * Wraps {@code work} so that a throwable it throws reaches the installed
     * funnel ({@link FailureFunnel#installed()}) exactly once, as that very
     * throwable, under this subject (the story-334 note). With no funnel
     * installed it is rethrown unchanged, as it would be without the wrapper.
     *
     * @param work the work to run
     * @return the guarded work
     */
    public Runnable guard(Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        return () -> {
            try {
                work.run();
            } catch (Throwable failure) {
                Optional<FailureFunnel> funnel = FailureFunnel.installed();
                if (funnel.isEmpty()) {
                    DeclaredSubject.<RuntimeException>rethrow(failure);
                    return;
                }
                funnel.get().reportDeclared(this, failure);
            }
        };
    }

    /** Rethrows {@code failure} unchanged, a checked one included, from a method that declares nothing. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
