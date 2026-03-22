# Enhancement: DAWproject Format Support for Session Interoperability

## Summary

Implement import and export support for the [DAWproject](https://github.com/bitwig/dawproject) open session interchange format. DAWproject enables users to transfer complete sessions (tracks, clips, automation, effects, mixer settings) between different DAWs — a capability currently unavailable in most commercial DAWs.

## Motivation

DAW lock-in is a major pain point for music producers who collaborate across different platforms or want to switch DAWs. The DAWproject format is an emerging open standard (supported by Bitwig and OpenDAW) designed to solve this by providing a common session representation. Supporting DAWproject would differentiate this DAW as interoperable and open, aligning with the project's open-source values and enabling seamless collaboration with users of other DAWs that support the format.

## Research Sources

- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Pattern #4: "DAWproject: An emerging open standard for DAW session interchange (supported by OpenDAW and Bitwig)"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — OpenDAW Features: "DAWproject schema support for import/export interoperability"
- [Open Source DAW Tools](../research/open-source-daw-tools.md) — Recommendation #3: "DAWproject interoperability: Support the DAWproject format for session exchange"
- [Research README](../research/README.md) — Architecture: "DAWproject format support enables session interoperability with other DAWs"

## Sub-Tasks

- [ ] Research and document the DAWproject XML schema (tracks, clips, automation, effects, mixer, metadata)
- [ ] Design `SessionImporter` and `SessionExporter` interfaces in `daw-sdk`
- [ ] Implement DAWproject XML parser for importing sessions
- [ ] Map DAWproject track types to internal `Track` / `TrackType` representations
- [ ] Map DAWproject audio clip references to internal `AudioClip` instances
- [ ] Map DAWproject automation curves to internal automation data structures
- [ ] Map DAWproject effect chains to internal `EffectsChain` (with best-effort plugin matching)
- [ ] Map DAWproject mixer settings (volume, pan, sends) to internal `MixerChannel` / `Mixer`
- [ ] Implement DAWproject XML serializer for exporting sessions
- [ ] Handle audio file references (relative paths, embedded audio, media pool)
- [ ] Implement graceful degradation for unsupported features during import (log warnings, preserve data)
- [ ] Add unit tests for DAWproject XML parsing with sample project files
- [ ] Add unit tests for round-trip export → import fidelity
- [ ] Add integration test importing a DAWproject file created by Bitwig or OpenDAW
- [ ] Document supported DAWproject features and known limitations

## Affected Modules

- `daw-sdk` (new `session/SessionImporter` and `session/SessionExporter` interfaces)
- `daw-core` (new `session/dawproject/` package)
- `daw-core` (`project/DawProject` — integrate import/export)
- `daw-app` (import/export menu actions)

## Priority

**Near-Term** — Differentiation feature, moderate implementation effort
