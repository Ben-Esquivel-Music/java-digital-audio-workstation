# asioshim — native FFM bridge for Steinberg ASIO driver capability
# queries (story 130 / 213). See README.md for the full contract.
#
# The build is opt-in: it only fires when ASIO_SDK_DIR points at a
# Steinberg ASIO SDK install. Steinberg's licence forbids us from
# redistributing the SDK source, so a fresh checkout — including
# CI runs that do not install the SDK — must continue to build.
#
# When ASIO_SDK_DIR is unset, this CMakeLists silently skips the target
# and prints a STATUS message; the Java layer then falls back to
# BufferSizeRange.DEFAULT_RANGE / the canonical sample-rate set, with
# a one-shot INFO log line.
#
# This file is normally pulled in from the top-level lib/CMakeLists.txt
# but can also be configured stand-alone:
#   cmake -S daw-core/native/asio -B target/build-asio \
#         -DASIO_SDK_DIR=C:/asiosdk -A x64

cmake_minimum_required(VERSION 3.17)

# Allow stand-alone configuration. When pulled in via add_subdirectory
# the parent project's settings (output dir, generator, etc.) win.
if(NOT DEFINED PROJECT_NAME)
    project(asioshim CXX)
endif()

# Resolve the ASIO SDK location from cache / CLI / environment.
if(NOT ASIO_SDK_DIR AND DEFINED ENV{ASIO_SDK_DIR})
    set(ASIO_SDK_DIR "$ENV{ASIO_SDK_DIR}" CACHE PATH "Steinberg ASIO SDK root")
endif()

if(NOT ASIO_SDK_DIR)
    message(STATUS "asioshim: ASIO_SDK_DIR not set — skipping asioshim build. "
                   "Set -DASIO_SDK_DIR=<path-to-asiosdk> to enable.")
    return()
endif()

if(NOT WIN32)
    message(STATUS "asioshim: Steinberg ASIO SDK is Windows-only — "
                   "skipping asioshim build on this host.")
    return()
endif()

# Fail loudly when ASIO_SDK_DIR is set but does not contain the SDK.
set(_asio_common "${ASIO_SDK_DIR}/common")
set(_asio_host   "${ASIO_SDK_DIR}/host")
set(_asio_pc     "${ASIO_SDK_DIR}/host/pc")
foreach(_dir ${_asio_common} ${_asio_host} ${_asio_pc})
    if(NOT IS_DIRECTORY "${_dir}")
        message(FATAL_ERROR
            "asioshim: ASIO_SDK_DIR=${ASIO_SDK_DIR} does not look like a "
            "Steinberg ASIO SDK tree (missing: ${_dir}). Either point "
            "ASIO_SDK_DIR at a real SDK extract or unset it to skip "
            "this target.")
    endif()
endforeach()

message(STATUS "── Configuring asioshim with ASIO_SDK_DIR=${ASIO_SDK_DIR} ──")

# Steinberg SDK source files we need linked in (see SDK README).
set(_asio_sdk_sources
    "${_asio_common}/asio.cpp"
    "${_asio_host}/asiodrivers.cpp"
    "${_asio_pc}/asiolist.cpp"
)

add_library(asioshim SHARED
    asioshim.cpp
    ${_asio_sdk_sources}
)

target_include_directories(asioshim PRIVATE
    "${_asio_common}"
    "${_asio_host}"
    "${_asio_pc}"
)

set_target_properties(asioshim PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    PREFIX ""           # produce "asioshim.dll", not "libasioshim.dll"
    OUTPUT_NAME "asioshim"
)

# Place the DLL alongside the other native libs so SymbolLookup finds
# it via -Djava.library.path=${native.libs.dir} (matches how
# portaudio_x64.dll is bundled — see lib/CMakeLists.txt).
if(DEFINED NATIVE_LIBS_OUTPUT_DIR)
    set_target_properties(asioshim PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )
    foreach(CFG IN ITEMS DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        string(TOUPPER "${CFG}" CFG_UPPER)
        set_target_properties(asioshim PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
            RUNTIME_OUTPUT_DIRECTORY_${CFG_UPPER} "${NATIVE_LIBS_OUTPUT_DIR}"
        )
    endforeach()
endif()

# Steinberg's host glue uses Win32 COM (CoCreateInstance, CoInitialize).
target_link_libraries(asioshim PRIVATE ole32 user32 advapi32)
