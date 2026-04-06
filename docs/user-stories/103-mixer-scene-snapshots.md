---
title: "Mixer Scene Snapshots and A/B Recall for Mix Comparison"
labels: ["enhancement", "mixer", "mixing", "usability"]
---

# Mixer Scene Snapshots and A/B Recall for Mix Comparison

## Motivation

During a mixing session, engineers frequently want to compare different mix approaches — for example, comparing a vocal-forward mix versus an instrument-forward mix, or comparing mix settings before and after a round of revisions. Currently, the only way to do this is to manually write down fader positions and effect settings, make changes, and then manually restore the previous settings. This is tedious and error-prone.

Professional DAWs offer mixer scene snapshots (also called "mixer scenes" or "console snapshots"): the ability to save the entire mixer state at a point in time and instantly recall it. This includes all channel volumes, pans, mute/solo states, insert effect settings, and send levels. Some DAWs (Pro Tools, Ardour, Harrison Mixbus) support dozens of snapshots per session, enabling rapid A/B comparison of different mix approaches.

## Goals

- Define a `MixerSnapshot` record in `daw-core` that captures the complete mixer state: per-channel volume, pan, mute, solo, insert effect parameters, insert bypass states, send levels, and master channel settings
- Add a "Save Snapshot" action that captures the current mixer state and stores it with a user-provided name and timestamp
- Add a "Recall Snapshot" action that restores a previously saved snapshot, updating all mixer channels to the stored state
- Support up to 32 snapshots per project
- Add a "Snapshots" panel in the mixer view showing a list of saved snapshots with name, timestamp, and recall/delete buttons
- Provide A/B shortcut: two dedicated snapshot slots (A and B) with a single-key toggle for instant switching between them during playback
- Snapshot recall should be undoable as a single compound action
- Include snapshots in project serialization so they persist across save/load
- Add tests verifying: (1) snapshot captures all mixer state, (2) recall restores exact values, (3) A/B toggle switches between two snapshots, (4) snapshots survive project save/load

## Non-Goals

- Partial snapshot recall (recalling only volume, or only inserts — all-or-nothing initially)
- Snapshot interpolation or morphing between two snapshots over time
- Automation of snapshot recall at specific timeline positions
- Snapshot comparison diff view (showing what changed between two snapshots)
