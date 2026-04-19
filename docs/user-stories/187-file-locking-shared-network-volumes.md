---
title: "File Locking on Shared Network Volumes to Prevent Concurrent-Write Corruption"
labels: ["enhancement", "persistence", "reliability", "collaboration"]
---

# File Locking on Shared Network Volumes to Prevent Concurrent-Write Corruption

## Motivation

When two users on the same NAS open the same project (one in the main studio, one in the edit suite) and both save, the later save silently wipes the earlier one. Every collaboration-aware tool handles this with file locks or soft-locks: Pro Tools' project-is-open warning, Logic's bundle-based single-writer, and any document-database pattern (CouchDB, Git). Without any lock, multi-user workflows actively destroy data.

## Goals

- Add `ProjectLockManager` in `com.benesquivelmusic.daw.core.persistence` that, on project open, writes a sidecar `.project.lock` file containing `{user, hostname, pid, openedAt}` JSON.
- On open, if a lock file exists: show a dialog with the holder's info and options "Open read-only," "Take over (force-steal)," "Cancel."
- Update lock file `lastSeenAt` timestamp every 30 s; on close, delete it.
- Stale-lock detection: if `lastSeenAt` is > 10 minutes old, mark the lock as stale and allow takeover with warning.
- Save operations confirm the lock is still ours before writing; if the lock was stolen, prompt the user to "Save As" to avoid data loss.
- Works on NFS, SMB, and local filesystems by using filesystem APIs rather than OS-level advisory locks (which behave inconsistently across networks).
- `LockStatusIndicator` in the title bar showing lock state (held / read-only / stolen).
- Tests: two mock sessions opening the same project produce the second dialog correctly; stale lock detection triggers after simulated clock advance; takeover leaves a clear audit trail in the project log.

## Non-Goals

- Multi-user concurrent editing (full real-time collaboration is a much larger story).
- Git-style merge resolution of conflicting saves.
- User identity management beyond OS username + hostname.
