# Contributing

Thank you for your interest in contributing to **java-digital-audio-workstation**.

## Building and testing

This project targets **Java 26+** and **Apache Maven 3.9.14**. Tests that
exercise JavaFX UI classes (e.g. `daw-app`) require an active display.
On headless systems use `xvfb-run`:

```bash
xvfb-run -a mvn verify
```

## Code style

### Controller-class size: ~200-line soft cap

Controller classes (anything ending in `Controller`, `View`, `Service`,
`Dispatcher`, or `Handler` under `com.benesquivelmusic.daw.app.ui`) should
stay below a **~200-line soft cap**, not counting Javadoc and blank
lines. Hitting or exceeding the cap is a strong signal that the class
has accumulated multiple responsibilities and that one or more focused
collaborators should be extracted.

This is a **soft** cap, not a hard rule:

- Crossing 200 lines does **not** block a pull request.
- Crossing 200 lines **does** trigger a code-review note asking whether
  a Single-Responsibility-Principle extraction is in order.
- Genuinely cohesive code that legitimately exceeds the cap (for
  example, a single switch over a large sealed hierarchy) is accepted —
  reviewers and authors should explain in the PR description why no
  extraction is appropriate.

### Why a cap at all?

Large UI controller classes tend to become "dumping grounds" for
related-but-distinct concerns (construction, action dispatch,
enable-state, animation, hit-testing, …). Each unrelated concern in a
single class makes the class harder to test in isolation, harder to
reason about, and harder to change safely. The 200-line guideline is a
deliberately low threshold so reviewers can apply gentle pressure to
keep responsibilities focused as the codebase grows.

### Recommended cleavage patterns

Recent decompositions in this codebase illustrate the patterns that
work well:

- **`DawMenuBarController`** → `MenuConstructionService` (builds the
  menu hierarchy) + `MenuEnablementPolicy` (pure-logic mapping from
  project state to enable/disable flags) + `Host` callback (action
  dispatch).
- **`AnimationController`** → `IdleVisualizationAnimator`,
  `TransportGlowAnimator`, `TimeTickerAnimator`, `ButtonPressAnimator`
  — the controller coordinates a single `AnimationTimer`; each animator
  owns one concern.
- **`EditorView`** → `MidiEditorView` + `AudioEditorView` (delegated
  per active selection).
- **`ArrangementCanvas`** → `TrackLaneRenderer`,
  `ClipOverlayRenderer`, `ClipWaveformRenderer`,
  `ClipMidiPreviewRenderer`, `AutomationLaneRenderer`,
  `TransportOverlayRenderer`, plus separate handlers
  (`ClipInteractionController`, `ClipTrimHandler`, `SlipToolHandler`,
  …) for interaction.

### Constructor injection

New collaborators must be wired via **constructor injection**. Do not
introduce static singletons or service-locator patterns when extracting
a focused service. Tests then drive the collaborator with stubs/fakes
without touching global state.

The application's composition root is
[`DawRuntime`](daw-app/src/main/java/com/benesquivelmusic/daw/app/DawRuntime.java)
— a small hand-rolled class instantiated once during `DawApplication`
construction that owns the canonical long-lived collaborators (`ProcessorRegistry`,
`java.time.Clock`, `java.util.random.RandomGenerator`, …) and passes
them into downstream constructors.

When a collaborator's behaviour depends on **time or randomness**,
inject a `java.time.Clock` and/or `java.util.random.RandomGenerator`
through its constructor instead of calling `System.currentTimeMillis()`,
`Instant.now()`, `Math.random()`, or `new Random()` directly. Tests
then pass `Clock.fixed(...)` or a seeded `RandomGenerator` to make the
behaviour deterministic.

A few historical static singletons are being removed
incrementally — one per PR. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#the-singleton-removal-migration)
for the migration pattern and current status.

### Extraction checklist

When extracting a focused service:

1. Identify a clear cleavage line — a group of fields plus the
   methods that read/write them, or a self-contained block of pure
   computation.
2. Move the block into a new class in the same package, accepting its
   dependencies via constructor.
3. Add at least one unit test that was **not possible before**
   extraction (because the prior class was too entangled to test in
   isolation).
4. Keep the extraction in **its own commit** — do not bundle multiple
   unrelated extractions in a single change.
5. Preserve behaviour: the existing public API of the original
   controller should keep working exactly as before unless the
   migration is explicit and documented.

## Commit and PR conventions

- One logical change per commit.
- PR descriptions list motivation, goals, and non-goals.
- Run the relevant module's tests under `xvfb-run` before pushing UI
  changes.
