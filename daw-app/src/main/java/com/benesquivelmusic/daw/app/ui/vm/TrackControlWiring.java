package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.LinkedTrackCommandDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The per-project-generation pair every control surface binds through — the
 * live {@link TrackChannelRegistry} (the VMs) and the one command sink (the
 * intent path) — story 322, Audio Engine Wiring Design Book §2.10 / §5.6
 * ("one intent path, both surfaces").
 *
 * <p>Both surfaces (the arrangement's {@code TrackStripController} and the
 * {@code MixerView}) receive a <strong>live {@code Supplier<TrackControlWiring>}</strong>
 * rather than an instance: {@code MainController.rebuildTrackControlWiring()}
 * builds a fresh wiring per project load and disposes the previous one, and a
 * surface that resolves the supplier at bind time always gets the current
 * generation.</p>
 *
 * <p>{@link #standalone(DawProject, FxDispatcher, MeterFeed)} composes the
 * production trio — a registry, a {@link CoreTrackIntentHandler} and a
 * {@link LinkedTrackCommandDispatcher} — over one project; it is what
 * {@code MainController} uses, and what a {@code MixerView} with no injected
 * wiring lazily builds for itself so the pure-unit {@code new MixerView(project)}
 * tests keep working. {@link #dispose()} releases the registry (the handler and
 * dispatcher hold no resources).</p>
 *
 * @param registry    the VM registry of this generation; must not be {@code null}
 * @param commandSink the intent sink every binder dispatches into; must not be {@code null}
 */
public record TrackControlWiring(TrackChannelRegistry registry,
                                 Consumer<TrackCommand> commandSink) {

    /** @throws NullPointerException if either component is {@code null} */
    public TrackControlWiring {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(commandSink, "commandSink must not be null");
    }

    /**
     * Builds the production wiring over {@code project}: a
     * {@link TrackChannelRegistry} (with the optional meter feed), a
     * {@link CoreTrackIntentHandler} and a {@link LinkedTrackCommandDispatcher}
     * as the sink.
     *
     * @param project    the live project; must not be {@code null}
     * @param dispatcher the FX marshalling seam; must not be {@code null}
     * @param meterFeed  the FX-pulse meter drain, or {@code null} to leave every
     *                   strip meter at its floor (pure-unit contexts)
     * @return the wiring; the caller owns it and must {@link #dispose()} it
     * @throws NullPointerException if {@code project} or {@code dispatcher} is {@code null}
     */
    public static TrackControlWiring standalone(DawProject project, FxDispatcher dispatcher,
                                                MeterFeed meterFeed) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        TrackChannelRegistry registry = new TrackChannelRegistry(project, dispatcher, meterFeed);
        CoreTrackIntentHandler handler = new CoreTrackIntentHandler(project);
        return new TrackControlWiring(registry, new LinkedTrackCommandDispatcher(project, handler));
    }

    /** Disposes the registry (every VM, listener and continuous channel). Idempotent. */
    public void dispose() {
        registry.dispose();
    }
}
