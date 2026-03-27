---
title: "Notification System with Contextual Feedback"
labels: ["enhancement", "ui", "usability"]
---

# Notification System with Contextual Feedback

## Motivation

The `NotificationBar` and `NotificationLevel` classes exist in the app module, providing a basic notification mechanism. However, user feedback throughout the application is minimal. When operations succeed (e.g., file saved, export complete) or fail (e.g., audio device not found, plugin load error), users need clear, non-intrusive notifications. The current implementation may show notifications for some operations but lacks comprehensive coverage. A consistent notification system builds user trust and reduces uncertainty.

## Goals

- Show non-intrusive toast notifications for key operations:
  - Project saved / auto-saved
  - Export started / completed / failed
  - Recording started / stopped
  - Track added / removed
  - Plugin loaded / failed to load
  - Audio device connected / disconnected
- Color-code notifications by severity (info=blue, success=green, warning=yellow, error=red)
- Auto-dismiss info/success notifications after a configurable timeout (default: 3 seconds)
- Allow dismissing notifications manually (click X)
- Show errors and warnings in a notification history panel accessible from the status bar
- Support undo actions directly from notifications (e.g., "Track deleted. Undo")

## Non-Goals

- Sound alerts for notifications
- Desktop OS notification integration (system tray popups)
- Email or remote notification of errors
