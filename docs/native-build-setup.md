# Native Library Build Setup

This document describes how to build the C/C++ native libraries that
the DAW links against at runtime via the FFM API (JEP 454). Most of
the libraries (PortAudio, libogg, libvorbis, libmp3lame, FluidSynth)
are vendored under `lib/` and built directly from source by the Maven
build via CMake. One — **asioshim** — depends on a third-party SDK
that we are not allowed to redistribute and therefore needs an extra
manual step before the native build can produce its DLL.

This is a companion to user-stories **115** (libogg/libvorbis native
build) and **224** (build and bundle the `asioshim.dll` native
library).

---

## 1. Toolchain prerequisites

| Platform | Compiler | Build tool |
| --- | --- | --- |
| Windows x64 | Visual Studio 2022 (MSVC, "Desktop development with C++" workload) | CMake ≥ 3.17 |
| Linux x64   | GCC 11+ or Clang 14+                                                   | CMake ≥ 3.17, `pkg-config`, `glib-2.0` (for FluidSynth) |
| macOS       | Xcode Command Line Tools                                                | CMake ≥ 3.17, `pkg-config`, `glib-2.0` (for FluidSynth) |

CMake is invoked transparently from Maven's `generate-resources`
phase (`daw-core/pom.xml`); a top-level `mvn package` produces every
native library in `target/build/native/` and copies them into
`daw-app/target/dist/native/<os>-<arch>/`.

To skip the native build entirely (for a pure-Java-only workflow):

```bash
mvn -DskipNativeBuild=true install
```

---

## 2. Building the asioshim FFM bridge (Windows only)

The `asioshim.dll` library bridges the JVM (via FFM) to the Steinberg
**ASIO SDK** for Windows audio drivers. It is the runtime carrier for
stories 130 / 212 / 213 / 215 / 216 / 218 / 220–223. Its source tree
lives at `daw-core/native/asio/`:

```
daw-core/native/asio/
├── CMakeLists.txt    # asioshim target — gated on ASIO_SDK_DIR
├── asioshim.cpp      # FFM-side exports
├── asioshim.h        # public ABI documentation
└── README.md
```

The Steinberg ASIO SDK licence forbids us from committing or
redistributing the SDK source, so the build is **opt-in**: the CMake
configure step silently skips the asioshim target unless
`ASIO_SDK_DIR` points at a local SDK extract. When the target is
skipped, the Java side gracefully degrades to fallback capability
ranges and the `AsioFormatChangeShim` becomes a no-op.

### 2.1. Obtain the Steinberg ASIO SDK

1. Visit <https://www.steinberg.net/asiosdk> (Steinberg account
   required for the licence acceptance form).
2. Accept the licence and download the SDK ZIP.
3. Extract it to a writable location, e.g. `C:\asiosdk` or
   `~/asiosdk`. The extracted tree must contain:

   ```
   <ASIO_SDK_DIR>/
   ├── common/    # asio.h, asio.cpp, asiosys.h, ...
   ├── host/      # asiodrivers.h/.cpp
   └── host/pc/   # asiolist.h/.cpp
   ```

   The CMake build verifies all three subdirectories exist before
   configuring the target and fails loudly if `ASIO_SDK_DIR` points
   somewhere that is not an SDK extract.

### 2.2. Configure the variable

Either pass `-DASIO_SDK_DIR=...` on the CMake command line or
export the `ASIO_SDK_DIR` environment variable so the Maven-driven
CMake configure step picks it up automatically:

```bash
# Linux / macOS / Windows Git Bash
export ASIO_SDK_DIR="$HOME/asiosdk"

# PowerShell
$env:ASIO_SDK_DIR = "C:\asiosdk"

# cmd.exe
set ASIO_SDK_DIR=C:\asiosdk
```

Then run the standard Maven build:

```bash
mvn package
```

The resulting DLL is written to:

* `target/build/native/asioshim.dll` — primary CMake output.
* `daw-app/target/dist/native/windows-x64/asioshim.dll` — bundled
  distribution copy (via the existing `*.dll` glob in
  `daw-app/pom.xml`'s assembly descriptor).

### 2.3. Disabling the asioshim target explicitly

Even on Windows, you can force the asioshim build off:

```bash
cmake -S lib -B target/build -DBUILD_ASIOSHIM=OFF
```

This is useful when you have an SDK extract on disk but want to
verify the fallback path in the Java layer.

### 2.4. Non-Windows platforms

The Steinberg ASIO SDK is Windows-only. On Linux / macOS the
`daw-core/native/asio/CMakeLists.txt` returns early with a `STATUS`
message and no asioshim target is produced. The Java side detects
the absence and falls back to default capability ranges; tests
gated on `Assumptions.assumeTrue(NativeLibraryDetector.isAvailable("asioshim"))`
are skipped.

---

## 3. Verifying the bundled libraries

Once the native build completes, the DAW's in-app **Help → About →
Native libraries** tab (rendered by `HelpDialog`) lists every
expected library along with its resolved on-disk path. On Windows
builds where `asioshim.dll` was produced, the entry should show as
**available** with a path under `target/dist/native/windows-x64/`.

You can also probe a single library programmatically:

```java
boolean haveAsio = NativeLibraryDetector.isAvailable("asioshim");
```

This is exactly what the Windows-gated tests in
`AsioFormatChangeShimTest` and `NativeLibraryDetectorTest` use to
skip on hosts that have not yet built the shim.

---

## 4. Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `cmake -DASIO_SDK_DIR=... ...` errors with `ASIO_SDK_DIR=... does not look like a Steinberg ASIO SDK tree` | Path points at the parent of the SDK extract, or the extract is missing the `host/` subtree | Re-extract the SDK ZIP and re-point `ASIO_SDK_DIR` at the directory that contains `common/`, `host/`, `host/pc/` |
| `mvn package` finishes but `daw-app/target/dist/native/windows-x64/` lacks `asioshim.dll` | `ASIO_SDK_DIR` was not visible to the CMake configure step | Set `ASIO_SDK_DIR` as an environment variable before running Maven, or pass it explicitly via `mvn -DargLine=...` |
| Help dialog reports asioshim missing on a fresh Windows build | The native build was skipped (`-DskipNativeBuild=true`) or `ASIO_SDK_DIR` was unset | Re-run `mvn package` with `ASIO_SDK_DIR` set |
| `AsioFormatChangeShimTest.shimRegistersAgainstBundledAsioshimDll` skipped on Windows CI | `asioshim.dll` not bundled on the FFM library path for the test JVM | Ensure the CI job runs the full native build (which copies into `target/build/native/`) before `mvn test` |

---

## 5. References

* `daw-core/native/asio/README.md` — exported symbol contract.
* `lib/CMakeLists.txt` — top-level native build orchestration.
* `THIRD_PARTY_NOTICES.md` — third-party licence attributions.
* User stories `115`, `218`, `224` under `docs/user-stories/`.
