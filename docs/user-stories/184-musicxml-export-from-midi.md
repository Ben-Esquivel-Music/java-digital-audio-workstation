---
title: "MusicXML Export from MIDI Clips for Notation Engraving"
labels: ["enhancement", "midi", "export", "notation"]
---

# MusicXML Export from MIDI Clips for Notation Engraving

## Motivation

Composers frequently need a score from their MIDI: send a part to a session player, archive a composition in notation, import into Sibelius / Finale / Dorico for engraving. MusicXML is the universal interchange format (supported by every notation program). The current DAW has no path from MIDI clips to a score; users must route through a separate tool. Every DAW supports this: Logic, Cubase, Studio One, Dorico-integrated-with-anything.

## Goals

- Add `MusicXmlExporter` in `com.benesquivelmusic.daw.core.midi.export` consuming `MidiClip` notes (with tempo, key, time-signature metadata) and producing a MusicXML 4.0 document.
- Quantize and beat-group MIDI notes to notation duration values (whole/half/quarter/…/sixteenth/thirty-second) with ties across bar lines; configurable quantization resolution.
- Per-track exports as a `<part>`; multi-track export produces a multi-part score.
- Detect key signature from the session's declared key (or auto-estimate from pitch class distribution when unset).
- Time signature changes over the session (story 036) produce `<time>` elements at the corresponding measures.
- Include dynamics and articulations from MIDI CC data where interpretable (velocity → dynamics, short notes → staccato, etc.).
- `MusicXmlExportDialog`: per-track selection, quantization resolution, clef defaults (treble / bass / auto by range), staff-per-channel or staff-per-track.
- Validation: emit valid MusicXML per the schema; attach a minimal XSD subset for the exporter to self-validate on write.
- Tests: a C-major scale in quarter notes round-trips through export and a third-party MusicXML parser (bundled test dependency) produces the same note sequence; a 7/8 time signature is preserved.

## Non-Goals

- Lyric alignment beyond text-event extraction.
- Guitar tab output.
- Advanced engraving hints (slurs, phrasing) — basic notation only.
