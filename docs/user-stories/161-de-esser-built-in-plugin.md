---
title: "De-esser Built-In Plugin with Split-Band Detection"
labels: ["enhancement", "plugins", "built-in", "dsp", "vocals"]
---

# De-esser Built-In Plugin with Split-Band Detection

## Motivation

Sibilance on vocal tracks (harsh "s" and "sh" sounds in the 4–8 kHz region) is one of the two or three most common mix problems. A de-esser is a frequency-conscious compressor that tames only the sibilant band. Every production DAW ships one (FabFilter Pro-DS, Waves Renaissance DeEsser, Logic DeEsser, Studio One De-esser). Missing a built-in means the user must reach for third-party every vocal mix.

## Goals

- Add `DeEsserProcessor` in `com.benesquivelmusic.daw.core.dsp.dynamics` implementing split-band de-essing: extract sibilant band via bandpass sidechain, detect level, attenuate only the sibilant band in the main path (not the full signal).
- Parameters on `DeEsserPlugin`: `frequency` (2–12 kHz), `Q` (0.5–4), `threshold` (-60 to 0 dB), `range` (0–20 dB max attenuation), `mode` (Wideband / Split-Band), `listen` (solos the sibilant band for tuning).
- Range limits attenuation depth to prevent over-processing.
- `DeEsserPluginView`: frequency sweep slider, reduction meter, listen button, mode toggle.
- "Listen" mode replaces the output with only the detection signal so the user can tune `frequency` + `Q` to isolate the sibilance before setting `threshold`.
- Registered with `BuiltInPluginRegistry`.
- Persist state via `ProjectSerializer`.
- Tests: on a vocal test file, attenuation meter correlates with manually-annotated sibilant events; wideband mode produces full-spectrum ducking; split-band mode leaves the non-sibilant band bit-exact.

## Non-Goals

- Dynamic spectral de-essing (e.g., iZotope's multi-band detection) — a more complex future story.
- Phoneme-aware de-essing using speech recognition.
- Auto-threshold learning.
