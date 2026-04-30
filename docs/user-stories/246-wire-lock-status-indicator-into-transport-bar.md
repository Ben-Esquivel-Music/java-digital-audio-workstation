---
title: "Wire LockStatusIndicator into Transport Bar and Surface Lock Conflicts in Project Open"
labels: ["enhancement", "persistence", "ui", "locking"]
---

# Wire LockStatusIndicator into Transport Bar and Surface Lock Conflicts in Project Open

## Motivation

Story 187 — "File Locking on Shared Network Volumes" — protects against two users opening the same project file on a shared NAS / SMB drive at the same time, with a structured lock conflict handler that prompts the second user with options (Open Read-Only, Steal Lock, Cancel). The core is implemented and the lock manager is composed in `ProjectManager`:

- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/ProjectLockManager.java` (`.lock` sidecar with user / hostname / pid / timestamp).
- `daw-core/src/main/java/com/benesquivelmusic/daw/core/persistence/LockConflictHandler.java`.
- `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/LockStatusIndicator.java` (a Label that shows "Locked by alice@studio-mac" / "Read-only" / "Locked by you").

But:

```
$ grep -rn 'new LockStatusIndicator' daw-app/src/main/
(no matches)
```

The indicator is constructed nowhere — it never appears in the transport bar, never refreshes, never warns the user that they opened the project read-only. There is also no `LockConflictHandler` UI: the existing `ProjectLockManager` returns a structured conflict but no dialog presents it to the user.

## Goals

- Instantiate `LockStatusIndicator` in `MainController` and mount it in the transport bar to the right of the project name. It auto-refreshes (every 5 s, or on `Flow.Publisher` events from `ProjectLockManager`) and shows:
  - Empty (default) when the project is locked by the current user (the normal case).
  - "Read-only" yellow badge when the user opted into read-only after a lock conflict.
  - "Locked by <user>@<host>" red badge when the lock could not be acquired (rare — only if the lock-conflict dialog was bypassed by automation).
  - Hovering the badge shows a tooltip with the lock holder's user / hostname / PID / acquired-at timestamp.
- Implement `JavaFxLockConflictHandler` (a JavaFX-side `LockConflictHandler`) that displays a `LockConflictDialog`:
  - Title: "Project is locked by <user> on <host>"
  - Body: lock holder details + "Acquired N minutes ago".
  - Buttons: "Open Read-Only", "Steal Lock", "Cancel".
  - "Steal Lock" warns ("Stealing the lock will cause the other user to lose unsaved changes if they save") and requires explicit confirmation.
- `ProjectLifecycleController` wires the JavaFX handler to `ProjectManager` so opening a locked project surfaces the dialog automatically.
- The "Save" path is disabled in read-only mode and the indicator's tooltip explains why ("Project opened read-only — cannot save. Click here to attempt to acquire the lock."). Clicking the indicator in read-only mode tries to acquire the lock and, if the holder has gone away, switches to write mode.
- The indicator's update flow uses the `ProjectLog` events the existing `ProjectLockManager` publishes (no polling required if the publisher is wired correctly).
- Tests:
  - Headless test: simulate a lock held by another user; open the project; assert the conflict dialog appears with the expected user / host string; clicking "Open Read-Only" loads the project with `Save` disabled and the indicator showing the read-only badge.
  - Test confirms "Steal Lock" requires confirmation and updates the indicator after success.

## Non-Goals

- OS-level advisory locks via `FileChannel.tryLock` (the `.lock` sidecar approach is documented and intentional per the existing `ProjectLockManager` Javadoc).
- Real-time multi-user collaborative editing.
- Centralized lock server (the lock is filesystem-based).

## Technical Notes

- Files: `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/MainController.java` (mount indicator + wire handler), new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/JavaFxLockConflictHandler.java`, new `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/LockConflictDialog.java`, `daw-app/src/main/java/com/benesquivelmusic/daw/app/ui/ProjectLifecycleController.java` (pass handler to `ProjectManager`).
- `ProjectLockManager`, `LockConflictHandler` interface, and the `.lock` schema already exist.
- Reference original story: **187 — File Locking on Shared Network Volumes**.
