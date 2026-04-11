cmake_minimum_required(VERSION 3.17)
project(libmp3lame C)

# ── Platform checks ──────────────────────────────────────────────────
include(CheckIncludeFile)
include(CheckTypeSize)
include(CheckSymbolExists)

check_include_file(stdint.h     HAVE_STDINT_H)
check_include_file(inttypes.h   HAVE_INTTYPES_H)
check_include_file(sys/types.h  HAVE_SYS_TYPES_H)
check_include_file(sys/stat.h   HAVE_SYS_STAT_H)
check_include_file(memory.h     HAVE_MEMORY_H)
check_include_file(stdlib.h     HAVE_STDLIB_H)
check_include_file(string.h     HAVE_STRING_H)
check_include_file(strings.h    HAVE_STRINGS_H)
check_include_file(unistd.h     HAVE_UNISTD_H)
check_include_file(errno.h      HAVE_ERRNO_H)
check_include_file(fcntl.h      HAVE_FCNTL_H)
check_include_file(limits.h     HAVE_LIMITS_H)
check_include_file(dlfcn.h      HAVE_DLFCN_H)
check_include_file(xmmintrin.h  HAVE_XMMINTRIN_H)

# STDC_HEADERS — set if stdlib.h and string.h both exist
if(HAVE_STDLIB_H AND HAVE_STRING_H)
    set(STDC_HEADERS 1)
endif()

check_symbol_exists(strchr  "string.h" HAVE_STRCHR)
check_symbol_exists(memcpy  "string.h" HAVE_MEMCPY)
check_symbol_exists(strtol  "stdlib.h" HAVE_STRTOL)

check_type_size(short              SIZEOF_SHORT)
check_type_size(int                SIZEOF_INT)
check_type_size(long               SIZEOF_LONG)
check_type_size("long long"        SIZEOF_LONG_LONG)
check_type_size(float              SIZEOF_FLOAT)
check_type_size(double             SIZEOF_DOUBLE)
check_type_size("long double"      SIZEOF_LONG_DOUBLE)
check_type_size("unsigned short"     SIZEOF_UNSIGNED_SHORT)
check_type_size("unsigned int"       SIZEOF_UNSIGNED_INT)
check_type_size("unsigned long"      SIZEOF_UNSIGNED_LONG)
check_type_size("unsigned long long" SIZEOF_UNSIGNED_LONG_LONG)

# Generate config.h into the binary tree
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake_config.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/config.h"
    @ONLY
)

# ── Source files ─────────────────────────────────────────────────────
set(LAME_ENCODER_SOURCES
    libmp3lame/VbrTag.c
    libmp3lame/bitstream.c
    libmp3lame/encoder.c
    libmp3lame/fft.c
    libmp3lame/gain_analysis.c
    libmp3lame/id3tag.c
    libmp3lame/lame.c
    libmp3lame/newmdct.c
    libmp3lame/presets.c
    libmp3lame/psymodel.c
    libmp3lame/quantize.c
    libmp3lame/quantize_pvt.c
    libmp3lame/reservoir.c
    libmp3lame/set_get.c
    libmp3lame/tables.c
    libmp3lame/takehiro.c
    libmp3lame/util.c
    libmp3lame/vbrquantize.c
    libmp3lame/version.c
    libmp3lame/mpglib_interface.c
)

set(LAME_VECTOR_SOURCES
    libmp3lame/vector/xmm_quantize_sub.c
)

set(MPGLIB_DECODER_SOURCES
    mpglib/common.c
    mpglib/dct64_i386.c
    mpglib/decode_i386.c
    mpglib/interface.c
    mpglib/layer1.c
    mpglib/layer2.c
    mpglib/layer3.c
    mpglib/tabinit.c
)

# ── Shared library target ───────────────────────────────────────────
add_library(mp3lame SHARED
    ${LAME_ENCODER_SOURCES}
    ${LAME_VECTOR_SOURCES}
    ${MPGLIB_DECODER_SOURCES}
)

target_include_directories(mp3lame PRIVATE
    "${CMAKE_CURRENT_BINARY_DIR}"   # generated config.h
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/libmp3lame"
    "${CMAKE_CURRENT_SOURCE_DIR}/mpglib"
)

target_compile_definitions(mp3lame PRIVATE
    HAVE_CONFIG_H
)

# Windows: export all public symbols via the existing .def file
if(WIN32)
    target_sources(mp3lame PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include/lame.def")
endif()

# Link math library on POSIX
if(NOT WIN32)
    target_link_libraries(mp3lame PRIVATE m)
endif()

# Suppress common harmless warnings in the LAME codebase
if(MSVC)
    target_compile_options(mp3lame PRIVATE /W3 /wd4244 /wd4305 /wd4996)
else()
    target_compile_options(mp3lame PRIVATE -Wall -Wno-sign-compare -Wno-unused-variable)
endif()

# Output name: produce libmp3lame.dll / libmp3lame.so
set_target_properties(mp3lame PROPERTIES
    OUTPUT_NAME "libmp3lame"
    PREFIX ""
)
