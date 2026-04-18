# `lib/` — Vendored Native Libraries

This directory contains the source trees of the third-party **native**
(C / C++) libraries that the DAW links against at runtime. Each library
is built into a shared library by `lib/CMakeLists.txt` and loaded from
Java via the Foreign Function & Memory API (JEP 454).

| Directory             | Library          | License      | Built by CMake |
|-----------------------|------------------|--------------|----------------|
| `Clap-1.2.7/`         | CLAP             | MIT          | header-only    |
| `fluidsynth-2.5.3/`   | FluidSynth       | LGPL-2.1     | yes            |
| `libmp3lame/`         | LAME             | LGPL-2       | yes            |
| `ogg-1.3.6/`          | libogg           | BSD-3-Clause | yes            |
| `portaudio/`          | PortAudio        | MIT          | yes            |
| `vorbis-1.3.7/`       | libvorbis        | BSD-3-Clause | yes            |

> **Every entry under `lib/` is a compiled native library.** If you are
> looking for the room-acoustics implementation, it lives in the
> [`daw-acoustics`](../daw-acoustics/) Maven module as a pure-Java port
> of [RoomAcoustiCpp](https://github.com/audiolabs/RoomAcoustiCpp). The
> vendored C++ source tree (`lib/RoomAcoustiCpp-1.0.1/`) was removed
> once the Java port was complete; see `daw-acoustics/NOTICE` for
> attribution and `THIRD_PARTY_LICENSES` for the GPLv3 derivative-work
> notice.

License metadata for each library is auto-discovered by
[`generate-third-party-notices.sh`](../generate-third-party-notices.sh)
and aggregated into `THIRD_PARTY_NOTICES.md` at the repository root.
