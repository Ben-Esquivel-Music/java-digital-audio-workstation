package com.benesquivelmusic.daw.app.ui.vm;

import com.benesquivelmusic.daw.app.ui.marshal.FxDispatcher;
import com.benesquivelmusic.daw.app.ui.metering.MeterFeed;
import com.benesquivelmusic.daw.app.ui.recording.InputRoutingGuard;
import com.benesquivelmusic.daw.app.ui.vm.command.CoreTrackIntentHandler;
import com.benesquivelmusic.daw.app.ui.vm.command.LinkedTrackCommandDispatcher;
import com.benesquivelmusic.daw.app.ui.vm.command.TrackCommand;
import com.benesquivelmusic.daw.core.audio.AudioEngine;
import com.benesquivelmusic.daw.core.project.DawProject;

import java.util.Objects;
import java.util.function.BooleanSupplier;
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
 * <p>{@link #standalone(DawProject, FxDispatcher, MeterFeed, AudioEngine, Consumer, BooleanSupplier)}
 * composes the production wiring — a registry, a {@link CoreTrackIntentHandler}
 * and a {@link LinkedTrackCommandDispatcher}, the input-routing guard, and the
 * gate every command passes — over one project; it is what
 * {@code MainController} uses, with {@code this::projectActionsAllowed} as the
 * gate. {@link #standalone(DawProject, FxDispatcher, MeterFeed, BooleanSupplier)}
 * is what a {@code MixerView} with no host wiring lazily builds for itself, over
 * the view's own gate — {@code () -> true} from its pure-unit constructors, so
 * the {@code new MixerView(project)} tests keep working — and
 * {@link #standalone(DawProject, FxDispatcher, MeterFeed)} is the pure-unit form
 * whose gate always admits, which tests that build a wiring of their own use.
 * {@link #dispose()} releases the registry and the guard (the handler and
 * dispatcher hold no resources).</p>
 *
 * <p>The gate sits at the intent boundary (Control Synchronization Design Book
 * §2.8: input source is irrelevant past it): while it says no — a project is
 * being installed, or a close of the open project waits on disk work — a
 * command raised into {@link #commandSink()} by a click, a key, a focus change
 * or an assistive technology's action changes nothing and returns silently,
 * and the binders show the VM's value again (PR #989 review 5711460812).
 * FX thread.</p>
 *
 * @param registry       the VM registry of this generation; must not be {@code null}
 * @param commandSink    the gated intent sink every binder dispatches into; must not be {@code null}
 * @param correctionSink the same dispatcher ungated: the model's correctness writers use it — the mixer's
 *                       mirror heal and the input-routing guard's corrective disarm and re-arm (ruling
 *                       R-1.4) — and so does a recorded rename's history entry, which replays an intent the
 *                       gate already admitted: its execute runs past the mixer's gate read and its undo and
 *                       redo are admitted at the history's own doors, so refusing the entry itself would only
 *                       set the model apart from the history; must not be {@code null}
 * @param inputGuard     the input-routing guard of the production wiring, or {@code null} when there is none
 */
public record TrackControlWiring(TrackChannelRegistry registry,
                                 Consumer<TrackCommand> commandSink,
                                 Consumer<TrackCommand> correctionSink,
                                 InputRoutingGuard inputGuard) {
    /** A pure-unit pair over one sink: the correction sink is the same sink, and there is no guard. */
    public TrackControlWiring(TrackChannelRegistry registry, Consumer<TrackCommand> sink) {
        this(registry, sink, sink, null);
    }

    /** @throws NullPointerException if the registry or either sink is {@code null} */
    public TrackControlWiring {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(commandSink, "commandSink must not be null");
        Objects.requireNonNull(correctionSink, "correctionSink must not be null");
    }

    /**
     * Builds the pure-unit wiring over {@code project}: as
     * {@link #standalone(DawProject, FxDispatcher, MeterFeed, BooleanSupplier)}
     * with a gate that always admits.
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
        return standalone(project, dispatcher, meterFeed, () -> true);
    }

    /**
     * Builds the wiring a {@code MixerView} with no host wiring owns: a
     * {@link TrackChannelRegistry} (with the optional meter feed), a
     * {@link CoreTrackIntentHandler} and a {@link LinkedTrackCommandDispatcher}
     * behind {@code projectActionsAllowed}, and no input-routing guard.
     *
     * @param project               the live project; must not be {@code null}
     * @param dispatcher            the FX marshalling seam; must not be {@code null}
     * @param meterFeed             the FX-pulse meter drain, or {@code null}
     * @param projectActionsAllowed the gate every command of {@link #commandSink()} passes; must not be {@code null}
     * @return the wiring; the caller owns it and must {@link #dispose()} it
     * @throws NullPointerException if {@code project}, {@code dispatcher} or the gate is {@code null}
     */
    public static TrackControlWiring standalone(DawProject project, FxDispatcher dispatcher,
                                                MeterFeed meterFeed, BooleanSupplier projectActionsAllowed) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        Objects.requireNonNull(projectActionsAllowed, "projectActionsAllowed must not be null");
        TrackChannelRegistry registry = new TrackChannelRegistry(project, dispatcher, meterFeed);
        CoreTrackIntentHandler handler = new CoreTrackIntentHandler(project);
        LinkedTrackCommandDispatcher linked = new LinkedTrackCommandDispatcher(project, handler);
        return new TrackControlWiring(registry, admitted(linked, projectActionsAllowed), linked, null);
    }

    /**
     * Builds the production wiring over {@code project}, as
     * {@code MainController.rebuildTrackControlWiring()} does: the registry,
     * the handler and the dispatcher of
     * {@link #standalone(DawProject, FxDispatcher, MeterFeed, BooleanSupplier)},
     * plus the {@link InputRoutingGuard} that validates every arm. The guard
     * holds the ungated dispatcher for its corrective disarm and re-arm and
     * reads {@code projectActionsAllowed} only when a user's arm completes.
     *
     * @param project               the live project; must not be {@code null}
     * @param dispatcher            the FX marshalling seam; must not be {@code null}
     * @param meterFeed             the FX-pulse meter drain, or {@code null}
     * @param engine                the engine the guard validates arms against
     * @param errors                where a refused arm's message is reported
     * @param projectActionsAllowed the gate every command of {@link #commandSink()} passes; must not be {@code null}
     * @return the wiring; the caller owns it and must {@link #dispose()} it
     * @throws NullPointerException if {@code project}, {@code dispatcher} or the gate is {@code null}
     */
    public static TrackControlWiring standalone(DawProject project, FxDispatcher dispatcher, MeterFeed meterFeed,
                                                AudioEngine engine, Consumer<String> errors,
                                                BooleanSupplier projectActionsAllowed) {
        Objects.requireNonNull(project, "project must not be null");
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        Objects.requireNonNull(projectActionsAllowed, "projectActionsAllowed must not be null");
        CoreTrackIntentHandler handler = new CoreTrackIntentHandler(project);
        LinkedTrackCommandDispatcher linked = new LinkedTrackCommandDispatcher(project, handler);
        InputRoutingGuard guard = new InputRoutingGuard(project, engine, dispatcher, errors, linked,
                projectActionsAllowed);
        handler.setArmValidator(guard::requestArm);
        handler.setArmCancellation(guard::cancelArm);
        TrackChannelRegistry registry = new TrackChannelRegistry(project, dispatcher, meterFeed);
        return new TrackControlWiring(registry, admitted(linked, projectActionsAllowed), linked, guard);
    }

    /**
     * The gate at the intent boundary: a command refused while
     * {@code projectActionsAllowed} says no changes nothing and returns,
     * silently (ruling R-2.1; PR #989 review 5711460812). FX thread.
     */
    private static Consumer<TrackCommand> admitted(Consumer<TrackCommand> sink,
                                                   BooleanSupplier projectActionsAllowed) {
        return command -> {
            if (!projectActionsAllowed.getAsBoolean()) {
                return;
            }
            sink.accept(command);
        };
    }

    /** Disposes the guard and the registry (every VM, listener and continuous channel). Idempotent. */
    public void dispose() {
        if (inputGuard != null) inputGuard.close();
        registry.dispose();
    }
}
