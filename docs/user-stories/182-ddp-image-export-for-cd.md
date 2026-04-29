---
title: "DDP Image Export for CD Replication (Red Book Compliant)"
labels: ["enhancement", "export", "mastering", "album"]
---

# DDP Image Export for CD Replication (Red Book Compliant)

## Motivation

CD replication plants accept DDP (Disc Description Protocol) image sets as the industry-standard master format. A DDP image consists of a set of files — `DDPID`, `DDPMS` (main), `IMAGE.DAT`, `PQDESCR`, `CDTEXT.bin` — that together describe the full disc: audio, track boundaries, pre-gaps, ISRC codes, UPC/EAN, and CD-Text. Every mastering DAW exports DDP: SADIE, Sonoris DDP Creator, Wavelab, Sequoia. Without it, a release intended for physical CD has no path through this DAW.

Story 168 provides ISRC + CD-Text metadata; story 025 provides album assembly. This story is the serializer.

## Goals

- Add `DdpImageWriter` in `com.benesquivelmusic.daw.core.export.ddp` implementing DDP 2.00 per the specification.
- Produce the six canonical files into a user-chosen folder: `DDPID`, `DDPMS`, `IMAGE.DAT` (PCM 16-bit 44.1 kHz stereo, regardless of session format — transcoded via story 167 dither + story 126 SRC), `PQDESCR`, `CDTEXT.bin`, `MD5SUM`.
- Enforce Red Book: max 99 tracks, track lengths ≥ 4 seconds (warn ≥ 2 seconds per spec), 2-second default pre-gap, 44.1 kHz 16-bit, max 80:00 total duration.
- Consume `AlbumProject` (story 025) for track boundaries and `AlbumTrackMetadata` (story 168) for ISRCs + CD-Text.
- `DdpExportDialog` with album / DDP summary: track count, total runtime, per-track pre-gap override, and a compliance checklist (red-light items block export).
- MD5 checksum generation for the image so replication plants can verify integrity.
- Validate the output by re-reading the DDP image post-export and confirming round-trip of all metadata + audio frame count.
- Tests: a known 3-track album produces a DDP image whose re-read metadata matches input; intentional Red-Book violations (1-second track) are rejected with a clear error.

## Non-Goals

- DDP 1.02 (legacy, not supported).
- CUE+BIN export (a separate story if needed; DDP is the professional standard).
- DVD-Audio or SACD image export.

## WON't DO