---
title: "Native Library Availability Detection and Export Format Validation"
labels: ["enhancement", "export", "usability", "file-io"]
---

# Native Library Availability Detection and Export Format Validation

## Motivation

The DAW supports exporting to MP3 (via `libmp3lame`), AAC (via `libfdk-aac`), and OGG Vorbis (via `libvorbisenc`/`libogg`) through FFM bindings. When these native libraries are not installed, the exporters throw `UnsupportedOperationException` at export time — after the user has already configured their export settings and clicked "Export". Additionally, the `OggVorbisExporter` hardcodes struct size constants for x86_64 Linux, meaning it will silently produce corrupt files or crash on Windows, macOS, or ARM platforms.

WAV and FLAC exports are pure-Java and always available. Users should be able to clearly see which export formats are available on their system **before** attempting to export, and unavailable formats should be gracefully disabled with explanatory messages rather than failing with a stack trace.

## Goals

- Create a `NativeLibraryDetector` utility in `daw-core` that probes for the presence of each required native library (`libmp3lame`, `libfdk-aac`, `libfluidsynth`, `libvorbisenc`, `libogg`, `libportaudio`) at application startup
- Store the detection results as an immutable `NativeLibraryStatus` record listing each library's name, required-for description, availability status, and detected path (if found)
- In the export dialog, disable format options (MP3, AAC, OGG) when their required native library is not detected — show a tooltip explaining what library is needed and how to install it
- Display a "System Capabilities" panel in Settings or Help that shows all native library statuses in a table: library name, status (Available/Missing), required for, installation instructions per platform
- Fix the `OggVorbisExporter` platform compatibility issue: detect the current OS and architecture, and either use platform-appropriate struct offsets or refuse to export with a clear error message on unsupported platforms
- Log all native library detection results at application startup at INFO level for diagnostic purposes
- Add tests verifying: (1) `NativeLibraryDetector` correctly reports availability, (2) export dialog disables unavailable formats, (3) `OggVorbisExporter` rejects unsupported platforms gracefully

## Non-Goals

- Bundling native libraries with the application
- Automatic native library installation
- Pure-Java implementations of MP3, AAC, or OGG encoding (significant scope)
- Detecting CLAP plugin libraries (handled by the plugin system)
