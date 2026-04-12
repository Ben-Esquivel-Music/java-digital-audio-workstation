---
title: "Replace Ogg Vorbis Import SPI with Native FFM Decoder"
labels: ["enhancement", "file-io", "native", "core"]
---

# Replace Ogg Vorbis Import SPI with Native FFM Decoder

## Motivation

`OggVorbisFileReader` (user story 068) currently decodes `.ogg` files by delegating to `AudioSystemDecoder.decode(path, "OGG Vorbis")`, which in turn goes through `javax.sound.sampled.AudioSystem`. That framework only knows how to read Ogg Vorbis if a third-party SPI implementation is present on the classpath — historically `com.googlecode.soundlibs:vorbisspi` or an equivalent. When no SPI is installed, `AudioSystem.getAudioInputStream()` throws `UnsupportedAudioFileException` and the import silently fails even though the file is a perfectly valid Ogg Vorbis stream. This also puts the import and export paths on **two completely different binding mechanisms**: export uses FFM to call libvorbisenc directly (stories 069 and 116), while import uses an optional pure-Java SPI that may or may not be on the classpath. The two paths have different failure modes, different quality, different license exposure, and different test coverage.

With libogg and libvorbis now vendored (`lib/ogg-1.3.6/`, `lib/vorbis-1.3.7/`) and built into the `native/` output directory by user story 115, the DAW can decode Ogg Vorbis through the same FFM layer it already uses for encoding. libvorbis ships a dedicated **decoder convenience library**, `libvorbisfile`, which exposes `ov_open`, `ov_info`, `ov_read_float`, and `ov_clear` — a high-level API that handles Ogg framing, packet reassembly, and PCM output in one call sequence. Adding vorbisfile to the CMake build and writing a small FFM binding removes the SPI dependency entirely, unifies import and export on one native stack, and fixes the silent-failure-when-SPI-missing problem at the same time.

## Goals

- Add libvorbisfile to the native build as part of user story 115 (it is part of the same `vorbis-1.3.7` source tree — enable its CMake target alongside `vorbis` and `vorbisenc`) and ensure it lands in `${NATIVE_LIBS_OUTPUT_DIR}` with the platform-correct name
- Create a new `LibVorbisFile` FFM binding inside `daw-core` that loads vorbisfile and links downcall handles for `ov_fopen`, `ov_info`, `ov_pcm_total`, `ov_read_float`, and `ov_clear`, reusing the OS-aware library-name resolution from user story 116 so the same logic handles Linux / macOS / Windows and prefers bundled libraries over system installs
- Rewrite `OggVorbisFileReader.read(Path)` to use the FFM binding instead of `AudioSystemDecoder.decode()`: open the file via `ov_fopen`, query channels and sample rate via `ov_info`, allocate a `float[channels][totalSamples]` target, loop `ov_read_float` to fill it, and call `ov_clear` in a `finally` block
- Produce the same `AudioReadResult` structure the existing WAV/FLAC readers produce, so `AudioFileImporter` (story 068) does not need to change
- Handle the `ov_read_float` return conventions correctly: negative return codes are errors, zero means end-of-stream, positive values indicate the number of frames decoded into the float buffer — the buffer pointer layout is `float**` (array of channel pointers), each channel pointer points to a per-channel float array, matching the pattern already used by `vorbis_analysis_buffer` in `OggVorbisExporter.java:157-174`
- Throw a clear `IOException` with the libvorbisfile error code translated to text (`OV_EREAD`, `OV_ENOTVORBIS`, `OV_EVERSION`, `OV_EBADHEADER`, `OV_EFAULT`) when decode fails, so users see "File is not a valid Ogg Vorbis stream" instead of a generic stack trace
- Remove the `vorbisspi` / `jorbis` / equivalent classpath dependency from `daw-core/pom.xml` if it was added for this purpose — after this change the OGG import path has zero Java-level SPI dependencies
- Remove the "OGG Vorbis" dispatch in `AudioSystemDecoder` if it exists solely to support this reader (confirm no other caller depends on it before deleting)
- Update `OggVorbisFileReaderTest` to include a round-trip test: generate a short deterministic buffer, write it through `OggVorbisExporter`, read it back through the new FFM-based reader, and verify channel count, sample rate, and length match the original (allowing for Vorbis's natural decoder warm-up discard)
- Gate all tests on native library availability via `Assumptions.assumeTrue(NativeLibraryDetector.isAvailable("libvorbisfile"))` so CI environments without the bundled native libs skip rather than fail — but the production code path should always find the bundled libraries after story 115

## Non-Goals

- Streaming / chunked decode for files larger than memory — the existing reader contract loads the whole file into a `float[][]`, which is fine for the DAW's use case (imported clips are already memory-resident)
- Seeking, looping, or partial decode by sample range — the reader decodes start-to-end
- Decoding `.oga`, `.ogv`, Opus, or FLAC-in-Ogg — only Vorbis-in-Ogg is in scope
- Preserving Vorbis comment metadata on import (can be added later if the rest of the import path grows a metadata field)
- Replacing any FLAC / MP3 / AIFF reader paths — those stay as-is
