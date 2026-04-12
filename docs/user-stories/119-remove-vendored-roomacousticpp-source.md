---
title: "Remove Vendored RoomAcoustiCpp Source and Finalize daw-acoustics Java Port"
labels: ["enhancement", "build", "spatial-audio", "dsp", "licensing", "cleanup"]
---

# Remove Vendored RoomAcoustiCpp Source and Finalize daw-acoustics Java Port

## Motivation

`lib/RoomAcoustiCpp-1.0.1/` contains the full upstream C++ source tree of the RoomAcoustiCpp room acoustics library (headers, implementation files, Visual Studio and Android projects, Unity integration, and the `moodycamel` lock-free queue). Unlike every other directory under `lib/` — PortAudio, FluidSynth, libmp3lame, CLAP, libogg, libvorbis — this one is **never compiled and never linked into the DAW at runtime**. A `grep` of `lib/CMakeLists.txt` confirms it: there is no `add_subdirectory(RoomAcoustiCpp-1.0.1)` block, no build target, and no FFM binding in `daw-core` or `daw-acoustics` that loads a `RoomAcoustiCpp` shared library. The directory exists solely as **reference source material** for the `daw-acoustics` Maven module, which is a pure-Java port of the same algorithms (the `pom.xml` description literally says "Room acoustics simulation engine — Java port of RoomAcoustiCpp", and more than 30 ported classes carry "Ported from RoomAcoustiCpp {@code ClassName}" Javadoc comments).

Keeping the C++ source in the repository has three costs:

1. **Repository bloat.** The tree contains 216 C/C++ files plus `.vcxproj` / `.sln` / `docs/` / `UnitTestData/` / `3dti_AudioToolkit` submodule directories. None of it ships; all of it is cloned by every contributor and every CI run.
2. **Licensing surface.** RoomAcoustiCpp is GPLv3. That is license-compatible with the DAW itself (also GPLv3), but `THIRD_PARTY_LICENSES` entry 5 points at `lib/RoomAcoustiCpp-1.0.1/RoomAcoustiCpp-1.0.1/LICENSE` — a path the attribution file will still reference after the directory is deleted, producing a broken pointer. GPLv3's derivative-work attribution obligation still applies to the Java port regardless of whether the C++ source stays in the tree, so the attribution itself is not going away — it just needs to be restated in terms of the port, not in terms of a directory that no longer exists.
3. **Maintenance confusion.** The presence of a full C++ source tree inside a Java project invites contributors to assume there is a JNI/FFM bridge they haven't found yet, or that the C++ code is the production path and Java is scaffolding. Both are false. Removing the source makes the intent of `daw-acoustics` unambiguous.

The blocker for this deletion has always been: *is the Java port actually complete enough to stand alone?* Today the port covers `common/`, `dsp/`, `spatialiser/`, `spatialiser/diffraction/`, and `simulator/` — 61 files across the mirror-structure packages the C++ has. That is substantial but not provably exhaustive. Before deleting the reference material, this story performs a line-by-line audit of what the C++ source contains versus what the Java port implements, ports any missing pieces (or explicitly documents them as non-goals — e.g., the Unity bridge and Windows VS projects are never going to have Java equivalents), establishes behavioral parity through comparison tests, verifies the integration work from user story 105 (`AcousticReverbProcessor`, `BinauralMonitoringProcessor`) is wired through the mixer, and only then removes `lib/RoomAcoustiCpp-1.0.1/` from the repository.

## Goals

- **Inventory the C++ source tree.** Produce a written audit in `docs/research/` listing every header under `lib/RoomAcoustiCpp-1.0.1/RoomAcoustiCpp-1.0.1/RoomAcoustiCpp/include/` and classifying each as:
  - **Ported** — a corresponding Java class exists in `daw-acoustics/src/main/java/com/benesquivelmusic/daw/acoustics/` with a matching Javadoc reference
  - **Intentionally omitted** — not applicable to the Java DAW (e.g., `Unity/*`, `moodycamel/*` lock-free queue, `Android/`, `Windows/` VS project files, `AudioThreadPool` — replaced by `java.util.concurrent`, `SpinLock` — replaced by `java.util.concurrent.locks`, `Timer`/`ScopedTimer`/`RACProfiler` — replaced by Micrometer or JFR if needed)
  - **Missing** — must be ported before deletion (likely candidates: `ImageSourceManager.h`, `Spatialiser/Globals.h`, `Spatialiser/Types.h`, `Common/ReleasePool.h`, any algorithm not yet represented in the Java tree)
- **Port any missing algorithmic pieces** identified in the audit. For each "Missing" entry, create the corresponding Java class under the appropriate package, match the C++ public API surface (adjusted for Java idioms — `std::vector` → `ArrayList` or primitive arrays, `std::shared_ptr` → plain references, raw pointers → null-safe types), and preserve the "Ported from RoomAcoustiCpp {@code ClassName}" Javadoc convention already used throughout `daw-acoustics/`
- **Establish behavioral parity tests.** Add a new test source set under `daw-acoustics/src/test/java/.../parity/` that exercises the Java port against known-good numerical fixtures captured from the C++ reference implementation before deletion. At minimum:
  - FDN reverb: feed an impulse through `FDN` at a fixed room geometry and seed, compare the first 8192 output samples against a fixture file generated from the C++ `FDN::Process` with matching inputs, allowing floating-point tolerance
  - Graphic EQ: for each band and gain combination in a small grid, verify the magnitude response at the band center frequency matches the C++ implementation within 0.01 dB
  - Image source reflections: for a shoebox room with a single source and receiver, verify the set of first- and second-order image source positions computed by `ImageSource` matches the C++ output exactly (deterministic geometry, no floating-point drift)
  - Diffraction models (Attenuate, LPF, UTD, BTM): for a fixed edge geometry, verify the attenuation / filter coefficients match the C++ output within tolerance
  - Air absorption: for a fixed distance / temperature / humidity, verify the frequency-dependent attenuation matches the C++ output
  - These fixtures are captured **once** from the C++ build before deletion (commit them as `.bin` or `.csv` files under `daw-acoustics/src/test/resources/parity/`), so the Java port can be re-verified indefinitely after the C++ source is gone
- **Confirm the daw-core integration from story 105 is live.** Before deletion, verify `AcousticReverbProcessor`, `BinauralMonitoringProcessor`, and any other adapters from story 105 are wired into the mixer insert chain and reachable from the UI. If story 105 is still open, this story depends on it — do not delete the reference source until the Java port is not only complete but *in use* on the audio path
- **Audit the daw-acoustics module for any references to `lib/RoomAcoustiCpp-1.0.1/` paths.** Search for hardcoded file paths, Javadoc `{@link}` targets into the C++ tree, build-system references, or CI config that assumes the directory exists. Fix or remove any hits before the delete
- **Rewrite the `THIRD_PARTY_LICENSES` RoomAcoustiCpp entry** so it reflects the post-deletion state accurately:
  - Change the `Files :` line from `lib/RoomAcoustiCpp-1.0.1/` to `daw-acoustics/src/main/java/com/benesquivelmusic/daw/acoustics/ (derivative work; Java port of RoomAcoustiCpp)`
  - Embed the full GPLv3 license text inline in `THIRD_PARTY_LICENSES` (or point to the project's own `LICENSE` file, since they are the same license) rather than pointing to a file that no longer exists
  - Preserve the original upstream URL, version (`1.0.1`), original author attribution, and the clear statement that `daw-acoustics` is a derivative work — this satisfies the GPLv3 §5 attribution requirement for derivative works
  - Add the same attribution to the `daw-acoustics/README.md` or `NOTICE` file if the module has one, so the port's origin is discoverable from inside the module itself and not only from the project root
- **Update `daw-acoustics/pom.xml` description** — the current text "Java port of RoomAcoustiCpp" stays (it's accurate and satisfies GPL attribution), but confirm it is correct after the audit
- **Delete `lib/RoomAcoustiCpp-1.0.1/`** in a single commit, after the audit + ports + parity tests + attribution rewrite have all landed. The commit should reference this story, cite the parity test results, and quote the updated `THIRD_PARTY_LICENSES` entry so the attribution trail is visible in git history
- **Run the full build and test suite** after deletion to confirm nothing — CMake, Maven, JavaDoc generation, IDE project files, CI scripts — was silently depending on the C++ tree
- **Update `lib/` documentation / README** (if present) to note that all remaining entries under `lib/` are compiled native libraries and that `daw-acoustics` is where room-acoustics source lives now

## Non-Goals

- Removing the attribution to RoomAcoustiCpp or the port-of-origin Javadoc comments — GPLv3 requires that attribution be preserved in the derivative work, and the project has no interest in erasing the provenance anyway
- Relicensing `daw-acoustics` under a non-GPL license — as a derivative of GPLv3 code, the Java port is GPLv3, matching the host project
- Porting the RoomAcoustiCpp Unity plugin, Android VS project, or `moodycamel` lock-free queue — these are not applicable to a Java DAW and are explicitly out of scope
- Preserving bit-exact output between the C++ and Java implementations indefinitely — parity is verified at deletion time via fixture tests; subsequent algorithmic improvements on the Java side (e.g. switching to a different FDN feedback matrix, tightening a filter) may cause the fixtures to be regenerated, which is acceptable as long as the change is intentional and reviewed
- Deleting `docs/research/` material that references RoomAcoustiCpp — research and design documentation can still cite the original source
- Adding a runtime JNI/FFM bridge to the C++ library as an alternative to the port — the whole point of `daw-acoustics` is to be pure-Java; bringing back a native dependency would defeat the purpose
- Back-porting this cleanup to any release branches where the C++ source may still be referenced by older build scripts
