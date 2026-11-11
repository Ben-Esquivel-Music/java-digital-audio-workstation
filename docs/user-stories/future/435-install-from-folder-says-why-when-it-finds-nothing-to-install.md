---
title: "Install from Folder Says Why When It Finds Nothing to Install"
labels: ["bug", "plugin", "failure-surfacing"]
---

# Install from Folder Says Why When It Finds Nothing to Install

## Motivation

The plugin browser's `+ Install from folder…` lists the chosen folder's JARs on a virtual thread (`PluginBrowser.installFromFolder`), inspects each one, then opens an install dialog for each JAR that has a valid manifest and one summary of the JARs it skipped. Two outcomes end in nothing once the busy row goes away:

- **A folder it cannot list.** The listing's `catch (IOException | RuntimeException e)` keeps nothing of the failure — its comment reads "Best-effort — an unreadable folder yields no installs." — so a folder removed after it was chosen, or one the user may not read, is neither reported nor logged.
- **A folder with no JAR.** With nothing to install and nothing skipped, no dialog follows.

The Failure Surfacing book's §2.1 "No failure is silent" asks for a log record and a visible surface, and §2.2 "No gesture does nothing" asks that a gesture that cannot proceed says why. A JAR that cannot be read is already reported: `PluginJarScanner.scanBlocking` returns it as not readable, and the skipped summary names it.

This dates from story 303. The sweep of story 334's pull-request review found it and left it here as tangential.

## Goals

- **A folder that cannot be listed says so**: one error naming the folder and what failed, and one WARNING record with the exception; the JARs listed before the failure are still offered.
- **A folder with no JAR says so**: one message naming the folder.
- **Its new texts are bundle keys**, read through `PluginText` at `Locale.ROOT`, as the texts story 334 added to the plugin surfaces are (`javafx-application-design` §14).
- **Nothing else changes**: the install dialogs and the skipped summary stay as they are.

## Acceptance Criteria

1. Choosing a folder that cannot be listed shows one error naming it and logs one WARNING with the exception.
2. Choosing a folder with no JAR shows one message naming it.
3. A folder with JARs behaves as it does today.
4. Its new texts come from `Messages.properties`.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- A folder that does not exist when it is listed: one error, one WARNING record, and the busy row gone.
- An empty folder: one message.
- Fault check, run once, turning a test red: the listing's failure swallowed again.

## Non-Goals

- Changing how a JAR is inspected or installed.
- Moving the browser's existing texts into the bundle (future story 430, which names `PluginBrowser`).

## Technical Notes

- Code locators: `PluginBrowser.installFromFolder` and `skippedSummary`; `PluginJarScanner.scanBlocking`; the `FxDispatcher` seam the scan already posts through.
- Design references: Failure Surfacing book §2.1, §2.2 and §5.1 "Per failure class: catch → log → surface → next step".
- Cross-refs: story 303 (the install-from-folder flow); story 334 (found by the sweep of its pull request's review); future story 430.

## Status

- Deferred
