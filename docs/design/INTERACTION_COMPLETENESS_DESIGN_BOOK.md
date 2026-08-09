# Interaction Completeness Design Book

> A reference design for **making every control in the Java Digital Audio Workstation do
> something real and every visual tell the truth** — the arrangement navigation shell,
> waveform and clip rendering honesty, drag-and-drop feedback, shortcut safety and live
> rebinding, menu truth and export reachability, MIDI-clip editing parity, render economy,
> and the conformance harness that keeps dead controls from ever shipping again.
> **No code in this document.**
>
> Book 5 of the five companion books produced by the 2026 ten-area functional audit:
> - `docs/design/AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` — the audible core: every sound-making promise of the UI is honoured by the engine.
> - `docs/design/RECORDING_RELIABILITY_DESIGN_BOOK.md` — a 2-hour session ends with all audio on disk.
> - `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` — save it, reopen it, it's still there.
> - `docs/design/FAILURE_SURFACING_DESIGN_BOOK.md` — no silent failures, no button does nothing.
>
> And companion to the five existing design books:
> - `docs/design/UI_DESIGN_BOOK.md` — visual language, tokens, grid, components (**note: its §1 and §6 are a stale baseline**; trust the token/component contracts, not its inventory of what exists).
> - `docs/design/CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` — what happens on the wire between surfaces: VMs, the event bus, the cascade contract.
> - `docs/design/PLUGIN_VIEW_DESIGN_BOOK.md` — the plugin editor surface and SDK seam.
> - `docs/design/SETTINGS_VIEW_DESIGN_BOOK.md` — settings model, scope, apply contract.
> - `docs/design/PROJECT_MANAGER_DESIGN_BOOK.md` — project lifecycle, autosave, recovery.
>
> Those books define what each surface contains, how state travels between surfaces, and
> what the engine must honour. This book defines the **last inch**: the promise a control
> makes at the moment a user sees it. The audit found the application chrome-complete but
> functionally hollow, and this book owns the interaction half of that verdict — zoom
> buttons that zoom nothing, waveforms that draw audio that will not play, drags with no
> feedback, shortcuts that fire while you type a track name, menu items that open windows
> which can never contain anything, and a 60 fps repaint loop whose only real function is
> hiding the repaint calls nobody wired. The organizing guarantee: **every control does
> something real, and every visual tells the truth.** Stories 341–347 operationalise it.

---

## 0. How to use this book

1. **Read §1 first.** A frank, file:line-verified inventory of every class of dead
   control and lying visual shipping today. Every later section is judged against it.
2. **§2 is the foundation.** Eight non-negotiable principles. When a PR and a principle
   disagree, the PR is wrong.
3. **§3 is the information model.** The handful of models this book adds or revives —
   the viewport transform, the peak cache, the command registry, the drag visual state,
   the invalidation ledger — and who owns each.
4. **§4 is the architecture.** Three stacks (arrangement rendering, input/command,
   conformance harness) drawn as wiring diagrams, showing where each §3 model sits.
5. **§5 is the behavioural contract.** The tables a reviewer checks a PR against: one
   per interaction class (navigation, visual truth, drag feedback, shortcut safety,
   command surface, MIDI parity, repaint economy, motion/theme).
6. **§6 is the conformance harness.** The source-scan sentinel design that makes a dead
   control a *build failure* instead of an audit finding. Read this before adding any
   new menu item, action, or shortcut.
7. **§7 binds this book to the other nine.** Which guarantees are consumed from the
   other four new books and which are provided to them.
8. **§8 is the migration path.** Seven stages in dependency order, one per story
   (341–347), each shippable, each with scope, landing proof, and what it unblocks —
   with the existing backlog stories (010, 021, 026, 027, 032, 126, 140, 143, 168,
   181, 185, 192, 197, 243, 248) woven into the stages that complete them.
9. **§9 is the rejection list.** Anti-patterns this codebase has already demonstrated;
   do not bring them back.
10. **Appendices** map every book concept to today's classes and cross-reference the
    SKILL files and research docs.

The ASCII diagrams are deliberately wide (~120 columns); read in a monospace viewer.

---

## 1. Critique of the interaction surface shipping today

Everything below was re-verified against the working tree on 2026-08-08. Line numbers are
locators pinned to today's tree; content correctness is what matters.

### 1.1 The navigation shell is a facade

The arrangement's inner editing loop is genuinely good — select/trim/fade/slip/split/
erase/glue, clipboard, nudge, and automation breakpoints all complete the
gesture→model→undo→repaint cycle. The shell around that loop is theatre:

- **Every zoom control is dead.** Ctrl+scroll (`ViewNavigationController.java:847‑858`),
  the keyboard bindings, the track context menu's "Zoom In"/"Zoom Out" items
  (`TrackStripController.java:1089/1102`), and Home
  (`MainController.java:4302‑4304`) all converge on `onZoomIn()/onZoomOut()/onZoomToFit()`
  (`ViewNavigationController.java:863‑885`), which mutate a `ZoomLevel` object and write
  "Zoom in: 125%" to the status bar. Nothing reads that object.
  `ArrangementCanvas.setPixelsPerBeat` (`ArrangementCanvas.java:162`) and
  `TimelineRuler.setPixelsPerBeat` (`TimelineRuler.java:159`) have **zero production
  callers** — the canvas is pinned at 40 px/beat forever, and the status bar reports a
  fiction.
- **There is no scrolling at all.** `setScrollXBeats`/`setScrollYPixels`
  (`ArrangementCanvas.java:191/197`) have zero production callers; only playhead
  auto-scroll ever moves the horizontal offset and nothing moves the vertical one. The
  arrangement pane in `main-view.fxml:144` is a bare StackPane with no ScrollPane and no
  pan handler. A session taller than ~8 tracks or longer than one screen is uneditable.
- **The story-021 navigation layer was built and never plugged in.**
  `ArrangementNavigator` (`ArrangementNavigator.java:23`) — self-described "central point
  for handling all navigation interactions", owning `ZoomLevel`, `TrackHeightZoom`,
  `ScrollPosition`, and `MinimapModel`, with a `ViewportState` persistence snapshot — is
  never instantiated in production. Only its `BASE_PIXELS_PER_BEAT` constant is referenced
  (`ArrangementCanvas.java:64`). `MinimapModel` has no view component anywhere.

### 1.2 Rendered fiction: visuals that misrepresent the session

- **Waveforms ignore the clip's source window.** `ClipWaveformRenderer`
  (`ClipWaveformRenderer.java:83‑85`) always stretches the *entire* source buffer across
  the clip's on-timeline width — it takes no source-offset or source-length parameter.
  But `AudioClip.splitAt` gives *both* halves the full buffer, distinguished only by
  `sourceOffsetBeats` (`AudioClip.java:471/484`), and playback honours the offset. So
  every split draws the whole waveform squeezed into each half, every trim redraws the
  wrong window, and a committed slip edit produces **zero visible change** — the feature
  looks broken because the renderer lies. The adjacent gain-envelope renderer call *does*
  receive `getSourceOffsetBeats` (`ClipOverlayRenderer.java:155‑160`), proving the
  omission rather than an architectural obstacle.
- **Two overlays never render because their feed was never wired.** The per-clip gain
  envelope is gated on `samplesPerBeat > 0` and the SRC-mismatch badge on
  `sessionRateHz > 0` (`ClipOverlayRenderer.java:155/86‑90`); the corresponding canvas
  setters (`ArrangementCanvas.java:175/185`) have zero production callers, so both
  features (stories 140 and 126) are invisible dead weight.
- **The header shows a fake ruler.** Eight static Labels "1"…"8" styled as "beat
  markers" sit in `main-view.fxml:135` directly above the real `TimelineRuler` — two
  timelines on screen, and the top one is decoration that tracks nothing.
- **The ruler itself goes stale after File▸Open.** The ruler is constructed once with
  the first project's Transport captured by value (`ArrangementCanvasFactory.java:49`,
  `TimelineRuler.java:118` — a final model field, no re-targeting seam); the ruler field
  is assigned exactly once (`MainController.java:2384`) and `handleProjectRebuild`
  (`MainController.java:1453`) rebuilds mixer/track controllers but never the ruler.
  Every `DawProject` constructs its own `Transport` (`DawProject.java:129`), so after
  open/new the ruler's tempo display and Shift+drag loop gestures read and write a dead
  object while the canvas loop overlay reads the live one
  (`MainController.java:4579‑4582`). Book 1's story 315 owns the re-bind; this book's
  navigation stage must not deepen the trap (§8 Stage 3).
- **The ruler converts frames at the wrong sample rate.** `sampleRate` defaults to
  44.1 kHz (`TimelineRuler.java:105`) and `setSampleRate` (`:244‑257`) has zero
  production callers, while the default project is 96 kHz — any deserialized punch
  region draws ~2.18× too far right (`:461`). Punch handles are drawn (`:292/422`) but
  not interactive, and the *only* production writer of a punch region is the project
  deserializer (`ProjectDeserializer.java:385`). Punch arming itself is
  `RECORDING_RELIABILITY_DESIGN_BOOK.md` story 328 territory; the honesty rules here
  still apply — see §5.2.
- **The Editor view's "Audio Waveform" panel is permanently empty.** `AudioEditorView`
  constructs a `WaveformDisplay` under that header (`AudioEditorView.java:44‑52,92`) and
  never loads clip samples into it; `WaveformDisplay.setWaveformData`
  (`WaveformDisplay.java:64`) has **zero production callers repo-wide**. Trim/Fade
  buttons operate on a selection the user cannot see.
- **Album Assembly's per-track "waveform preview" boxes are empty for the same
  reason** — displays constructed and registered (`AlbumAssemblyView.java:533/538`) then
  only ever iterated to dispose them (`:296‑303`).
- **Track strips misalign with lanes.** Left-panel rows are content-sized HBoxes while
  canvas lanes are a uniform `trackHeight` (80 px default, `ArrangementCanvas.java:75`)
  plus 60 px per expanded automation lane; the context menu's "Expand Track" resizes
  only the strip to 120 px (`TrackStripController.java:1145‑1148`), desynchronising every
  row below it. Hit-testing follows canvas geometry, so clicks land on a different track
  than the row they appear beside.
- **Folded automation lanes take full-height clicks.** The interaction controller
  hit-tests with the fixed `AUTOMATION_LANE_HEIGHT` constant
  (`ClipInteractionController.java:365/528/601`) instead of the canvas's fold-aware
  `automationLaneHeight(trackIndex)` (`ArrangementCanvas.java:616`) — a click in the 3 px
  folded summary strip creates a near-maximum breakpoint.
- **Right-edge trim cannot re-extend.** `clampRightEdge` caps the edge at the
  drag-start duration (`ClipTrimHandler.java:337‑344`) even when source audio remains —
  the true source length is computable (the slip handler already computes it), so
  non-destructive trim behaves destructively.

### 1.3 Drag feedback is computed and never rendered

Story 197 landed `DragVisualAdvisor` — a fully tested state machine producing ghost
preview, drop-target highlight, snap indicator, and modifier-cursor state. Nothing
renders it:

- `update(...)` (`DragVisualAdvisor.java:155`) — the method that produces per-cursor-move
  visual state — has **zero production callers**. `currentVisualState()` (`:275`) has no
  production consumer. The package-info admits presenters are "expected to translate"
  `DragVisualState`; none exist.
- All three drag sources call only the lifecycle half — `beginDrag`/`commit`/`cancel`/
  `revertCompleted` (`BrowserPanel.java:681`, `ClipInteractionController.java:1233/1248`,
  `InsertEffectRack.java:658`) — and discard the returned state.
- A pointer clip drag paints nothing at all: the drag handler falls through to the
  comment "Drag preview is visual only — the actual move happens on release"
  (`ClipInteractionController.java:588‑592`) with no code drawing a preview. The clip
  teleports on mouse release. The Esc cancel-revert path drives advisor states and a
  PauseTransition (`:1204‑1213`) — nothing on screen animates.
- The dock drop-zone highlight loses its CSS cascade: `.dock-drop-target-active`
  (`styles.css:1274`) is a single-class selector declared *before* the equal-specificity
  `.content-area` (`:1365`) and `.browser-panel` (`:1376`) background rules, so the
  later declaration wins and the highlight never appears over the CENTER or LEFT zones.
- Audio import — the usual endpoint of a browser drag — decodes synchronously on the FX
  thread (`AudioImportController.java:98`), imports only the first of a multi-file drop
  (`:226` — the rest vanish silently), and mutates the live transport position to the
  drop beat and back (`:224/228`), audibly jolting playback.

### 1.4 The keyboard is a hazard and rebinding is cosmetic

- **Bare single-key accelerators fire while typing.** `register(scene)` puts every
  binding straight into `scene.getAccelerators()` with no focus-owner guard
  (`KeyboardShortcutController.java:297`). Defaults include bare SPACE, ESC, R, L, M,
  I, O (`DawAction.java:18‑37`) and V/P/E/C/G tool keys (`:173‑182`). The main scene
  hosts live text inputs — the track-rename TextField
  (`TrackStripController.java:1661`) and the browser search field
  (`BrowserPanel.java:227`). Typing a track name containing "r" starts recording. The
  codebase knows the fix: the plugin frame's key handler starts by returning when the
  event target is a `TextInputControl` (`EditorFrameSkin.java:854`) — the global
  accelerators simply lack the same guard.
- **Rebinding does nothing until restart.** `MainController` constructs the
  KeyBindingManager the scene and menus consume (`MainController.java:863`);
  `SettingsModel.getKeyBindingManager()` lazily constructs a *second* manager over the
  same prefs node (`SettingsModel.java:660`), and Settings' `applyKeyBindings` mutates
  only that copy (`SettingsDialog.java:2163‑2164`). Nothing re-registers scene
  accelerators or menu accelerators, so a rebind is invisible until relaunch — and the
  old combination keeps firing all session.
- **The Help dialog's shortcut list is hand-typed fiction.** Literal strings
  ("Record:"/"R", "Save:"/"Ctrl+S" — `HelpDialog.java:103‑104/138`) covering roughly a
  third of the 92 actions, never consulting `KeyBindingManager.getDisplayText` — so it
  is both incomplete and wrong after any rebind.
- **The palette is honest but partial.** The supplier iterates `DawAction.values()` and
  skips any action with no handler (`MainController.java:2073‑2092`) — correctly derived
  from the real handler map, but every menu-only command (exports, snapshots, plugin
  activation, workspaces, layouts…) is invisible to Ctrl+K, and
  `CommandPaletteEntry.of` hard-codes `enabled=true` (`CommandPaletteEntry.java:50‑58`),
  so the palette's disabled-entry rendering is unreachable.
- **Advertised, rebindable, dead.** SET_PUNCH_IN/SET_PUNCH_OUT/TOGGLE_PUNCH and
  TOGGLE_SESSION_MANAGER are offered in Settings as rebindable shortcuts, yet
  `buildActionHandlers` maps none of them (verified by grep of
  `KeyboardShortcutController.java`) — binding them produces silence. `EditTool.COMP`
  exists (`EditTool.java:35`) with a full swipe-comping handler
  (`ClipInteractionController.java:1083`) and **no activation path anywhere**: no
  TOOL_COMP action, no toolbar button, no menu item.

### 1.5 Menus that dead-end

- **File ▸ Render Queue… opens a window that can never contain a job.**
  `RenderQueue.enqueue` (`RenderQueue.java:118`) has zero production callers (grep: only
  its own tests). `onOpenRenderQueue` (`MainController.java:4124`) builds the stage; the
  view has per-row pause/resume/cancel and drag-reorder but no add-job affordance. The
  queue is a lobby with no doors.
- **No audio deliverable export is reachable from any menu.** The File menu contains
  project/snapshot/DAWproject/render-queue/import items only
  (`MenuConstructionService.java:88‑97`). Four *landed* export surfaces —
  `BundleExportDialog`, `AafExportDialog`, `AdmBwfExportController`,
  `AlbumAssemblyView` — are constructed only by their tests. A DAW that cannot export
  a mixdown from its menu bar is not finished software.
- **Window-menu toggles have no state.** All five panel toggles are plain MenuItems,
  never CheckMenuItems (`MenuConstructionService.java:411‑419`); the six view entries
  are plain items, not a RadioMenuItem group (contrast `LayoutsMenu.java:104‑105`,
  which does it correctly).
- **Dock toggles desynchronise.** Window-menu and Ctrl+1‑4/Ctrl+B handlers call
  `switchView`/`toggleBrowserPanel` directly (`MainController.java:2470‑2473`), leaving
  the dock model's visibility flags stale; the F3/F4/F5 paths reconcile against those
  stale flags (`MainController.java:3313‑3317`), so the first press after a menu toggle
  is a silent no-op. The one hook that could re-sync —
  `ViewNavigationController.setOnViewChanged` (`:714‑716`) — has no production caller.
- **Quitting in Workshop view bricks the next launch's centre pane.** Every
  non-performance view is persisted (`ViewNavigationController.java:329‑330`), restore
  sets the centre from a cache that only ever contains the four standard views
  (`:256‑258`), and the `view == activeView` early-return (`:286`) sits *before*
  `ensureWorkshopBuilt` (`:309‑310`) — so a persisted WORKSHOP restores a null centre
  that F12 cannot repair.
- **The Workspaces menu is a startup snapshot.** Built once from `listAll()`
  (`WorkspacesMenu.java:85`, installed once); newly saved or imported workspaces never
  appear, and there is no Delete item at all — while the sibling `LayoutsMenu`
  repopulates on every `savedLayouts()` change (`LayoutsMenu.java:80`), showing the
  intended pattern.
- **HRTF profile management is unreachable.** `PluginViewController.onManageHrtfProfiles`
  (`PluginViewController.java:289`) exists, per its own javadoc, "so the menu bar can
  route it" — no menu item does.
- **Placeholder clutter.** Twenty "Coming soon" tooltips ship in
  `TrackStripController` alone (`:951‑1379` — align/fullscreen/minimize/PiP plus a
  block of social-media items in a professional audio tool), each a disabled control
  that trains users to distrust the menu. The Hub/Welcome surfaces hard-code
  "Workspace: Personal" (`WelcomeView.java:356`, `ProjectHubView.java:237`) with no
  workspace model behind it, and the Hub detail strip permanently shows "—" for sample
  rate (`ProjectHubView.java:711` — "Stage 2 — no sample-rate fact yet", a stage no
  story owns).
- **Edit tools are invisible.** `initializeEditTools` is an intentional no-op whose
  comment claims tools are "available through menu actions" (`ViewNavigationController.java:672‑675`)
  — no tools menu exists (verified by grep of `MenuConstructionService`). Scissors and
  glue are reachable *only* via bare keystrokes C and G, the only active-tool feedback
  is a transient status-bar line, and a stray "E" press silently turns clicks into
  deletions.

### 1.6 MIDI clips are second-class citizens of the arrangement

Copy/cut/duplicate/delete read only the audio selection
(`ClipEditController.java:96‑104` and siblings — `getSelectedClips()` yields audio
`ClipboardEntry` only); nudge resolves audio targets only (`:395‑397`). A pointer press
on a MIDI clip selects but never arms a drag (`ClipInteractionController.java:891‑897` —
no dragClip is set), and eraser/scissors/glue hit-test with the audio-only `clipAt`
(`:256`, used at `:1017/1040`). The **only** arrangement operation that treats MIDI
clips as equals is slip (`ClipEditController.java:207‑246`, which handles both kinds) —
proof the selection model and undo layer can carry MIDI; the operations simply never
consult them. Worse, the pencil deposits a silent `AudioClip` on MIDI tracks
(`ClipInteractionController.java:1004` — drawn by the canvas, never played by the MIDI
path): a phantom the user cannot hear and, given §1.5's tool invisibility, cannot
explain.

### 1.7 Render extravagance: the 60 fps repaint that hides bugs

- The single main `AnimationTimer` runs every frame from startup and is never stopped —
  `AnimationController.stop()` (`AnimationController.java:127‑131`) has no caller.
- Every frame, regardless of transport state, the playhead callback
  (`AnimationController.java:116‑118`) runs `tickArrangementOverlays` →
  `applyLoopAndRulerGrid` (`MainController.java:4565‑4589`), which calls
  `arrangementCanvas.setLoopRegion(...)` — and `setLoopRegion` redraws unconditionally
  (`ArrangementCanvas.java:231‑236`; contrast `setPlayheadBeat`'s change guard at
  `:209‑218`) — plus `timelineRuler.redraw()`. Full canvas clear + lanes + clips +
  automation, 60×/second, forever.
- Each redraw min/max-scans **every raw sample of every clip** per visible pixel column
  (`ClipWaveformRenderer.java:83‑107`): O(total session samples) on the FX thread, per
  frame. Minutes of 96 kHz audio, rescanned sixty times a second, on the machine that is
  supposed to be running a low-latency ASIO stream.
- The waste is also a correctness crutch: `onUndo()/onRedo()`
  (`MainController.java:3839‑3857`) never repaint the canvas — undo only *appears* to
  repaint because the per-frame loop repaints everything anyway. Fix the hot loop
  without wiring the missing repaint and undo goes visually stale. This is the
  clearest possible argument for §2.7: correctness must come from declared
  invalidation, not from a clock.
- The transport glow rewrites an inline `-fx-effect` style string via `String.format`
  on every frame of playback and recording (`TransportGlowAnimator.java:40‑68` —
  forcing per-pulse CSS re-parse), never consults `MotionManager`, and hard-codes the
  glow colour as a hex literal — three violations in one class
  (`UI_DESIGN_BOOK.md` tokens-not-hex; story 279 Reduce Motion).

### 1.8 Motion and theme non-conformance

- None of the GpuCanvas displays consult `MotionManager` (grep of `ui/display`: zero
  hits); the shared base gates only on scene attachment (`GpuCanvas.java:439`). Reduce
  Motion — story 279's landed accessibility contract — does not stop room-telemetry
  particles, sonar ripples, or idle sweeps. The controls package shows the intended
  pattern: `LevelMeter` captures the MotionManager singleton once and combines it with
  its local animated flag (`controls/LevelMeter.java:167`).
- Every display hard-codes a near-black background and low-alpha white text
  (`SpectrumDisplay.java:40‑42` and its six siblings) — under the selectable Atelier
  light theme they render as black slabs with unreadable labels inside correctly
  re-themed `-surface-1` tile chrome. The repo already solved this class of problem for
  glyphs (IconNode resolves tint from ancestor CSS); the displays never adopted it.

### 1.9 Why nothing catches this

The repo has a proven answer to "how do we make a rule stick": the source-scan
conformance sentinel family — `SourceScanSupport` (`daw-app/src/test/java/.../ui/SourceScanSupport.java`)
shared by `RunLaterConsolidationTest`, `LegacyHardcodedColorAuditTest`,
`NoClassNameFieldAnywhereTest`, `EveryDialogConformsTest`, `IconAuditTest` and a dozen
more. Those sentinels guard *structural* rules (no ad-hoc runLater, no hex colours, no
legacy dialogs). **No sentinel guards liveness**: nothing fails the build when a
`DawAction` ships without a handler, when a public wiring seam (`setPixelsPerBeat`,
`setWaveformData`, `update(...)`) has zero production callers, when a menu item's
handler chain terminates in a no-op, or when a "Coming soon" item is added. Every
finding in §§1.1–1.8 would have been caught by the harness §6 specifies. That harness —
not any individual fix — is this book's most durable deliverable.

### 1.10 What today's code gets right (keep)

- **The editing core is real.** Trim/fade/slip/split/glue/clipboard/nudge/automation
  complete the full gesture→model→undo→repaint cycle with proper undo actions. This
  book adds *around* that core; nothing in it is rewritten.
- **The dormant models are well designed.** `ArrangementNavigator` + `ZoomLevel` +
  `TrackHeightZoom` + `ScrollPosition` + `MinimapModel` (+ `ViewportState`
  persistence), `DragVisualAdvisor` + `DragVisualState`, `CompToolHandler`,
  `CrossTrackSelection`, the export dialogs, `RenderQueue` — all built, tested, and
  waiting for a consumer. The stories in §8 are overwhelmingly *wiring* stories, which
  is why seven stories can honestly cover this much ground.
- **The palette derives from the real handler map** (`MainController.java:2070`) rather
  than a divergent copy — the single-registry principle (§2.6) generalises an instinct
  the code already has.
- **The guard pattern exists in miniature.** `EditorFrameSkin`'s TextInputControl
  check, `LevelMeter`'s combined motion gate, `LayoutsMenu`'s reactive rebuild,
  `setPlayheadBeat`'s change short-circuit — every fix in this book has a working
  precedent in-tree to copy from, which §5's contract tables cite.
- **The sentinel infrastructure exists** (§1.9) and only needs new scan dimensions.

### 1.11 Summary of the gap

| Symptom (today) | Root cause | This book's fix |
|---|---|---|
| Zoom/scroll/minimap dead; status bar lies | Story-021 models never consumed | One viewport transform, consumed everywhere (§3.1, §5.1, story 341) |
| Waveforms/splits/slips draw wrong audio; empty waveform panels | Renderer ignores source window; feeds unwired | Visual-truth contract (§5.2, story 342) |
| Drags paint nothing; drop highlight invisible | Advisor has no presenter; CSS cascade lost | Drag presenter + rendered state (§5.3, story 343) |
| Typing fires transport; rebinds need restart | No focus guard; two KeyBindingManagers | Shortcut safety + one manager (§5.4, story 344) |
| Render queue unfillable; exports unreachable; toggles stateless | Menu items wired to dead ends | Menu-truth contract + command registry (§5.5, story 345) |
| MIDI clips un-editable in arrangement | Ops consult audio-only selection/hit-test | Parity matrix (§5.6, story 346) |
| 60 fps full repaints; glow setStyle; motion/theme ignored | Clock-driven painting; no gates | Repaint-on-change + conformance (§5.7, §5.8, story 347) |
| All of the above shipped unnoticed | No liveness enforcement | Dead-control conformance harness (§6, woven through §8) |

---

## 2. Design principles

Eight non-negotiable rules. Each exists because §1 documents the cost of violating it.

### 2.1 A control that does nothing is a defect, not a placeholder

If a button, menu item, shortcut, gesture, or handle is visible or advertised, it works.
There is no third state. "Coming soon" items, disabled-forever entries, advertised-but-
unhandled actions, and decorative rulers ship the message that controls in this
application are unreliable — which poisons trust in the controls that *do* work. When a
feature is not ready, its control does not exist (deferral is expressed in the backlog,
not the UI). This is the operating rule the conformance harness (§6) mechanises.
*Why:* §1's inventory shows the alternative — 20 placeholder menu items in one file and
users who cannot tell a broken feature from an unshipped one.

### 2.2 Every visual derives from live state — never fabricate, never decorate with data

A pixel that encodes session information (a waveform, a beat number, a meter, a badge,
an alignment) must derive from the live model, or not render. Fabricated motion and
placeholder facts are worse than absence because they inspire false confidence — the
fake header ruler, the "Workspace: Personal" label, the waveform that draws audio the
split will not play. When there is no data, render an explicit empty state (the
`TunerDisplay` "No Signal" branch is the in-tree precedent). *Why:* an engineer makes
cut decisions from the waveform; §1.2 shows today's waveform lies after every edit.

### 2.3 One viewport transform, consumed by everything that draws or hit-tests

Exactly one model owns pixels-per-beat, horizontal/vertical scroll, and per-track row
heights (including automation-lane expansion and fold state). The canvas, ruler, track
strips, minimap, drag presenter, and every hit-test consume the *same* transform; no
surface keeps a private copy of any of its facts. *Why:* §1.1–1.2's misalignments are
all divergent-copy bugs — strip heights vs lane heights, fixed constants vs fold-aware
heights, a ruler pinned at 44.1 kHz beside a 96 kHz project. This is
`CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §2.1 ("one source of truth, observed")
specialised to geometry.

### 2.4 Feedback accompanies the gesture, not just the release

Every drag renders its consequence continuously: ghost at the snapped position, target
highlight, snap indicator, modifier cursor. Every mode change (tool, view, toggle)
renders persistently, not as a transient status-bar line. Commit-on-release is fine —
*preview* must not wait for release. *Why:* §1.3 — the clip that teleports on release
and the tool that silently turns clicks into deletions are the two cheapest ways to
make a user distrust a pointer.

### 2.5 Typing wins: focus context gates single-key input

A bare (modifier-free) accelerator never fires while the focus owner or event target is
a text input or an editing cell. Modified accelerators (Ctrl/Alt/Shift+key) remain
global. The guard lives in exactly one place — the scene-level input seam — not
per-control. *Why:* §1.4; and the fix is proven in-tree (`EditorFrameSkin.java:854`).
A DAW whose rename box can start recording is not a professional tool.

### 2.6 One command registry: menu, palette, shortcut, help, and enablement are projections

Every user-invocable command is registered once — id, label, category, handler,
optional key binding, enablement (with a human-readable disabled reason), and checked
state where applicable. The menu bar, command palette, accelerator map, Help shortcut
table, and toolbar tooltips are *renderings* of that registry. One `KeyBindingManager`
instance serves everyone, and rebinds re-project live. *Why:* §1.4–1.5 catalogue the
drift of five hand-maintained copies; `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §2.8
already requires keyboard/menu/click to be one intent — this extends the same rule to
*discovery* surfaces.

### 2.7 Repaint on change, not on clock

Painting is driven by declared invalidation: a state change marks a scope dirty; a
frame with no dirty scope costs nothing. Continuous state (the moving playhead) invalidates
the narrowest layer that renders it, not the world. Expensive derivations (waveform
min/max) are cached and keyed, never recomputed per frame. The undo/redo path publishes
invalidation like every other mutation — correctness must survive deleting the
animation loop. *Why:* §1.7 — today's design burns a core's worth of FX-thread time to
mask missing repaint wiring, on the same machine that must service a real-time audio
callback (`AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` owns that callback's budget; this book
owns not stealing it).

### 2.8 Completeness is enforced by sentinels, not vigilance

Every rule above gets a machine check that fails the build: action-without-handler,
advertised-shortcut-without-registration, wiring-seam-without-caller, disabled-forever
menu item, motion-ungated animation driver, hex colour in a themed surface. Exemptions
are explicit, in-source, and carry a story reference. *Why:* §1.9 — the audit found
195 findings in a codebase with a green test suite; review vigilance demonstrably does
not scale, and the repo's existing sentinel family demonstrably does.

---

## 3. Information model

Five models carry this book. Three already exist and need consumers; two are new.

| Model | Status | Authoritative home | Consumed by |
|---|---|---|---|
| Viewport transform | **Exists, dormant** (`ArrangementNavigator` + `ZoomLevel`/`TrackHeightZoom`/`ScrollPosition`/`MinimapModel`/`ViewportState`) | One navigator instance per arrangement, owned beside the canvas factory | Canvas, ruler, track strips, minimap view, hit-testing, drag presenter, peak cache keys |
| Peak cache | **New** | A per-clip pyramid store beside the waveform renderer | Clip waveform painting, editor waveform panel, album previews; later browser thumbnails (existing story 027) |
| Command registry | **New** (generalises the palette's handler map) | One registry built where menus are constructed today | Menu bar, palette, accelerators, Help, toolbar tooltips, enablement/checked sync |
| Drag visual state | **Exists, unrendered** (`DragVisualAdvisor`/`DragVisualState`) | The advisor (unchanged) | The new drag presenter overlay; cursor management |
| Invalidation ledger | **New** | The arrangement view layer | Repaint scheduling (§5.7); conformance tests assert mutations publish it |

### 3.1 The viewport transform

One navigator instance owns four facts and their derived mappers:

- **pixelsPerBeat** (horizontal zoom) — bounded, cursor-anchored on wheel zoom (the
  beat under the cursor stays under the cursor).
- **scrollXBeats / scrollYPixels** — horizontal offset in musical units (so tempo and
  zoom changes do not shear the view), vertical in device units.
- **rowLayout** — the ordered per-track row model: base track height (from
  `TrackHeightZoom`), automation-lane expansion heights, fold state. The strip panel and
  the canvas both render *from this*, which makes §1.2's misalignment structurally
  impossible rather than carefully avoided.
- **contentExtent** — session length in beats and total row height, feeding scrollbar
  ranges, zoom-to-fit, and the minimap.

Rules: the transform is the *only* holder of these facts (§2.3); every consumer reads,
none cache; changes publish through the invalidation ledger; `ViewportState` persists
per project (the model already defines the snapshot). The status bar reports the
transform's actual value — after story 341 the "125%" it prints is true by
construction.

### 3.2 The peak cache

A per-clip pyramid of min/max summaries at power-of-two decimations, computed once per
(clip content, source window) on a background thread, invalidated by destructive edits
and slip/trim (which change the window, not the pyramid — the pyramid is keyed on the
*source buffer*, and the window is applied at paint time). Painting reads the level
nearest the current pixels-per-beat and never touches raw samples on the FX thread.
The same cache serves the arrangement, the editor waveform panel, album previews, and
(later) browser row thumbnails — one decode, four surfaces. *Why one cache:* §1.7's
per-frame rescan is the cost of not having it; four independent caches would repeat
§1.2's divergent-copy mistake at the data layer.

### 3.3 The command registry

One entry per user-invocable command: stable id, display label, category, handler,
optional default key combination, enablement supplier + disabled reason, optional
observable checked/selected state, and a visibility flag for context-only commands.
`DawAction` remains the enum for *bindable* commands; the registry is the superset that
also carries menu-only commands (exports, snapshots, workspaces, plugin activation).
Menu construction consumes registry entries; the palette lists all visible entries
(rendering disabled ones greyed with their reason — the rendering already exists and is
unreachable today, §1.4); the accelerator map is rebuilt from the registry on every
rebind; Help renders the registry grouped by category via the live display text.
Enablement stays in `MenuEnablementPolicy` terms — the registry references it, it does
not fork it.

### 3.4 Drag visual state and its presenter

The advisor's state machine is kept exactly as designed (story 197). This book adds the
missing half: a single **drag presenter** — an overlay layer owned by the arrangement
(and a lightweight equivalent for browser/rack list drags) that renders, from
`DragVisualState`: the ghost preview at the *snapped* position (translated through the
viewport transform), the drop-target row/slot highlight (tokens: the `-accent-soft`
family, never hex), the snap guide line with its label, and the modifier cursor
(copy/link/no-drop). Drag handlers gain one obligation: call `update(...)` on every
pointer move. The presenter is the *only* renderer of drag state — surfaces never
hand-roll their own ghosts (the slip/trim previews already drawn by the canvas migrate
into it in story 343's cleanup or are explicitly exempted as tool-local previews).

### 3.5 The invalidation ledger

A small taxonomy of repaint causes, each with a declared scope:

| Cause | Scope invalidated |
|---|---|
| Playhead moved | Playhead overlay layer only |
| Loop/punch/selection range changed | Overlay layer only (with change short-circuit) |
| Clip added/removed/moved/trimmed/slipped | Affected lanes (base layer) + overlay |
| Track added/removed/reordered/expanded/folded | Row layout → full base repaint |
| Viewport changed (zoom/scroll/height) | Full repaint (cheap: peak cache absorbs the cost) |
| Undo/redo | Whatever the reversed action declares — published by the undo manager wrapper, not remembered per call site |
| Theme/density/motion changed | Full repaint + colour re-resolution |

Every model mutation that affects arrangement pixels publishes exactly one cause.
`CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` §5's cascade contract is the transport for
these signals; this ledger defines their *payload* for the painting layer.

---

## 4. Architecture — the three completeness stacks

### 4.1 The arrangement navigation and rendering stack

```
      gestures                       one transform                          layered painting
 ┌──────────────────┐   writes   ┌────────────────────────┐   reads    ┌──────────────────────────────────────┐
 │ wheel = v-scroll │──────────►│  VIEWPORT TRANSFORM     │◄──────────│  BASE LAYER (repaints on base causes) │
 │ Shift+wheel = h  │           │  (ArrangementNavigator) │           │   lanes · clips · waveforms · autom.  │
 │ Ctrl+wheel = zoom│           │  pixelsPerBeat          │           │   ← PEAK CACHE (bg-computed pyramids) │
 │  (cursor-anchor) │           │  scrollXBeats           │           ├──────────────────────────────────────┤
 │ Alt+wheel = row h│           │  scrollYPixels          │           │  OVERLAY LAYER (cheap, frequent)      │
 │ +/- keys · menu  │           │  rowLayout (heights,    │           │   playhead · loop/punch · selection · │
 │ Home / fit       │           │   autom. expansion,     │           │   snap grid                           │
 │ minimap drag     │           │   fold state)           │           ├──────────────────────────────────────┤
 │ scrollbars       │           │  contentExtent          │           │  DRAG LAYER (only during drags)       │
 └──────────────────┘           └───────────┬────────────┘           │   ghost · target highlight · snap line│
                                            │ same instance          └──────────────────────────────────────┘
                          ┌─────────────────┼───────────────────┬───────────────────┐
                          ▼                 ▼                   ▼                   ▼
                   TimelineRuler      Track strip panel     Minimap view      Hit-testing
                   (grid, labels,     (rows sized FROM      (viewport box     (x→beat, y→row via
                    locators)          rowLayout)            drag-to-pan)      the SAME rowLayout)
```

The load-bearing property: **one arrow direction**. Gestures write the transform;
every surface reads it; nothing else stores geometry. The invalidation ledger (§3.5)
decides which layer repaints; the peak cache keeps the base layer's worst case bounded.

### 4.2 The input and command stack

```
                          key event                    ┌─────────────────────────────────────────────┐
  Scene ──► FOCUS GUARD ───────────────► accelerators  │            COMMAND REGISTRY                 │
            (one filter: bare keys      (rebuilt from  │  id · label · category · handler            │
             suppressed when target      registry on   │  binding (via ONE KeyBindingManager)        │
             is a text input /           every rebind) │  enablement + disabled reason               │
             editing cell)                             │  checked-state (observable)                 │
                                                       └──────┬──────────┬─────────┬─────────┬──────┘
                                                              │ project  │ project │ project │ project
                                                              ▼          ▼         ▼         ▼
                                                          Menu bar    Palette    Help     Toolbar
                                                          (Check/     (all       (live    (tooltip
                                                           Radio       entries,   table)   shortcut
                                                           items       greyed +           text)
                                                           bound to    reason)
                                                           checked
                                                           state)
```

Handlers appear once, in the registry. The conformance harness (§4.3, §6) closes the
loop: an entry with no handler, or a `DawAction` absent from the registry, fails the
build.

### 4.3 The conformance harness stack

```
  SOURCE-SCAN SENTINELS (extend the existing SourceScanSupport family — build-time, no toolkit)
  ├─ Harness A · command liveness: every DawAction has a registry handler or an in-source exemption
  ├─ Harness B · reachability: named wiring seams have production callers; no setDisable(true)-forever items
  └─ Harness C · visual conformance: animation drivers gate on MotionManager; themed surfaces use tokens
  RUNTIME CONFORMANCE TESTS (headless FX where needed)
  ├─ registry↔menu↔palette↔help agree on the same entry set and display text
  ├─ rebind round-trip: change a binding, assert old combination inert + new one live, no restart
  └─ toggle truth: checked state tracks actual panel/view visibility through all input routes
```

---

## 5. Behavioural contracts

The tables a reviewer checks a PR against. "Proof" names the discriminating check —
per the repo's green-suite lesson, a test only counts if it *fails today*.

### 5.1 Navigation gesture contract (story 341)

| Gesture / control | Required effect | Proof |
|---|---|---|
| Ctrl+wheel over arrangement | pixelsPerBeat changes; **beat under cursor stays fixed**; canvas + ruler + minimap update together | Cursor-anchor test: beat at pointer identical before/after |
| Wheel | Vertical scroll (clamped to contentExtent); strips scroll in lock-step | Row N aligns with strip N at every offset |
| Shift+wheel | Horizontal scroll in beats | Ruler origin and canvas origin agree |
| Alt+wheel | Track-height zoom via rowLayout | Strip heights change with lanes, never independently |
| +/- keys, menu zoom, context-menu zoom | Same path as Ctrl+wheel (anchor = view centre) | Single code path (§2.8) |
| Home / zoom-to-fit | Transform frames contentExtent exactly | Fit shows first and last clip |
| Minimap click/drag | Scroll to framed region; viewport box tracks live | Box position derives from transform |
| Scrollbars | Visible when content exceeds viewport; two-way bound to transform | Ranges follow contentExtent |
| Status bar zoom % | Reads the transform (truth by construction) | Reported % equals pixelsPerBeat ratio |
| Persistence | ViewportState saved per project, restored on open | Reopen restores zoom + scroll |

### 5.2 Visual truth contract (story 342)

| Surface | Required truth | Today's violation |
|---|---|---|
| Clip waveform | Renders exactly the source window `[sourceOffset, sourceOffset+duration)`; splits/trims/slips visibly change it | Full buffer stretched (`ClipWaveformRenderer.java:83‑85`) |
| Editor "Audio Waveform" panel | Fed from the peak cache on selection change + after every destructive edit, playhead cursor bound | Never fed (`WaveformDisplay.setWaveformData`: 0 callers) |
| Album Assembly previews | Fed per entry from the peak cache | Never fed (`AlbumAssemblyView.java:533`) |
| Clip gain envelope | Renders (samplesPerBeat wired on init/load/tempo-change) and gains story-140 editing gestures | Gated off forever (`ArrangementCanvas.java:175`) |
| SRC-mismatch badge | Session rate wired on init/load; badge appears on mismatched clips | Gated off forever (`ArrangementCanvas.java:185`) |
| Header beat labels | The static label row is **deleted** — the live ruler is the only timeline | Fake "1"…"8" (`main-view.fxml:135`) |
| Strip/lane alignment | Both render from rowLayout; expand/fold affects both | Independent sizing (`TrackStripController.java:1145‑1148`) |
| Folded automation lanes | Fold-aware hit heights; folded strip is read-only | Fixed-constant hit-test (`ClipInteractionController.java:365`) |
| Right-edge trim | Re-extends up to true source length (slip handler's computation reused) | Capped at drag-start duration (`ClipTrimHandler.java:337‑344`) |
| Pencil on MIDI track | Creates/extends MIDI content or no-ops with a hint — never a phantom AudioClip | Phantom clip (`ClipInteractionController.java:1004`) |
| Ruler frame→beat conversion | Uses the live session rate | 44.1 kHz constant (`TimelineRuler.java:105`) — honesty rule here; arming is story 328's (Book 2) |

### 5.3 Drag feedback contract (story 343)

| Phase | Presenter must render | Source of truth |
|---|---|---|
| beginDrag | Ghost at grab point; source row/slot marked | Advisor state (already produced, discarded today) |
| every pointer move | `update(...)` called; ghost at snapped position; target highlight; snap line + label; modifier cursor | DragVisualState via the viewport transform |
| commit | Ghost removed; clip appears where the ghost was (no teleport) | Model mutation + invalidation ledger |
| cancel (Esc) | Revert animation the advisor already sequences, actually visible | Advisor CancelRevert |
| dock drags | Drop-zone highlight visibly wins for all five zones | Dedicated overlay or cascade-safe rule — never an equal-specificity class fighting `.content-area` (`styles.css:1274/1365`) |
| file drop → import | Async decode with `ProjectOperationProgress`; **all** dropped files imported; drop beat passed as a parameter, transport untouched | `AudioImportController.java:98/224/226` violations retired |

### 5.4 Shortcut safety contract (story 344)

| Rule | Detail | Proof |
|---|---|---|
| Bare-key guard | Modifier-free accelerators suppressed when the event target / focus owner is a text input or editing cell (one scene-level seam, the `EditorFrameSkin.java:854` pattern) | Type "record" into rename box: transport untouched, text intact |
| Modified keys stay global | Ctrl/Alt/Shift combinations unaffected by the guard | Ctrl+S works while renaming |
| One KeyBindingManager | Settings mutates the same instance the scene/menus consume; the `SettingsModel` duplicate is retired | Object-identity + rebind round-trip test |
| Live re-registration | On apply: accelerator map rebuilt, MenuItem accelerators re-projected, tooltips refreshed; old combination inert immediately | No-restart rebind test |
| Help is generated | Registry-derived, grouped by category, live display text | Rebind visible in Help without restart |
| Palette completeness | All registry entries listed; disabled entries greyed with reason | Menu-only command reachable via Ctrl+K |
| No advertised dead action | Every `DawAction` handled or exempted-with-story-ref (harness A) | Build fails on unhandled action |

### 5.5 Command surface contract (story 345)

| Command class | Requirement |
|---|---|
| Every menu item | Terminates in observable behaviour. A window it opens must be able to reach a non-empty state from within the app |
| Render queue | Gains its producer path: export surfaces enqueue jobs (completes existing story 243's unlanded goal); queue window offers an add path |
| Deliverable exports | File ▸ Export submenu wires the four landed surfaces (existing stories 181, 185, 026, 168/album); jobs route through the queue, cancel deletes partial output |
| Panel/view toggles | CheckMenuItem / RadioMenuItem bound to registry checked-state; all input routes (menu, F-keys, Ctrl+1‑4, dock chrome) converge on the dock model so state never forks |
| View restore | Total: every persistable view restores a working centre (build-on-restore or coerce), and a re-request always repairs the pane |
| Dynamic menus | Workspaces menu is reactive (the `LayoutsMenu` pattern) and gains its Delete path |
| Orphan commands | TOGGLE_SESSION_MANAGER handled; HRTF profile browser gets its menu route |
| Placeholders | Zero permanently disabled items (harness B); Hub/Welcome placeholder facts implemented or removed |
| Tools | Persistent tool selector (toolbar segment) with active-tool indication + Tools menu carrying the existing accelerators — a mode this destructive (§1.6 phantom deletions) is never invisible |

### 5.6 MIDI clip parity matrix (story 346)

| Operation | Audio today | MIDI today | Required |
|---|---|---|---|
| Select / marquee | yes | select only | parity (group selections mix kinds) |
| Drag-move | yes | **no drag armed** | parity, with ghost + snap via §5.3 |
| Cut/copy/paste/duplicate/delete | yes | silent no-op | parity, same undo actions |
| Split / glue | yes | no-op (audio-only hit-test) | parity |
| Nudge | yes | no-op | parity |
| Slip | yes | yes | keep — it is the template: the one op already handling both kinds proves the substrate |
| Eraser | yes | no-op | parity |
| Pencil | creates clip | phantom AudioClip | story 342 owns the fix; 346 asserts the guard holds for every op |

The parity bar: after 346, a test enumerating arrangement clip operations passes each
against a MIDI clip with the same observable contract (model change + undo + ledger
publication) as audio. Silent no-ops are the defect class being eliminated, so a no-op
without a status reason fails the test.

### 5.7 Repaint economy contract (story 347)

| Rule | Detail |
|---|---|
| No idle work | Transport stopped + no gesture + no dirty scope ⇒ zero canvas/ruler painting; the main timer stops (or its callbacks no-op measurably) when nothing animates, and stops on shutdown |
| Change short-circuits | Every overlay setter guards like `setPlayheadBeat` (`ArrangementCanvas.java:209`), never like today's `setLoopRegion` (`:231`) |
| Peaks cached | Painting never scans raw samples on the FX thread; pyramid levels keyed by decimation (§3.2) |
| Layered painting | Playhead/loop/selection on the overlay layer; base repaints only on base causes (§3.5) |
| Undo/redo repaint | Published through the ledger — proven by disabling the animation loop in a test and asserting undo still repaints (the §1.7 crutch test) |
| Transport glow | Pseudo-class state toggled once per transport change; any pulse honours Reduce Motion; colours from tokens (retires `TransportGlowAnimator`'s per-frame `String.format` + hex) |
| Budget | With a representative session idle, arrangement paint cost ≈ 0; during playback, per-frame cost bounded by overlay work only |

### 5.8 Motion and theme conformance contract (story 347)

| Rule | Detail |
|---|---|
| Combined gate | Every GpuCanvas display adopts the controls-package gate: local animated flag AND MotionManager allows (captured once, `controls/LevelMeter.java:167` pattern); Reduce Motion freezes decorative motion, data updates still render on arrival (story 279's landed distinction) |
| Token-derived colours | Displays resolve background/grid/text/trace from ancestor CSS tokens (the IconNode tint pattern) and re-render on theme change; legible under all selectable themes |
| No new hex | The existing `LegacyHardcodedColorAuditTest` scope extends to the display package as violations are retired (harness C) |
| Hidden window ⇒ no animation | Canvas drivers also gate on stage showing (the repo's hidden-Stage animation-leak lesson) |

---

## 6. Cross-cutting wiring — the dead-control conformance harness

The pattern is modelled on the repo's existing sentinel family (§1.9): a shared scan
harness (`SourceScanSupport` — module-root location + comment/string stripping), one
test class per rule, each with its own scope, forbidden/required patterns, offender
messaging, and a non-empty guard so a scan that finds nothing to check fails rather
than passing vacuously. Exemptions are in-source markers adjacent to the exempted
declaration, carrying a story reference — the same discipline as the existing
mandatory-TODO sentinels. Reuse the existing marker conventions; do not invent a
parallel mechanism.

### 6.1 Harness A — command liveness (lands with story 344)

Asserts: every `DawAction` constant is either (a) mapped to a handler in the registry,
or (b) explicitly exempted in source with a story reference. Also asserts every action
with a default key combination is registered on the scene, and that the Settings
key-binding catalogue offers only live-or-exempted actions. Today this fails for the
three punch actions (exempt with a reference to story 328,
`RECORDING_RELIABILITY_DESIGN_BOOK.md`, which owns punch arming) and
TOGGLE_SESSION_MANAGER (fixed in story 345) — the harness ships with those two
exemptions and the exemptions are deleted as the owning stories land.

### 6.2 Harness B — reachability (lands with story 345, extended by 341–343)

Two scans:

1. **Wiring-seam liveness.** A declared list of public seams that exist to be wired
   (the §1 corpus: `setPixelsPerBeat`, `setScrollXBeats`, `setSamplesPerBeat`,
   `setSessionRateHz`, `setWaveformData`, `DragVisualAdvisor.update`,
   `RenderQueue.enqueue`, `onManageHrtfProfiles`, …) each of which must have at least
   one production caller. The list starts as the exemption ledger — every entry marked
   with the story that will wire it — and each landing story deletes its entries. When
   the list is empty the scan generalises: any *new* public method on the catalogued
   wiring surfaces with zero production callers fails.
2. **No permanent placeholders.** No menu item constructed disabled-forever
   ("Coming soon" tooltips, unconditional setDisable at build time). Conditional
   enablement through `MenuEnablementPolicy` is of course fine.

### 6.3 Harness C — visual conformance (lands with story 347)

Asserts: every AnimationTimer/Timeline/transition driver in the audited UI packages
consults MotionManager (or carries the existing animation-allowed exemption marker with
rationale); GpuCanvas display subclasses resolve their palette from tokens (extends
`LegacyHardcodedColorAuditTest`'s scope as §5.8 retires violations); overlay setters in
the painting layer carry change short-circuits (pattern-level scan on the known setter
list).

### 6.4 Runtime conformance (woven through stories 344–345)

Headless-FX tests asserting the projections agree: registry entries == menu items ==
palette entries (ids and display text); rebind round-trip with no restart; toggle
checked-state tracks real visibility through every input route; every persisted view
restores a non-null, populated centre. These complement the dialog-dismissibility
conformance test that `FAILURE_SURFACING_DESIGN_BOOK.md` story 339 owns — same
philosophy, different bug class; do not duplicate its dialog scan here.

### 6.5 Exemption discipline

An exemption is a *debt record*, not a waiver: in-source, adjacent, story-referenced,
and deleted by the story that pays it. A sentinel whose exemption list grows for two
consecutive stories is treated as a failed design and re-reviewed. This mirrors the
repo's landed experience: sentinels with unmaintained exemption lists rot into noise;
sentinels with shrinking lists finish migrations.

---

## 7. Integration with the other design books

| Book | What this book consumes from it | What this book provides to it |
|---|---|---|
| `AUDIO_ENGINE_WIRING_DESIGN_BOOK.md` | Engine-driven transport clock and loop truth (story 315 re-binds the ruler's Transport on project switch — §1.2's stale-ruler bug is *its* story; this book's navigation work must route around, not re-fix); the metering tap bus (318) and analyzer feeds (319) that give visual surfaces real data | Honest empty-states and render-economy rules so its live feeds land on surfaces that repaint affordably (§5.7) and never fabricate while unfed (§2.2) |
| `RECORDING_RELIABILITY_DESIGN_BOOK.md` | Punch arming, count-in, loop-take lanes, and COMP activation (story 328 owns the capture workflows; the ruler's punch-rate honesty and take-lane rendering hooks in §5.2 serve it) | The tool-selector surface (§5.5) its COMP tool will occupy; harness A exemption that keeps punch actions honest until 328 lands |
| `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` | Audio reload on open (329) so waveforms have data after reopen; its app-exit protocol story 333 owns File ▸ Exit/Save As — this book's menu-truth story deliberately excludes those items | ViewportState persistence expectations (§5.1); the menu-truth bar its new File items must meet |
| `FAILURE_SURFACING_DESIGN_BOOK.md` | Global exception visibility (336) so a throwing handler is never mistaken for a dead control; notification injection + keybinding-conflict surfacing (339 — the conflict UX excluded from story 344's scope) | The conformance-harness pattern §6 (its dialog-dismissibility test is the sibling); the command registry's failure-surfacing seam (a failing command handler is nameable) |
| `UI_DESIGN_BOOK.md` | Tokens, spacing, icon language for the new chrome (tool selector, minimap, scrollbars, drag ghost styling); its §1/§6 inventories are stale — do not cite them as ground truth | Retirement of hex-coloured, hardcoded-dark surfaces (§5.8) toward its token contract |
| `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` | The VM/event-bus substrate and cascade contract — the invalidation ledger's causes travel as its signals; §2.8 keyboard/menu/click-one-intent is generalised by §2.6 | The command registry as the concrete realisation of its §2.8; repaint causes as declared cascade effects |
| `PLUGIN_VIEW_DESIGN_BOOK.md` | The EditorFrame focus-guard precedent (§5.4); plugin editor chrome stays its domain | Registry entries for plugin activation commands (menu/palette) |
| `SETTINGS_VIEW_DESIGN_BOOK.md` | The apply/catalogue contract for the key-binding rows | The single-KeyBindingManager requirement (§5.4) its catalogue must respect |
| `PROJECT_MANAGER_DESIGN_BOOK.md` | Hub/Welcome surface ownership | The mockup-fact cleanup bar (§5.5: implement the sample-rate/session facts or remove the rows) |

---

## 8. Migration path

Seven stages, one story each, in dependency order. Every stage is independently
shippable; each names its scope, its landing proof (discriminating checks that fail
before and pass after), and what it unblocks. Existing backlog stories are woven in
where a stage completes or unblocks them.

### Stage 1 — Shortcut Safety and Live Rebinding (story 344)

**Scope.** The scene-level bare-key focus guard (§5.4); one `KeyBindingManager`
instance shared by Settings, scene, and menus with live re-registration on apply; the
Help shortcut table generated from live bindings; the command palette fed from the
registry seed (initially the existing handler map, so menu-only coverage completes in
Stage 2) with disabled-entry rendering reachable; **harness A** (§6.1) with its two
initial exemptions. Completes the live-apply half of **story 010 — keyboard shortcuts
(existing)** and the coverage/disabled-rendering goals of **story 192 — command
palette (existing)**. Keybinding-*conflict* feedback is excluded — story 339
(`FAILURE_SURFACING_DESIGN_BOOK.md`) owns it.

**Proof.** Typing "record" in the track-rename field neither starts recording nor
toggles anything (fails today); a rebind applied in Settings fires on the new
combination and not the old, without restart (fails today); Help shows the rebound
text; harness A fails when a new `DawAction` ships unhandled.

**Unblocks.** Safe single-key tool shortcuts for the Stage-2 tool selector; the
registry substrate Stage 2 extends; every later stage's new shortcuts arrive guarded.

*Why first:* it is the active session hazard (a rename can start recording), it is
independent of the arrangement chain, and it lands the harness pattern early so every
subsequent stage inherits enforcement.

### Stage 2 — Menu Truth and Export Reachability (story 345)

**Scope.** The command registry generalised to all menu commands (§3.3); File ▸ Export
submenu wiring the four landed surfaces and giving the render queue its enqueue path —
completing **story 243 — instantiate render queue view (existing)**, **story 181 —
stem + master bundle export (existing)**, **story 185 — OMF/AAF interchange
(existing)**, **story 026 — ADM-BWF export (existing)**, and the album/ISRC surface of
**story 168 (existing)**; checked/radio state for Window-menu toggles converging on the
dock model (wiring `setOnViewChanged` at last); total view restore (the Workshop blank-
centre repair); reactive Workspaces menu + Delete; TOGGLE_SESSION_MANAGER handler; HRTF
browser menu route; removal of all permanently disabled placeholder items and
Hub/Welcome mockup facts (implement or remove); the persistent tool selector with
active-tool indication; **harness B** (§6.2) and the runtime registry-agreement tests
(§6.4). File ▸ Exit / Save As / Close Project are *excluded* — story 333
(`PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`) owns the exit protocol.

**Proof.** An export enqueued from the menu produces a running job in the queue window
(impossible today); Window-menu items show correct checked state after any toggle
route; quitting in Workshop restores a working centre; harness B fails on a
"Coming soon" item; palette lists a menu-only command.

**Unblocks.** The offline-render deliverables layer becomes user-reachable (exports
render offline and do not wait on Book 1's live-engine stories); Stage 5's drag-drop
work inherits truthful menus for import; the tool selector gives Stages 4–6 a visible
mode surface (and the slot Book 2's COMP activation will occupy).

### Stage 3 — Arrangement Navigation: Zoom That Zooms, Scrolling, Minimap (story 341)

**Scope.** Instantiate `ArrangementNavigator` as the single viewport transform (§3.1);
route every zoom/scroll gesture through it (§5.1); drive canvas + ruler +
strip-panel + hit-testing from it; build the minimap view over `MinimapModel`;
scrollbars; cursor-anchored wheel zoom; ViewportState persistence per project;
status-bar zoom truth. Completes **story 021 — waveform zoom and minimap (existing)**.
The ruler's stale-Transport re-bind is story 315's (Book 1); this stage coordinates
with it by keeping all ruler geometry reads on the transform, and must not capture
project state by value anywhere new.

**Proof.** Ctrl+wheel visibly zooms with the beat under the cursor fixed; a 40-track,
200-bar session is fully reachable by wheel and minimap; strips stay aligned at every
scroll offset; reopening a project restores zoom/scroll; harness B's seam list shrinks
by the five viewport setters.

**Unblocks.** Stage 4 (waveform windows and peak-cache keys need live pixels-per-beat),
Stage 5 (ghost/snap positions translate through the transform), Stage 7 (viewport
changes become ledger causes), and **story 032 — markers and locators (existing)**,
whose ruler flags and jump navigation only make sense once the ruler scrolls and zooms.

### Stage 4 — Waveform and Clip Visual Truth (story 342)

**Scope.** Source-window-correct waveform rendering (§5.2) backed by the peak cache
(§3.2); feed the Editor waveform panel and Album Assembly previews from the same cache;
wire samplesPerBeat and sessionRateHz on init/load/tempo-change so the gain envelope
and SRC badge render — completing the feed half of **story 140 — per-clip gain
envelope (existing)** (and its editing gestures) and **story 126 — SRC badge
(existing)**; delete the fake header beat-label row; unify strip/lane heights on the
transform's rowLayout; fold-aware automation hit heights; right-edge trim re-extension;
pencil track-kind guard.

**Proof.** Split a clip: each half draws its own window (fails today); commit a slip:
the waveform visibly shifts (fails today); select a clip: the Editor panel shows its
waveform (fails today); a 44.1 kHz import into a 96 kHz session shows the badge;
strips align with lanes with an automation lane expanded.

**Unblocks.** Stage 7's economy work (the cache it needs now exists); **story 027 —
browser sample preview (existing, deferred scope)** — row thumbnails become a small
consumer of the same peak-cache infrastructure; honest take-lane rendering for Book 2's
comping story 328.

### Stage 5 — Drag-and-Drop Feedback Rendered, Import Off the FX Thread (story 343)

**Scope.** The drag presenter (§3.4, §5.3) rendering ghost/highlight/snap-line/cursors
from `DragVisualState`, with `update(...)` called from every drag handler across
arrangement, browser, and insert-rack drags — completing **story 197 — drag-and-drop
target visual feedback (existing)** and **story 248 — integrate the drag visual
advisor into arrangement and mixer drag sources (existing)**; the dock drop-zone
highlight made cascade-proof for all five zones; audio import moved to a background
thread with progress, importing every dropped file, drop beat passed as a parameter.

**Proof.** Dragging a clip shows a ghost at the snapped position and the drop lane
highlighted (nothing renders today); Esc visibly reverts; every dock zone's highlight
visible over its content; a 10-file drop imports 10 files without freezing the UI or
moving the playhead.

**Unblocks.** Stage 6 (MIDI drag-moves inherit the presenter); trustworthy browser →
arrangement workflows for sample-based sessions.

### Stage 6 — MIDI Clip Editing Parity in the Arrangement (story 346)

**Scope.** The parity matrix (§5.6): drag-move, cut/copy/paste/duplicate/delete,
split/glue, nudge, eraser, and group selection for MIDI clips, with the same undo
actions and ledger publications as audio; the slip implementation is the template. The
pencil fix stays in Stage 4; 346 owns operation parity and asserts the guard across
all ops. Weaves **story 143 — cross-track range selection (existing)**: the RANGE-tool
time-selection substrate should land with (or immediately after) this stage, since
parity makes mixed-kind selections meaningful and Stage 3 made over-lane marquees
reachable.

**Proof.** The parity test sweep (§5.6) passes each operation against a MIDI clip —
today every one of them except slip fails as a silent no-op.

**Unblocks.** MIDI-centric arranging as a first-class workflow; ripple/trim-to-selection
consumers get selections that can actually be created (143).

### Stage 7 — Render Economy and Motion Conformance (story 347)

**Scope.** Layered, ledger-driven repainting (§3.5, §5.7): change short-circuits on
every overlay setter, playhead on its own layer, idle frames free, the main timer
stopped when idle and on shutdown; explicit undo/redo repaint (the §1.7 crutch removed
last, after Stages 3–6 made every mutation publish invalidation); transport glow as
pseudo-class state honouring Reduce Motion with token colours; the combined motion gate
on all GpuCanvas displays and token-derived display palettes — conforming to landed
**story 279 (Reduce Motion)** and the story-277 theme-token architecture; **harness C**
(§6.3). This stage deliberately runs last: deleting the 60 fps loop is only safe once
every earlier stage's mutations are ledger-clean.

**Proof.** Idle CPU for the arrangement view ≈ 0 with the transport stopped (today it
rescans every sample at 60 fps); undo repaints with the animation loop disabled in the
test harness (fails today); Reduce Motion freezes display animations; the light theme
renders legible analyzers; harness C fails on a new ungated animation driver.

**Unblocks.** FX-thread headroom for Book 1's live meter/analyzer feeds (318/319) to
land on affordable surfaces; laptop battery life during sessions; the conformance
harness reaches its full three-scan strength.

---

## 9. Rejection list (do not bring these back)

1. **Placeholder controls** ("Coming soon" items, disabled-forever entries, decorative
   rulers). If it does not work, it does not render. Harness B enforces.
2. **Model-without-consumer landings.** Landing "model classes and tests" and calling
   the feature done is how stories 021/197/143 became audit findings. A story that adds
   a wiring seam either wires it or registers it in the harness-B ledger with the story
   that will.
3. **The clock as correctness.** Never rely on a periodic repaint to hide missing
   invalidation. The ledger is the contract; the §5.7 undo test keeps it honest.
4. **Per-frame inline style mutation.** setStyle in an animation loop forces CSS
   re-parse every pulse. State changes are pseudo-classes; continuous decoration
   honours Reduce Motion.
5. **Private copies of shared geometry.** No surface stores its own row heights,
   pixels-per-beat, or sample rate. One transform (§2.3); divergent copies are §1.2's
   entire bug family.
6. **Second instances of stateful managers.** The duplicate `KeyBindingManager` made
   rebinding cosmetic. Managers with observers are injected, never re-constructed over
   the same backing store (the repo's capture-singleton-once lesson).
7. **Status-bar-only feedback for modes.** A transient text line is not mode
   indication. Destructive modes (eraser, scissors) require persistent visible state.
8. **Silent no-ops on valid input.** An operation that declines (wrong clip kind,
   empty selection, invalid target) says why — status hint, disabled reason, or
   notification. The MIDI ops and the multi-file drop failed exactly this way.
9. **Equal-specificity CSS toggling for critical affordances.** A drop highlight that
   depends on declaration order will regress. Use dedicated overlays or
   cascade-proof selectors, and keep the §6.4 visibility assertion.
10. **Hand-maintained projections of the command set.** The hardcoded Help table and
    the palette's partial coverage are the same bug: a second copy of the registry.
    Projections are generated or they are wrong.
11. **Raw-sample scans on the FX thread.** All waveform painting reads the peak cache.
    A renderer that touches `float[]` audio data on the FX thread fails review.
12. **Vendor-named hardware in any surface or story.** Name the open standard and the
    category (per repo convention), never the brand.

---

## Appendix A — Mapping to existing code

| Book concept | Current class / evidence (today's tree) |
|---|---|
| Viewport transform | `ArrangementNavigator` (+`ZoomLevel`, `TrackHeightZoom`, `ScrollPosition`, `MinimapModel`, `ViewportState`) — `daw-app/.../ui/ArrangementNavigator.java:23` (dormant) |
| Dead zoom routes | `ViewNavigationController.java:847‑885`, `TrackStripController.java:1089/1102`, `MainController.java:4302‑4304` |
| Unconsumed viewport seams | `ArrangementCanvas.java:162/175/185/191/197`, `TimelineRuler.java:159/244‑257` |
| Waveform windowing | `ClipWaveformRenderer.java:83‑107`, `ClipOverlayRenderer.java:86‑160`, `AudioClip.java:471/484` |
| Peak cache | New; replaces the per-frame scan at `ClipWaveformRenderer.java:93` |
| Empty waveform consumers | `AudioEditorView.java:44‑92`, `AlbumAssemblyView.java:533‑538`, `WaveformDisplay.java:64` |
| Drag advisor / presenter | `DragVisualAdvisor.java:155/275` (unconsumed), `ClipInteractionController.java:588‑592/1204‑1248`, `BrowserPanel.java:681`, `InsertEffectRack.java:658`; presenter is new |
| Drop-zone cascade loss | `styles.css:1274` vs `:1365/:1376`, `DockDropZones` |
| Import off-thread | `AudioImportController.java:98/224/226` |
| Focus guard precedent | `EditorFrameSkin.java:854`; missing at `KeyboardShortcutController.java:297` |
| Bare-key inventory | `DawAction.java:18‑37/173‑182` |
| Duplicate KeyBindingManager | `MainController.java:863` vs `SettingsModel.java:660`; `SettingsDialog.java:2163` |
| Command registry seed | Palette supplier `MainController.java:2070‑2092`, `CommandPaletteEntry.java:50‑58`, `MenuConstructionService`, `MenuEnablementPolicy`, `KeyBindingManager.getDisplayText` |
| Hand-typed Help table | `HelpDialog.java:103‑138` |
| Render queue dead-end | `RenderQueue.java:118` (0 production callers), `MainController.java:4124`, `RenderQueueView` |
| Unreachable exports | `BundleExportDialog`, `AafExportDialog`, `AdmBwfExportController`, `AlbumAssemblyView` (test-only construction) |
| Toggle statelessness / desync | `MenuConstructionService.java:411‑419`, `MainController.java:2470‑2473/3313‑3317`, `ViewNavigationController.java:714‑716` |
| Workshop blank centre | `ViewNavigationController.java:256‑258/286/309‑310/329‑330`, `ToolbarStateStore` |
| Stale Workspaces menu | `WorkspacesMenu.java:85` vs `LayoutsMenu.java:80/104` |
| Placeholder corpus | `TrackStripController.java:951‑1379`, `WelcomeView.java:356`, `ProjectHubView.java:237/711` |
| Invisible tools / dead COMP | `ViewNavigationController.java:672‑675`, `EditorView.java:513‑528`, `EditTool.java:35`, `ClipInteractionController.java:1083` |
| MIDI parity gaps | `ClipEditController.java:96‑104/207‑246/395‑397`, `ClipInteractionController.java:256/891‑897/1004/1017‑1040` |
| Repaint hot loop | `AnimationController.java:97‑131`, `MainController.java:4565‑4589`, `ArrangementCanvas.java:209‑236`, `MainController.java:3839‑3857` (undo crutch) |
| Glow / motion / theme violations | `TransportGlowAnimator.java:40‑68`, `GpuCanvas.java:439`, `SpectrumDisplay.java:40‑42`; conforming patterns `controls/LevelMeter.java:167`, `ButtonPressAnimator.java:57` |
| Sentinel harness | `daw-app/src/test/.../ui/SourceScanSupport.java` + the `RunLaterConsolidationTest`/`LegacyHardcodedColorAuditTest`/`EveryDialogConformsTest` family |
| Stale-ruler transport (Book 1's fix) | `ArrangementCanvasFactory.java:49`, `TimelineRuler.java:118`, `MainController.java:1453/2384`, `DawProject.java:129` |

## Appendix B — Cross-references

- **SKILL files:** `javafx-application-design` (§ Canvas rendering, properties,
  threading, CSS — the layered-canvas and pseudo-class guidance behind §5.7);
  `dawg-annotations-reflection` (marker/annotation conventions the harness exemptions
  reuse); `research-daw` (§ DAW architecture — editor viewport and command patterns in
  Ardour/Audacity/LMMS informing §3.1/§3.3).
- **New companion books:** `AUDIO_ENGINE_WIRING_DESIGN_BOOK.md`,
  `RECORDING_RELIABILITY_DESIGN_BOOK.md`, `PERSISTENCE_INTEGRITY_DESIGN_BOOK.md`,
  `FAILURE_SURFACING_DESIGN_BOOK.md` — roles and hand-offs in §7.
- **Existing books:** `UI_DESIGN_BOOK.md` (tokens/components; §1/§6 stale),
  `CONTROL_SYNCHRONIZATION_DESIGN_BOOK.md` (cascade/bus substrate),
  `PLUGIN_VIEW_DESIGN_BOOK.md`, `SETTINGS_VIEW_DESIGN_BOOK.md`,
  `PROJECT_MANAGER_DESIGN_BOOK.md`.
- **Existing stories completed or advanced by this book:** 010, 021, 026, 027
  (deferred scope), 032, 126, 140, 143, 168, 181, 185, 192, 197, 243, 248 — woven into
  §8's stages. Landed stories relied on: 277 (theme tokens), 278 (density), 279
  (Reduce Motion), 283/292 (event bus), 295 (status strip), 306 (settings shell).
- **Audit provenance:** the 2026 ten-area audit, areas 01 (startup/hub), 04
  (arrangement editing), 07 (menus/actions/shortcuts), 08 (visual surfaces/meters);
  adjudication history in the repo-root `GAP_AUDIT_NOTES.md`.
