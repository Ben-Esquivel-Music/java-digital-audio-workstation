---
title: "DAWproject Format Import/Export for Session Interoperability"
labels: ["enhancement", "session", "interoperability", "file-io"]
---

# DAWproject Format Import/Export for Session Interoperability

## Motivation

The `DawProjectSessionExporter`, `DawProjectSessionImporter`, `DawProjectXmlSerializer`, and `DawProjectXmlParser` classes exist in the core module for DAWproject format support. DAWproject is an emerging open standard (created by Bitwig and PreSonus) for exchanging sessions between different DAWs. Exposing this capability in the UI allows users to import sessions from other DAWs (Bitwig, Studio One) and export sessions for use in those DAWs. This is a differentiating feature — very few DAWs support session interchange natively.

## Goals

- Add File > Import Session and File > Export Session menu items
- Import DAWproject (.dawproject) files, creating tracks, clips, and mixer settings
- Export the current project to DAWproject format
- Show a summary dialog after import listing what was imported and any unsupported features
- Handle gracefully any DAWproject elements not supported by this DAW (warn but don't fail)
- Preserve track names, audio file references, volume/pan settings, and basic plugin references

## Non-Goals

- Full fidelity round-trip (some DAW-specific features will not transfer)
- Importing/exporting Ableton Live, Pro Tools, or Logic Pro native formats
- Automatic plugin substitution for incompatible plugins
