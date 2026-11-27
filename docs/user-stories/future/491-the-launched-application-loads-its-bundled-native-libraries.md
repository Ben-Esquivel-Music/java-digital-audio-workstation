---
title: "The Launched Application Loads Its Bundled Native Libraries"
labels: ["bug", "native", "ffm", "packaging", "windows"]
---

# The Launched Application Loads Its Bundled Native Libraries

## Motivation

The native build bundles its libraries with the application: `daw-app/pom.xml` copies every `.dll`, `.so` and `.dylib` of `target/build/native/` into `daw-app/target/dist/native/<os>-<arch>/`, and the jpackage app image carries that folder as `app/native/<os>-<arch>/`. The Java side finds a bundled library only in a directory that `java.library.path` names (`NativeLibraryLoader`, `NativeLibraryDetector`), and no way of starting the application names that folder: not `mvn javafx:run`, not the jlink launcher (`bin/daw`, `bin/daw.bat`), not the jpackage app image. Only daw-core's test JVM names the build's native folder.

So today no bundled native library loads in the launched application: not PortAudio, not libogg / libvorbis / libvorbisenc / libvorbisfile, not libmp3lame. Unless the system has its own copy of one, Help → Help… → System Capabilities lists every one of them as missing, OGG Vorbis import and export and MP3 export are unavailable, and the PortAudio rung is absent from the audio backend ladder. Story 348 named the Windows PortAudio build `portaudio.dll` so the Java side can load it, and made a person whose saved settings name no backend be told, once per session, that audio plays and records through Java Sound because PortAudio is unavailable; in the launched application on Windows that notice appears at every start until this story lands (story 348, studio-engineer ruling R6).

## Goals

- The application as launched — the jlink launcher, the jpackage app image and `mvn javafx:run` — loads the native libraries bundled with it, without the person changing `PATH`, `JDK_JAVA_OPTIONS` or any other JVM option.
- A library the system provides and the bundle does not still loads as it does today.

## Constraints

- Lands after story 349, because it makes PortAudio the default head for every Windows person with no backend saved, and story 349 bounds the PortAudio calls that can freeze the window on a driver that stops responding (story 348, studio-engineer ruling R6). Story 348's fix of the PortAudio stream's suggested latency has landed.
- The bundled build wins over a system copy of the same library, as `NativeLibraryLoader`'s search order already says.
- Every launch path loads the libraries of the build it launches, and none depends on the working directory the application is started from.
- No new third-party dependency. No test assumes a Windows or a non-Windows host.
- Starting the application is an owner-only test: the verification below is the owner's.

## Acceptance Criteria

1. On Windows, in the jpackage app image, Help → Help… → System Capabilities lists libportaudio, libogg, libvorbis, libvorbisenc, libvorbisfile and libmp3lame as available, each with a path under the app image's `native` folder.
2. The same holds for the jlink launcher and for `mvn javafx:run`, with paths under the folder that launch path bundles or builds.
3. With no backend saved, starting the application on Windows shows no "PortAudio is unavailable" notice, and Audio Settings offers PortAudio.
4. A test in the gate fails when a change to any launch path would stop the launched application from loading the bundled libraries, rather than the libraries silently going missing again.
5. On Linux, the jlink launcher loads the bundled libraries the same way (checked on the WSL Ubuntu mirror or the CI runner).

## Non-Goals

- PortAudio's lifecycle and its untimed calls (story 349).
- PortAudio's choice of default host API on Windows (MME), and a host-API preference.
- FluidSynth, which the Windows build skips without GLib2.
- A macOS launch path; there is no macOS runner.

## Technical Notes

- Code locators: `daw-app/pom.xml` (the antrun copy into `dist/native/<os>-<arch>`; the javafx plugin's `<options>`, which also feed `JLINK_VM_OPTIONS` of `bin/daw` and `bin/daw.bat`; the jpackage `--java-options` and `--input`), the root `pom.xml` (`javafx-maven-plugin`), the built `app/DigitalAudioWorkstation.cfg`, `NativeLibraryLoader` (`loadLibrary`, `findFirstLoadableInLibraryPath`), `NativeLibraryDetector` (`detectAll`), `HelpDialog` (System Capabilities tab), `daw-core/pom.xml` (the surefire `argLine` that names `target/build/native` for daw-core's tests only).
- Cross-refs: story 348 (the PortAudio name and the notice), story 349 (lands first), stories 118, 224 and 256 (bundling).

## Status

- Deferred
