cmake_minimum_required(VERSION 3.17)
project(daw-native-libs C CXX)

# ── Output directory ────────────────────────────────────────────────
# All shared libraries are placed under a single directory so the Java
# FFM layer (SymbolLookup.libraryLookup) can find them at runtime with
# -Djava.library.path=<native.libs.dir>.
set(NATIVE_LIBS_OUTPUT_DIR "${CMAKE_BINARY_DIR}/native" CACHE PATH
    "Directory where compiled shared libraries are placed")
file(MAKE_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}")

set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}")

# Multi-config generators (MSVC, Xcode) append a per-config subdirectory
# (e.g. native/Release/) by default. Override per-config output directories
# so all DLLs land directly in native/ regardless of build configuration.
foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    string(TOUPPER "${CFG}" CFG_UPPER)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}")
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}")
endforeach()

# ── Build options ───────────────────────────────────────────────────
option(BUILD_PORTAUDIO      "Build PortAudio shared library"      ON)
option(BUILD_FLUIDSYNTH     "Build FluidSynth shared library"     ON)
option(BUILD_LIBMP3LAME     "Build libmp3lame shared library"     ON)

# ── 1. PortAudio ───────────────────────────────────────────────────
if(BUILD_PORTAUDIO)
    message(STATUS "── Configuring PortAudio ──")
    set(PA_BUILD_SHARED_LIBS ON CACHE BOOL "Build PortAudio as shared library" FORCE)
    # Build only the library — skip examples / tests / install targets
    set(PA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
    set(PA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
    set(PA_DISABLE_INSTALL ON  CACHE BOOL "Disable install/uninstall targets" FORCE)
    add_subdirectory(portaudio EXCLUDE_FROM_ALL)
    # Force output into the common native directory (all configurations)
    set_target_properties(portaudio PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )
    foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        string(TOUPPER "${CFG}" CFG_UPPER)
        set_target_properties(portaudio PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
            RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
        )
    endforeach()
endif()

# ── 2. FluidSynth ─────────────────────────────────────────────────
# If dependencies are missing, the FluidSynth build is skipped (non-fatal).
if(BUILD_FLUIDSYNTH)
    # Check for FluidSynth's required dependency before entering its CMake.
    find_package(PkgConfig QUIET)
    if(PkgConfig_FOUND)
        pkg_check_modules(_GLIB2_CHECK QUIET glib-2.0)
    endif()
    if(NOT _GLIB2_CHECK_FOUND)
        message(WARNING "FluidSynth dependencies not found (GLib2); skipping FluidSynth build.")
    else()
        message(STATUS "── Configuring FluidSynth ──")
        set(BUILD_SHARED_LIBS ON CACHE BOOL "Build FluidSynth as shared library" FORCE)
        set(enable-aufile       OFF CACHE BOOL "" FORCE)
        set(enable-dbus         OFF CACHE BOOL "" FORCE)
        set(enable-ipv6         OFF CACHE BOOL "" FORCE)
        set(enable-jack         OFF CACHE BOOL "" FORCE)
        set(enable-ladspa       OFF CACHE BOOL "" FORCE)
        set(enable-libinstpatch OFF CACHE BOOL "" FORCE)
        set(enable-midishare    OFF CACHE BOOL "" FORCE)
        set(enable-opensles     OFF CACHE BOOL "" FORCE)
        set(enable-oboe         OFF CACHE BOOL "" FORCE)
        set(enable-network      OFF CACHE BOOL "" FORCE)
        set(enable-oss          OFF CACHE BOOL "" FORCE)
        set(enable-dsound       OFF CACHE BOOL "" FORCE)
        set(enable-waveout      OFF CACHE BOOL "" FORCE)
        set(enable-winmidi      OFF CACHE BOOL "" FORCE)
        set(enable-sdl3         OFF CACHE BOOL "" FORCE)
        set(enable-pulseaudio   OFF CACHE BOOL "" FORCE)
        set(enable-pipewire     OFF CACHE BOOL "" FORCE)
        set(enable-readline     OFF CACHE BOOL "" FORCE)
        set(enable-systemd      OFF CACHE BOOL "" FORCE)
        add_subdirectory(fluidsynth-2.5.3/fluidsynth-2.5.3 EXCLUDE_FROM_ALL)
        # Force output into the common native directory (all configurations)
        if(TARGET libfluidsynth)
            set_target_properties(libfluidsynth PROPERTIES
                LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
                RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
            )
            foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
                string(TOUPPER "${CFG}" CFG_UPPER)
                set_target_properties(libfluidsynth PROPERTIES
                    LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
                    RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
                )
            endforeach()
        endif()
    endif()
endif()

# ── 3. libmp3lame ─────────────────────────────────────────────────
if(BUILD_LIBMP3LAME)
    message(STATUS "── Configuring libmp3lame ──")
    add_subdirectory(libmp3lame EXCLUDE_FROM_ALL)
    set_target_properties(mp3lame PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )
    foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        string(TOUPPER "${CFG}" CFG_UPPER)
        set_target_properties(mp3lame PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
            RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
        )
    endforeach()
endif()

# ── Aggregate target ───────────────────────────────────────────────
# Since sub-projects are added with EXCLUDE_FROM_ALL (to suppress
# their install/uninstall targets), we create an explicit aggregate
# target so 'cmake --build .' compiles everything.
set(DAW_NATIVE_TARGETS "")
if(TARGET portaudio AND BUILD_PORTAUDIO)
    list(APPEND DAW_NATIVE_TARGETS portaudio)
endif()
if(TARGET libfluidsynth AND BUILD_FLUIDSYNTH)
    list(APPEND DAW_NATIVE_TARGETS libfluidsynth)
endif()
if(TARGET mp3lame AND BUILD_LIBMP3LAME)
    list(APPEND DAW_NATIVE_TARGETS mp3lame)
endif()
add_custom_target(daw-native-libs ALL DEPENDS ${DAW_NATIVE_TARGETS})

# ── 4. CLAP ───────────────────────────────────────────────────────
# CLAP is a header-only interface library; no shared library to build.
# The headers are available at lib/Clap-1.2.7/include/clap/ for any
# future native plugin host code that needs them.
