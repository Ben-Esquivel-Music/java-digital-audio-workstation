/*
 * Compatibility header for RoomAcoustiCpp compilation with GCC 13+.
 *
 * The upstream RoomAcoustiCpp sources assume certain standard headers are
 * transitively included. GCC 13+ tightened transitive include coverage,
 * so we explicitly include the missing headers here and force-include this
 * file via -include in the CMake build.
 */
#ifndef RAC_COMPAT_H
#define RAC_COMPAT_H

#include <cfloat>    /* DBL_MIN / FLT_MIN — used in Common/Definitions.h */
#include <optional>  /* std::optional     — used in DSP/LinkwitzRileyFilter.h */
#include <cmath>     /* std::signbit      — used in Spatialiser/Diffraction/Path.cpp */

/* Bring math functions into the global namespace so unqualified calls compile. */
using std::signbit;
using std::isnan;

/*
 * localtime_s has reversed parameter order on Windows vs POSIX.
 * RoomAcoustiCpp's Context.h calls localtime_s(result, timer) — the MSVC
 * convention.  On Linux/macOS localtime_r(timer, result) is the equivalent.
 */
#if !defined(_WIN32) && !defined(__ANDROID__)
#include <ctime>
inline std::tm* localtime_s(std::tm* result, const std::time_t* timer) {
    return localtime_r(timer, result);
}
#endif

#endif /* RAC_COMPAT_H */
