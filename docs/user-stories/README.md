# User Stories

Comprehensive user stories for improving the DAW application. Each story includes **Motivation**, **Goals**, and **Non-Goals** sections to enable iterative development.

Stories are organized by priority and functional area. Use the [issue creation script](../scripts/create-github-issues.sh) to create GitHub issues from these files.

---

## Core Audio Engine

| # | Story | Priority | Area |
|---|-------|----------|------|
| 006 | [Real-Time Audio Playback Engine with Track Mixing](006-audio-engine-real-time-playback.md) | 🔴 Critical | Audio Engine |
| 007 | [Complete Recording Workflow with Monitoring and Count-In](007-recording-workflow.md) | 🔴 Critical | Recording |
| 016 | [Metronome with Configurable Sound and Count-In](016-metronome.md) | 🔴 Critical | Transport |
| 019 | [Project Save, Load, and Auto-Save Reliability](019-project-save-load-autosave.md) | 🔴 Critical | Persistence |
| 057 | [Connect Audio Engine Playback Pipeline to Hardware Output](057-audio-engine-hardware-playback-loop.md) | 🔴 Critical | Audio Engine |
| 060 | [Complete Recording Pipeline with Audio Capture and Clip Creation](060-recording-audio-capture-integration.md) | 🔴 Critical | Recording |
| 063 | [Complete Project Serialization Including Clips, Mixer, and Automation](063-project-serialization-full-state.md) | 🔴 Critical | Persistence |
| 067 | [MIDI Track Playback via SoundFont Synthesis in Audio Engine](067-midi-playback-soundfont-synthesis.md) | 🔴 Critical | Audio Engine |
| 074 | [Wire MIDI Recording Pipeline into Transport and Arrangement View](074-midi-recording-pipeline-integration.md) | 🔴 Critical | Recording |
| 086 | [Apply Mixer Channel Insert Effects During Live Audio Playback](086-insert-effects-live-playback.md) | 🔴 Critical | Audio Engine |
| 087 | [Apply Automation Lane Values to Mixer Parameters During Audio Playback](087-automation-playback-engine.md) | 🔴 Critical | Automation |
| 102 | [Playback-Export Parity: Unified Render Pipeline](102-playback-export-parity.md) | 🔴 Critical | Audio Engine |
| 123 | [Buffer-Underrun Detection and Reporting](123-buffer-underrun-detection-and-reporting.md) | 🟠 High | Audio Engine |
| 125 | [Multicore Parallel Graph Processing](125-multicore-parallel-graph-processing.md) | 🟠 High | Audio Engine |
| 126 | [Automatic Sample-Rate Conversion at Bus Boundaries](126-automatic-sample-rate-conversion-bus-boundaries.md) | 🟠 High | Audio Engine |
| 127 | [64-bit Internal Mix Bus](127-64bit-internal-mix-bus.md) | 🟡 Medium | Audio Engine |
| 128 | [Crash-Safe Audio Thread Isolation](128-crash-safe-audio-thread-isolation.md) | 🟠 High | Audio Engine |
| 129 | [Per-Track CPU Budget Enforcement](129-per-track-cpu-budget.md) | 🟡 Medium | Audio Engine |
| 130 | [Audio Backend Selection (ASIO / CoreAudio / WASAPI / JACK)](130-backend-selection-asio-coreaudio-wasapi-jack.md) | 🟠 High | Audio Engine |
| 212 | [Native Driver Control Panel Launch](212-native-driver-control-panel-launch.md) | 🟠 High | Audio Engine |
| 213 | [Driver-Reported Buffer Size and Sample-Rate Enumeration](213-driver-reported-buffer-size-and-rate-enumeration.md) | 🟠 High | Audio Engine |
| 214 | [Audio Device Hot-Plug Detection and Reconnect](214-audio-device-hot-plug-detection-and-reconnect.md) | 🟠 High | Audio Engine |
| 215 | [Driver-Reported Channel Names in Routing UI](215-driver-reported-channel-names-in-routing-ui.md) | 🟠 High | Audio Engine |
| 216 | [Hardware Clock Source Selection](216-hardware-clock-source-selection.md) | 🟡 Medium | Audio Engine |
| 217 | [Driver-Reported Round-Trip Latency Compensation](217-driver-reported-roundtrip-latency-recording-compensation.md) | 🟠 High | Audio Engine |
| 218 | [Driver-Initiated Reset Request Handling](218-driver-initiated-reset-request-handling.md) | 🟠 High | Audio Engine |

## Recording & Monitoring

| # | Story | Priority | Area |
|---|-------|----------|------|
| 131 | [Punch-In/Out Recording Regions](131-punch-in-out-recording-regions.md) | 🟠 High | Recording |
| 132 | [Loop Record with Multiple Takes](132-loop-record-multiple-takes.md) | 🟠 High | Recording |
| 133 | [Per-Track Input Monitoring Modes (OFF / AUTO / ALWAYS / TAPE)](133-per-track-input-monitoring-modes.md) | 🟠 High | Monitoring |
| 134 | [Pre-Roll / Post-Roll Configuration](134-pre-roll-post-roll-configuration.md) | 🟡 Medium | Recording |
| 135 | [Headphone Cue Mix Bus](135-headphone-cue-mix-bus.md) | 🟠 High | Monitoring |
| 136 | [Click Track Side-Output for Headphone Cue](136-click-track-side-output.md) | 🟡 Medium | Monitoring |
| 137 | [Input Gain Staging with Clip Indicators](137-input-gain-staging-clip-indicators.md) | 🟠 High | Recording |

## Arrangement View & Editing

| # | Story | Priority | Area |
|---|-------|----------|------|
| 004 | [Timeline Ruler with Bar/Beat Grid and Click-to-Seek Playhead](004-timeline-ruler-and-playhead.md) | 🔴 Critical | Arrangement |
| 052 | [Arrangement View Track Lanes with Clip Rendering](052-arrangement-track-lanes-clip-rendering.md) | 🔴 Critical | Arrangement |
| 055 | [Playhead Visual Rendering and Click-to-Seek in Arrangement View](055-playhead-rendering-and-click-to-seek.md) | 🔴 Critical | Arrangement |
| 002 | [Audio Clip Splitting, Trimming, and Fade Handles](002-clip-splitting-and-trimming.md) | 🟠 High | Editing |
| 038 | [Clip Clipboard Operations (Copy, Cut, Paste, Duplicate)](038-clip-clipboard-operations.md) | 🟠 High | Editing |
| 001 | [Track Drag-and-Drop Reordering](001-track-drag-and-drop-reordering.md) | 🟠 High | Arrangement |
| 008 | [Audio File Import via Drag-and-Drop and File Menu](008-audio-file-import.md) | 🟠 High | File I/O |
| 003 | [Per-Track Automation Lanes with Envelope Editing](003-automation-lanes.md) | 🟠 High | Automation |
| 053 | [Wire Edit Tools to Clip Interactions in Arrangement View](053-edit-tool-clip-interaction.md) | 🟠 High | Editing |
| 054 | [Wire Track Drag-and-Drop Reordering in Track List Panel](054-track-drag-and-drop-ui-wiring.md) | 🟠 High | Arrangement |
| 058 | [Interactive Clip Edge Trim Handles in Arrangement View](058-clip-edge-trim-handles.md) | 🟠 High | Editing |
| 059 | [Automation Lane UI Rendering and Breakpoint Editing](059-automation-lane-ui-rendering.md) | 🟠 High | Automation |
| 061 | [Interactive Clip Fade-In and Fade-Out Handles in Arrangement View](061-clip-fade-handles.md) | 🟠 High | Editing |
| 064 | [Audio File Import with Clip Placement in Arrangement View](064-audio-file-import-to-arrangement.md) | 🟠 High | File I/O |
| 070 | [Implement Clip Paste Over, Trim to Selection, and Crop Operations](070-clip-paste-over-trim-to-selection-crop.md) | 🟠 High | Editing |
| 071 | [Loop Region Visualization and Editing in Arrangement View](071-loop-region-visualization.md) | 🟠 High | Transport |
| 072 | [Time Selection Range Visualization and Interaction](072-time-selection-range-visualization.md) | 🟠 High | Editing |
| 075 | [Rubber-Band Multi-Clip Selection in Arrangement View](075-rubber-band-multi-clip-selection.md) | 🟠 High | Editing |
| 076 | [Multi-Clip Group Move, Delete, and Duplicate](076-multi-clip-group-operations.md) | 🟠 High | Editing |
| 031 | [Clip Crossfade Editing Between Overlapping Clips](031-clip-crossfades.md) | 🟡 Medium | Editing |
| 032 | [Markers and Locators for Session Navigation](032-markers-and-locators.md) | 🟡 Medium | Navigation |
| 021 | [Waveform Zoom and Scroll with Minimap Navigation](021-waveform-zoom-and-minimap.md) | 🟡 Medium | Navigation |
| 040 | [Multi-Take Comping Workflow](040-multi-take-comping.md) | 🟡 Medium | Recording |
| 101 | [Plugin Parameter Automation with Write/Latch/Touch Modes](101-plugin-parameter-automation.md) | 🟠 High | Automation |
| 104 | [Render-in-Place for Tracks with Insert Effects and Virtual Instruments](104-render-in-place.md) | 🟠 High | Editing |
| 138 | [Ripple Edit Mode](138-ripple-edit-mode.md) | 🟡 Medium | Editing |
| 139 | [Slip Edit Within Clip](139-slip-edit-within-clip.md) | 🟡 Medium | Editing |
| 140 | [Per-Clip Gain Envelope](140-per-clip-gain-envelope.md) | 🟠 High | Editing |
| 141 | [Clip Reverse and Normalize (Destructive Operations)](141-clip-reverse-normalize-destructive.md) | 🟡 Medium | Editing |
| 142 | [Nudge by Grid and Sample](142-nudge-by-grid-and-sample.md) | 🟡 Medium | Editing |
| 143 | [Cross-Track Range Selection](143-cross-track-range-selection.md) | 🟡 Medium | Editing |
| 144 | [Lane Folding / Collapse for Track List](144-lane-folding-collapse.md) | 🟡 Medium | Arrangement |
| 145 | [Clip Time-Lock](145-clip-time-lock.md) | 🟢 Low | Editing |

## MIDI

| # | Story | Priority | Area |
|---|-------|----------|------|
| 013 | [MIDI Recording and Piano Roll Note Editing](013-midi-recording-and-editing.md) | 🟠 High | MIDI |
| 043 | [SoundFont Browser and MIDI Instrument Assignment](043-soundfont-browser.md) | 🟡 Medium | MIDI |
| 096 | [Graceful MIDI Playback Degradation with User Notification](096-midi-playback-fallback-notification.md) | 🟠 High | MIDI |
| 146 | [Piano-Roll Velocity and CC Lanes](146-piano-roll-velocity-and-cc-lanes.md) | 🟠 High | MIDI |
| 147 | [MIDI Quantize with Swing and Groove Templates](147-midi-quantize-with-swing-and-groove.md) | 🟠 High | MIDI |
| 148 | [MIDI Humanization](148-midi-humanization.md) | 🟡 Medium | MIDI |
| 149 | [MIDI Clip Looping](149-midi-clip-looping.md) | 🟡 Medium | MIDI |
| 150 | [Drum Grid Editor](150-drum-grid-editor.md) | 🟠 High | MIDI |
| 151 | [MIDI Arpeggiator Built-In Plugin](151-midi-arpeggiator-plugin.md) | 🟡 Medium | MIDI |
| 152 | [External MIDI Controller Mapping](152-external-midi-controller-mapping.md) | 🟠 High | MIDI |

## Mixer & Effects

| # | Story | Priority | Area |
|---|-------|----------|------|
| 009 | [Per-Channel Insert Effects and EQ in Mixer View](009-mixer-channel-eq-and-inserts.md) | 🟠 High | Mixer |
| 005 | [Mixer Send/Return Bus Routing](005-mixer-send-return-routing.md) | 🟠 High | Mixer |
| 030 | [Plugin Parameter UI with Knobs, Sliders, and Preset Management](030-plugin-parameter-ui.md) | 🟠 High | Plugins |
| 062 | [Wire Mixer Channel Insert Effects Slots to DSP Processors](062-mixer-insert-effects-ui-wiring.md) | 🟠 High | Mixer |
| 065 | [Wire Mixer Send/Return Bus Routing in Mixer View](065-mixer-send-return-ui-wiring.md) | 🟠 High | Mixer |
| 090 | [Plugin Delay Compensation for Insert Effects](090-plugin-delay-compensation.md) | 🟠 High | Audio Engine |
| 091 | [Sidechain Input Routing for Compressor and Dynamics](091-sidechain-input-routing.md) | 🟠 High | Mixer |
| 092 | [Input/Output Audio Device Routing per Track](092-audio-io-routing-per-track.md) | 🟠 High | Mixer |
| 103 | [Mixer Scene Snapshots and A/B Recall](103-mixer-scene-snapshots.md) | 🟡 Medium | Mixing |
| 100 | [Track Templates and Channel Strip Presets](100-track-templates-channel-presets.md) | 🟡 Medium | Mixer |
| 034 | [CLAP Plugin Hosting Integration in Mixer](034-clap-plugin-hosting.md) | 🟡 Medium | Plugins |
| 124 | [Plugin Latency Compensation Telemetry](124-plugin-latency-compensation-telemetry.md) | 🟡 Medium | Mixer |
| 153 | [VCA Groups](153-vca-groups.md) | 🟡 Medium | Mixer |
| 154 | [Per-Send Pre/Post-Fader Toggle](154-per-send-pre-post-fader-toggle.md) | 🟠 High | Mixer |
| 157 | [Mid/Side Processing Wrapper](157-mid-side-processing-wrapper.md) | 🟡 Medium | Mixer |
| 158 | [Solo-Safe / Solo-In-Place / Solo Defeat](158-solo-safe-solo-in-place-defeat.md) | 🟡 Medium | Mixer |
| 159 | [Mixer Channel Link / Stereo Pair](159-mixer-channel-link-stereo-pair.md) | 🟠 High | Mixer |

## Built-In Plugins

| # | Story | Priority | Area |
|---|-------|----------|------|
| 078 | [BuiltInDawPlugin Sealed Interface for First-Party Plugin Capabilities](078-built-in-daw-plugin-sealed-interface.md) | 🟠 High | Plugins |
| 079 | [Built-In Plugin Discovery and Plugins Menu Integration](079-built-in-plugin-discovery-and-menu-integration.md) | 🟠 High | Plugins |
| 080 | [Virtual Keyboard Built-In Plugin (KeyboardProcessorView)](080-keyboard-processor-view-built-in-plugin.md) | 🟠 High | Plugins |
| 081 | [Spectrum Analyzer Built-In Plugin](081-spectrum-analyzer-built-in-plugin.md) | 🟡 Medium | Plugins |
| 082 | [Chromatic Tuner Built-In Plugin](082-tuner-built-in-plugin.md) | 🟡 Medium | Plugins |
| 083 | [Signal Generator Built-In Plugin](083-signal-generator-built-in-plugin.md) | 🟡 Medium | Plugins |
| 084 | [Metronome Built-In Plugin](084-metronome-built-in-plugin.md) | 🟡 Medium | Plugins |
| 088 | [Bridge Built-In Effect Plugins to Their DSP Processors](088-built-in-effect-plugin-dsp-bridge.md) | 🔴 Critical | Plugins |
| 089 | [DawPlugin Audio Processing Contract for Real-Time Effect Chains](089-plugin-audio-processing-contract.md) | 🟠 High | Plugins |
| 099 | [Graphic Equalizer Built-In Plugin (Octave/Third-Octave)](099-graphic-equalizer-built-in-plugin.md) | 🟡 Medium | Plugins |
| 106 | [Waveshaper and Saturation Built-In Plugin with Oversampling](106-waveshaper-saturation-built-in-plugin.md) | 🟡 Medium | Plugins |
| 155 | [Bus Compressor Built-In Plugin](155-bus-compressor-built-in-plugin.md) | 🟡 Medium | Plugins |
| 156 | [Spectrum-Matched EQ](156-spectrum-matched-eq.md) | 🟢 Low | Plugins |
| 160 | [Multiband Compressor Built-In Plugin](160-multiband-compressor-built-in-plugin.md) | 🟡 Medium | Plugins |
| 161 | [De-Esser Built-In Plugin](161-de-esser-built-in-plugin.md) | 🟡 Medium | Plugins |
| 162 | [True-Peak Brickwall Limiter](162-true-peak-brickwall-limiter.md) | 🟠 High | Plugins |
| 163 | [Transient Shaper Built-In Plugin](163-transient-shaper-built-in-plugin.md) | 🟡 Medium | Plugins |
| 164 | [Noise Gate with Sidechain](164-noise-gate-with-sidechain.md) | 🟡 Medium | Plugins |
| 165 | [Convolution Reverb Built-In Plugin](165-convolution-reverb-built-in-plugin.md) | 🟡 Medium | Plugins |
| 169 | [Harmonic Exciter / Enhancer](169-harmonic-exciter-enhancer.md) | 🟢 Low | Plugins |

## Metering & Analysis

| # | Story | Priority | Area |
|---|-------|----------|------|
| 014 | [LUFS Loudness Metering with Platform Targets](014-lufs-loudness-metering.md) | 🟠 High | Metering |
| 023 | [Spectrum Analyzer with Real-Time FFT Display](023-spectrum-analyzer.md) | 🟡 Medium | Analysis |
| 028 | [Stereo Correlation Meter and Goniometer/Vectorscope](028-goniometer-vectorscope.md) | 🟡 Medium | Metering |
| 037 | [CPU and Audio Performance Monitor](037-cpu-performance-monitor.md) | 🟡 Medium | Performance |
| 166 | [LUFS Short-Term and Momentary Histories](166-lufs-short-term-and-momentary.md) | 🟠 High | Metering |

## Mastering

| # | Story | Priority | Area |
|---|-------|----------|------|
| 015 | [Mastering Chain View with Presets and A/B Comparison](015-mastering-chain-view.md) | 🟡 Medium | Mastering |
| 073 | [Mastering Chain Live Audio Processing and Gain Reduction Metering](073-mastering-chain-live-processing.md) | 🟠 High | Mastering |
| 025 | [Album Sequencing and Assembly View](025-album-sequencing.md) | 🟡 Medium | Mastering |
| 041 | [Reference Track A/B Comparison](041-reference-track-comparison.md) | 🟡 Medium | Mixing |
| 167 | [Dithered Bit-Depth Reduction Stage](167-dithered-bit-depth-reduction-stage.md) | 🟠 High | Mastering |
| 168 | [ISRC and CD-Text Metadata](168-isrc-cd-text-metadata.md) | 🟡 Medium | Mastering |
| 182 | [DDP Image Export for CD Replication](182-ddp-image-export-for-cd.md) | 🟡 Medium | Mastering |

## Export & Interoperability

| # | Story | Priority | Area |
|---|-------|----------|------|
| 011 | [Multi-Format Audio Export with Dithering and Sample Rate Conversion](011-multi-format-export.md) | 🟠 High | Export |
| 068 | [Support FLAC, AIFF, OGG, and MP3 Audio File Import](068-non-wav-audio-file-import.md) | 🟠 High | File I/O |
| 069 | [Support OGG, MP3, and AAC Audio Export Formats](069-ogg-mp3-aac-audio-export.md) | 🟠 High | Export |
| 029 | [Track Stem Export (Bounce Individual Tracks)](029-track-stem-export.md) | 🟡 Medium | Export |
| 020 | [DAWproject Format Import/Export for Session Interoperability](020-dawproject-import-export.md) | 🟡 Medium | Interop |
| 026 | [ADM BWF Export for Dolby Atmos Deliverables](026-adm-bwf-atmos-export.md) | 🟢 Low | Immersive |
| 097 | [Native Library Availability Detection and Export Format Validation](097-native-library-detection-export-validation.md) | 🟠 High | Export |
| 181 | [Stem-Plus-Master Bundle Export](181-stem-plus-master-bundle-export.md) | 🟠 High | Export |
| 183 | [Game-Audio Export for Wwise / FMOD](183-game-audio-export-wwise-fmod.md) | 🟢 Low | Export |
| 184 | [MusicXML Export from MIDI](184-musicxml-export-from-midi.md) | 🟢 Low | Interop |
| 185 | [OMF/AAF Interchange for Film Post](185-omf-aaf-interchange-for-film-post.md) | 🟢 Low | Interop |
| 186 | [Offline Render Queue](186-offline-render-queue.md) | 🟡 Medium | Export |

## Spatial / Immersive Audio

| # | Story | Priority | Area |
|---|-------|----------|------|
| 017 | [3D Spatial Panner UI for Immersive Audio Positioning](017-spatial-panner-ui.md) | 🟡 Medium | Spatial |
| 018 | [Binaural Monitoring with HRTF Profile Selection](018-binaural-monitoring.md) | 🟡 Medium | Monitoring |
| 039 | [Fold-Down Monitoring Preview (Immersive to Stereo to Mono)](039-folddown-monitoring.md) | 🟡 Medium | Monitoring |
| 045 | [Room Acoustic Simulation Parameter Controls in Telemetry View](045-room-simulation-controls.md) | 🟡 Medium | Spatial |
| 066 | [Sound Wave Telemetry Visualization Enhancements](066-telemetry-visualization-enhancements.md) | 🟡 Medium | Telemetry |
| 105 | [Integrate daw-acoustics Module into Core Audio Pipeline](105-daw-acoustics-integration.md) | 🟡 Medium | Spatial |
| 170 | [ADM BWF Import with Object Round-Trip](170-adm-bwf-import-with-object-round-trip.md) | 🟢 Low | Immersive |
| 171 | [7.1.4 Bed Mixing Workflow](171-7-1-4-bed-mixing-workflow.md) | 🟡 Medium | Immersive |
| 172 | [Object Panner Automation](172-object-panner-automation.md) | 🟡 Medium | Immersive |
| 173 | [Ambisonic Decoder Routing](173-ambisonic-decoder-routing.md) | 🟢 Low | Immersive |
| 174 | [Personalized HRTF SOFA Import](174-personalized-hrtf-sofa-import.md) | 🟡 Medium | Monitoring |
| 175 | [Atmos Renderer A/B Comparison](175-atmos-renderer-ab-comparison.md) | 🟡 Medium | Immersive |

## Telemetry & Acoustics

| # | Story | Priority | Area |
|---|-------|----------|------|
| 120 | [Telemetry Auto-Configure from Armed Tracks](120-telemetry-auto-configure-from-armed-tracks.md) | 🟡 Medium | Telemetry |
| 121 | [Telemetry Auto-Detect Room Dimensions from Mic Distance](121-telemetry-auto-room-dimensions-from-mic-distance.md) | 🟢 Low | Telemetry |
| 122 | [Telemetry Support for Complex (Non-Shoebox) Room Shapes](122-telemetry-complex-room-shapes.md) | 🟢 Low | Telemetry |
| 176 | [Per-Surface Material Map](176-per-surface-material-map.md) | 🟢 Low | Acoustics |
| 177 | [Absorber/Diffuser Placement Hints](177-absorber-diffuser-placement-hints.md) | 🟢 Low | Acoustics |
| 178 | [Speaker Boundary Interference Response](178-speaker-boundary-interference-response.md) | 🟢 Low | Acoustics |
| 179 | [Room Mode Schroeder Frequency Calculator](179-room-mode-schroeder-frequency.md) | 🟡 Medium | Acoustics |
| 180 | [Critical Distance Visualization](180-critical-distance-visualization.md) | 🟡 Medium | Acoustics |

## Project & Persistence

| # | Story | Priority | Area |
|---|-------|----------|------|
| 187 | [File Locking on Shared Network Volumes](187-file-locking-shared-network-volumes.md) | 🟡 Medium | Persistence |
| 188 | [Project Version Migration Registry](188-project-version-migration-registry.md) | 🟠 High | Persistence |
| 189 | [Project Archive (ZIP With Assets)](189-project-archive-zip-with-assets.md) | 🟠 High | Persistence |
| 190 | [Snapshot History Browser](190-snapshot-history-browser.md) | 🟡 Medium | Persistence |
| 191 | [Auto-Backup Rotation and Retention Policy](191-auto-backup-rotation-retention-policy.md) | 🟠 High | Persistence |

## UI / UX & Usability

| # | Story | Priority | Area |
|---|-------|----------|------|
| 010 | [Comprehensive Keyboard Shortcuts and Customizable Key Bindings](010-keyboard-shortcuts.md) | 🟠 High | Usability |
| 022 | [Track Color Coding and Custom Naming](022-track-color-and-naming.md) | 🟠 High | Usability |
| 024 | [Undo/Redo UI Integration with History Panel](024-undo-redo-history-panel.md) | 🟠 High | Usability |
| 056 | [Metronome UI Toggle and Configuration in Transport Bar](056-metronome-ui-transport-integration.md) | 🟠 High | Transport |
| 033 | [Dark Theme Polish and UI Consistency Pass](033-dark-theme-polish.md) | 🟡 Medium | Design |
| 027 | [Browser Panel Sample Preview and Waveform Thumbnails](027-browser-sample-preview.md) | 🟡 Medium | Browser |
| 044 | [Notification System with Contextual Feedback](044-notification-system.md) | 🟡 Medium | Usability |
| 098 | [Audio Engine Configuration UI (Buffer Size, Sample Rate, Backend)](098-audio-engine-configuration-ui.md) | 🟠 High | Usability |
| 192 | [Command Palette (Ctrl+K)](192-command-palette-ctrl-k.md) | 🟠 High | Usability |
| 193 | [Customizable Workspace Layouts](193-customizable-workspace-layouts.md) | 🟡 Medium | Usability |
| 194 | [WCAG-Accessible Color Themes](194-wcag-accessible-color-themes.md) | 🟠 High | Accessibility |
| 195 | [Resizable, Detachable, Dockable Panels](195-resizable-detachable-dockable-panels.md) | 🟠 High | Usability |
| 196 | [Contextual Help Overlay](196-contextual-help-overlay.md) | 🟡 Medium | Usability |
| 197 | [Drag-and-Drop Target Visual Feedback](197-drag-and-drop-target-visual-feedback.md) | 🟡 Medium | Usability |

## Code Quality & Refactoring

| # | Story | Priority | Area |
|---|-------|----------|------|
| 093 | [Extract Remaining Responsibilities from MainController](093-extract-remaining-main-controller.md) | 🟠 High | Refactoring |
| 094 | [Split EditorView into MIDI and Audio Editor Components](094-split-editor-view.md) | 🟠 High | Refactoring |
| 095 | [Extract Clip and Waveform Rendering from ArrangementCanvas](095-extract-arrangement-canvas-renderers.md) | 🟡 Medium | Refactoring |
| 198 | [Decompose God-Class Controllers](198-decompose-god-class-controllers.md) | 🟠 High | Refactoring |
| 199 | [Replace Static Singletons with Dependency Injection](199-replace-static-singletons-with-di.md) | 🟠 High | Refactoring |
| 200 | [Tighten module-info Exports](200-tighten-module-info-exports.md) | 🟡 Medium | Refactoring |
| 201 | [Immutable Record Domain Models](201-immutable-record-domain-models.md) | 🟡 Medium | Refactoring |
| 202 | [Sealed Event Hierarchy](202-sealed-event-hierarchy.md) | 🟡 Medium | Refactoring |
| 203 | [Central Typed Event Bus](203-central-typed-event-bus.md) | 🟠 High | Refactoring |

## Performance

| # | Story | Priority | Area |
|---|-------|----------|------|
| 035 | [Track Freeze and Unfreeze for CPU Management](035-track-freeze.md) | 🟡 Medium | Performance |
| 204 | [ZGC Tuning and Buffer-Pool Recycling](204-zgc-tuning-buffer-pool-recycling.md) | 🟡 Medium | Performance |
| 205 | [Virtual Threads for Non-Realtime Workloads](205-virtual-threads-for-non-realtime.md) | 🟡 Medium | Performance |
| 206 | [Rendered-Track Cache (Persistent Frozen-Track Audio)](206-rendered-track-cache.md) | 🟡 Medium | Performance |

## Testing & CI

| # | Story | Priority | Area |
|---|-------|----------|------|
| 207 | [Headless Audio Test Harness for Deterministic CI Runs](207-headless-audio-test-harness.md) | 🟠 High | Testing |
| 208 | [JavaFX Snapshot / Visual Regression Testing](208-javafx-snapshot-visual-regression-testing.md) | 🟡 Medium | Testing |
| 209 | [Long-Running Render Test Profile for End-to-End Exports](209-long-running-render-test-profile.md) | 🟡 Medium | Testing |
| 210 | [Plugin DSP Regression Test Framework (Golden-File Audio)](210-plugin-dsp-regression-test-framework.md) | 🟠 High | Testing |

## Audio Processing

| # | Story | Priority | Area |
|---|-------|----------|------|
| 042 | [Audio Time-Stretching and Pitch-Shifting](042-time-stretch-pitch-shift.md) | 🟡 Medium | DSP |
| 036 | [Tempo and Time Signature Changes Along the Timeline](036-tempo-changes.md) | 🟡 Medium | Transport |
| 012 | [Track Grouping and Folder Tracks](012-track-grouping-and-folders.md) | 🟡 Medium | Arrangement |

## Reflection & Annotations

| # | Story | Priority | Area |
|---|-------|----------|------|
| 107 | [Annotation-Driven Processor Parameter Discovery](107-annotation-driven-processor-parameter-discovery.md) | 🟠 High | Plugins |
| 108 | [Reflective Processor Registry and Insert Factory](108-reflective-processor-registry-insert-factory.md) | 🟠 High | Plugins |
| 109 | [Reflection-Based Real-Time Safety Verification](109-reflection-real-time-safety-verification.md) | 🟠 High | Audio Engine |
| 110 | [Reflective Preset Serializer for Processors](110-reflective-preset-serializer-for-processors.md) | 🟡 Medium | Plugins |
| 111 | [Dynamic Plugin Capability Introspection](111-dynamic-plugin-capability-introspection.md) | 🟡 Medium | Plugins |
| 112 | [Annotation-Driven Built-In Plugin Metadata](112-annotation-driven-built-in-plugin-metadata.md) | 🟡 Medium | Plugins |
| 113 | [Reflective Parameter Binding for Automation](113-reflective-parameter-binding-for-automation.md) | 🟡 Medium | Automation |
| 114 | [Reflection-Powered Processor Test Harness](114-reflection-powered-processor-test-harness.md) | 🟡 Medium | Testing |

## Native Libraries / FFM

| # | Story | Priority | Area |
|---|-------|----------|------|
| 115 | [Integrate libogg/libvorbis Native Build](115-integrate-libogg-libvorbis-native-build.md) | 🟡 Medium | FFM |
| 116 | [Portable FFM Bindings for libvorbis](116-portable-ffm-bindings-for-libvorbis.md) | 🟡 Medium | FFM |
| 117 | [OGG Vorbis Import via FFM](117-ogg-vorbis-import-via-ffm.md) | 🟡 Medium | FFM |
| 118 | [Bundle libogg/libvorbis with Distribution](118-bundle-libogg-libvorbis-with-distribution.md) | 🟡 Medium | FFM |
| 119 | [Remove Vendored RoomAcousticPP Source](119-remove-vendored-roomacousticpp-source.md) | 🟡 Medium | FFM |
