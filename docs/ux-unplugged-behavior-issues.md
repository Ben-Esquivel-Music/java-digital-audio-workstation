# UX & Unplugged Behavior Issues

> Issues derived from a comprehensive audit of the DAW application UI against state-of-the-art DAW expectations.
> All issues target the `daw-app` and `daw-core` modules unless otherwise noted.
> The **Sound Wave Telemetry view** is excluded from this audit (handled in a separate epic).
>
> See also: [AES Feature Enhancements](research/aes-feature-enhancements.md) · [Open Source DAW Tools](research/open-source-daw-tools.md)

---

## Issue Index

| # | Category | Title | Priority |
|---|----------|-------|----------|
| 1 | Recording | [Recording Pipeline Not Connected to Audio Engine](#1-recording-pipeline-not-connected-to-audio-engine) | Critical |
| 2 | Track I/O | [Add Audio Input Port Selection Dialog for Tracks](#2-add-audio-input-port-selection-dialog-for-tracks) | Critical |
| 3 | Track Context Menu | [Track Context Menu Operations Are Stubs](#3-track-context-menu-operations-are-stubs) | High |
| 4 | Track Context Menu | [Track Context Menu Items Not Conditionally Disabled](#4-track-context-menu-items-not-conditionally-disabled) | High |
| 5 | UX Feedback | [User Action Feedback Is Insufficient](#5-user-action-feedback-is-insufficient) | High |
| 6 | Editor | [Editor View Tool Buttons Are Non-Functional](#6-editor-view-tool-buttons-are-non-functional) | High |
| 7 | Editor | [Audio Editor Trim and Fade Buttons Are Non-Functional](#7-audio-editor-trim-and-fade-buttons-are-non-functional) | High |
| 8 | Transport | [Loop Mode Not Connected to Transport Engine](#8-loop-mode-not-connected-to-transport-engine) | Medium |
| 9 | Mixer | [Mixer Send Level Slider Not Wired to Audio Routing](#9-mixer-send-level-slider-not-wired-to-audio-routing) | Medium |
| 10 | Track Controls | [Phase Invert Toggle Has No Backing Model State](#10-phase-invert-toggle-has-no-backing-model-state) | Medium |
| 11 | Editing | [Snap-to-Grid Flag Not Applied to Editing Operations](#11-snap-to-grid-flag-not-applied-to-editing-operations) | Medium |
| 12 | Sidebar | [Sidebar Home, Search, and Help Buttons Lack Actions](#12-sidebar-home-search-and-help-buttons-lack-actions) | Medium |
| 13 | Settings | [Settings Dialog Changes Not Applied to Running Engine](#13-settings-dialog-changes-not-applied-to-running-engine) | Medium |
| 14 | Export | [Track Export Operations Are Stubs](#14-track-export-operations-are-stubs) | Medium |
| 15 | Toolbar | [Top Toolbar Layout Inconsistent with Design Screenshots](#15-top-toolbar-layout-inconsistent-with-design-screenshots) | Low |

---

## Recording

### 1. Recording Pipeline Not Connected to Audio Engine

**Category:** Recording · **Priority:** Critical

**Motivation:**
The record button (`onRecord()` in `MainController`) transitions the transport to `TransportState.RECORDING` and starts the time ticker, but no actual audio recording occurs. The `RecordingSession` class in `daw-core` has a full session/segment model, and the `AudioEngine` has a `processBlock()` method, but there is no wiring between the transport's record state, the audio engine's input capture, and the `RecordingSession`. A DAW that cannot record audio is fundamentally broken for its core use case.

**Current Behavior:**
- User presses Record → transport state changes to `RECORDING`, time ticker starts, status bar reads "Recording — auto-save active"
- No `RecordingSession` is created
- No audio input data is captured from the `AudioEngine` or `NativeAudioBackend`
- No segment files are written to disk
- No armed tracks receive audio data
- Pressing Stop does not finalize any recording — there is nothing to finalize

**Expected Behavior:**
- Pressing Record should check that at least one track is armed for recording
- If no tracks are armed, the user should be informed with a clear dialog or notification (not just a status bar message)
- A `RecordingSession` should be created using the project's `AudioFormat` and a configured output directory
- The `AudioEngine` should begin capturing audio from the configured input device and routing it to armed tracks
- The `RecordingSession` should segment audio per its `maxSegmentDuration` / `maxSegmentBytes` configuration
- The time display should reflect actual recording duration
- Pressing Stop should finalize the recording session and commit segments
- Undo/redo should be supported for the recording action (remove recorded clip)

**Subtasks:**
- [ ] Wire `MainController.onRecord()` to create and start a `RecordingSession` for each armed track
- [ ] Validate that at least one track is armed before starting recording; show an alert dialog if none are armed
- [ ] Connect the `AudioEngine.processBlock()` input path to `RecordingSession.recordSamples()`
- [ ] Start/stop the `AudioEngine` in coordination with the transport's record/stop transitions
- [ ] Write captured audio data to segment files on disk via `RecordingSession`
- [ ] Finalize the `RecordingSession` when the user presses Stop
- [ ] Display recording duration and segment count in the status bar or a dedicated recording indicator
- [ ] Register the recorded audio clip(s) on the armed track(s) for playback
- [ ] Support undo for the recording action (remove recorded clips from timeline)
- [ ] Add integration test for the record → stop → verify-segments lifecycle

**Non-Goals:**
- Punch-in / punch-out recording (separate feature)
- Multi-take / comp lane recording (separate feature)
- Real-time waveform rendering during recording (separate feature)

**Affected Files:**
- `daw-app/…/ui/MainController.java` — `onRecord()`, `onStop()`
- `daw-core/…/recording/RecordingSession.java`
- `daw-core/…/audio/AudioEngine.java`
- `daw-core/…/transport/Transport.java`

---

### 2. Add Audio Input Port Selection Dialog for Tracks

**Category:** Track I/O · **Priority:** Critical

**Motivation:**
When the user clicks "Add Audio Track" or "Add MIDI Track", a track is silently added with no opportunity to select which audio input port the track should record from. Professional DAWs (Ardour, LMMS, OpenDAW) present an input configuration dialog at track creation time and provide access to it after the track is created. Without this, users have no control over which physical input (microphone, instrument, line-in) feeds each track. The `AudioDeviceInfo` record in `daw-sdk` already models input device capabilities, but it is never surfaced to the user.

**Current Behavior:**
- Clicking "Add Audio Track" creates a track named "Audio N" with no input assignment
- No dialog, pop-up, or configuration step is shown
- The I/O routing label on the track strip shows a generic connector icon but is not interactive
- There is no way to change the input assignment after the track is created
- The settings dialog has audio device configuration but no per-track routing

**Expected Behavior:**
- Clicking "Add Audio Track" should open an **Input Port Selection Dialog** that:
  - Lists all available audio input devices and their channels (from `AudioDeviceInfo`)
  - Shows the device name, host API, channel count, and sample rate
  - Allows the user to select an input device and specific channel pair (e.g., "USB Audio — Channels 1-2")
  - Provides a "Test Input" button that displays a brief level meter to verify the input is receiving signal
  - Has "OK" and "Cancel" buttons — Cancel aborts the track creation
- After the track is created, double-clicking or right-clicking the I/O routing indicator on the track strip should re-open the same dialog to change the input assignment
- MIDI tracks should show a MIDI input port selector instead of audio inputs
- The track's input assignment should be persisted as part of the project state

**Subtasks:**
- [ ] Create `InputPortSelectionDialog` class in `daw-app/…/ui/` that queries available input devices
- [ ] Enumerate audio input devices using `AudioBackendFactory` / `NativeAudioBackend.getDevices()` (or a stub list when no backend is active)
- [ ] Display a `ListView` of input devices with device name, host API, channel count, and latency
- [ ] Add a "Test Input" button that opens a temporary input stream and displays a `LevelMeterDisplay` for 3–5 seconds
- [ ] Show the dialog when "Add Audio Track" is pressed; abort track creation on Cancel
- [ ] Add an `inputDeviceIndex` (or similar) field to `Track` to persist the input assignment
- [ ] Make the I/O routing label on the track strip interactive (double-click or right-click opens the dialog)
- [ ] Create `MidiInputPortSelectionDialog` (or a tab within the same dialog) for MIDI track input selection
- [ ] Update `onAddAudioTrack()` and `onAddMidiTrack()` in `MainController` to invoke the dialog
- [ ] Add unit tests for the dialog's device enumeration and selection logic

**Non-Goals:**
- Output routing configuration (separate issue)
- Multi-output / surround routing per track (separate feature)

**Affected Files:**
- `daw-app/…/ui/MainController.java` — `onAddAudioTrack()`, `onAddMidiTrack()`
- `daw-app/…/ui/InputPortSelectionDialog.java` (new)
- `daw-core/…/track/Track.java` — add input assignment field
- `daw-sdk/…/audio/AudioDeviceInfo.java`

---

## Track Context Menu

### 3. Track Context Menu Operations Are Stubs

**Category:** Track Context Menu · **Priority:** High

**Motivation:**
The right-click context menu on track strips (built in `MainController.buildTrackContextMenu()`) contains over 40 menu items across editing, fading, alignment, view, export, social, and general categories. Every single item is a stub — the `onAction` handler only updates the status bar text with a message like "Copied: Audio 1" but performs no actual operation. This gives users the impression that the DAW has rich editing capabilities, but clicking any item does nothing. This is deceptive and frustrating.

**Current Behavior (all stubs):**
- **Editing:** Copy Track, Paste Over, Split at Playhead, Trim to Selection, Crop, Move, Reverse, Select All — all update status bar only
- **Fade:** Fade In, Fade Out — update status bar only
- **Alignment:** Align to Grid, Align Left, Align Right — update status bar only
- **View:** Expand Track, Collapse Track, Fullscreen Editor, Minimize, Picture-in-Picture, Go to Start — update status bar only (except Go to Start which calls `onSkipBack()`)
- **Export:** Export as WAV, MP3, AAC, MIDI, WMA — update status bar only
- **Social:** Share Track, Broadcast, Stream, Rate Track, Dislike Track, Add Comment, Follow Track — update status bar only
- **General:** Add to Favorites, Add to Playlist, Film Score Mode, Set Alert, Repeat Once, Rename — Rename works; all others are status bar only

**Expected Behavior:**
Each menu item should either perform a real operation or be clearly marked as unavailable (disabled with a tooltip explaining "Coming soon" or similar). Priority items that should be implemented:

1. **Copy Track** — duplicate the track and its configuration in the project
2. **Split at Playhead** — split the track's audio/MIDI clip at the current transport position
3. **Trim to Selection** — trim clip boundaries to the selected time range
4. **Reverse** — reverse the audio data on the track
5. **Fade In / Fade Out** — apply a fade envelope to the clip on the track
6. **Export as WAV** — bounce/export the track's audio to a WAV file via a save dialog
7. **Expand / Collapse Track** — toggle the track strip height between expanded and compact views
8. Items that are not yet implementable (e.g., Share, Broadcast, Stream, Film Score Mode) should be disabled with a descriptive tooltip

**Subtasks:**
- [ ] Implement Copy Track — duplicate the `Track` object and its `MixerChannel`, add to project and UI
- [ ] Implement Split at Playhead — requires audio clip model; split clip at `transport.getPositionInBeats()`
- [ ] Implement Trim to Selection — requires selection model; trim clip start/end to selection bounds
- [ ] Implement Reverse — reverse the audio data buffer on the track's clip
- [ ] Implement Fade In / Fade Out — apply a linear or exponential fade envelope to the clip
- [ ] Implement Export as WAV — open a save dialog, bounce the track's audio to a WAV file using `AudioClip` and `javax.sound.sampled`
- [ ] Implement Expand Track / Collapse Track — toggle the track item `HBox` preferred height and show/hide detail controls
- [ ] Disable unimplemented items (Paste Over, Crop, Move, Select All, Align *, Zoom *, Fullscreen, Minimize, PiP, all Social items, Playlist, Film Score, Alert, Repeat One) with descriptive tooltips
- [ ] Add Export as MP3, AAC, WMA, MIDI as disabled items with "Format not yet supported" tooltip
- [ ] Add undo/redo support for destructive editing operations (Copy, Split, Trim, Reverse, Fade)
- [ ] Add unit tests for each implemented context menu operation

**Non-Goals:**
- Full non-linear audio editing (separate editor feature)
- Social sharing / collaboration features (future epic)

**Affected Files:**
- `daw-app/…/ui/MainController.java` — `buildTrackContextMenu()`
- `daw-core/…/audio/AudioClip.java` — may need clip split/trim/reverse methods
- `daw-core/…/track/Track.java` — may need clip reference field

---

### 4. Track Context Menu Items Not Conditionally Disabled

**Category:** Track Context Menu · **Priority:** High

**Motivation:**
All context menu items are always enabled regardless of the application state. In a state-of-the-art DAW, menu items should be dynamically enabled/disabled based on preconditions. For example, "Split at Playhead" should be disabled if there is no audio clip on the track, "Paste Over" should be disabled if nothing has been copied to the clipboard, and "Trim to Selection" should be disabled if there is no active selection. Currently, users can click any item at any time with no indication that the operation cannot be performed.

**Current Behavior:**
- All ~40+ context menu items are always enabled
- Clicking an item that cannot perform its operation silently does nothing (updates status bar only)
- No visual indication that an item is contextually unavailable

**Expected Behavior:**
- Menu items should be disabled when their preconditions are not met:
  - **Paste Over** → disabled when clipboard is empty
  - **Split at Playhead** → disabled when the track has no audio/MIDI clip
  - **Trim to Selection** → disabled when there is no active time selection
  - **Crop** → disabled when there is no active time selection
  - **Reverse** → disabled when the track has no audio clip (or is a MIDI track)
  - **Fade In / Fade Out** → disabled when the track has no audio clip
  - **Export as WAV / MP3 / AAC / WMA** → disabled when the track has no audio data
  - **Export as MIDI** → disabled when the track is not a MIDI track
  - **Zoom In / Zoom Out** → disabled when at max/min zoom level
- Disabled items should have a tooltip explaining why they are disabled (e.g., "No audio data to export")
- Items that represent unimplemented features should be permanently disabled with a "Coming soon" tooltip

**Subtasks:**
- [ ] Add a clipboard model to `MainController` (or a dedicated `ClipboardManager`) to track copied content
- [ ] Add a selection model to track the current time selection range
- [ ] Evaluate preconditions at menu-build time in `buildTrackContextMenu()` and call `setDisable(true)` with explanatory tooltip
- [ ] Disable export items when the track has no recorded/imported audio data
- [ ] Disable MIDI-specific items (Export as MIDI) when the track type is not MIDI
- [ ] Disable audio-specific items (Reverse, Fade In/Out) when the track type is MIDI
- [ ] Add visual differentiation for disabled items (grayed-out text — standard JavaFX behavior)
- [ ] Add unit tests to verify precondition-based disabling

**Non-Goals:**
- Implementing a full clipboard with cross-application paste (system clipboard for audio)

**Affected Files:**
- `daw-app/…/ui/MainController.java` — `buildTrackContextMenu()`

---

## UX Feedback

### 5. User Action Feedback Is Insufficient

**Category:** UX Feedback · **Priority:** High

**Motivation:**
When users perform actions (click buttons, select menu items, complete operations), the only feedback is a small text update in the bottom status bar label. There is no visual confirmation (toast notification, brief highlight, animation), no audible confirmation (click sound), and no error indication beyond the status bar. For operations that fail silently (stub context menu items), users receive a positive-sounding message (e.g., "Copied: Audio 1") that implies success when nothing actually happened. A state-of-the-art DAW should make it unambiguous whether an action succeeded, failed, or is not supported.

**Current Behavior:**
- All user actions update `statusBarLabel` with a brief text message
- Stub operations show success-sounding messages (e.g., "Reversed: Audio 1") despite doing nothing
- No toast / snackbar notifications for important events
- No distinct visual treatment for errors vs. success vs. informational messages
- Recording does not show a prominent "RECORDING" indicator beyond the status label class change
- Save operations show a checkpoint number but no visual confirmation pulse

**Expected Behavior:**
- **Toast/Snackbar Notifications:** Implement a notification system that shows brief, auto-dismissing notifications above the status bar for important events (track added, recording started, project saved, export completed)
- **Error Notifications:** Errors should be visually distinct (red background, warning icon) and persist longer than success messages
- **Restricted Action Feedback:** When a user attempts an action that is currently restricted (e.g., recording with no armed tracks, pasting with empty clipboard), show a clear notification explaining what is wrong and how to fix it
- **Success Confirmation:** Successful destructive operations (delete track, reverse audio, split clip) should show a brief confirmation with an "Undo" action link in the notification
- **Recording Indicator:** When recording, display a prominent pulsing red "REC" indicator in the transport bar (beyond the existing button glow)

**Subtasks:**
- [ ] Create a `NotificationBar` or `ToastNotification` component in `daw-app/…/ui/` that shows auto-dismissing messages above the status bar
- [ ] Define notification levels: `SUCCESS`, `INFO`, `WARNING`, `ERROR`
- [ ] Style each notification level distinctly (green/success, blue/info, orange/warning, red/error)
- [ ] Replace status-bar-only feedback in `MainController` with notification system calls for important operations
- [ ] Add "Undo" action link to notifications for destructive operations
- [ ] Add a persistent "REC" indicator to the transport bar that is visible only during recording
- [ ] Replace misleading success messages in stub context menu items with honest messages (e.g., "Copy Track — not yet implemented") or remove stub feedback entirely
- [ ] Add unit tests for the notification component lifecycle (show, auto-dismiss, dismiss-on-click)

**Non-Goals:**
- Audio feedback (click sounds) for button presses
- System-level desktop notifications

**Affected Files:**
- `daw-app/…/ui/MainController.java` — all action handlers
- `daw-app/…/ui/NotificationBar.java` (new)
- `daw-app/…/resources/…/styles.css` — notification styling

---

## Editor

### 6. Editor View Tool Buttons Are Non-Functional

**Category:** Editor · **Priority:** High

**Motivation:**
The `EditorView` builds a toolbar with Pointer, Pencil, Eraser, Zoom In, and Zoom Out buttons, but none of them have `onAction` handlers. The buttons are purely decorative. The sidebar edit tool buttons (`MainController.initializeEditTools()`) do set the `activeEditTool` state, but this state is never read or applied to any editing operation in the `EditorView`. Users expect tool selection to change the cursor behavior and enable the corresponding editing mode in the piano roll or waveform editor.

**Current Behavior:**
- Clicking Pointer, Pencil, or Eraser buttons in the editor toolbar does nothing
- Clicking Zoom In or Zoom Out buttons in the editor toolbar does nothing
- The sidebar tool buttons update `activeEditTool` state but the state is never consumed
- No cursor change when switching tools
- No editing interactions (draw notes, erase notes, select regions) are implemented in the piano roll or audio waveform

**Expected Behavior:**
- **Pointer Tool:** Click and drag to select notes (MIDI) or time regions (audio). Cursor changes to pointer/arrow.
- **Pencil Tool:** Click to insert a MIDI note at the clicked pitch and time position. Cursor changes to crosshair.
- **Eraser Tool:** Click to delete a MIDI note or audio region under the cursor. Cursor changes to eraser icon.
- **Zoom In / Zoom Out:** Adjust the zoom level of the editor canvas (piano roll grid columns get wider/narrower, waveform display scales horizontally).
- Tool selection in the editor toolbar should synchronize with the sidebar tool state.

**Subtasks:**
- [ ] Wire `EditorView` toolbar buttons to set the active tool (synchronize with `MainController.activeEditTool`)
- [ ] Implement cursor changes based on active tool (pointer, crosshair, eraser icon)
- [ ] Implement Pointer tool: click-to-select on piano roll canvas and waveform display
- [ ] Implement Pencil tool: click-to-insert MIDI note on piano roll canvas
- [ ] Implement Eraser tool: click-to-delete MIDI note on piano roll canvas
- [ ] Implement Zoom In / Zoom Out for editor canvas (adjust `GRID_COLUMNS` or column width, redraw)
- [ ] Add a note/event model to represent MIDI notes in the piano roll
- [ ] Redraw piano roll canvas after tool interactions (note insert/delete)
- [ ] Add unit tests for tool selection synchronization and canvas interaction

**Non-Goals:**
- Full MIDI editing (velocity editing, note resizing, multiple selection)
- Audio waveform editing (destructive editing in the waveform view)

**Affected Files:**
- `daw-app/…/ui/EditorView.java` — `buildToolBar()`, canvas interactions
- `daw-app/…/ui/MainController.java` — tool state synchronization

---

### 7. Audio Editor Trim and Fade Buttons Are Non-Functional

**Category:** Editor · **Priority:** High

**Motivation:**
The audio editor section of `EditorView` includes Trim, Fade In, and Fade Out buttons (built in `buildAudioHandles()`), but none have `onAction` handlers. These are core audio editing operations that users expect to work when they see the buttons.

**Current Behavior:**
- Trim, Fade In, and Fade Out buttons are visible in audio editor mode
- Clicking any of them does nothing — no action handler is registered

**Expected Behavior:**
- **Trim:** Remove audio data outside the current selection (or trim silence from start/end if no selection)
- **Fade In:** Apply a fade-in envelope to the beginning of the audio clip (or selected region)
- **Fade Out:** Apply a fade-out envelope to the end of the audio clip (or selected region)
- Each operation should be undoable
- Visual feedback should confirm the operation completed (waveform re-rendered, notification shown)

**Subtasks:**
- [ ] Add `onAction` handlers to Trim, Fade In, and Fade Out buttons in `EditorView.buildAudioHandles()`
- [ ] Implement Trim: remove audio data outside the selection or trim silence thresholds
- [ ] Implement Fade In: apply a linear or configurable fade curve to the clip start
- [ ] Implement Fade Out: apply a linear or configurable fade curve to the clip end
- [ ] Update `WaveformDisplay` to re-render after editing operations
- [ ] Add undo/redo support for each operation
- [ ] Show a notification confirming the operation succeeded
- [ ] Disable buttons when no audio clip is loaded in the editor
- [ ] Add unit tests for trim and fade DSP operations

**Non-Goals:**
- Non-linear fade curves (exponential, S-curve — separate enhancement)
- Crossfade between adjacent clips (separate feature)

**Affected Files:**
- `daw-app/…/ui/EditorView.java` — `buildAudioHandles()`
- `daw-core/…/audio/AudioClip.java` — trim/fade methods
- `daw-app/…/ui/display/WaveformDisplay.java` — re-render after edit

---

## Transport

### 8. Loop Mode Not Connected to Transport Engine

**Category:** Transport · **Priority:** Medium

**Motivation:**
The loop button (`onToggleLoop()` in `MainController`) toggles a `loopEnabled` boolean and updates the button styling, but this flag is never read by the transport or audio engine. Pressing Play with loop enabled should cause playback to automatically return to the loop start point when it reaches the loop end point. The `Transport` class has no loop start/end fields or loop-aware playback logic.

**Current Behavior:**
- Toggling the loop button changes `loopEnabled` and applies a purple background style
- The `loopEnabled` flag is never passed to `Transport` or checked during playback
- Playback always runs linearly with no looping

**Expected Behavior:**
- `Transport` should have `loopStart`, `loopEnd`, and `loopEnabled` properties
- When loop mode is enabled and the playback position reaches `loopEnd`, it should automatically reset to `loopStart`
- The arrangement view should display loop region markers (start/end handles)
- The loop region should be editable (drag handles to adjust start/end)

**Subtasks:**
- [ ] Add `loopEnabled`, `loopStartInBeats`, and `loopEndInBeats` fields to `Transport`
- [ ] Wire `MainController.onToggleLoop()` to set `transport.setLoopEnabled(loopEnabled)`
- [ ] Add loop-aware position advancement logic in the transport (wrap position at loop end)
- [ ] Add default loop region (e.g., 0–16 beats) that can be adjusted
- [ ] Display loop region markers in the arrangement view timeline
- [ ] Add unit tests for loop-aware position wrapping

**Non-Goals:**
- Nested loops or loop queue
- Loop record (separate from loop playback)

**Affected Files:**
- `daw-core/…/transport/Transport.java` — add loop fields and logic
- `daw-app/…/ui/MainController.java` — `onToggleLoop()`

---

## Mixer

### 9. Mixer Send Level Slider Not Wired to Audio Routing

**Category:** Mixer · **Priority:** Medium

**Motivation:**
Each channel strip in `MixerView` includes a "SEND" label and a `Slider` for send level, but the slider's `valueProperty` has no listener. The `MixerChannel` model has no send level field. In a DAW, send controls route a copy of the channel's audio to an auxiliary/return bus (e.g., reverb, delay return). Without this wiring, the send slider is purely decorative.

**Current Behavior:**
- Each channel strip shows a "SEND" slider at default 0.0
- Moving the slider has no effect — no listener, no model update, no audio routing

**Expected Behavior:**
- `MixerChannel` should have a `sendLevel` property (0.0–1.0)
- Moving the send slider should update `MixerChannel.sendLevel`
- The send should route audio to a configurable aux/return bus
- The send destination bus should be selectable (context menu or drop-down on the send label)

**Subtasks:**
- [ ] Add `sendLevel` field with getter/setter to `MixerChannel`
- [ ] Add a `valueProperty` listener to the send slider in `MixerView.buildChannelStrip()` to update `MixerChannel.sendLevel`
- [ ] Add an aux/return bus concept to `Mixer` (at minimum, one shared reverb return)
- [ ] Route audio to the aux bus at the configured send level during `AudioEngine.processBlock()`
- [ ] Add a right-click menu on the SEND label to select the send destination
- [ ] Add unit tests for send level routing

**Non-Goals:**
- Multiple send buses per channel (enhancement)
- Pre/post fader send toggle (enhancement)

**Affected Files:**
- `daw-core/…/mixer/MixerChannel.java` — add `sendLevel`
- `daw-core/…/mixer/Mixer.java` — add aux bus support
- `daw-app/…/ui/MixerView.java` — wire send slider listener

---

## Track Controls

### 10. Phase Invert Toggle Has No Backing Model State

**Category:** Track Controls · **Priority:** Medium

**Motivation:**
Each track strip includes a Phase Invert button (Ø icon) that, when clicked, only updates the status bar with "Phase inverted: <trackName>". The `Track` class has no `phaseInverted` boolean field. Phase inversion is an essential mixing tool for correcting phase cancellation between multi-miked sources. The button should toggle a persistent state that is applied during audio processing.

**Current Behavior:**
- Clicking the phase button updates the status bar text
- No state change occurs on the `Track` object
- No audio processing change occurs (samples are not inverted)
- The button does not toggle visually (no active/inactive style)

**Expected Behavior:**
- `Track` should have a `phaseInverted` boolean field
- Clicking the phase button should toggle `track.phaseInverted` and update the button style (active = highlighted)
- During audio processing, phase-inverted tracks should have their sample values multiplied by −1.0
- The phase state should be persisted with the project

**Subtasks:**
- [ ] Add `phaseInverted` field with getter/setter to `Track`
- [ ] Update the phase button `onAction` in `MainController.addTrackToUI()` to toggle `track.setPhaseInverted()` and apply active/inactive styling
- [ ] Apply phase inversion (multiply samples by −1.0) in the audio processing pipeline for inverted tracks
- [ ] Sync phase state with the `MixerView` channel strip (if phase button exists there)
- [ ] Add unit tests for phase inversion toggle and audio processing

**Non-Goals:**
- Per-frequency phase rotation (allpass filter — separate DSP feature)

**Affected Files:**
- `daw-core/…/track/Track.java` — add `phaseInverted`
- `daw-app/…/ui/MainController.java` — phase button `onAction`
- `daw-core/…/audio/AudioEngine.java` — apply inversion during processing

---

## Editing

### 11. Snap-to-Grid Flag Not Applied to Editing Operations

**Category:** Editing · **Priority:** Medium

**Motivation:**
The snap toggle button and grid resolution context menu are fully wired for UI state management (toggle, persistence, styling), but the `snapEnabled` flag and `gridResolution` are never consumed by any editing logic. Snap-to-grid should quantize the playback cursor position, note placement in the piano roll, clip positioning on the timeline, and transport seek operations. Currently, these values exist as dead state.

**Current Behavior:**
- Toggling snap updates `snapEnabled`, persists it, and updates button styling
- Changing grid resolution updates `gridResolution` and persists it
- No editing operation reads `snapEnabled` or `gridResolution`
- Transport position seeks (`onSkipForward`) do not quantize to grid

**Expected Behavior:**
- When snap is enabled, transport seek operations should snap the position to the nearest grid boundary
- MIDI note placement (pencil tool) should snap the note start time to the grid
- Clip positioning (move operations) should snap clip start to the grid
- The grid resolution (whole, half, quarter, eighth, sixteenth, triplet) should determine the snap granularity in beats

**Subtasks:**
- [ ] Create a `SnapQuantizer` utility class that snaps a beat position to the nearest grid boundary for a given `GridResolution`
- [ ] Apply `SnapQuantizer` in `onSkipForward()` to quantize the jump distance
- [ ] Apply `SnapQuantizer` in the editor view when placing MIDI notes (pencil tool)
- [ ] Apply `SnapQuantizer` when moving clips on the timeline
- [ ] Add unit tests for `SnapQuantizer` with each `GridResolution` value
- [ ] Show the current grid resolution in the status bar or arrangement timeline ruler

**Non-Goals:**
- Magnetic snap (snap only when within a proximity threshold)
- Relative grid snapping (maintain offset when moving multiple items)

**Affected Files:**
- `daw-app/…/ui/MainController.java` — `onSkipForward()`, editing operations
- `daw-app/…/ui/SnapQuantizer.java` (new)
- `daw-app/…/ui/EditorView.java` — note placement quantization

---

## Sidebar

### 12. Sidebar Home, Search, and Help Buttons Lack Actions

**Category:** Sidebar · **Priority:** Medium

**Motivation:**
Three sidebar toolbar buttons — Home, Search, and Help — are visible and styled with icons and tooltips, but have no `onAction` handlers. They do nothing when clicked. Users expect these buttons to perform meaningful navigation or assistance actions.

**Current Behavior:**
- **Home button:** No `onAction` handler. Tooltip says "Home — Return to the default view" but clicking does nothing.
- **Search button:** No `onAction` handler. Tooltip says "Search — Find tracks, clips, and project items" but clicking does nothing.
- **Help button:** No `onAction` handler. Tooltip says "Help — View documentation and keyboard shortcuts" but clicking does nothing.

**Expected Behavior:**
- **Home:** Reset the view to the default arrangement view, clear any selection, and reset zoom to fit. Essentially a "reset workspace" action.
- **Search:** Open a search/filter panel or floating dialog that allows the user to search for tracks by name, clips, and project items. Could integrate with the existing `BrowserPanel` search field.
- **Help:** Open a help dialog or panel that displays keyboard shortcuts (the key bindings table from `SettingsDialog`), a brief feature guide, and links to documentation.

**Subtasks:**
- [ ] Wire Home button `onAction` to switch to arrangement view, reset zoom, clear selection, and update status bar
- [ ] Wire Search button `onAction` to either open a search dialog or focus the `BrowserPanel` search field (toggling the browser panel visible if hidden)
- [ ] Wire Help button `onAction` to open a `HelpDialog` that displays keyboard shortcuts and feature descriptions
- [ ] Create `HelpDialog` class in `daw-app/…/ui/` with tabbed sections for shortcuts, getting started, and about
- [ ] Add unit tests for button action wiring

**Non-Goals:**
- Full-text search across audio file content (future feature)
- Context-sensitive help (tooltip overlays on hover)

**Affected Files:**
- `daw-app/…/ui/MainController.java` — `initialize()`, new `onHome()`, `onSearch()`, `onHelp()` methods
- `daw-app/…/ui/HelpDialog.java` (new)

---

## Settings

### 13. Settings Dialog Changes Not Applied to Running Engine

**Category:** Settings · **Priority:** Medium

**Motivation:**
The `SettingsDialog` allows users to change audio settings (sample rate, bit depth, buffer size), project defaults (auto-save interval, tempo), appearance (UI scale), and plugin scan paths. When the user clicks "Apply", the `SettingsModel` preferences are updated, but these changes are not propagated to the running `AudioEngine`, `Transport`, or UI. A restart hint is shown for audio settings, but non-audio settings (tempo, UI scale, auto-save) should take effect immediately.

**Current Behavior:**
- Clicking "Apply" writes values to `SettingsModel` (backed by `Preferences`)
- The running `AudioEngine` continues using the format from project creation
- Changing the default tempo does not update the current project's transport tempo
- Changing the UI scale does not adjust the current window's scale transform
- Changing the auto-save interval does not reconfigure the `CheckpointManager`

**Expected Behavior:**
- **Default Tempo:** Should update the current project's transport tempo if the user confirms (or only apply to new projects)
- **UI Scale:** Should immediately apply a scale transform to the root scene
- **Auto-Save Interval:** Should reconfigure the `CheckpointManager` immediately
- **Audio Settings:** Should display a clear message that a restart is required, and ideally offer to restart the audio engine (stop + reconfigure + start)
- **Plugin Scan Paths:** Should trigger a re-scan for plugins

**Subtasks:**
- [ ] After `applySettings()`, propagate UI scale to the scene's root transform
- [ ] After `applySettings()`, reconfigure the `CheckpointManager` auto-save interval
- [ ] After `applySettings()`, optionally update the transport tempo (with user confirmation)
- [ ] Add a "Restart Audio Engine" button that stops, reconfigures, and restarts the `AudioEngine` with new format settings
- [ ] After `applySettings()`, trigger a plugin re-scan if scan paths changed
- [ ] Add a callback mechanism from `SettingsDialog` to `MainController` to apply live changes
- [ ] Add unit tests for settings propagation

**Non-Goals:**
- Hot-swapping audio backend while recording (always require stop first)

**Affected Files:**
- `daw-app/…/ui/SettingsDialog.java` — `applySettings()`
- `daw-app/…/ui/MainController.java` — receive and apply setting changes
- `daw-core/…/persistence/CheckpointManager.java` — reconfigure interval

---

## Export

### 14. Track Export Operations Are Stubs

**Category:** Export · **Priority:** Medium

**Motivation:**
The track context menu includes five export options (WAV, MP3, AAC, MIDI, WMA), all of which are stubs that only update the status bar. Export is a fundamental DAW feature — users need to bounce tracks to files for sharing, mastering, or external processing.

**Current Behavior:**
- Clicking "Export as WAV" (or any export option) shows "Exporting WAV: Audio 1" in the status bar
- No file dialog is opened
- No audio data is written to disk

**Expected Behavior:**
- **Export as WAV:** Open a save dialog, bounce the track's audio data to a WAV file at the project's sample rate and bit depth
- **Export as MIDI:** Open a save dialog, write the track's MIDI data to a Standard MIDI File (.mid)
- **Export as MP3, AAC, WMA:** Either implement via a transcoding library (if available) or disable with a tooltip "Format not supported — export as WAV and convert externally"
- A progress indicator should be shown for long exports
- The notification system should confirm export success with the file path

**Subtasks:**
- [ ] Implement WAV export: open `FileChooser`, write track audio data using `javax.sound.sampled.AudioSystem`
- [ ] Implement MIDI export: open `FileChooser`, write MIDI events using `javax.sound.midi.MidiSystem`
- [ ] Disable MP3, AAC, WMA export items with "Format not yet supported" tooltip
- [ ] Show a progress dialog or notification for exports that take more than 1 second
- [ ] Confirm export success with a notification showing the output file path
- [ ] Add unit tests for WAV and MIDI export

**Non-Goals:**
- Lossless compression export (FLAC — separate feature)
- Batch export of all tracks (separate feature)
- Full project export / stems export (separate feature)

**Affected Files:**
- `daw-app/…/ui/MainController.java` — export menu item handlers in `buildTrackContextMenu()`
- `daw-core/…/audio/AudioClip.java` — access to raw audio data for bounce

---

## Toolbar

### 15. Top Toolbar Layout Inconsistent with Design Screenshots

**Category:** Toolbar · **Priority:** Low

**Motivation:**
The README screenshots show a polished, well-spaced toolbar with properly aligned transport controls, track management buttons, and status displays. The actual rendered toolbar has alignment and spacing issues that make it look less refined than the design intent. Button groups are not visually separated, the time display is not prominently sized, and the status label can overflow or truncate in narrow windows.

**Current Behavior:**
- Transport buttons (skip back, play, pause, stop, record, skip forward, loop) are in an HBox but without clear visual grouping
- The time display label is the same size as other labels, not prominent
- Track management buttons (add audio, add MIDI) are adjacent to transport with no separator
- Undo/redo buttons are adjacent to add track buttons with no separator
- `preventButtonTruncation()` sets minimum width to preferred, but this doesn't fully prevent overflow at narrow widths
- The status bar label can be pushed off-screen by long project info text

**Expected Behavior:**
- Transport controls should be visually grouped (centered, with spacing separators)
- The time display should be larger / monospaced for easy reading
- Button groups should have visual separators or spacing gaps between them:
  - Group 1: Skip Back, Play, Pause, Stop, Record, Skip Forward, Loop
  - Group 2: Add Audio Track, Add MIDI Track
  - Group 3: Undo, Redo
  - Group 4: Snap, Save, Plugins
- At narrow widths, lower-priority buttons should hide or collapse to an overflow menu
- The time display should use a monospaced font for stable width

**Subtasks:**
- [ ] Add `Separator` nodes or spacer `Region` nodes between toolbar button groups in the FXML layout
- [ ] Apply a monospaced font CSS class to `timeDisplay` for stable character widths
- [ ] Increase the font size of `timeDisplay` to make it more prominent
- [ ] Add a CSS class for toolbar button groups that provides consistent spacing and visual separation
- [ ] Review and adjust the FXML layout constraints to match the README screenshot proportions
- [ ] Add a responsive overflow behavior for very narrow windows (hide non-essential buttons)
- [ ] Verify layout at minimum window size (1280px) and comfortable width (1600px+)

**Non-Goals:**
- Customizable toolbar (drag-and-drop reordering of buttons)
- Detachable toolbar (floating toolbar window)

**Affected Files:**
- `daw-app/…/resources/…/main-view.fxml` — toolbar layout
- `daw-app/…/resources/…/styles.css` — toolbar styling
- `daw-app/…/ui/MainController.java` — `preventButtonTruncation()`

---

## Summary

These 15 issues address the most critical usability gaps preventing the DAW from functioning as a state-of-the-art application. They are ordered by priority:

| Priority | Count | Issues |
|----------|-------|--------|
| **Critical** | 2 | #1 Recording Pipeline, #2 Input Port Selection |
| **High** | 5 | #3 Context Menu Stubs, #4 Conditional Disabling, #5 User Feedback, #6 Editor Tools, #7 Audio Editor Buttons |
| **Medium** | 7 | #8 Loop Mode, #9 Mixer Send, #10 Phase Invert, #11 Snap-to-Grid, #12 Sidebar Buttons, #13 Settings Propagation, #14 Track Export |
| **Low** | 1 | #15 Toolbar Layout |

Issues #1 and #2 are **critical blockers** — without a working recording pipeline and input port selection, the DAW cannot perform its primary function. Issues #3–#7 are **high priority** because they directly affect user trust (stub operations that appear to work but do nothing). Issues #8–#14 are **medium priority** infrastructure gaps. Issue #15 is a **low priority** polish item.
