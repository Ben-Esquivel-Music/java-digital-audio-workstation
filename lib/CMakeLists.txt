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

# ── Build options ───────────────────────────────────────────────────
option(BUILD_PORTAUDIO      "Build PortAudio shared library"      ON)
option(BUILD_FLUIDSYNTH     "Build FluidSynth shared library"     ON)
# NOTE: RoomAcoustiCpp is distributed under GPLv3. Building it as part
# of this project may impose copyleft obligations on downstream
# distribution. Enable only after reviewing licensing implications.
option(BUILD_ROOMACOUSTICPP "Build RoomAcoustiCpp shared library (GPLv3)" ON)

# ── 1. PortAudio ───────────────────────────────────────────────────
if(BUILD_PORTAUDIO)
    message(STATUS "── Configuring PortAudio ──")
    set(PA_BUILD_SHARED_LIBS ON CACHE BOOL "Build PortAudio as shared library" FORCE)
    # Build only the library — skip examples / tests / install targets
    set(PA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
    set(PA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
    set(PA_DISABLE_INSTALL ON  CACHE BOOL "Disable install/uninstall targets" FORCE)
    add_subdirectory(portaudio EXCLUDE_FROM_ALL)
    # Force output into the common native directory
    set_target_properties(portaudio PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )
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
        # Force output into the common native directory
        if(TARGET libfluidsynth)
            set_target_properties(libfluidsynth PROPERTIES
                LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
                RUNTIME_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
            )
        endif()
    endif()
endif()

# ── 3. RoomAcoustiCpp ─────────────────────────────────────────────
# Built as a shared library from the upstream sources + the existing
# extern "C" DLL-linkage API in Spatialiser/Main.cpp.
#
# LICENSING: RoomAcoustiCpp is GPLv3-licensed. Disable with
# -DBUILD_ROOMACOUSTICPP=OFF if copyleft obligations are not acceptable.
if(BUILD_ROOMACOUSTICPP)
    message(STATUS "── Configuring RoomAcoustiCpp ──")

    # RoomAcoustiCpp depends on the 3D Tune-In Toolkit for binaural processing.
    # Fetch the forked 3DTI repository referenced by the upstream submodule.
    include(FetchContent)
    FetchContent_Declare(
        3dti_AudioToolkit
        GIT_REPOSITORY https://github.com/jmannall/3dti_AudioToolkit.git
        GIT_SHALLOW    TRUE
        GIT_SUBMODULES_RECURSE TRUE
    )
    FetchContent_MakeAvailable(3dti_AudioToolkit)

    set(RAC_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/RoomAcoustiCpp-1.0.1/RoomAcoustiCpp-1.0.1/RoomAcoustiCpp")
    set(RAC_CODEGEN "${RAC_ROOT}/codegen/lib/myNN/include")

    # Collect all library source files (exclude Unity wrappers)
    set(RAC_SOURCES
        ${RAC_ROOT}/source/Common/Matrix.cpp
        ${RAC_ROOT}/source/Common/Vec.cpp
        ${RAC_ROOT}/source/DSP/AudioThreadPool.cpp
        ${RAC_ROOT}/source/DSP/Buffer.cpp
        ${RAC_ROOT}/source/DSP/FIRFilter.cpp
        ${RAC_ROOT}/source/DSP/GraphicEQ.cpp
        ${RAC_ROOT}/source/DSP/IIRFilter.cpp
        ${RAC_ROOT}/source/DSP/LinkwitzRileyFilter.cpp
        ${RAC_ROOT}/source/Spatialiser/AirAbsorption.cpp
        ${RAC_ROOT}/source/Spatialiser/Context.cpp
        ${RAC_ROOT}/source/Spatialiser/Diffraction/Models.cpp
        ${RAC_ROOT}/source/Spatialiser/Diffraction/Path.cpp
        ${RAC_ROOT}/source/Spatialiser/Edge.cpp
        ${RAC_ROOT}/source/Spatialiser/FDN.cpp
        ${RAC_ROOT}/source/Spatialiser/ImageEdge.cpp
        ${RAC_ROOT}/source/Spatialiser/ImageSource.cpp
        ${RAC_ROOT}/source/Spatialiser/Interface.cpp
        ${RAC_ROOT}/source/Spatialiser/Main.cpp
        ${RAC_ROOT}/source/Spatialiser/Reverb.cpp
        ${RAC_ROOT}/source/Spatialiser/Room.cpp
        ${RAC_ROOT}/source/Spatialiser/Source.cpp
        ${RAC_ROOT}/source/Spatialiser/SourceManager.cpp
        ${RAC_ROOT}/source/Spatialiser/Wall.cpp
        # MATLAB Coder generated NN inference sources
        ${RAC_ROOT}/codegen/lib/myNN/source/elementwiseOperationInPlace.cpp
        ${RAC_ROOT}/codegen/lib/myNN/source/myBestNN.cpp
        ${RAC_ROOT}/codegen/lib/myNN/source/myNN_data.cpp
        ${RAC_ROOT}/codegen/lib/myNN/source/myNN_initialize.cpp
        ${RAC_ROOT}/codegen/lib/myNN/source/myNN_terminate.cpp
        ${RAC_ROOT}/codegen/lib/myNN/source/mySmallNN.cpp
        ${RAC_ROOT}/codegen/lib/myNN/source/predict.cpp
    )

    add_library(roomacousticpp SHARED ${RAC_SOURCES})
    target_include_directories(roomacousticpp PRIVATE
        ${RAC_ROOT}/include
        ${3dti_audiotoolkit_SOURCE_DIR}/3dti_Toolkit                                                  # BinauralSpatializer headers
        ${3dti_audiotoolkit_SOURCE_DIR}/3dti_ResourceManager                                           # HRTF / resource headers
        ${3dti_audiotoolkit_SOURCE_DIR}/3dti_ResourceManager/third_party_libraries/cereal/include       # cereal serialization
        ${3dti_audiotoolkit_SOURCE_DIR}/3dti_ResourceManager/third_party_libraries/sofacoustics/include # SOFA acoustics
    )
    # Use SYSTEM include for codegen dir so its vendored omp.h does not
    # shadow the real system OpenMP header included by DSP/Interpolate.h.
    target_include_directories(roomacousticpp SYSTEM PRIVATE
        ${RAC_CODEGEN}
    )
    # C++20 required for std::atomic<std::shared_ptr<>> (used in IIRFilter.h)
    target_compile_features(roomacousticpp PRIVATE cxx_std_20)

    # The upstream RoomAcoustiCpp sources are missing a few standard includes
    # that GCC 13+ enforces strictly. Force-include a compatibility header
    # rather than patching vendored source.
    target_compile_options(roomacousticpp PRIVATE
        $<$<CXX_COMPILER_ID:MSVC>:/FI${CMAKE_CURRENT_SOURCE_DIR}/compat/rac_compat.h>
        $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-include${CMAKE_CURRENT_SOURCE_DIR}/compat/rac_compat.h>
    )

    # Context.cpp uses CErrorHandler unconditionally; the 3DTI ErrorHandler.h
    # only defines the class when SWITCH_ON_3DTI_ERRORHANDLER is set.
    target_compile_definitions(roomacousticpp PRIVATE
        SWITCH_ON_3DTI_ERRORHANDLER
        $<$<PLATFORM_ID:Windows>:_WINDOWS>
    )

    set_target_properties(roomacousticpp PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        LIBRARY_OUTPUT_DIRECTORY "${NATIVE_LIBS_OUTPUT_DIR}"
    )

    # Link pthreads on Linux/macOS
    find_package(Threads)
    if(Threads_FOUND)
        target_link_libraries(roomacousticpp PRIVATE Threads::Threads)
    endif()

    # Link OpenMP (used by NN codegen sources and DSP/Interpolate.h)
    find_package(OpenMP COMPONENTS CXX)
    if(OpenMP_CXX_FOUND)
        target_link_libraries(roomacousticpp PRIVATE OpenMP::OpenMP_CXX)
    endif()
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
if(TARGET roomacousticpp AND BUILD_ROOMACOUSTICPP)
    list(APPEND DAW_NATIVE_TARGETS roomacousticpp)
endif()
add_custom_target(daw-native-libs ALL DEPENDS ${DAW_NATIVE_TARGETS})

# ── 4. CLAP ───────────────────────────────────────────────────────
# CLAP is a header-only interface library; no shared library to build.
# The headers are available at lib/Clap-1.2.7/include/clap/ for any
# future native plugin host code that needs them.
