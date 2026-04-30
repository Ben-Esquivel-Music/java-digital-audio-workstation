---
title: "Instantiate RenderQueueView and Wire Offline Render Queue Menu / Persistence"
labels: ["enhancement", "export", "ui", "batch"]
---

# Instantiate RenderQueueView and Wire Offline Render Queue Menu / Persistence

## Motivation

Story 186 — "Offline Render Queue for Batch Export Without UI Blocking" — lets the user queue up renders (stereo master, stem bundles, ADM BWF, DDP, deliverable bundles) and walk away while the engine processes them faster than real time. The core is implemented:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/export/RenderQueue.java` (`BlockingDeque<RenderJob>` with bounded-parallelism worker, pause / resume / cancel per job, `Flow.Publisher<JobProgress>`).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/export/RenderQueuePersistence.java` (queue state in `~/.daw/render-queue.json`).
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/export/RenderQueueView.java` (the JavaFX view with per-job progress, drag-reorder, etc.).
- `RenderQueueTest`.

But:

```
$ grep -rn 'new RenderQueueView' daw-app/src/main/
(no matches)
```

`RenderQueueView` is constructed nowhere. Every export action still runs synchronously and blocks the UI; the queue is dead code. Users can't render an album of stems and walk away.

## Goals

- Compose `RenderQueue` in `MainController` (singleton scoped to the app lifetime — survives project changes; the queue is a tool, not a project state).
- Add "Export → Render Queue…" menu item that opens `RenderQueueView` in a dock pane.
- Replace the current "synchronous export" behaviour of the existing export dialogs with "Add to queue" by default, plus a "Run now (blocks)" alternate option for users who want the legacy behaviour.
  - Stereo Master Export, Stem Export, ADM BWF Export, DDP Export, Bundle Export — all gain an "Add to queue" button.
  - Each export configuration becomes a `RenderJob` (`StereoMasterJob`, `StemBundleJob`, `AtmosBundleJob`, `DdpImageJob`, `BundleDeliverableJob`).
- The queue runs on a single worker by default (configurable in Settings → Performance → "Render queue parallelism") to prevent disk contention.
- `JobProgress` events drive the per-job progress bars in `RenderQueueView`.
- On app shutdown, persist the queue via `RenderQueuePersistence`. On app startup, prompt the user: "Resume / Retry / Clear" if a non-empty queue exists.
- Per-job completion notification through `NotificationManager` ("Stem export 'Album / Track 03' completed") + an optional OS-level toast (using the JavaFX `Toolkit.getDefaultToolkit().beep()` or platform-specific notification — see the existing pattern in `NotificationManager`).
- Cancellation: cancelling a mid-job render leaves output in a clean state (partial file deleted via the existing temp-file pattern from the bundle exporters).
- Tests:
  - Headless test: enqueue three small synthetic jobs, run the queue, assert all three complete and the progress publisher fires the expected sequence of phases.
  - Test confirms cancelling a mid-job render deletes the partial output file.
  - Test confirms the queue persists across simulated app restart.

## Non-Goals

- Distributed rendering across multiple machines.
- Email-on-completion notification.
- Render-farm integration (GPU offload).
- A separate queue per project (one app-wide queue is sufficient for MVP).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (compose `RenderQueue` + mount menu item), the various export dialogs (find them under `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/export/` and at the top-level UI package — `AdmBwfExportController.java` etc.), `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/export/RenderQueueView.java` (instantiation only).
- `RenderQueue.enqueue(RenderJob)`, `RenderQueue.subscribe(Subscriber<JobProgress>)`, `RenderQueue.pause(jobId)`, `RenderQueue.cancel(jobId)` are already present.
- Reference original story: **186 — Offline Render Queue**.
