---
title: "Offline Render Queue for Batch Export Without UI Blocking"
labels: ["enhancement", "export", "batch", "performance"]
---

# Offline Render Queue for Batch Export Without UI Blocking

## Motivation

Rendering a full album (10 songs, each with stems) takes 20–40 minutes if done one at a time. The current `ExportService` runs synchronously and blocks the UI. Every pro DAW has a render queue: Logic's "Bounce Queue," Studio One's "Project / Export Range," Cubase's "Batch Export." Queuing up jobs and walking away is the correct workflow.

## Goals

- Add `RenderJob` sealed interface in `com.benesquivelmusic.daw.sdk.export`: permits `StereoMasterJob`, `StemBundleJob(List<StemSpec>)`, `AtmosBundleJob`, `DdpImageJob`, `BundleDeliverableJob` (story 181).
- Add `RenderQueue` in `com.benesquivelmusic.daw.core.export` that manages a `BlockingDeque<RenderJob>`. Jobs run on a bounded-parallelism executor (default 1 worker to prevent I/O contention; configurable).
- Each job runs offline (faster than real time) and publishes `JobProgress(jobId, phase, percent)` on a `Flow.Publisher`.
- `RenderQueueView` in `daw-app.ui.export`: queued jobs list with per-job progress bars, pause/resume/cancel per job, reorder by drag.
- Queue persists across app restarts in `~/.daw/render-queue.json`; on restart the user is prompted to resume, retry, or clear.
- Per-job completion notification (NotificationManager + optional OS-level toast).
- Tests: a 3-job queue completes all jobs; cancel mid-job leaves output in a clean state (partial file deleted); pause-resume does not corrupt the output.

## Non-Goals

- Distributed rendering across multiple machines.
- Email-on-completion notification.
- Render-farm integration (GPU offload).
