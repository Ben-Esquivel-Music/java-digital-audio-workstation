package com.benesquivelmusic.daw.app.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A source scan, not a reflection consumer (dawg-annotations-reflection §4
 * "Adding a new reflection consumer"): the completeness sentinel of the
 * project-action gate (INTERACTION_COMPLETENESS_DESIGN_BOOK §2.8
 * "Completeness is enforced by sentinels, not vigilance"; PR #989 review
 * 5711460812). While a close waits on disk work, or a project is being
 * installed, {@code MainController.projectActionsAllowed()} says no, and every
 * edit a window's controls raise must be refused where its intent is handled,
 * whatever raised it: an assistive technology's action and a focus change
 * reach a control without the input event the window's fence consumes. The
 * scan reads every {@code .java} file under {@code daw-app/src/main/java}:
 *
 * <ol>
 *   <li><strong>Every edit site is guarded where it runs.</strong> A site is
 *       an {@code execute}, {@code undo}, {@code redo},
 *       {@code goToHistoryIndex} or {@code clearRedoStack} on an
 *       {@code UndoManager} — an identifier the file declares with that type
 *       or as a {@code Supplier} of it, a method returning one, or the name
 *       {@code undoManager}; an {@code accept} on an identifier declared as a
 *       {@code Consumer} of a {@code TrackCommand}, {@code TransportCommand}
 *       or {@code HistoryCommand}, or on a {@code correctionSink()}; and a
 *       dirty mark — {@code markDirty()}, {@code markProjectDirty()}, a
 *       {@code Runnable} of that name run, or a method reference to either.
 *       It is accepted when the innermost method or lambda holding it holds,
 *       at its own top level and before the site, the one refusal idiom — an
 *       {@code if (!…projectActionsAllowed…) return…;}, or a block ending in a
 *       {@code return}, with no {@code else} — and no dialog lies between the
 *       last such read and the site: an inline {@code showAndWait}, file
 *       chooser or input-port {@code choose}, or a call of a same-file method
 *       that shows one, found by a walk over its same-file callees. A site is
 *       also accepted when it runs only from a pointer handler, an input event
 *       the window's fence consumes while a close waits and the disabled root
 *       blocks while a project is installed; when it sits in a private method
 *       whose every same-file call is accepted; or when an allowance covers it.
 *       A site inside history replay ({@code UndoableAction}) is reached only
 *       through a history site and is excluded. The text of nested lambdas,
 *       anonymous classes and local types is blanked out of a scope, so a read
 *       inside one never guards the scope around it.</li>
 *   <li><strong>The binders take the gated sink.</strong> Every
 *       {@code TrackControlBinder} and {@code ChannelControlBinder} is built
 *       over {@code commandSink()} (or the mixer's recorded renames), never
 *       {@code correctionSink()}; the transport and history binders dispatch
 *       through the two gated {@code MainController} methods; only the
 *       binders call {@code SliderBinding.bind}; no code builds a
 *       {@code TrackControlWiring} itself, and every {@code standalone} call
 *       ends with the gate.</li>
 *   <li><strong>The raw sink stays with its writers:</strong>
 *       {@code correctionSink()} appears only in the mixer's mirror heal, a
 *       correctness writer (ruling R-1.4), and in its recorded renames, whose
 *       history entries replay an intent the gate admitted.</li>
 * </ol>
 *
 * <p>The scan's bound: a dialog reached through another class's object — a
 * {@code Predicate}, a {@code Confirmation} — is not seen, so a handler that
 * reads the gate again after such a call has a named behaviour test; a direct
 * write of saved state that neither marks dirty nor goes through the history
 * is not a site (future story 413), wherever it runs — a control's handler,
 * or a {@code Runnable} handed to a notification or another object as its
 * action; and nor is a door that starts a project action or opens a dialog
 * with no site of its own — an export, an archive, a door into Settings, a
 * lifecycle door — so such a door reads the gate, or the lifecycle
 * controller's close, itself, and its refusal is pinned by a named behaviour
 * test or a source pin instead (ruling-2 F9).</p>
 *
 * <p>The conventions are {@code MixerControlTruthScanTest}'s: allowance keys
 * are relative to {@code src/main/java}; the allowances are a sealed type and
 * every reason that rests on a fact carries a {@link Fact} the scan evaluates;
 * an allowance that covers no site is an error; counts guard against a
 * vacuous pass; and {@link #theScopeRuleCatchesEveryShapeAnUngatedEditCanTake}
 * feeds one fixture per shape through the same pipeline. Comments and the
 * contents of literals are blanked in place with their newlines kept, so an
 * offender's line is its source line ({@link SourceScanSupport#stripComments}
 * closes each comment to one space).</p>
 */
final class ProjectActionGateScanTest {

    /** Where a site needs no read of the gate, and why. */
    private sealed interface Allowance permits WholeFile, MethodBody {
        String key();

        Fact fact();

        String reason();
    }

    /** Every site in the file is covered. */
    private record WholeFile(String key, Fact fact, String reason) implements Allowance {
        WholeFile {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(fact, "fact must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /**
     * Only the sites textually inside one body are covered, nested lambdas
     * included. {@code method} is a simple name, or, when the name is
     * overloaded, the name with its parameters' simple type names, generics
     * erased, in declaration order; it must resolve to exactly one body.
     */
    private record MethodBody(String key, String method, Fact fact, String reason) implements Allowance {
        MethodBody {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(method, "method must not be null");
            Objects.requireNonNull(fact, "fact must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** What must hold for an allowance's reason to be true; the scan evaluates it. */
    private sealed interface Fact
            permits PointerOnlyFile, NoCallInMain, GatedSinkBinder, HandoffOnly, HostMethodUncalled, AllOf, Ruling {
    }

    /** The file has no action handler at all: every edit it makes comes from a pointer gesture. */
    private record PointerOnlyFile() implements Fact {
    }

    /** No match of {@code pattern} in main, outside the files keyed by {@code exceptKeys}. */
    private record NoCallInMain(String pattern, Set<String> exceptKeys) implements Fact {
        NoCallInMain {
            Objects.requireNonNull(pattern, "pattern must not be null");
            exceptKeys = Set.copyOf(exceptKeys);
        }
    }

    /** Rules 2 and 2b hold, with their counts: the binders dispatch only into the gated sink. */
    private record GatedSinkBinder() implements Fact {
    }

    /**
     * Every covered site is a dirty mark handed over — a method reference, or
     * an expression lambda whose whole body is one dirty mark — as a direct
     * argument of a call or a constructor.
     */
    private record HandoffOnly() implements Fact {
    }

    /** The window's {@code ViewNavigationController.Host#markProjectDirty} has no caller in main. */
    private record HostMethodUncalled() implements Fact {
    }

    /** Every member holds. */
    private record AllOf(List<Fact> facts) implements Fact {
        AllOf {
            facts = List.copyOf(facts);
            if (facts.isEmpty()) {
                throw new IllegalArgumentException("an AllOf names at least one fact");
            }
        }
    }

    /** A studio-engineer ruling: the reason is the ruling itself. */
    private record Ruling() implements Fact {
    }

    private static final String UI = "com/benesquivelmusic/daw/app/ui/";
    private static final String POINTER_FILE =
            "canvas drags driven by ClipInteractionController's mouse handlers: pointer events the window's "
                    + "fence consumes";
    private static final String BINDER = "it dispatches only into the sink it is built with";
    private static final String HANDOFF = "the dirty mark handed to a controller whose own sites the scan checks";
    private static final String TAKE = "ruling R-1.4: the take the close waits for (ruling R1)";
    private static final String CORRECTIVE_ARM = "ruling R-1.4: the corrective disarm and re-arm on the raw sink";
    private static final String INSTALLATION =
            "an installation's own dirty mark: installations are the gate's first half";

    private static final List<Allowance> ALLOWANCES = List.of(
            new WholeFile(UI + "ClipFadeHandler.java", new PointerOnlyFile(), POINTER_FILE),
            new WholeFile(UI + "ClipTrimHandler.java", new PointerOnlyFile(), POINTER_FILE),
            new WholeFile(UI + "SlipToolHandler.java", new PointerOnlyFile(), POINTER_FILE),
            new WholeFile(UI + "CompToolHandler.java", new PointerOnlyFile(), POINTER_FILE),
            new WholeFile(UI + "MidiEditorView.java",
                    new NoCallInMain("\\.setUndoManager\\s*\\(\\s*[^)\\s]", Set.of(UI + "EditorView.java")),
                    "its UndoManager is never installed in production"),
            new WholeFile(UI + "vm/TrackControlBinder.java", new GatedSinkBinder(), BINDER),
            new WholeFile(UI + "vm/ChannelControlBinder.java", new GatedSinkBinder(), BINDER),
            new WholeFile(UI + "vm/SliderBinding.java", new GatedSinkBinder(), BINDER),
            new WholeFile(UI + "vm/TransportControlBinder.java", new GatedSinkBinder(), BINDER),
            new WholeFile(UI + "vm/HistoryControlBinder.java", new GatedSinkBinder(), BINDER),
            new WholeFile(UI + "vm/UndoHistoryDirtyBinder.java", new Ruling(),
                    "the history's dirty projection, after a change the gate or a correctness writer admitted"),
            new WholeFile(UI + "vm/command/CoreTransportIntentHandler.java",
                    new AllOf(List.of(new GatedSinkBinder(),
                            new NoCallInMain("\\bnew\\s+CoreTransportIntentHandler\\s*\\(",
                                    Set.of(UI + "TransportController.java")))),
                    "the transport intent handler, reached through the gated dispatchTransportCommand"),
            new WholeFile(UI + "vm/command/CoreHistoryIntentHandler.java",
                    new NoCallInMain("\\bnew\\s+CoreHistoryIntentHandler\\s*\\(", Set.of()),
                    "no production instance"),
            new MethodBody(UI + "ArrangementCanvasFactory.java", "create", new HandoffOnly(),
                    "the ruler's loop/punch mark and the Deps' markProjectDirty, handed over"),
            new MethodBody(UI + "MainController.java", "createTrackStripController", new HandoffOnly(), HANDOFF),
            new MethodBody(UI + "MainController.java", "createPluginViewController", new HandoffOnly(), HANDOFF),
            new MethodBody(UI + "MainController.java", "createClipEditController", new HandoffOnly(), HANDOFF),
            new MethodBody(UI + "MainController.java", "createRippleModeController", new HandoffOnly(), HANDOFF),
            new MethodBody(UI + "MainController.java", "createTrackCreationController", new HandoffOnly(), HANDOFF),
            new MethodBody(UI + "MainController.java", "createAudioImportController", new HandoffOnly(), HANDOFF),
            new MethodBody(UI + "TransportController.java",
                    "TransportController(DawProject, AudioEngine, UndoManager, NotificationBar, Label, Label, Label, "
                            + "Button, Button, BooleanSupplier, Supplier, Supplier, Consumer, BooleanSupplier, "
                            + "Supplier, Runnable, FxDispatcher)",
                    new HandoffOnly(),
                    "project::markDirty handed to CoreTransportIntentHandler, behind the gated transport sink"),
            new MethodBody(UI + "MainController.java", "markProjectDirty", new HostMethodUncalled(),
                    "dead: no caller"),
            new MethodBody(UI + "RecordCoordinator.java", "publishRecordedTake", new Ruling(), TAKE),
            new MethodBody(UI + "RecordCoordinator.java", "stopMidiRecording(Throwable)", new Ruling(), TAKE),
            new MethodBody(UI + "recording/InputRoutingGuard.java", "validateMutation", new Ruling(), CORRECTIVE_ARM),
            new MethodBody(UI + "recording/InputRoutingGuard.java", "disarmAfterAFailedValidation", new Ruling(),
                    CORRECTIVE_ARM),
            new MethodBody(UI + "MixerView.java", "healTrackMirrorsFromChannels", new Ruling(),
                    "ruling R-1.4: the mixer's mirror heal"),
            new MethodBody(UI + "ProjectLifecycleController.java", "installImportedSessionAfterClose", new Ruling(),
                    INSTALLATION),
            new MethodBody(UI + "ProjectLifecycleController.java", "installRestoredSnapshot", new Ruling(),
                    INSTALLATION),
            new MethodBody(UI + "TrackFreezeController.java", "rollBackCancelledBatch", new Ruling(),
                    "completes a cancel the user made on a freeze the gate admitted"));

    // ── the counts a non-vacuous scan reaches ────────────────────────────────

    private static final String SITES = "rule-1 sites";
    private static final String GUARDED = "sites accepted by a guarding read";
    private static final String POINTER = "sites accepted as pointer handlers";
    private static final String CALLER_GUARDED = "sites accepted in caller-guarded private methods";
    private static final String HISTORY_REPLAY = "sites excluded as history replay";
    private static final String METHOD_REFERENCE = "::markDirty / ::markProjectDirty sites";
    private static final String CHOOSE = "inline .choose( dialogs";
    private static final String DIRTY_RUNNABLE = "markProjectDirty.run( sites";
    private static final String TYPE_KEYED = "sites found through a typed identifier not named undoManager";
    private static final String BINDERS = "TrackControlBinder / ChannelControlBinder constructions";
    private static final String TRANSPORT_BINDERS = "TransportControlBinder constructions";
    private static final String HISTORY_BINDERS = "HistoryControlBinder constructions";
    private static final String STANDALONE = "TrackControlWiring.standalone calls";
    private static final String CORRECTION_MENTIONS = ".correctionSink() mentions";

    @Test
    void everyEditAWindowRaisesReadsTheProjectActionGate() throws IOException {
        Path javaRoot = SourceScanSupport.locateDawAppModule().resolve("src/main/java");
        assertThat(Files.isDirectory(javaRoot.resolve("com/benesquivelmusic/daw/app")))
                .as("daw-app Java sources must live under %s", javaRoot)
                .isTrue();
        List<SourceFile> files = load(javaRoot);

        Report report = new GateScan(files, ALLOWANCES).run();

        assertThat(files).as("the scan visits every daw-app main file").hasSizeGreaterThan(300);
        assertThat(report.count(SITES)).as(SITES).isGreaterThan(120);
        assertThat(report.count(GUARDED)).as(GUARDED).isGreaterThanOrEqualTo(60);
        assertThat(report.count(POINTER)).as(POINTER).isGreaterThanOrEqualTo(10);
        assertThat(report.count(CALLER_GUARDED)).as(CALLER_GUARDED).isGreaterThanOrEqualTo(1);
        assertThat(report.count(HISTORY_REPLAY)).as(HISTORY_REPLAY).isGreaterThanOrEqualTo(1);
        assertThat(report.count(METHOD_REFERENCE)).as(METHOD_REFERENCE).isGreaterThanOrEqualTo(2);
        assertThat(report.count(CHOOSE)).as(CHOOSE).isGreaterThanOrEqualTo(2);
        assertThat(report.count(DIRTY_RUNNABLE)).as(DIRTY_RUNNABLE).isGreaterThanOrEqualTo(2);
        assertThat(report.count(TYPE_KEYED)).as(TYPE_KEYED).isGreaterThanOrEqualTo(1);
        assertThat(report.count(BINDERS)).as(BINDERS).isGreaterThanOrEqualTo(4);
        assertThat(report.count(TRANSPORT_BINDERS)).as(TRANSPORT_BINDERS).isGreaterThanOrEqualTo(1);
        assertThat(report.count(HISTORY_BINDERS)).as(HISTORY_BINDERS).isGreaterThanOrEqualTo(1);
        assertThat(report.count(STANDALONE)).as(STANDALONE).isGreaterThanOrEqualTo(2);
        assertThat(report.count(CORRECTION_MENTIONS)).as(CORRECTION_MENTIONS).isGreaterThanOrEqualTo(2);
        assertThat(report.problems())
                .as("every allowance names a file and one body that exist, covers a site, and its fact holds")
                .isEmpty();
        assertThat(report.offenders())
                .as("PR #989 review 5711460812 — every edit a window raises reads the project-action gate "
                        + "where it is handled (allowances: %s)", ALLOWANCES.size())
                .isEmpty();
    }

    @Test
    void theScopeRuleCatchesEveryShapeAnUngatedEditCanTake() {
        // 1, 2 — the bare site, and the one idiom before it.
        assertOffender("1 an execute with no read", "void f() { undoManager.execute(a); }");
        assertClean("2 a guard, then the execute",
                "void f() { if (!projectActionsAllowed()) return; undoManager.execute(a); }");
        // 3 — a guarded method does not guard a handler it registers.
        assertOffender("3 a handler registered by a guarded method",
                "void f() { if (!projectActionsAllowed()) return; b.setOnAction(_ -> undoManager.execute(a)); }");
        // 4 — the guard at the top of a lambda block.
        assertClean("4 a guard at the top of a lambda block", """
                void f() {
                    b.setOnAction(_ -> {
                        if (!projectActionsAllowed.getAsBoolean()) {
                            return;
                        }
                        project.markDirty();
                    });
                }""");
        // 5, 6 — a dialog after the read makes it stale; a second read heals it.
        assertOffender("5 a dialog between the read and the site", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    dialog.showAndWait();
                    undoManager.execute(a);
                }""");
        assertClean("6 a second read after the dialog", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    dialog.showAndWait();
                    if (!projectActionsAllowed()) return;
                    undoManager.execute(a);
                }""");
        // 7 — intent sinks are keyed on the declared type.
        assertOffender("7 a Consumer<TrackCommand> accept",
                "Consumer<TrackCommand> sink; void f(TrackCommand c) { sink.accept(c); }");
        assertClean("7 a Consumer<String> accept",
                "Consumer<String> sink; void f(String c) { sink.accept(c); }");
        // 8 — the raw sink's accept is a site wherever it is.
        assertOffender("8 the correction sink", "void f(TrackCommand c) { w.correctionSink().accept(c); }");
        // 9 — history replay is reached only through a history site.
        assertClean("9 a guarded execute of an anonymous UndoableAction", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    undoManager.execute(new UndoableAction() {
                        public void execute() { project.markDirty(); }
                        public void undo() { project.markDirty(); }
                    });
                }""");
        assertClean("9 a named UndoableAction", """
                static final class A implements UndoableAction {
                    private final Consumer<TrackCommand> sink;
                    public void execute() { sink.accept(command); }
                    public void undo() { sink.accept(inverse); }
                }""");
        // 10 — comments and literals are not code.
        assertClean("10 a site in a comment and in a string",
                "void f() { /* undoManager.execute(a); */ log(\"project.markDirty()\"); } // undoManager.undo();");
        // 11, 11b — a switch rule is not a lambda.
        assertClean("11 a switch rule's expression", """
                void f(Object x) {
                    if (!projectActionsAllowed()) return;
                    switch (x) {
                        case A a -> undoManager.execute(a);
                        default -> { }
                    }
                }""");
        assertClean("11b a switch rule's block", """
                void f(Object x) {
                    if (!projectActionsAllowed()) return;
                    switch (x) {
                        case A a -> { undoManager.execute(a); }
                        default -> { }
                    }
                }""");
        // 12 — a declaration is not a site.
        assertClean("12 a declaration", "void markProjectDirty() {}");
        // 13 — a read in a sibling lambda guards nothing.
        assertOffender("13 a read in a sibling lambda", """
                void f() {
                    a.setOnAction(_ -> { if (!projectActionsAllowed()) return; });
                    b.setOnAction(_ -> { undoManager.execute(x); });
                }""");
        // 14 (hole i) — a read nested in a lambda does not guard the scope around it.
        assertOffender("14 a read inside a nested lambda", """
                void f() {
                    Runnable r = () -> { if (!projectActionsAllowed()) return; };
                    undoManager.execute(a);
                }""");
        // 15 (hole ii) — only the one idiom guards.
        assertOffender("15 a read that guards nothing",
                "void f() { if (projectActionsAllowed()) log(); undoManager.execute(a); }");
        assertOffender("15 a read into a local",
                "void f() { boolean ok = projectActionsAllowed(); undoManager.execute(a); }");
        assertOffender("15 a compound condition",
                "void f() { if (x || !projectActionsAllowed()) return; undoManager.execute(a); }");
        // 16 (hole iii) — a dialog behind same-file helpers is a dialog.
        assertOffender("16 a dialog two helpers deep", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    confirmAll();
                    undoManager.execute(a);
                }
                private void confirmAll() { ask(); }
                private void ask() { alert.showAndWait(); }""");
        assertClean("16 a read after the helper", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    confirmAll();
                    if (!projectActionsAllowed()) return;
                    undoManager.execute(a);
                }
                private void confirmAll() { ask(); }
                private void ask() { alert.showAndWait(); }""");
        assertClean("16 a helper that only registers a dialog's handler", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    install();
                    undoManager.execute(a);
                }
                private void install() { b.setOnAction(_ -> alert.showAndWait()); }""");
        // 16b — the input-port chooser seam is a dialog.
        assertOffender("16b a chooser after the read", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    chooser.choose(a, b);
                    undoManager.execute(x);
                }""");
        assertClean("16b a read after the chooser", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    chooser.choose(a, b);
                    if (!projectActionsAllowed()) return;
                    undoManager.execute(x);
                }""");
        // 17 — history sites are keyed on the declared type.
        assertOffender("17 a typed local", "void f() { UndoManager history = h(); history.execute(a); }");
        assertOffender("17 a Supplier of one", "Supplier<UndoManager> h; void f() { h.get().redo(); }");
        assertOffender("17 a record component",
                "record Deps(UndoManager journal) {} void f(Deps deps) { deps.journal().undo(); }");
        // 18 — method references are dirty sites.
        assertOffender("18 a reference in an ungated method", "void f() { Runnable r = project::markDirty; }");
        assertThat(scan("void f() { new Foo(project::markDirty); }",
                List.of(new MethodBody(FIXTURE, "f", new HandoffOnly(), "handed over"))))
                .as("18 a reference handed to a constructor is a hand-off")
                .satisfies(report -> {
                    assertThat(report.offenders()).isEmpty();
                    assertThat(report.problems()).isEmpty();
                });
        // 19 — pointer handlers, and only them.
        assertClean("19 a mouse-released handler",
                "void f() { slider.setOnMouseReleased(e -> undoManager.execute(a)); }");
        assertClean("19 a typed mouse event filter",
                "void f() { node.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> { undoManager.execute(a); }); }");
        assertOffender("19 the same in an action handler",
                "void f() { button.setOnAction(e -> undoManager.execute(a)); }");
        assertOffender("19 a private method a mouse handler and an action handler both call", """
                void f() {
                    canvas.setOnMousePressed(e -> edit());
                    button.setOnAction(e -> edit());
                }
                private void edit() { undoManager.execute(a); }""");
        assertClean("19 a private method a mouse registration names", """
                void f() { canvas.setOnMousePressed(this::onPressed); }
                private void onPressed(MouseEvent e) { undoManager.execute(a); }""");
        // 20 — caller-guarded private methods.
        assertClean("20 a private method called only after a guard", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    edit();
                }
                private void edit() { undoManager.execute(a); }""");
        assertOffender("20 one also called from an unguarded lambda", """
                void f() {
                    if (!projectActionsAllowed()) return;
                    edit();
                    b.setOnAction(_ -> edit());
                }
                private void edit() { undoManager.execute(a); }""");
        // 21 — a fact is evaluated, never trusted.
        assertThat(scan("void f() { b.setOnAction(_ -> { }); canvas.setOnMouseReleased(e -> undoManager.execute(a)); }",
                List.of(new WholeFile(FIXTURE, new PointerOnlyFile(), "pointer only"))).problems())
                .as("21 a pointer-only file with an action handler fails its fact")
                .anySatisfy(problem -> assertThat(problem).contains("fact fails"));
        assertThat(scan("void f() { run(new SetCompRegionAction()); undoManager.execute(a); }",
                List.of(new WholeFile(FIXTURE, new PointerOnlyFile(), "pointer only"))).problems())
                .as("21 SetCompRegionAction is not an action handler")
                .isEmpty();
        assertThat(hostMethodFixture("void f() { host.markProjectDirty(); }").problems())
                .as("21 a Host that calls markProjectDirty fails HostMethodUncalled")
                .anySatisfy(problem -> assertThat(problem).contains("fact fails"));
        assertThat(hostMethodFixture("interface Host { void markProjectDirty(); }").problems())
                .as("21 a Host that only declares it holds")
                .isEmpty();
        // 22 — rules 2 and 2b.
        assertOffender("22 a binder over the correction sink",
                "void f() { new TrackControlBinder(t, vm, c, cvm, wiring.correctionSink()); }");
        assertClean("22 a binder over the command sink",
                "void f() { new TrackControlBinder(t, vm, c, cvm, wiring.commandSink()); }");
        assertOffender("22 a standalone wiring without the gate",
                "void f() { TrackControlWiring.standalone(p, d, m, e, err); }");
        // 22b — each branch of rules 2, 2b and 3 alone: one construct, which only that branch reports, by its rule.
        assertOnlyRule("22b a binder over neither sink",
                "void f() { new TrackControlBinder(t, vm, c, cvm, sink); }",
                "rule 2 — a TrackControlBinder takes the gated sink");
        assertOnlyRule("22b SliderBinding.bind outside the binders",
                "void f() { SliderBinding.bind(slider, vm, sink); }",
                "rule 2 — only the binders call SliderBinding.bind");
        assertOnlyRule("22b a transport binder over an ungated dispatch", """
                void f() { new TransportControlBinder(controls, this::dispatchTransportCommand); }
                private void dispatchTransportCommand(TransportCommand command) { run(command); }""",
                "rule 2 — a TransportControlBinder dispatches through this::dispatchTransportCommand");
        assertClean("22b a transport binder over the gated dispatch", """
                void f() { new TransportControlBinder(controls, this::dispatchTransportCommand); }
                private void dispatchTransportCommand(TransportCommand command) {
                    if (!projectActionsAllowed()) return;
                    run(command);
                }""");
        assertOnlyRule("22b a transport binder over a lambda beside the gated dispatch", """
                void f() { new TransportControlBinder(controls, command -> run(command)); }
                private void dispatchTransportCommand(TransportCommand command) {
                    if (!projectActionsAllowed()) return;
                    run(command);
                }""",
                "rule 2 — a TransportControlBinder dispatches through this::dispatchTransportCommand");
        assertThat(scan("void f() { new TrackControlBinder(t, vm, c, cvm, wiring.commandSink(),"
                + " wiring.correctionSink()); }", List.of()).offenders())
                .as("22b a binder over the command sink and the correction sink offends rule 2, as well as rule 3")
                .anySatisfy(offender -> assertThat(offender).contains("rule 2 — a TrackControlBinder takes the gated"
                        + " sink"));
        assertOnlyRule("22b a wiring built outside TrackControlWiring",
                "void f() { new TrackControlWiring(registry, sink, sink, gate); }",
                "rule 2b — only TrackControlWiring builds a TrackControlWiring");
        assertOnlyRule("22b a standalone wiring without the gate",
                "void f() { TrackControlWiring.standalone(p, d, m, e, err); }",
                "rule 2b — a standalone wiring ends with the gate argument");
        assertOnlyRule("22b the raw sink outside its writers",
                "void f() { Consumer<TrackCommand> raw = wiring.correctionSink(); }",
                "rule 3 — the raw sink stays with");
        // 23 — an overloaded name must say which body.
        String overloads = """
                void stop() { log(); }
                private void stop(Throwable t) { project.markDirty(); }""";
        assertThat(scan(overloads, List.of(new MethodBody(FIXTURE, "stop", new Ruling(), "r"))).problems())
                .as("23 an unqualified overloaded name")
                .anySatisfy(problem -> assertThat(problem).contains("ambiguous allowance"));
        assertThat(scan(overloads, List.of(new MethodBody(FIXTURE, "stop(Throwable)", new Ruling(), "r"))))
                .as("23 the qualified name covers its body's site")
                .satisfies(report -> {
                    assertThat(report.problems()).isEmpty();
                    assertThat(report.offenders()).isEmpty();
                });
        assertThat(scan(overloads, List.of(new MethodBody(FIXTURE, "stop()", new Ruling(), "r"))).problems())
                .as("23 the other overload covers no site")
                .anySatisfy(problem -> assertThat(problem).contains("stale entry"));
        // 24 — a dirty Runnable's run.
        assertOffender("24 a dirty Runnable run",
                "private final Runnable markProjectDirty; void f() { markProjectDirty.run(); }");
        assertClean("24 a guard first",
                "private final Runnable markProjectDirty; void f() { if (!projectActionsAllowed()) return; markProjectDirty.run(); }");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static final String FIXTURE = "fixture/Fixture.java";

    private static Report scan(String body, List<Allowance> allowances) {
        String source = "final class Fixture {\n" + body + "\n}\n";
        return new GateScan(List.of(new SourceFile(FIXTURE, blank(source))), allowances).run();
    }

    private static Report hostMethodFixture(String viewNavigationBody) {
        String navigation = "final class ViewNavigationController {\nprivate final Host host;\n"
                + viewNavigationBody + "\n}\n";
        String main = "final class MainController {\npublic void markProjectDirty() { project.markDirty(); }\n}\n";
        return new GateScan(List.of(
                new SourceFile("fixture/ViewNavigationController.java", blank(navigation)),
                new SourceFile("fixture/MainController.java", blank(main))),
                List.of(new MethodBody("fixture/MainController.java", "markProjectDirty",
                        new HostMethodUncalled(), "dead"))).run();
    }

    private static void assertOffender(String shape, String body) {
        assertThat(scan(body, List.of()).offenders()).as(shape + " is an offender").isNotEmpty();
    }

    private static void assertClean(String shape, String body) {
        assertThat(scan(body, List.of()).offenders()).as(shape + " is clean").isEmpty();
    }

    /** {@code body} has exactly one offender, and it names {@code rule}: that rule's branch alone sees it. */
    private static void assertOnlyRule(String shape, String body, String rule) {
        assertThat(scan(body, List.of()).offenders()).as(shape + " is an offender of that rule alone")
                .singleElement().satisfies(offender -> assertThat(offender).contains(rule));
    }

    private static List<SourceFile> load(Path javaRoot) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(javaRoot)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String key = javaRoot.relativize(file).toString().replace('\\', '/');
                files.add(new SourceFile(key, blank(Files.readString(file, StandardCharsets.UTF_8))));
            }
        }
        return files;
    }

    // ── the scan ─────────────────────────────────────────────────────────────

    /** One scanned file: its key relative to {@code src/main/java} and its code, comments and literals blanked. */
    private record SourceFile(String key, String code) {
        SourceFile {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(code, "code must not be null");
        }
    }

    /** What the scan found: the offenders, the allowance problems and the counts. */
    private record Report(List<String> offenders, List<String> problems, Map<String, Integer> counts) {
        Report {
            offenders = List.copyOf(offenders);
            problems = List.copyOf(problems);
            counts = Map.copyOf(counts);
        }

        int count(String name) {
            return counts.getOrDefault(name, 0);
        }
    }

    private enum SiteKind { HISTORY, INTENT, DIRTY }

    /** An edit site: where it starts, where its argument list ends, its text and how it was found. */
    private record Site(SiteKind kind, int start, int end, String text, boolean typeKeyed) {
    }

    private enum ScopeKind { METHOD, LAMBDA_BLOCK, EXPRESSION_LAMBDA, FILE }

    /**
     * The innermost method body, lambda block or expression lambda holding a
     * position. {@code start} is the body's {@code &#123;} (an expression
     * lambda's arrow); {@code end} is its closing brace (the expression's
     * terminator); {@code arrow} is a lambda's arrow, else {@code -1}.
     */
    private record Scope(ScopeKind kind, int start, int end, String name, int arrow) {
    }

    /** A method or constructor body the file declares. */
    private record MethodDecl(String name, List<String> parameterTypes, int nameStart, int bodyStart, int bodyEnd,
                              boolean isPrivate) {
    }

    private static final Pattern HISTORY_DECLARATION = Pattern.compile("\\bUndoManager\\s+(\\w+)");
    private static final Pattern HISTORY_SUPPLIER_DECLARATION = Pattern.compile(
            "\\bSupplier\\s*<\\s*(?:[\\w.]*\\.)?UndoManager\\s*>\\s+(\\w+)");
    private static final Pattern INTENT_DECLARATION = Pattern.compile(
            "\\bConsumer\\s*<\\s*(?:\\?\\s*super\\s+)?(?:[\\w.]*\\.)?"
                    + "(?:TrackCommand|TransportCommand|HistoryCommand)\\s*>\\s+(\\w+)");
    private static final Pattern CORRECTION_ACCEPT = Pattern.compile(
            "\\.\\s*correctionSink\\s*\\(\\s*\\)\\s*\\.\\s*accept\\s*\\(");
    private static final Pattern CORRECTION_MENTION = Pattern.compile("\\.\\s*correctionSink\\s*\\(\\s*\\)");
    private static final Pattern DIRTY = Pattern.compile(
            "\\b(?:markDirty|markProjectDirty)\\s*\\(\\s*\\)"
                    + "|\\b(?:markDirty|markProjectDirty)\\s*\\.\\s*run\\s*\\("
                    + "|::\\s*(?:markDirty|markProjectDirty)\\b");
    private static final Pattern HISTORY_TYPE = Pattern.compile(
            "(?:new\\s+UndoableAction\\s*\\(\\s*\\)"
                    + "|\\bimplements\\s+[\\w.,<>\\s]*\\bUndoableAction\\b[\\w.,<>\\s]*)\\s*\\{");
    private static final Pattern DIALOG = Pattern.compile(
            "\\.\\s*(?:showAndWait|showOpenDialog|showSaveDialog|showOpenMultipleDialog|showDialog|choose)\\s*\\(");
    private static final Pattern CHOOSE_DIALOG = Pattern.compile("\\.\\s*choose\\s*\\(");
    private static final String GATE = "(?:[\\w.]+(?:\\(\\s*\\))?\\s*\\.\\s*)*projectActionsAllowed\\s*"
            + "(?:\\(\\s*\\)(?:\\s*\\.\\s*getAsBoolean\\s*\\(\\s*\\))?|\\.\\s*getAsBoolean\\s*\\(\\s*\\))";
    private static final Pattern REFUSAL_CONDITION = Pattern.compile("!\\s*" + GATE);
    private static final Pattern THROWS_TAIL = Pattern.compile("\\)\\s*throws\\s+[\\w.,\\s<>]+$");
    private static final Pattern POINTER_SETTER = Pattern.compile("setOn(?:Mouse|Drag|Scroll)\\w*");
    private static final Pattern POINTER_EVENT_TYPE = Pattern.compile(
            "(?:MouseEvent|MouseDragEvent|DragEvent|ScrollEvent)\\s*\\.");
    private static final Pattern ACTION_HANDLER = Pattern.compile(
            "\\bsetOnAction\\s*\\(|\\bonAction\\b|\\bActionEvent\\b|\\bexecuteAccessibleAction\\b");
    private static final Pattern ONE_DIRTY_MARK = Pattern.compile(
            "(?:[\\w]+(?:\\s*\\(\\s*\\))?\\s*\\.\\s*)*(?:markDirty|markProjectDirty)"
                    + "\\s*(?:\\(\\s*\\)|\\.\\s*run\\s*\\(\\s*\\))");
    private static final Set<String> CONTROL = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "else", "do", "super", "this");

    private static final class GateScan {

        private final List<SourceFile> sources;
        private final List<Allowance> allowances;
        private final Map<String, FileModel> models = new LinkedHashMap<>();
        private final List<String> offenders = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
        private final Map<String, Integer> counts = new HashMap<>();
        private final List<String> sinkRuleViolations = new ArrayList<>();

        GateScan(List<SourceFile> sources, List<Allowance> allowances) {
            this.sources = List.copyOf(sources);
            this.allowances = List.copyOf(allowances);
            for (SourceFile source : this.sources) {
                models.put(source.key(), new FileModel(source.key(), source.code()));
            }
        }

        Report run() {
            Map<Allowance, List<Site>> covered = new LinkedHashMap<>();
            Map<Allowance, int[]> ranges = new LinkedHashMap<>();
            for (Allowance allowance : allowances) {
                covered.put(allowance, new ArrayList<>());
                resolve(allowance).ifPresent(range -> ranges.put(allowance, range));
            }
            for (FileModel file : models.values()) {
                Matcher choose = CHOOSE_DIALOG.matcher(file.code);
                while (choose.find()) {
                    add(CHOOSE);
                }
                scanSites(file, ranges, covered);
            }
            checkSinkRules();
            for (Allowance allowance : allowances) {
                if (!ranges.containsKey(allowance)) {
                    continue;
                }
                if (covered.get(allowance).isEmpty()) {
                    problems.add(describe(allowance) + " covers no site — stale entry, remove it (reason on file: "
                            + allowance.reason() + ")");
                }
                Optional<String> failure = failure(allowance.fact(), allowance, covered.get(allowance));
                failure.ifPresent(why -> problems.add(describe(allowance) + ": its fact fails — " + why));
            }
            offenders.sort(String::compareTo);
            return new Report(offenders, problems, counts);
        }

        private void add(String count) {
            counts.merge(count, 1, Integer::sum);
        }

        // ── rule 1 ───────────────────────────────────────────────────────────

        private void scanSites(FileModel file, Map<Allowance, int[]> ranges, Map<Allowance, List<Site>> covered) {
            List<Site> sites = file.sites();
            for (Site site : sites) {
                if (nestedInAnother(site, sites)) {
                    continue;
                }
                add(SITES);
                if (site.typeKeyed()) {
                    add(TYPE_KEYED);
                }
                if (site.text().startsWith("::")) {
                    add(METHOD_REFERENCE);
                }
                if (site.text().replaceAll("\\s", "").startsWith("markProjectDirty.run(")) {
                    add(DIRTY_RUNNABLE);
                }
                if (insideHistoryType(file.code, site.start())) {
                    add(HISTORY_REPLAY);
                    continue;
                }
                List<Allowance> coveringAllowances = new ArrayList<>();
                for (Map.Entry<Allowance, int[]> range : ranges.entrySet()) {
                    if (range.getKey().key().equals(file.key)
                            && range.getValue()[0] <= site.start() && site.start() < range.getValue()[1]) {
                        coveringAllowances.add(range.getKey());
                        covered.get(range.getKey()).add(site);
                    }
                }
                Scope scope = scopeOf(file.code, site.start());
                if (file.guardedAt(scope, site.start())) {
                    add(GUARDED);
                } else if (file.pointerScope(scope)) {
                    add(POINTER);
                } else if (scope.kind() == ScopeKind.METHOD && file.callerGuarded().contains(scope.name())) {
                    add(CALLER_GUARDED);
                } else if (coveringAllowances.isEmpty()) {
                    offenders.add(file.key + ":" + lineOf(file.code, site.start()) + " " + site.text().strip()
                            + " — read the project-action gate (an early return on projectActionsAllowed) at the "
                            + "top of the innermost method or lambda that holds this edit, after any dialog it shows "
                            + "(PR #989 review 5711460812; INTERACTION_COMPLETENESS §2.8)");
                }
            }
        }

        private static boolean nestedInAnother(Site site, List<Site> sites) {
            for (Site other : sites) {
                if (other != site && other.start() < site.start() && site.start() < other.end()) {
                    return true;
                }
            }
            return false;
        }

        // ── rules 2, 2b and 3 ────────────────────────────────────────────────

        private void checkSinkRules() {
            for (FileModel file : models.values()) {
                String code = file.code;
                for (String binder : List.of("TrackControlBinder", "ChannelControlBinder")) {
                    Matcher m = Pattern.compile("\\bnew\\s+" + binder + "\\s*\\(").matcher(code);
                    while (m.find()) {
                        add(BINDERS);
                        String args = code.substring(m.end(), matching(code, m.end() - 1, '(', ')'))
                                .replaceAll("\\s", "");
                        boolean gated = args.contains(".commandSink()") || args.endsWith("undoableRenames(wiring)");
                        if (!gated || args.contains(".correctionSink()")) {
                            sinkRule(file, m.start(), "rule 2 — a " + binder + " takes the gated sink, "
                                    + "commandSink() or the mixer's recorded renames, never correctionSink()");
                        }
                    }
                }
                binderDispatch(file, "TransportControlBinder", "dispatchTransportCommand", TRANSPORT_BINDERS);
                binderDispatch(file, "HistoryControlBinder", "dispatchHistoryCommand", HISTORY_BINDERS);
                if (!file.key.endsWith("vm/TrackControlBinder.java") && !file.key.endsWith("vm/ChannelControlBinder.java")) {
                    Matcher bind = Pattern.compile("\\bSliderBinding\\s*\\.\\s*bind\\s*\\(").matcher(code);
                    while (bind.find()) {
                        sinkRule(file, bind.start(), "rule 2 — only the binders call SliderBinding.bind");
                    }
                }
                if (!file.key.endsWith("vm/TrackControlWiring.java")) {
                    Matcher wiring = Pattern.compile("\\bnew\\s+TrackControlWiring\\s*\\(").matcher(code);
                    while (wiring.find()) {
                        sinkRule(file, wiring.start(), "rule 2b — only TrackControlWiring builds a TrackControlWiring");
                    }
                    Matcher standalone = Pattern.compile("\\bTrackControlWiring\\s*\\.\\s*standalone\\s*\\(")
                            .matcher(code);
                    while (standalone.find()) {
                        add(STANDALONE);
                        String args = code.substring(standalone.end(),
                                matching(code, standalone.end() - 1, '(', ')'));
                        List<String> split = topLevelSplit(args);
                        String last = split.isEmpty() ? "" : split.getLast().strip();
                        if (!last.equals("this::projectActionsAllowed") && !last.equals("projectActionsAllowed")) {
                            sinkRule(file, standalone.start(),
                                    "rule 2b — a standalone wiring ends with the gate argument");
                        }
                    }
                }
                Matcher mention = CORRECTION_MENTION.matcher(code);
                while (mention.find()) {
                    add(CORRECTION_MENTIONS);
                    boolean inWriter = file.key.endsWith("ui/MixerView.java")
                            && Stream.of("healTrackMirrorsFromChannels", "undoableRenames").anyMatch(name ->
                                    file.methods().stream().anyMatch(method -> method.name().equals(name)
                                            && method.bodyStart() < mention.start()
                                            && mention.start() < method.bodyEnd()));
                    if (!inWriter) {
                        sinkRule(file, mention.start(), "rule 3 — the raw sink stays with its writers "
                                + "(the mixer's mirror heal and its recorded renames)");
                    }
                }
            }
        }

        private void binderDispatch(FileModel file, String binder, String dispatcher, String count) {
            Matcher m = Pattern.compile("\\bnew\\s+" + binder + "\\s*\\(").matcher(file.code);
            while (m.find()) {
                add(count);
                String args = file.code.substring(m.end(), matching(file.code, m.end() - 1, '(', ')'))
                        .replaceAll("\\s", "");
                boolean gatedBody = file.methods().stream()
                        .filter(method -> method.name().equals(dispatcher))
                        .anyMatch(method -> file.code.substring(method.bodyStart(), method.bodyEnd())
                                .contains("projectActionsAllowed()"));
                if (!args.contains("this::" + dispatcher) || !gatedBody) {
                    sinkRule(file, m.start(), "rule 2 — a " + binder + " dispatches through this::" + dispatcher
                            + ", whose body reads projectActionsAllowed()");
                }
            }
        }

        private void sinkRule(FileModel file, int at, String rule) {
            String where = file.key + ":" + lineOf(file.code, at) + " " + rule + " (PR #989 review 5711460812)";
            offenders.add(where);
            sinkRuleViolations.add(where);
        }

        // ── allowances and facts ─────────────────────────────────────────────

        private Optional<int[]> resolve(Allowance allowance) {
            FileModel file = models.get(allowance.key());
            if (file == null) {
                problems.add(describe(allowance) + " names a file that does not exist");
                return Optional.empty();
            }
            return switch (allowance) {
                case WholeFile _ -> Optional.of(new int[] {0, file.code.length()});
                case MethodBody body -> {
                    List<MethodDecl> matches = file.bodiesNamed(body.method());
                    if (matches.isEmpty()) {
                        problems.add(describe(allowance) + " names no body in the file");
                        yield Optional.empty();
                    }
                    if (matches.size() > 1) {
                        problems.add(describe(allowance) + " is an ambiguous allowance: " + matches.size()
                                + " bodies are named " + body.method() + " — name its parameter types");
                        yield Optional.empty();
                    }
                    yield Optional.of(new int[] {matches.getFirst().bodyStart(), matches.getFirst().bodyEnd()});
                }
            };
        }

        private Optional<String> failure(Fact fact, Allowance allowance, List<Site> sites) {
            return switch (fact) {
                case Ruling _ -> Optional.empty();
                case PointerOnlyFile _ -> {
                    Matcher handler = ACTION_HANDLER.matcher(models.get(allowance.key()).code);
                    yield handler.find()
                            ? Optional.of("the file has an action handler: " + handler.group())
                            : Optional.empty();
                }
                case NoCallInMain(String pattern, Set<String> exceptKeys) -> {
                    Pattern compiled = Pattern.compile(pattern);
                    for (FileModel file : models.values()) {
                        if (exceptKeys.contains(file.key)) {
                            continue;
                        }
                        Matcher m = compiled.matcher(file.code);
                        if (m.find()) {
                            yield Optional.of(pattern + " matches at " + file.key + ":" + lineOf(file.code, m.start()));
                        }
                    }
                    yield Optional.empty();
                }
                case GatedSinkBinder _ -> {
                    if (!sinkRuleViolations.isEmpty()) {
                        yield Optional.of("rules 2, 2b or 3 are broken: " + sinkRuleViolations);
                    }
                    if (counts.getOrDefault(BINDERS, 0) < 4 || counts.getOrDefault(TRANSPORT_BINDERS, 0) < 1
                            || counts.getOrDefault(HISTORY_BINDERS, 0) < 1 || counts.getOrDefault(STANDALONE, 0) < 2) {
                        yield Optional.of("rules 2 and 2b found too few binders or standalone wirings to prove "
                                + "anything: " + counts);
                    }
                    yield Optional.empty();
                }
                case HandoffOnly _ -> {
                    FileModel file = models.get(allowance.key());
                    for (Site site : sites) {
                        if (!file.handedOver(site)) {
                            yield Optional.of("line " + lineOf(file.code, site.start()) + " " + site.text().strip()
                                    + " is not a dirty mark handed over as an argument");
                        }
                    }
                    yield Optional.empty();
                }
                case HostMethodUncalled _ -> hostMethodCalled();
                case AllOf(List<Fact> facts) -> {
                    for (Fact member : facts) {
                        Optional<String> why = failure(member, allowance, sites);
                        if (why.isPresent()) {
                            yield Optional.of(member + ": " + why.get());
                        }
                    }
                    yield Optional.empty();
                }
            };
        }

        /** HostMethodUncalled's three checks; empty when they hold. */
        private Optional<String> hostMethodCalled() {
            int declared = 0;
            for (FileModel file : models.values()) {
                boolean navigation = file.key.endsWith("ViewNavigationController.java");
                Pattern declaration = Pattern.compile(navigation
                        ? "\\b(?:ViewNavigationController\\s*\\.\\s*)?Host\\s+(\\w+)\\b"
                        : "\\bViewNavigationController\\s*\\.\\s*Host\\s+(\\w+)\\b");
                Matcher m = declaration.matcher(file.code);
                Set<String> ids = new LinkedHashSet<>();
                while (m.find()) {
                    ids.add(m.group(1));
                }
                if (navigation) {
                    declared += ids.size();
                }
                for (String id : ids) {
                    Matcher call = Pattern.compile("\\b" + Pattern.quote(id)
                            + "\\s*(?:\\.\\s*markProjectDirty\\s*\\(|::\\s*markProjectDirty\\b)").matcher(file.code);
                    if (call.find()) {
                        return Optional.of(file.key + ":" + lineOf(file.code, call.start())
                                + " calls the Host's markProjectDirty");
                    }
                }
                if (file.key.endsWith("MainController.java")) {
                    Matcher call = Pattern.compile("\\bmarkProjectDirty\\s*\\(").matcher(file.code);
                    while (call.find()) {
                        if (!precededByVoid(file.code, call.start())) {
                            return Optional.of(file.key + ":" + lineOf(file.code, call.start())
                                    + " calls markProjectDirty");
                        }
                    }
                }
            }
            return declared == 0
                    ? Optional.of("ViewNavigationController.java declares no Host identifier: the check proves nothing")
                    : Optional.empty();
        }

        private static String describe(Allowance allowance) {
            return switch (allowance) {
                case WholeFile whole -> "allowance " + whole.key();
                case MethodBody body -> "allowance " + body.key() + "#" + body.method();
            };
        }
    }

    // ── one file's model ─────────────────────────────────────────────────────

    private static final class FileModel {

        private final String key;
        private final String code;
        private List<Site> sites;
        private List<MethodDecl> methods;
        private Set<String> dialogMethods;
        private Set<String> pointerMethods;
        private Set<String> callerGuarded;
        private final Map<Integer, String> ownTexts = new HashMap<>();

        FileModel(String key, String code) {
            this.key = key;
            this.code = code;
        }

        List<Site> sites() {
            if (sites == null) {
                List<Site> found = new ArrayList<>();
                Set<String> histories = new LinkedHashSet<>();
                for (Pattern declaration : List.of(HISTORY_DECLARATION, HISTORY_SUPPLIER_DECLARATION)) {
                    Matcher m = declaration.matcher(code);
                    while (m.find()) {
                        histories.add(m.group(1));
                    }
                }
                histories.add("undoManager");
                for (String id : histories) {
                    addSites(found, SiteKind.HISTORY, Pattern.compile("\\b" + Pattern.quote(id)
                            + "\\b(?:\\s*\\(\\s*\\))?(?:\\s*\\.\\s*get\\s*\\(\\s*\\))?\\s*\\.\\s*"
                            + "(?:execute|undo|redo|goToHistoryIndex|clearRedoStack)\\s*\\("), !id.equals("undoManager"));
                }
                Matcher intents = INTENT_DECLARATION.matcher(code);
                Set<String> sinks = new LinkedHashSet<>();
                while (intents.find()) {
                    sinks.add(intents.group(1));
                }
                for (String sink : sinks) {
                    addSites(found, SiteKind.INTENT,
                            Pattern.compile("\\b" + Pattern.quote(sink) + "\\s*\\.\\s*accept\\s*\\("), true);
                }
                addSites(found, SiteKind.INTENT, CORRECTION_ACCEPT, false);
                Matcher dirty = DIRTY.matcher(code);
                while (dirty.find()) {
                    if (!precededByVoid(code, dirty.start())) {
                        found.add(site(SiteKind.DIRTY, dirty, false));
                    }
                }
                Map<Integer, Site> byStart = new LinkedHashMap<>();
                found.stream().sorted((a, b) -> Integer.compare(a.start(), b.start()))
                        .forEach(s -> byStart.putIfAbsent(s.start(), s));
                sites = List.copyOf(byStart.values());
            }
            return sites;
        }

        private void addSites(List<Site> found, SiteKind kind, Pattern pattern, boolean typeKeyed) {
            Matcher m = pattern.matcher(code);
            while (m.find()) {
                found.add(site(kind, m, typeKeyed));
            }
        }

        private Site site(SiteKind kind, Matcher m, boolean typeKeyed) {
            // A site that ends in its own '(' spans its argument list.
            int end = code.charAt(m.end() - 1) == '(' ? matching(code, m.end() - 1, '(', ')') : m.end();
            return new Site(kind, m.start(), end, m.group(), typeKeyed);
        }

        List<MethodDecl> methods() {
            if (methods == null) {
                List<MethodDecl> found = new ArrayList<>();
                for (int i = 0; i < code.length(); i++) {
                    if (code.charAt(i) != '{') {
                        continue;
                    }
                    int k = skipBackWs(code, i - 1);
                    if (k >= 1 && code.charAt(k) == '>' && code.charAt(k - 1) == '-') {
                        continue;
                    }
                    methodDeclaration(code, i).ifPresent(found::add);
                }
                methods = List.copyOf(found);
            }
            return methods;
        }

        List<MethodDecl> bodiesNamed(String allowanceMethod) {
            int open = allowanceMethod.indexOf('(');
            String name = open < 0 ? allowanceMethod : allowanceMethod.substring(0, open).strip();
            Optional<List<String>> types = open < 0 ? Optional.empty() : Optional.of(topLevelSplit(
                    allowanceMethod.substring(open + 1, allowanceMethod.lastIndexOf(')'))).stream()
                    .map(String::strip).filter(t -> !t.isEmpty()).toList());
            return methods().stream()
                    .filter(method -> method.name().equals(name))
                    .filter(method -> types.isEmpty() || method.parameterTypes().equals(types.get()))
                    .toList();
        }

        /** The scope's text, with every nested lambda body, anonymous-class body and local type body blanked. */
        String ownText(Scope scope) {
            return ownTexts.computeIfAbsent(scope.start(), _ -> blankNested(code, interiorStart(scope), scope.end()));
        }

        Set<String> dialogMethods() {
            if (dialogMethods == null) {
                Set<String> showing = new LinkedHashSet<>();
                for (MethodDecl method : methods()) {
                    String own = methodOwnText(method);
                    if (DIALOG.matcher(own.substring(method.bodyStart(), method.bodyEnd())).find()) {
                        showing.add(method.name());
                    }
                }
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (MethodDecl method : methods()) {
                        if (!showing.contains(method.name())
                                && callsAny(methodOwnText(method), method.bodyStart(), method.bodyEnd(), showing)) {
                            showing.add(method.name());
                            changed = true;
                        }
                    }
                }
                dialogMethods = showing;
            }
            return dialogMethods;
        }

        private String methodOwnText(MethodDecl method) {
            return ownText(new Scope(ScopeKind.METHOD, method.bodyStart(), method.bodyEnd(), method.name(), -1));
        }

        private static boolean callsAny(String text, int from, int to, Set<String> names) {
            String region = text.substring(from, Math.min(to, text.length()));
            for (String name : names) {
                if (Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\s*\\(|\\bthis\\s*\\.\\s*"
                        + Pattern.quote(name) + "\\s*\\(").matcher(region).find()) {
                    return true;
                }
            }
            return false;
        }

        /** Rule 1 (a): a guarding read at the scope's top level before {@code pos}, with no dialog after it. */
        boolean guardedAt(Scope scope, int pos) {
            if (scope.kind() != ScopeKind.METHOD && scope.kind() != ScopeKind.LAMBDA_BLOCK) {
                return false;
            }
            String own = ownText(scope);
            int last = -1;
            for (int[] guard : guards(own, scope)) {
                if (guard[1] <= pos) {
                    last = Math.max(last, guard[1]);
                }
            }
            if (last < 0) {
                return false;
            }
            return !DIALOG.matcher(own.substring(last, pos)).find() && !callsAny(own, last, pos, dialogMethods());
        }

        /** Rule 1 (c): the scope runs only as a pointer handler. */
        boolean pointerScope(Scope scope) {
            return switch (scope.kind()) {
                case LAMBDA_BLOCK, EXPRESSION_LAMBDA ->
                        isPointerRegistrationArgument(code, lambdaParameterStart(code, scope.arrow()));
                case METHOD -> pointerMethods().contains(scope.name());
                case FILE -> false;
            };
        }

        Set<String> pointerMethods() {
            if (pointerMethods == null) {
                fixPoint();
            }
            return pointerMethods;
        }

        Set<String> callerGuarded() {
            if (callerGuarded == null) {
                fixPoint();
            }
            return callerGuarded;
        }

        /** Rules 1 (c) and (d) for private methods, to their fixed point. */
        private void fixPoint() {
            pointerMethods = new LinkedHashSet<>();
            callerGuarded = new LinkedHashSet<>();
            Set<String> privateNames = new LinkedHashSet<>();
            Set<String> publicNames = new LinkedHashSet<>();
            for (MethodDecl method : methods()) {
                (method.isPrivate() ? privateNames : publicNames).add(method.name());
            }
            privateNames.removeAll(publicNames);
            Set<Integer> declarations = new LinkedHashSet<>();
            for (MethodDecl method : methods()) {
                declarations.add(method.nameStart());
            }
            Map<String, List<Integer>> calls = new HashMap<>();
            Map<String, List<Integer>> references = new HashMap<>();
            for (String name : privateNames) {
                List<Integer> callPositions = new ArrayList<>();
                Matcher call = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\s*\\(|(?<=this\\s?\\.\\s?)"
                        + Pattern.quote(name) + "\\s*\\(").matcher(code);
                while (call.find()) {
                    if (!declarations.contains(call.start()) && !"new".equals(wordBefore(code, call.start()))) {
                        callPositions.add(call.start());
                    }
                }
                calls.put(name, callPositions);
                List<Integer> referencePositions = new ArrayList<>();
                Matcher reference = Pattern.compile("::\\s*" + Pattern.quote(name) + "\\b").matcher(code);
                while (reference.find()) {
                    referencePositions.add(reference.start());
                }
                references.put(name, referencePositions);
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String name : privateNames) {
                    List<Integer> callPositions = calls.get(name);
                    List<Integer> referencePositions = references.get(name);
                    if (!pointerMethods.contains(name) && !(callPositions.isEmpty() && referencePositions.isEmpty())
                            && callPositions.stream().allMatch(pos -> pointerScope(scopeOf(code, pos)))
                            && referencePositions.stream().allMatch(pos -> isPointerRegistrationArgument(code,
                                    expressionStartBack(code, skipBackWs(code, pos - 1))))) {
                        pointerMethods.add(name);
                        changed = true;
                    }
                    if (!callerGuarded.contains(name) && !callPositions.isEmpty() && referencePositions.isEmpty()
                            && callPositions.stream().allMatch(pos -> {
                                Scope scope = scopeOf(code, pos);
                                return guardedAt(scope, pos) || pointerScope(scope)
                                        || (scope.kind() == ScopeKind.METHOD && callerGuarded.contains(scope.name()));
                            })) {
                        callerGuarded.add(name);
                        changed = true;
                    }
                }
            }
        }

        /** HandoffOnly: the site is a dirty mark handed over as a direct argument of a call or constructor. */
        boolean handedOver(Site site) {
            if (site.text().startsWith("::")) {
                int receiver = expressionStartBack(code, skipBackWs(code, site.start() - 1));
                return directArgument(receiver, site.end());
            }
            Scope scope = scopeOf(code, site.start());
            if (scope.kind() != ScopeKind.EXPRESSION_LAMBDA) {
                return false;
            }
            String body = code.substring(scope.arrow() + 2, scope.end()).strip();
            return ONE_DIRTY_MARK.matcher(body).matches()
                    && directArgument(lambdaParameterStart(code, scope.arrow()), scope.end());
        }

        private boolean directArgument(int start, int end) {
            int before = skipBackWs(code, start - 1);
            int after = skipFwdWs(code, end);
            return before >= 0 && after < code.length()
                    && (code.charAt(before) == '(' || code.charAt(before) == ',')
                    && (code.charAt(after) == ',' || code.charAt(after) == ')');
        }
    }

    // ── the scope walk ───────────────────────────────────────────────────────

    /** The innermost method body, lambda block or expression lambda holding {@code pos}. */
    private static Scope scopeOf(String code, int pos) {
        int paren = 0;
        int brace = 0;
        boolean blocked = false;
        for (int i = pos - 1; i >= 0; i--) {
            char ch = code.charAt(i);
            if (ch == ')') {
                paren++;
            } else if (ch == '(') {
                if (paren == 0) {
                    blocked = false; // the argument list the position is inside
                } else {
                    paren--;
                }
            } else if ((ch == ',' || ch == ';') && paren == 0 && brace == 0) {
                blocked = true; // a sibling argument or an earlier statement
            } else if (ch == '}') {
                brace++;
            } else if (ch == '{') {
                if (brace > 0) {
                    brace--;
                    continue;
                }
                blocked = false;
                int k = skipBackWs(code, i - 1);
                if (k >= 1 && code.charAt(k) == '>' && code.charAt(k - 1) == '-') {
                    if (isSwitchRule(code, k - 1)) {
                        continue; // a switch rule's block is transparent
                    }
                    return new Scope(ScopeKind.LAMBDA_BLOCK, i, matching(code, i, '{', '}'), "lambda", k - 1);
                }
                Optional<MethodDecl> method = methodDeclaration(code, i);
                if (method.isPresent()) {
                    return new Scope(ScopeKind.METHOD, i, method.get().bodyEnd(), method.get().name(), -1);
                }
                // a class body, a control block or an initializer: keep walking outward
            } else if (ch == '>' && i > 0 && code.charAt(i - 1) == '-' && paren == 0 && brace == 0 && !blocked
                    && !isSwitchRule(code, i - 1)) {
                return new Scope(ScopeKind.EXPRESSION_LAMBDA, i - 1, expressionEnd(code, i + 1), "lambda", i - 1);
            }
        }
        return new Scope(ScopeKind.FILE, 0, code.length(), "file", -1);
    }

    /** The method or constructor whose body opens at {@code brace}, if it is one. */
    private static Optional<MethodDecl> methodDeclaration(String code, int brace) {
        int k = skipBackWs(code, brace - 1);
        if (k < 0) {
            return Optional.empty();
        }
        int close = k;
        int from = Math.max(0, k - 300);
        Matcher thrown = THROWS_TAIL.matcher(code.substring(from, k + 1));
        if (thrown.find()) {
            close = from + thrown.start();
        }
        if (code.charAt(close) != ')') {
            return Optional.empty();
        }
        int open = matchingBack(code, close);
        int j = skipBackWs(code, open - 1);
        int end = j + 1;
        while (j >= 0 && Character.isJavaIdentifierPart(code.charAt(j))) {
            j--;
        }
        String name = code.substring(j + 1, end);
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) || CONTROL.contains(name)) {
            return Optional.empty();
        }
        String before = wordBefore(code, j + 1);
        if (before.equals("new") || before.equals("record")) {
            return Optional.empty(); // an anonymous class body or a record body
        }
        int header = j;
        while (header >= 0 && ";{}".indexOf(code.charAt(header)) < 0) {
            header--;
        }
        boolean isPrivate = Pattern.compile("\\bprivate\\b").matcher(code.substring(header + 1, j + 1)).find();
        List<String> types = new ArrayList<>();
        for (String parameter : topLevelSplit(code.substring(open + 1, close))) {
            String p = parameter.replaceAll("@\\w+(?:\\([^)]*\\))?", " ").replaceAll("\\bfinal\\b", " ").strip();
            if (p.isEmpty()) {
                continue;
            }
            String type = p.substring(0, p.lastIndexOf(' ') < 0 ? p.length() : p.lastIndexOf(' ')).strip();
            String erased = type;
            String previous;
            do {
                previous = erased;
                erased = erased.replaceAll("<[^<>]*>", "");
            } while (!erased.equals(previous));
            erased = erased.replaceAll("\\s", "");
            types.add(erased.substring(erased.lastIndexOf('.', Math.max(0, erased.indexOf('[') < 0
                    ? erased.length() - 1 : erased.indexOf('['))) + 1));
        }
        return Optional.of(new MethodDecl(name, List.copyOf(types), j + 1, brace, matching(code, brace, '{', '}'),
                isPrivate));
    }

    /** Whether the arrow at {@code arrow} ends a switch rule's label ({@code case …}, {@code default}). */
    private static boolean isSwitchRule(String code, int arrow) {
        int k = arrow - 1;
        int depth = 0;
        while (k >= 0) {
            char x = code.charAt(k);
            if (x == ')' || x == ']') {
                depth++;
            } else if (x == '(' || x == '[') {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if ((x == ';' || x == '{' || x == '}') && depth == 0) {
                break;
            }
            k--;
        }
        String label = code.substring(k + 1, arrow).strip();
        return label.equals("default") || label.matches("(?s)case\\b.*");
    }

    /** The top-level refusal statements of a method body or lambda block: {@code [start, end)} each. */
    private static List<int[]> guards(String own, Scope scope) {
        List<int[]> found = new ArrayList<>();
        int to = scope.end();
        int depth = 0;
        for (int i = scope.start() + 1; i < to; i++) {
            char ch = own.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
            } else if (depth == 0 && own.startsWith("if", i) && wordAt(own, i, 2)) {
                int open = skipFwdWs(own, i + 2);
                if (open >= to || own.charAt(open) != '(') {
                    continue;
                }
                int close = matching(own, open, '(', ')');
                String condition = own.substring(open + 1, close).strip();
                int body = skipFwdWs(own, close + 1);
                int statementEnd = -1;
                if (REFUSAL_CONDITION.matcher(condition).matches()) {
                    if (own.startsWith("return", body) && wordAt(own, body, 6)) {
                        statementEnd = statementEnd(own, body);
                    } else if (body < to && own.charAt(body) == '{') {
                        int blockClose = matching(own, body, '{', '}');
                        if (endsWithReturn(own.substring(body + 1, blockClose))) {
                            statementEnd = blockClose + 1;
                        }
                    }
                }
                if (statementEnd > 0) {
                    int next = skipFwdWs(own, statementEnd);
                    if (!(own.startsWith("else", next) && wordAt(own, next, 4))) {
                        found.add(new int[] {i, statementEnd});
                        i = statementEnd - 1;
                        continue;
                    }
                }
                i = close;
            }
        }
        return found;
    }

    private static boolean endsWithReturn(String block) {
        String t = block.stripTrailing();
        if (!t.endsWith(";")) {
            return false;
        }
        int depth = 0;
        int k = t.length() - 2;
        for (; k >= 0; k--) {
            char c = t.charAt(k);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                depth--;
            } else if (c == '}') {
                if (depth == 0) {
                    break;
                }
                depth++;
            } else if (c == ';' && depth == 0) {
                break;
            }
        }
        String last = t.substring(k + 1, t.length() - 1).strip();
        return last.equals("return") || last.matches("(?s)return\\b.*");
    }

    private static int statementEnd(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ';' && depth == 0) {
                return i + 1;
            }
        }
        return code.length();
    }

    private static int interiorStart(Scope scope) {
        return switch (scope.kind()) {
            case METHOD, LAMBDA_BLOCK -> scope.start() + 1;
            case EXPRESSION_LAMBDA -> scope.arrow() + 2;
            case FILE -> 0;
        };
    }

    /** {@code code} with every lambda body, anonymous-class body and local type body in {@code [from, to)} blanked. */
    private static String blankNested(String code, int from, int to) {
        char[] out = code.toCharArray();
        int i = from;
        while (i < to) {
            char ch = code.charAt(i);
            if (ch == '-' && i + 1 < to && code.charAt(i + 1) == '>' && !isSwitchRule(code, i)) {
                int body = skipFwdWs(code, i + 2);
                if (body < to && code.charAt(body) == '{') {
                    int close = Math.min(matching(code, body, '{', '}'), to);
                    blank(out, body + 1, close);
                    i = close + 1;
                } else {
                    int end = Math.min(expressionEnd(code, i + 2), to);
                    blank(out, i + 2, end);
                    i = Math.max(end, i + 2);
                }
                continue;
            }
            if (ch == '{' && isTypeBody(code, i)) {
                int close = Math.min(matching(code, i, '{', '}'), to);
                blank(out, i + 1, close);
                i = close + 1;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    private static void blank(char[] out, int from, int to) {
        for (int i = from; i < to && i < out.length; i++) {
            if (out[i] != '\n') {
                out[i] = ' ';
            }
        }
    }

    /** Whether the brace opens an anonymous class body or a local or inner type's body. */
    private static boolean isTypeBody(String code, int brace) {
        int k = skipBackWs(code, brace - 1);
        if (k >= 0 && code.charAt(k) == ')') {
            int open = matchingBack(code, k);
            int j = skipBackWs(code, open - 1);
            while (j >= 0 && (Character.isJavaIdentifierPart(code.charAt(j)) || code.charAt(j) == '.'
                    || code.charAt(j) == '<' || code.charAt(j) == '>' || code.charAt(j) == ','
                    || code.charAt(j) == '?' || Character.isWhitespace(code.charAt(j)))) {
                if (code.startsWith("new", j - 2) && j - 2 >= 0 && wordAt(code, j - 2, 3)) {
                    return true;
                }
                j--;
            }
        }
        int s = brace - 1;
        while (s >= 0 && ";{}".indexOf(code.charAt(s)) < 0) {
            s--;
        }
        return Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w+").matcher(code.substring(s + 1, brace)).find();
    }

    /** The end of the expression starting at {@code from}: its first top-level {@code ,} or {@code ;}, or an unmatched closer. */
    private static int expressionEnd(String code, int from) {
        int depth = 0;
        for (int q = from; q < code.length(); q++) {
            char x = code.charAt(q);
            if (x == '(' || x == '{' || x == '[') {
                depth++;
            } else if (x == ')' || x == '}' || x == ']') {
                if (depth == 0) {
                    return q;
                }
                depth--;
            } else if ((x == ',' || x == ';') && depth == 0) {
                return q;
            }
        }
        return code.length();
    }

    private static int lambdaParameterStart(String code, int arrow) {
        int k = skipBackWs(code, arrow - 1);
        if (k >= 0 && code.charAt(k) == ')') {
            return matchingBack(code, k);
        }
        while (k >= 0 && Character.isJavaIdentifierPart(code.charAt(k))) {
            k--;
        }
        return k + 1;
    }

    /** Whether the expression starting at {@code start} is the handler argument of a pointer registration. */
    private static boolean isPointerRegistrationArgument(String code, int start) {
        int k = skipBackWs(code, start - 1);
        if (k < 0) {
            return false;
        }
        char c = code.charAt(k);
        int open;
        if (c == '(') {
            open = k;
        } else if (c == ',') {
            open = unmatchedOpenBack(code, k - 1);
            if (open < 0) {
                return false;
            }
        } else {
            return false;
        }
        String name = identifierBefore(code, open);
        if (POINTER_SETTER.matcher(name).matches()) {
            return c == '(';
        }
        if (c == ',' && (name.equals("addEventHandler") || name.equals("addEventFilter"))) {
            List<String> args = topLevelSplit(code.substring(open + 1, k));
            return args.size() == 1 && POINTER_EVENT_TYPE.matcher(args.getFirst().strip()).lookingAt();
        }
        return false;
    }

    private static int unmatchedOpenBack(String code, int from) {
        int depth = 0;
        for (int i = from; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth == 0) {
                    return c == '(' ? i : -1;
                }
                depth--;
            }
        }
        return -1;
    }

    private static String identifierBefore(String code, int open) {
        int j = skipBackWs(code, open - 1);
        int end = j + 1;
        while (j >= 0 && Character.isJavaIdentifierPart(code.charAt(j))) {
            j--;
        }
        return code.substring(j + 1, end);
    }

    /** The word before the identifier starting at {@code identifierStart}, skipping a qualifier. */
    private static String wordBefore(String code, int identifierStart) {
        int j = skipBackWs(code, identifierStart - 1);
        while (j >= 0 && code.charAt(j) == '.') {
            j = skipBackWs(code, j - 1);
            while (j >= 0 && Character.isJavaIdentifierPart(code.charAt(j))) {
                j--;
            }
            j = skipBackWs(code, j);
        }
        int end = j + 1;
        while (j >= 0 && Character.isJavaIdentifierPart(code.charAt(j))) {
            j--;
        }
        return code.substring(j + 1, end);
    }

    /** The start of the receiver expression ending at {@code end}: identifiers, dots and balanced groups. */
    private static int expressionStartBack(String code, int end) {
        int i = end;
        while (i >= 0) {
            char c = code.charAt(i);
            if (Character.isJavaIdentifierPart(c) || c == '.') {
                i--;
            } else if (c == ')') {
                i = matchingBack(code, i) - 1;
            } else if (c == ']') {
                int depth = 0;
                while (i >= 0) {
                    if (code.charAt(i) == ']') {
                        depth++;
                    } else if (code.charAt(i) == '[' && --depth == 0) {
                        break;
                    }
                    i--;
                }
                i--;
            } else {
                break;
            }
        }
        return i + 1;
    }

    private static List<String> topLevelSplit(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '<' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']' || c == '}') {
                // a lambda's or method reference's arrow is not a closer
                if (!(c == '>' && i > 0 && text.charAt(i - 1) == '-')) {
                    depth--;
                }
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(from, i));
                from = i + 1;
            }
        }
        if (!text.substring(from).isBlank() || !parts.isEmpty()) {
            parts.add(text.substring(from));
        }
        return parts;
    }

    private static boolean insideHistoryType(String code, int site) {
        Matcher m = HISTORY_TYPE.matcher(code);
        while (m.find()) {
            int open = m.end() - 1;
            if (open > site) {
                break;
            }
            if (site > open && site < matching(code, open, '{', '}')) {
                return true;
            }
        }
        return false;
    }

    private static boolean precededByVoid(String code, int at) {
        int k = skipBackWs(code, at - 1);
        return k >= 3 && code.startsWith("void", k - 3) && wordAt(code, k - 3, 4);
    }

    private static boolean wordAt(String code, int at, int length) {
        boolean before = at == 0 || !Character.isJavaIdentifierPart(code.charAt(at - 1));
        boolean after = at + length >= code.length() || !Character.isJavaIdentifierPart(code.charAt(at + length));
        return before && after;
    }

    private static int matching(String code, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    private static int matchingBack(String code, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char ch = code.charAt(i);
            if (ch == ')') {
                depth++;
            } else if (ch == '(' && --depth == 0) {
                return i;
            }
        }
        return 0;
    }

    private static int skipBackWs(String code, int i) {
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int skipFwdWs(String code, int i) {
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ── blanking ─────────────────────────────────────────────────────────────

    /**
     * Text blocks, string and char literals and comments, in that order of
     * precedence (SourceScanSupport's tokenizer): a delimiter inside a literal
     * is not a comment, and a quote inside a comment is consumed with it.
     */
    private static final Pattern COMMENT_OR_LITERAL = Pattern.compile(
            "\"\"\"[\\s\\S]*?\"\"\""
                    + "|\"(?:\\\\.|[^\"\\\\])*\""
                    + "|'(?:\\\\.|[^'\\\\])'"
                    + "|//[^\\n]*"
                    + "|/\\*[\\s\\S]*?\\*/");

    /** Blanks comments and the contents of literals in place, newlines kept, so offsets and lines stay true. */
    private static String blank(String source) {
        char[] out = source.toCharArray();
        Matcher m = COMMENT_OR_LITERAL.matcher(source);
        while (m.find()) {
            String token = m.group();
            boolean literal = token.startsWith("\"") || token.startsWith("'");
            int from = m.start() + (literal ? (token.startsWith("\"\"\"") ? 3 : 1) : 0);
            int to = m.end() - (literal ? (token.startsWith("\"\"\"") ? 3 : 1) : 0);
            blank(out, from, to);
        }
        return new String(out);
    }
}
