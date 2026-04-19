---
title: "Auto-Backup Rotation with Configurable Retention Policy"
labels: ["enhancement", "persistence", "safety"]
---

# Auto-Backup Rotation with Configurable Retention Policy

## Motivation

Story 019 covers autosave. The natural follow-on is retention: keep the last N autosaves per project, trim older ones, but also keep hourly/daily/weekly milestones so a week-old version is recoverable. The "grandfather-father-son" backup pattern is standard in any backup system. Without retention, autosaves either fill the disk or are trimmed too aggressively for multi-week projects.

## Goals

- Add `BackupRetentionPolicy` record in `com.benesquivelmusic.daw.sdk.persistence`: `record BackupRetentionPolicy(int keepRecent, int keepHourly, int keepDaily, int keepWeekly, Duration maxAge, long maxBytes)`.
- Default policy: 10 recent + 24 hourly + 14 daily + 8 weekly + max 30 days + max 2 GiB per project.
- Add `BackupRetentionService` in `com.benesquivelmusic.daw.core.persistence.backup` that, on each new autosave, prunes older snapshots by applying the policy.
- Snapshots ranked into buckets: "recent" (most recent N), "hourly" (one per hour in the last 24), "daily" (one per day in the last 14), "weekly" (one per week in the last 8). An autosave in multiple buckets occupies the most-recent bucket.
- Global default policy in `~/.daw/backup-retention.json`; per-project override in the project file.
- `BackupSettingsDialog`: sliders/inputs for each retention parameter and a live preview of "what would be kept" based on the current autosave directory.
- Disk-space visualization: per-project disk usage pie chart with "autosaves / archives / assets" breakdown.
- Tests: a simulated autosave stream over a year produces a final set that matches the policy's bucket counts; disabling a bucket (set to 0) stops that class from being kept.

## Non-Goals

- Network-destination backups (cloud, FTP, rsync).
- Cross-machine backup synchronization.
- Restoration is handled by the snapshot browser (story 190).
