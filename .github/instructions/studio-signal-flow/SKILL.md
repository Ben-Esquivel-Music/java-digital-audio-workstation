---
name: studio-signal-flow
description: "Trace and design studio console, patchbay, interface, and DAW routing with gain staging, balanced connections, grounding, cue mixes, and fault isolation. Use for end-to-end signal-path questions rather than component-level circuit analysis."
---

# Studio Signal Flow

## Source selection

Use the [schematic catalog](../studio-analog-circuits/references/schematic-catalog.md) to find Neotek input, aux, mix, balanced-output, control-room/solo, and PSU sheets or Mackie console drawings. Match the actual model and sheet revision before tracing. Consult [DAW architecture research](../../../docs/research/open-source-daw-tools.md) for software routing, then inspect the implementation.

## Trace the path

Draw the path from source through input selection, pad/preamp, insert/EQ, fader, bus, output driver, conversion, track, and monitor. Include the actual order found in the source. Label pre/post-fader sends, record taps, solo/PFL/AFL behavior, and control-room source selection; do not infer them from a console family name.

For each boundary, state channel identifiers, connector and pin mapping, nominal level, impedance, gain, headroom, and reference. Distinguish mic, instrument, line, speaker, and headphone connections. For patchbays, specify full-normal, half-normal, or through wiring and what plugging into each jack breaks.

## Levels and interfaces

- Use `dBu = 20 log10(Vrms / 0.775 V)` and `dBV = 20 log10(Vrms / 1 V)`. State whether a quoted analog level is differential or measured from one leg to reference.
- For a sine only, `Vpeak = sqrt(2) Vrms`. Do not convert arbitrary program peaks to RMS with that relation.
- Derive dBFS/analog relationships from the converter's calibration and full-scale convention. A nominal line level does not define a digital headroom margin by itself.
- Balanced transmission concerns impedance balance and differential reception; both legs need not carry equal and opposite signals. Check the output topology before grounding a leg or adapting to an unbalanced input.
- Separate protective earth, chassis, cable shield, and circuit reference in the diagram. Diagnose hum with loop paths, common-mode behavior, isolation, and measured noise spectra. Never use removal of protective earth as a noise remedy.
- Calculate latency across ADC, buffers, processing, DAC, and any lookahead. Distinguish round-trip latency from one-way monitoring and offset compensation.

## Diagnosis and result

Find the first stage where the observed behavior diverges from the expected path. Test channel identity, mute/solo states, gain, clipping, polarity, and cue feedback before suggesting component replacement. Use a known signal at a documented level and work through accessible boundaries.

Return a routing diagram or patch list and a stage-by-stage level budget. For a failure, include the distinguishing measurements and their interpretation. Use [studio-analog-circuits](../studio-analog-circuits/SKILL.md) when the fault reaches individual devices or feedback networks.
