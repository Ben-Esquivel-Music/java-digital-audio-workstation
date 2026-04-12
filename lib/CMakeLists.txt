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
option(BUILD_LIBOGG         "Build libogg shared library"         ON)
option(BUILD_LIBVORBIS      "Build libvorbis shared library"      ON)

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

# ── 4. libogg ──────────────────────────────────────────────────────
# libogg is the Ogg bitstream container library (BSD 3-clause).
# It must be configured before libvorbis because libvorbis depends on
# libogg at both compile time (headers) and link time (shared library).
# Added with EXCLUDE_FROM_ALL so ogg targets are not part of the default
# ALL build (they are pulled in via the daw-native-libs aggregate target
# instead).  Install/docs/pkg-config targets are suppressed by the
# INSTALL_* cache variables set below.
if(BUILD_LIBOGG)
    message(STATUS "── Configuring libogg ──")
    set(BUILD_SHARED_LIBS ON CACHE BOOL "Build libogg as shared library" FORCE)
    # Suppress install targets and docs
    set(INSTALL_DOCS                    OFF CACHE BOOL "" FORCE)
    set(INSTALL_PKG_CONFIG_MODULE       OFF CACHE BOOL "" FORCE)
    set(INSTALL_CMAKE_PACKAGE_MODULE    OFF CACHE BOOL "" FORCE)
    add_subdirectory(ogg-1.3.6 EXCLUDE_FROM_ALL)
    # Force output into the common native directory (all configurations)
    set_target_properties(ogg PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )
    foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        string(TOUPPER "${CFG}" CFG_UPPER)
        set_target_properties(ogg PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
            RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
        )
    endforeach()
endif()

# ── 5. libvorbis / libvorbisenc / libvorbisfile ────────────────────
# Dependency order: libogg → libvorbis → libvorbisenc / libvorbisfile.
# libvorbis's CMake calls find_package(Ogg REQUIRED), which invokes its
# bundled cmake/FindOgg.cmake.  We pre-populate the OGG_INCLUDE_DIR and
# OGG_LIBRARY cache variables so FindOgg succeeds against the in-tree
# libogg target (Ogg::ogg alias) built in the previous step.
if(BUILD_LIBVORBIS)
    if(NOT BUILD_LIBOGG OR NOT TARGET ogg)
        message(FATAL_ERROR "BUILD_LIBVORBIS requires BUILD_LIBOGG=ON (libogg must be built first)")
    endif()
    message(STATUS "── Configuring libvorbis / libvorbisenc / libvorbisfile ──")
    set(BUILD_SHARED_LIBS ON CACHE BOOL "Build libvorbis as shared library" FORCE)
    # Force the framework build off — the framework path in vorbis's
    # CMakeLists.txt links via ${OGG_LIBRARIES} (a plain string) instead
    # of the Ogg::ogg target, which would fail with our in-tree target.
    if(APPLE)
        set(BUILD_FRAMEWORK OFF CACHE BOOL "Disable macOS framework build for vorbis" FORCE)
    endif()
    # Pre-populate cache variables so libvorbis's FindOgg.cmake finds the
    # in-tree libogg instead of searching the system.  The Ogg::ogg alias
    # target already exists from the ogg-1.3.6 subdirectory above, so the
    # actual linking uses the target — these variables only satisfy the
    # find_package_handle_standard_args check.
    set(OGG_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ogg-1.3.6/include" CACHE PATH
        "Path to libogg headers (in-tree)" FORCE)
    set(OGG_LIBRARY "ogg" CACHE FILEPATH
        "libogg library (in-tree target name; satisfies FindOgg.cmake's check)" FORCE)
    # Skip optional sub-projects that would slow or fail the build
    set(INSTALL_CMAKE_PACKAGE_MODULE OFF CACHE BOOL "" FORCE)
    add_subdirectory(vorbis-1.3.7 EXCLUDE_FROM_ALL)
    # The upstream vorbis CMakeLists.txt sets SOVERSION to the full
    # version string (e.g. "0.4.9") instead of just the soversion number,
    # which prevents CMake from creating the expected SOVERSION symlinks
    # (libvorbis.so.0, libvorbisenc.so.2).  Override VERSION and SOVERSION
    # to match the libtool convention used by autotools builds:
    #   vorbis:    V_LIB_CURRENT=4, V_LIB_AGE=4, V_LIB_REVISION=9
    #             → SOVERSION=0 (4-4), VERSION=0.4.9
    #   vorbisenc: VE_LIB_CURRENT=2, VE_LIB_AGE=0, VE_LIB_REVISION=12
    #             → SOVERSION=2 (2-0), VERSION=2.0.12
    if(TARGET vorbis)
        set_target_properties(vorbis PROPERTIES SOVERSION 0 VERSION 0.4.9)
    endif()
    if(TARGET vorbisenc)
        set_target_properties(vorbisenc PROPERTIES SOVERSION 2 VERSION 2.0.12)
    endif()
    #   vorbisfile: VF_LIB_CURRENT=6, VF_LIB_AGE=3, VF_LIB_REVISION=8
    #             → SOVERSION=3 (6-3), VERSION=3.3.8
    if(TARGET vorbisfile)
        set_target_properties(vorbisfile PROPERTIES SOVERSION 3 VERSION 3.3.8)
    endif()
    # Force vorbis, vorbisenc, and vorbisfile output into the common native directory
    foreach(_target IN ITEMS vorbis vorbisenc vorbisfile)
        if(TARGET ${_target})
            set_target_properties(${_target} PROPERTIES
                LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
                RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
            )
            foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
                string(TOUPPER "${CFG}" CFG_UPPER)
                set_target_properties(${_target} PROPERTIES
                    LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
                    RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
                )
            endforeach()
        endif()
    endforeach()
endif()

# ── Aggregate target ───────────────────────────────────────────────
# Since sub-projects are added with EXCLUDE_FROM_ALL (so their targets
# are not part of the default ALL build), we create an explicit aggregate
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
if(TARGET ogg AND BUILD_LIBOGG)
    list(APPEND DAW_NATIVE_TARGETS ogg)
endif()
if(TARGET vorbis AND BUILD_LIBVORBIS)
    list(APPEND DAW_NATIVE_TARGETS vorbis)
endif()
if(TARGET vorbisenc AND BUILD_LIBVORBIS)
    list(APPEND DAW_NATIVE_TARGETS vorbisenc)
endif()
if(TARGET vorbisfile AND BUILD_LIBVORBIS)
    list(APPEND DAW_NATIVE_TARGETS vorbisfile)
endif()
add_custom_target(daw-native-libs ALL DEPENDS ${DAW_NATIVE_TARGETS})

# ── Smoke test: verify libogg + libvorbis are loadable ─────────────
if(BUILD_LIBOGG AND BUILD_LIBVORBIS AND TARGET vorbis)
    enable_testing()
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/test_vorbis_smoke.c" [[
#include <stdio.h>
#include <vorbis/codec.h>

int main(void) {
    const char *ver = vorbis_version_string();
    if (ver == NULL) {
        fprintf(stderr, "vorbis_version_string() returned NULL\n");
        return 1;
    }
    printf("libvorbis version: %s\n", ver);
    return 0;
}
]])
    add_executable(test_vorbis_smoke EXCLUDE_FROM_ALL
        "${CMAKE_CURRENT_BINARY_DIR}/test_vorbis_smoke.c")
    target_link_libraries(test_vorbis_smoke PRIVATE vorbis)
    # Place the test executable next to the shared libraries; the test
    # environment below adds LD_LIBRARY_PATH / DYLD_LIBRARY_PATH so the
    # dynamic linker can resolve the in-tree shared libraries at runtime.
    set_target_properties(test_vorbis_smoke PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )
    foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        string(TOUPPER "${CFG}" CFG_UPPER)
        set_target_properties(test_vorbis_smoke PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
        )
    endforeach()
    add_test(NAME vorbis_smoke_test
        COMMAND test_vorbis_smoke
        WORKING_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )
    # Ensure the shared libraries are on the dynamic linker search path
    set_tests_properties(vorbis_smoke_test PROPERTIES
        ENVIRONMENT "LD_LIBRARY_PATH=${NATIVE_LIBS_OUTPUT_DIR};DYLD_LIBRARY_PATH=${NATIVE_LIBS_OUTPUT_DIR}"
    )
    # Build the smoke-test executable as part of the main build
    add_dependencies(daw-native-libs test_vorbis_smoke)
endif()

# ── 6. CLAP ───────────────────────────────────────────────────────
# CLAP is a header-only interface library; no shared library to build.
# The headers are available at lib/Clap-1.2.7/include/clap/ for any
# future native plugin host code that needs them.
