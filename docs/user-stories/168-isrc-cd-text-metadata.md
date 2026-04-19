---
title: "ISRC and CD-Text Metadata Entry for Album Assembly and DDP"
labels: ["enhancement", "mastering", "metadata", "album"]
---

# ISRC and CD-Text Metadata Entry for Album Assembly and DDP

## Motivation

Delivering a mastered album requires per-track metadata: ISRC codes (International Standard Recording Codes, 12 characters per track, uniquely identify each recording for royalty tracking) and CD-Text (title, artist, composer, per track). Story 025 covers album sequencing; this story adds the metadata layer so the output can be ingested by a replication plant or uploaded to a distributor. Without this, the user cannot actually deliver a pressed CD or a proper digital release.

## Goals

- Add `AlbumTrackMetadata` record in `com.benesquivelmusic.daw.sdk.mastering.album`: `record AlbumTrackMetadata(String title, String artist, String composer, String isrc, Optional<CdText> cdText, Map<String, String> extra)`.
- `CdText` sub-record: `record CdText(String songwriter, String arranger, String message, String upcEan)`.
- ISRC validator: format is `CC-XXX-YY-NNNNN` (country, registrant, year, designation) per ISO 3901.
- Extend `AlbumAssemblyView` with a per-track metadata pane: title, artist, composer, ISRC (with real-time format validation and auto-hyphen insertion), CD-Text fields, and a free-form `extra` key/value grid for distributor-specific tags.
- Album-level fields: album title, artist, year, genre, UPC/EAN, release date.
- Auto-fill helpers: "Propagate artist to all tracks," "Auto-generate ISRC sequence" (user inputs the first; subsequent tracks increment the designation code).
- Persist metadata via `ProjectSerializer`.
- Export embeds metadata:
  - WAV: `bext` (BWF) chunk for ISRC and `LIST/INFO` for text.
  - FLAC: Vorbis comments.
  - DDP (story 182): CD-Text table as part of the image.
- Tests: ISRC validation rejects malformed codes; propagation works; round-trip through export and re-import preserves metadata.

## Non-Goals

- MusicBrainz / Gracenote CDDB lookup auto-population.
- Cover-art embedding (album-level only for MVP; per-track cover art is a future story).
- Multiple-artist per-track (featured artists) beyond a single `artist` string.
