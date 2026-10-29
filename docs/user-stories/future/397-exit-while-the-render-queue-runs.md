---
title: "Exit While the Render Queue Runs"
labels: ["enhancement", "export", "render", "reliability"]
---

# Exit While the Render Queue Runs

## Motivation

Story 333's exit protocol asks before it ends a take (ruling R1, the take rule). A recording still in flight is stopped only once the user consents — the default, and Escape, mean “Keep recording”, which ends the protocol at GUARD — and a take still being written is waited out under the closing surface, where the user can still choose to keep working. The exit asks nothing about the render queue. `ExitCoordinator`'s GUARD reads the takes (`recordingInFlight()`, `takeBeingWritten()`) and nothing of the queue.

At DISPOSE the window's disposal (`MainController.disposeWindowLifetime`) closes the queue's window, and its hand-off, `EngineShutdown.run()` on the `daw-engine-shutdown` thread, persists the queue first (`RenderQueue.persist()`), then stops the audio engine, then calls `RenderQueue.shutdown()`. That sets the queue closed, calls `shutdownNow()` on its worker pool, which interrupts the workers, and waits for them at most 5 seconds (`awaitTermination(5, TimeUnit.SECONDS)`). The workers are daemon threads. So a bounce in progress at exit is cut, unless it happens to finish before the process ends, and the user was never asked. What is left depends on where the cut lands:

- **A render that stops at the interrupt.** `RenderQueue.runJob` treats an `InterruptedException` from the runner as a cancellation: it deletes the paths the job registered for cleanup (`JobControl.registerCleanupPath`), ends the job `CANCELLED`, notifies, and persists the queue again.
- **A render that does not.** The job's own checkpoint reacts to a cancel request, not to the interrupt (`ControlImpl.checkpoint` throws only when `cancelRequested` is set, or while a paused job waits). Such a render runs on through the 5-second wait, and the end of the process cuts it if it has not finished by then. Its cleanup then never runs, so whatever it had written stays at its output path, and the queue's state persisted at DISPOSE still shows it running. The next launch's first use of the queue (`MainController.ensureRenderQueue`) finds a job that is not terminal and shows "Resume Render Queue"; its three answers only clear the persisted file, because a job's render configuration is not persisted.

Today nothing in the application enqueues a render: no main code calls `RenderQueue.enqueue`, the export dialogs' “Add to queue” (a goal of story 243) does not exist yet, and `DefaultRenderJobRunner`'s per-type handlers write a placeholder file. The gap becomes real once an export dialog enqueues renders. Story 333 records it as a known-limit category of its exit, with this story as its owner.

## Goals

- **An exit asks before it cuts a render**, in the take rule's shape (story 333, ruling R1). The exit's GUARD reads whether a render-queue job is running. If one is, the user chooses between keeping the application open — the default, which Escape and the dialog's close box also mean, and which ends the exit at GUARD, as “Keep recording” does — and cancelling the render and going on with the exit. The studio engineer rules the prompt's texts, and whether a paused or a queued job counts as one that is running.
- **A cancelled render is finished before the exit goes on.** On consent the exit cancels the job through the queue's own cancel (`RenderQueue.cancel`), so the job's registered output is deleted, as story 186 requires of a cancel mid-job, and the exit waits until the job is terminal, under the closing surface, as R1's take wait does, with a way to keep the application open while it waits. The Resolution states the wait's bound and what the closing surface shows.
- **Every exit door asks the same.** The window's close box, File ▸ Exit and Ctrl+Q ask as the take rule asks at those doors. The application-stop fallback (story 333, ruling R4) cannot ask; the Resolution states what it does with a running render.
- **Close Project is ruled, not assumed.** The render queue is the application's, not the project's (`MainController.ensureRenderQueue`: “batch renders are a tool, not project state”), so a Close Project that leaves the application open need not ask; the studio engineer rules whether it does.

## Acceptance Criteria

1. With a render-queue job running, the window's close box, File ▸ Exit and Ctrl+Q each ask at GUARD; keeping the application open ends the exit there, runs no later step and leaves the job running.
2. Choosing to cancel the render and exit cancels the job, deletes its registered output, and runs the exit's next step only once the job is terminal.
3. With no job running, an exit asks nothing more than it does today.
4. The Resolution states the application-stop fallback's behaviour with a running render, and whether Close Project asks.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- **Ask**: with a job held running in the queue (a runner that blocks until the test releases it), the close box asks; keeping the application open publishes no exit step after GUARD and the job is still running.
- **Cancel and exit**: the same setup, then consent. The job ends `CANCELLED`, its registered cleanup path is gone, and the exit's next step is published only after the job is terminal; the exit then runs to HIDE.
- **Nothing running**: with an empty queue, or only terminal jobs, the exit runs as story 333's tests drive it today, with no new prompt.
- **Fault check**: a GUARD that ignores the queue turns the Ask test red.

## Non-Goals

- What the next launch's "Resume Render Queue" prompt offers (story 243), and persisting a job's render configuration.
- The queue's non-atomic persist, which story 377 moves onto `AtomicFileWriter`.
- The export dialogs' “Add to queue” (story 243).

## Technical Notes

- Code locators: `ExitCoordinator` — `guard`, `rejudge` (the take and switch waits), `stopTransport`; `MainController` — `disposeWindowLifetime`, `EngineShutdown`, `closeRenderQueueWindow`, `ensureRenderQueue`, `promptResumeRetryClear`; daw-core `export/RenderQueue` — `shutdown`, `cancel`, `snapshot`, `awaitQuiescence`, `runJob`, `cleanup`, `ControlImpl.checkpoint`; `DefaultRenderJobRunner`.
- The Resolution states whether `RenderQueue.shutdown()` stays the end of the queue's workers at DISPOSE, and what becomes of a queued job the exit did not ask about, if the studio engineer rules that a queued job does not count.
- Ruled by the studio engineer in story 333 (the stage-7 review, ruling 4: “exit while the render queue runs”, the take rule's shape for an offline render; and the final ruling F17c, which files it as a future story). The category in story 333's Resolution reads: “An exit asks nothing about a render-queue job that is still rendering: the queue's workers are stopped at once (RenderQueue.shutdown()), so a bounce in progress at exit is cut and does not finish.”
- Design references: Persistence Integrity design book §2.7 (exit is a protocol, not an event), §5.4 (the exit protocol, ordered).
- Cross-refs: story 186 (the offline render queue), story 243 (its view and the export dialogs' “Add to queue”), story 333 (the exit protocol and its take rule), story 377 (the queue's persist through the atomic writer).

## Status

- Deferred
