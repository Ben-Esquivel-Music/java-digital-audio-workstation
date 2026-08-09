---
title: "Full-State Persistence: Plugin Inserts, Take Stacks, Tempo Map"
labels: ["bug", "persistence", "plugins", "recording", "transport"]
---

# Full-State Persistence: Plugin Inserts, Take Stacks, Tempo Map

## Motivation

Whole categories of a session either cannot be expressed by the project format or are silently discarded on load:

- **Plugin-backed inserts are silently dropped, shifting the chain.** `InsertEffectFactory.createSlotFromPlugin` (`InsertEffectFactory.java:86`) infers a *built-in* type (`InsertEffectFactory.java:107`) — null for any external-JAR or CLAP plugin. The serializer guards the `effect-type` attribute *and all parameter children* behind a non-null type (`ProjectSerializer.java:510-512`), so a plugin slot is written as name/bypassed/expensive only. On load, `parseInsertSlot` returns for an empty `effect-type` and **explicitly returns for `CLAP_PLUGIN`** (`ProjectDeserializer.java:893-902`) — the slot is never recreated and every later slot shifts one position up. A mix using any third-party insert reopens with a different, shorter chain and no warning.
- **The installed-plugin registry is session-scoped.** `PluginRegistry` keeps entries in in-memory collections only (`PluginRegistry.java:25-27`, registration at `:37`); nothing persists them and nothing re-registers at startup — which turns the dropped-insert bug above from "recoverable by hand" into "unrecoverable without re-installing first". Story 303 landed its install flow with a truthful no-persistence notice for exactly this gap.
- **Take stacks and comping are never persisted.** Tracks own `TakeComping` and a take-group map (`Track.java:98-99`); loop recording populates them (`RecordingPipeline.java:294`) and the comp tool consumes them in production — yet the serializer emits no take-lane, take-group, or comp-region element of any kind (the only `take` token in the format is the `takes-folded` fold-state attribute, `ProjectSerializer.java:292`). Every take but the composited result is lost on save.
- **The tempo map cannot be expressed.** `Transport` is backed by a full `TempoMap` (`Transport.java:74`) that the timeline ruler already consults (`TimelineRulerModel.java:58`), but the format serializes exactly one beat-0 tempo and time signature (`ProjectSerializer.java:235` onward). Tempo or meter changes are unrepresentable end to end.

For a studio engineer: the mix topology changes silently between sessions, comped performances collapse to a single take, and a song with a tempo change cannot be saved at all. Mix topology is data, not a cache — it must never load shorter than it saved.

## Goals

- **Plugin-slot descriptor** (design book §4.5): a plugin-backed `InsertSlot` serializes plugin identity (stable manifest id + version — never a jar path), source kind (external JAR / CLAP), display name, bypass/expensive flags, chain position, and the plugin's opaque state (the reflective parameter snapshot where that path applies, otherwise the plugin's own state blob).
- **Unresolved placeholder, chain order inviolate**: on load the descriptor resolves through the plugin registry; resolution failure produces a placeholder slot in the same chain position — audibly bypassed, visually distinct in the rack (Plugin View book idiom), carrying the full descriptor so a later install or relink restores it losslessly. The chain never shifts and a slot is never silently deleted.
- **Installed-plugin registry persists**: registry entries plus manifest provenance from the story-303 install flow persist in the app configuration directory (written via the story-331 atomic-replace writer) and re-register at startup. Story 303's truthful no-persistence notice comes out when the gap closes.
- **Take schema** (book §4.6): per-track take lanes, take groups, and comp selections serialize alongside clips, referencing takes by id, so the comping surface reopens exactly as left; the schema stores the ordered segment-file list that Book 2's story 323 finalises.
- **Tempo-map schema**: the tempo map serializes its full tempo-change and meter-change lists (position, value, transition type), with the single beat-0 attributes retained as the backward-compatible degenerate case.
- **Migration + parity**: every format addition rides the story-188 migration registry (schema-version bump, pre-migration backup; old files open unchanged with defaults), and extends the story-330 write/read parity harness **in the same PRs** — the §3.1 inventory rows for plugin inserts, takes, tempo map, and the registry flip to OK.

## Goals — Tests

- **Mixed-chain round-trip**: a chain of built-in + external-JAR + CLAP inserts saves and reopens identical in content **and order**, parameters included where the snapshot path applies.
- **Placeholder round-trip**: uninstall one plugin → reopen → a placeholder holds its exact chain position, audibly bypassed and visually distinct; reinstall → the slot restores losslessly from the preserved descriptor. No load path ever shortens a chain silently.
- **Registry persistence**: install a third-party plugin → restart the app → the registry is intact and the plugin usable with no re-install; the story-303 no-persistence notice is gone.
- **Take round-trip**: loop-record a take stack (Book 2's capture stories landed), make comp selections, save, close, reopen → lanes, groups, and comp selections are identical and the comping surface resumes where it left off.
- **Tempo-map round-trip**: mid-song tempo and meter changes survive save→reopen, and the timeline ruler renders the same grid before and after (it reads the same `TempoMap`).
- **Parity-harness extension**: each new element/attribute registers in the story-330 harness; removing any new element's reader turns the harness red with the offending name.
- **Migration test**: a pre-334 project file opens unchanged through the story-188 registry (version bump honoured, absent elements defaulted, pre-migration backup written).

## Non-Goals

- **Recorded-audio durability** — real WAV segments under the project's recordings directory, project-relative paths, every segment referenced — Book 2's story 323 produces them; this story's schema stores the ordered list 323 finalises.
- **Capture workflows that produce takes** (loop-record enablement, comping activation path) — Book 2's story 328.
- **Rehydrating the referenced audio on open** — story 329's rehydrator and missing-assets surface.
- **The parity harness itself** — landed by story 330; this story extends it.
- **CLAP hosting reachability** (discovering, loading, inserting a CLAP plugin from the UI) — existing story 034; this story persists whatever chain exists, whichever world produced it.
- **Making menu-opened plugin editors part of the signal path** — Book 1's story 320; likewise mixer control truth (322) and the master chain (321) are the consumers that get to *trust* the topology this story makes durable.
- **Fault eviction and insert-chain concurrency** — Book 4's story 340.

## Technical Notes

- Implements **Stage 6 — Full-State Persistence: Plugin Inserts, Take Stacks, Tempo Map** of `docs/design/PERSISTENCE_INTEGRITY_DESIGN_BOOK.md` (§4.5 descriptor + placeholder, §4.6 schema growth, §3.1 inventory rows, §5.1 round-trip contract, §6.3 schema evolution).
- Files: `InsertEffectFactory.java:86-107` (type inference stops deciding persistability); `ProjectSerializer.java:510-512` (descriptor replaces the non-null-type guard), `:235` onward (transport build gains the tempo-map lists), `:268` onward (track build gains take elements), `:292` (fold-state attribute keeps working alongside real take data); `ProjectDeserializer.java:893-902` (the drop-and-shift returns become descriptor resolution + placeholder); `PluginRegistry.java:25-37` (+ a new persistence writer/loader in the app configuration directory); `Track.java:98-99`, `Transport.java:74`, `TimelineRulerModel.java:58` (consumers already in place); the story-330 parity harness in `daw-core`.
- **Identity is the manifest id** the story-303 install flow already validates — never a jar path (paths break on machine moves, book §3.3; §4.5's rejected alternative). Refusing to load a project with unresolvable plugins was also rejected — the placeholder preserves everything while degrading audibly and visibly (book §2.4).
- The built-in reflective parameter-snapshot path (`ProjectSerializer.java:513-525`, robust per book §1.10) is the model for descriptor state where it applies; see the `dawg-annotations-reflection` skill for the snapshot machinery.
- Cross-refs: **story 303** (registry gap closes; notice removed), **story 188** (migration registry, landed), **story 330** (Stage 2 harness this stage builds against), **story 331** (atomic writer for the registry file), **stories 323/328** (Book 2 — take-audio durability and take production), **stories 321/322** (Book 1 — downstream consumers of trustworthy topology), **story 034** (CLAP hosting reachability), `PLUGIN_VIEW_DESIGN_BOOK.md` + stories 300–304 (manifest identity, rack idiom for the placeholder).
- Rejections bound here: book §9.3 (write-only persistence), §9.4 (silently dropping unresolvable state on load), §9.5 (absolute/OS-temp asset paths).
