---
title: "Fix TruePeakDetector Polyphase Coefficients to Properly Reconstruct Inter-Sample Peaks (BS.1770-4)"
labels: ["bug", "dsp", "metering", "loudness", "limiter"]
---

# Fix TruePeakDetector Polyphase Coefficients to Properly Reconstruct Inter-Sample Peaks (BS.1770-4)

## Motivation

`com.benesquivelmusic.daw.core.dsp.TruePeakDetector` is the repo-wide 4× oversampling true-peak detector and is consumed by `LimiterProcessor` as well as the new `InputLevelMonitor` added in story 137. Its current polyphase FIR coefficient table does not form a valid linear-phase reconstruction low-pass: across every probe signal tried (fs/4 sine, fs/2 alternating, fs/8 offset), the "oversampled" peak never exceeds the input sample magnitude. In other words, it behaves like a sample-peak detector with extra arithmetic — the entire point of an inter-sample peak detector is defeated.

ITU-R BS.1770-4 Annex 2 specifies the exact 48-tap, 12-tap-per-phase polyphase FIR for 4× reconstruction (the same filter used by every commercial LUFS/TP meter). Replacing the coefficient table with the BS.1770-4 taps (or an equivalent properly-designed Kaiser-windowed sinc) is a small, surgical fix that restores correct true-peak behaviour everywhere it is used. Downstream consequences today: the limiter's true-peak ceiling is effectively a sample-peak ceiling, and clip indicators miss the very inter-sample peaks they exist to flag (a +0.5 dBTP signal with samples at -0.3 dBFS would slip through silently).

## Goals

- Replace the coefficient table in `com.benesquivelmusic.daw.core.dsp.TruePeakDetector` with the BS.1770-4 Annex 2 polyphase FIR (48 taps at 4× upsampling — 12 per phase), or a documented equivalent Kaiser-windowed sinc meeting the same magnitude response (−6 dB at Nyquist, >80 dB stopband rejection).
- Cite the source for the coefficient table in a comment (BS.1770-4 Annex 2, or the design parameters for a regenerated set).
- Preserve the existing public API of `TruePeakDetector` — only the internal taps change.
- Add `TruePeakDetectorTest` cases that FAIL with the old coefficients and PASS with the corrected ones:
  - fs/4 sine at −0.3 dBFS sample peak reconstructs to ≥ +0.3 dBTP (the canonical BS.1770-4 reference case).
  - fs/2 alternating ±0.891 (−1 dBFS) reconstructs to ≥ 0 dBTP.
  - DC and low-frequency signals are unaffected (peak ≈ sample peak, within 0.05 dB).
  - Impulse response is linear phase (symmetric taps) — verify by direct tap inspection.
- Update `com.benesquivelmusic.daw.core.analysis.InputLevelMonitorTest` to use a true inter-sample-peak fixture (samples below 0 dBFS, oversampled peak above), removing the temporary fs/4-sine workaround and the caveat Javadoc that story 137 had to add.
- Validate `LimiterProcessor` still hits its existing test assertions after the fix; adjust any test that was passing only because the old detector under-reported true peak (these are latent test bugs, not regressions).
- Run `mvn -pl daw-core,daw-app -am test` and confirm the full suite still passes.

## Non-Goals

- Changing the 4× oversampling ratio (BS.1770-4 mandates 4×; 8× is a separate discussion).
- Adding a new metering record or UI surface — the InputLevelMeter / LevelMeter surfaces are already correct, they just receive accurate data after the fix.
- Rewriting the LimiterProcessor's lookahead or release logic — only its true-peak input changes.
- Exposing the coefficient table as a plugin parameter (it is a fixed, standardised filter).
