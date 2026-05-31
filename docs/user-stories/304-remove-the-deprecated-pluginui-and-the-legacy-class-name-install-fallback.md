---
title: "Remove the Deprecated PluginUI and the Legacy Class-Name Install Fallback"
labels: ["enhancement", "plugin-view", "sdk", "phase-5"]
---

# Remove the Deprecated PluginUI and the Legacy Class-Name Install Fallback

## Motivation

By the time this story runs, the Design Book §1 problems are all solved: the SDK has a real editor contract (story 300), the host honours it for third parties (story 301) and built-ins (story 302), and the install flow is manifest-driven (story 303). Two transitional artifacts remain alive **only** for backward compatibility, each on a documented expiry clock:

- **The orphaned `PluginUI`.** Story 300 marked `daw-sdk/.../ui/PluginUI.java` `@Deprecated(since = "<next>", forRemoval = true)` (§4.3). It was never called even before deprecation — verified at the start of Phase 1, the only `createUI` references were its own declaration and Javadoc (`PluginUI.java:7,21`), and the host dispatches exclusively through `editorFactory()`. It is pure dead weight kept solely to honour the SDK's two-release deprecation promise.
- **The legacy "type a class name" install fallback.** Story 303 kept the no-manifest fallback form (§6.8) so plugins built against the pre-manifest SDK could still be sideloaded via `ExternalPluginEntry(jarPath, className)` (`daw-app/.../ui/PluginManagerDialog.java:179-184`). The Design Book scopes its removal to "if telemetry shows no remaining usage" (§8.5.2).

This is **Phase 5 of the §8 migration path** — *deprecations expire* (§8.5), explicitly framed to run **after two release cycles past Phase 1** (§8.5 preamble). This is a deprecation-expiry cleanup, not new functionality: it deletes the two compatibility shims once their clocks have run and (for the fallback) once telemetry confirms disuse.

## Goals

- **Remove `com.benesquivelmusic.daw.sdk.ui.PluginUI`** (§8.5.1) — delete the interface and its now-redundant `exports`/Javadoc references. Because nothing in the tree ever called it and the deprecation has stood for two release cycles, removal is a pure deletion with no migration. Any straggler reference (there should be none) is migrated to `PluginEditorFactory` first.
- **Remove the legacy class-name install fallback** (§8.5.2), *gated on telemetry*. Delete the no-manifest "type a class name" form path from the install flow (story 303) and the residual `classNameField` plumbing, so a JAR **must** carry `META-INF/daw-plugin.json` to install. A JAR without a manifest now gets a clear "this plugin predates the manifest format — ask the vendor to rebuild against the current SDK" message rather than a class-name prompt.
- **Confirm disuse before deleting the fallback.** This story only removes the fallback **if** Phase-4 telemetry (or, absent telemetry, an explicit maintainer decision recorded in the PR) shows no remaining class-name installs. If usage is still observed, this Goal is split out and deferred while the `PluginUI` removal proceeds independently — the two are not coupled.
- **Drop the dead `PluginUI` test seam.** The `PluginUiDeprecatedForRemovalTest` from story 300 (which pinned `@Deprecated(forRemoval = true)`) is removed with the interface — it tested an artifact that no longer exists (`feedback_test_only_callers_are_not_live_usage.md`: a test that only tests a removed thing is dead, delete it; do not keep the interface alive to satisfy its own test).
- Tests:
  - `PluginUiRemovedTest` (new): reflectively assert the type `com.benesquivelmusic.daw.sdk.ui.PluginUI` no longer resolves (or a source-scan asserts the file is absent) — pins the §8.5.1 removal.
  - `ManifestRequiredForInstallTest` (new, paired with the fallback removal): a JAR with no `daw-plugin.json` is rejected with the "rebuild against current SDK" message and is **not** offered a class-name field; a JAR with a manifest installs as in story 303.
  - `NoClassNameFieldAnywhereTest` (new): a source/scene scan confirms no `classNameField` (or equivalent class-name `TextField`) remains in `PluginManagerDialog` or the install flow — the §1.4 friction is fully gone.
  - `SdkUiPackageStillCompilesTest` (new): the `daw-sdk/.../ui` package (now without `PluginUI`, still holding `PanelState`/`Rectangle2D`/`Workspace`) still compiles and exports cleanly — guards against an over-broad deletion that takes the surviving workspace types with it.

## Non-Goals

- **No new editor or install capability.** This is a removal-only story; the contract (story 300), chrome (story 301), built-in migration (story 302), and install flow (story 303) are unchanged.
- **No removal of the other `daw-sdk/.../ui` types.** `PanelState`, `Rectangle2D`, and `Workspace` stay — story 300 chose the new `editor` package precisely so the `ui` package remains stable for the workspace concept (§4.1). Only `PluginUI` leaves.
- **No coupling of the two removals.** If telemetry still shows class-name installs, the fallback removal is deferred to a future cycle while `PluginUI` removal lands now. Per `feedback_scope_fix_defer_tangential.md`, ship what is unblocked and record the deferral against the backlog (using the deferred-story convention — `docs/user-stories/future/`, the folder is the marker, per the project's user-story disposition convention).
- **No premature run.** This story must not be picked up before two release cycles have elapsed since story 300 shipped (§8.5 preamble). If that window has not passed, it stays unstarted — the prerequisite is a date/release gate, not a code gate.
- **No re-introduction of a second UI interface.** With `PluginUI` gone there is exactly one plugin-GUI seam (`PluginEditorFactory`); §9 rejection #1 forbids a replacement orphan.

## Technical Notes

- **Removal is risk-free for `PluginUI`** because (a) nothing called `createUI()` even at Phase 1 (verified), and (b) the host has dispatched solely through `editorFactory()` since story 301. The deletion touches `daw-sdk/.../ui/PluginUI.java`, the SDK `module-info.java` Javadoc/`exports` (the `ui` package export stays for the surviving types), and the story-300 deprecation test.
- **Fallback removal is gated, not automatic.** Wire the decision to Phase-4 telemetry if present; otherwise require an explicit maintainer sign-off recorded in the PR body before deleting the path. This honours §8.5.2's "if telemetry shows no remaining usage" exactly — do not delete a path users still hit.
- **A manifest-less JAR after removal** should fail loudly and helpfully (point the user at the vendor / the SDK manifest docs `daw-sdk/docs/EDITOR_FACTORY.md` from §7.7), not silently no-op. Route the message through `DawgDialog.error(...)` for the story-276 chrome, consistent with the existing `showError(...)` (`PluginManagerDialog.java:202-207`).
- **`ExternalPluginEntry` keeps its `className` field** — it is still the registry's identity for a loaded plugin; story 303/304 only stop *asking the user* for it (the manifest supplies it). Do not remove `ExternalPluginEntry.className()`; remove the *user-facing input*, not the data model.
- **Source-scan conformance** for "no class-name field" and "`PluginUI` absent" follows the established sentinel methodology (`feedback_ui_overhaul_conformance_sentinel.md`): comment-strip, non-empty-scan guard, self-exclude — reuse the harness, do not fork it (`feedback_prefer_existing_conventions.md`).
- Files (deleted): `daw-sdk/.../ui/PluginUI.java`, the story-300 `PluginUiDeprecatedForRemovalTest`, and (gated) the no-manifest fallback path + residual `classNameField` plumbing in `daw-app/.../ui/PluginManagerDialog.java` / the story-303 install flow. Files (edited): `daw-sdk/.../module-info.java` (prune `PluginUI` Javadoc; keep the `ui` export for surviving types), `PluginManagerDialog.java` (manifest-required messaging).
- Reference: Plugin View Design Book §4.3 (deprecate `PluginUI`), §6.8 (the fallback being removed), §8.5 (this phase — two-cycle gate + telemetry gate), §9 rejection #1 (no replacement orphan interface); user story 300 (where `PluginUI` was deprecated and the manifest defined), user story 303 (where the fallback was retained, and the manifest install that now becomes mandatory). SKILLs: `javafx-application-design` (§15 anti-patterns — keeping dead seams alive), `research-daw`.
