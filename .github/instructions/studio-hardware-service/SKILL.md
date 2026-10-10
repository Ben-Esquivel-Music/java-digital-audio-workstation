---
name: studio-hardware-service
description: "Plan studio hardware assembly, BOM reconciliation, fault isolation, repair, and calibration from service manuals, schematics, placement drawings, and datasheets. Use for physical console, preamp, EQ, and compressor work."
---

# Studio Hardware Service

## Establish the unit and references

Use the [schematic catalog](../studio-analog-circuits/references/schematic-catalog.md) to select the matching schematic, placement drawing, BOM, and manual. Record hardware/PCB revision, modifications, symptoms, prior measurements, and available instruments. A user manual may describe operation without giving an internal repair procedure.

For an EZ1290 project, reconcile the V2.6 build guide with its `.xls` BOM; for SSL/Gyraf, compare schematic variant notes, component placement, and THAT VCA type; for API-style hardware, match transformer/DOA footprints and connector definitions to the actual board.

## Assembly and substitution

- Reconcile reference designators, quantities, value, tolerance, voltage/power rating, package, lead spacing, polarity, temperature rating, and mounting constraints. Preserve the source file and use a reader that supports legacy `.xls`; do not treat it as CSV or rename it to `.xlsx`.
- Flag contradictory or missing specifications instead of selecting an arbitrary source as authoritative. Verify current manufacturer data and lifecycle/availability when procurement or a substitution is requested.
- Evaluate substitutions electrically, mechanically, thermally, and for calibration. Pin compatibility alone does not establish equivalence, especially for VCA trim variants, transformers, regulators, and discrete op-amps.
- Prepare an assembly and inspection sequence appropriate to the manual: orientation, joints, wiring, hardware, connector continuity, and unpowered checks before commissioning. Route enclosure/thermal changes to [studio-mechanical-design](../studio-mechanical-design/SKILL.md).

## Fault isolation and commissioning

Rank likely causes from the symptom, then propose the least invasive measurement that separates them. Check routing and external cables before internal component replacement. Relate rail ripple, channel imbalance, hum spectra, oscillation, DC offset, gain error, and switching faults to specific circuit stages.

For each test, specify instrument, signal level/frequency, bandwidth/filter, load, reference node, expected range and its source, and how the outcomes change the diagnosis. Use current limiting and power-up stages only as appropriate to the verified supply design and service procedure.

Mains wiring and tube/high-voltage supplies require qualified handling and appropriate instruments. Disconnect power and verify stored-energy discharge with a rated instrument before unpowered work. Never defeat protective earth or attach an earth-referenced oscilloscope ground to an unidentified live/floating node. When qualifications or safe conditions are unknown, provide document analysis and de-energized checks; identify the energized measurements for a qualified technician.

Calibrate in the manual's order and state source conditions: supply/bias, gain, frequency response, channel match, noise, distortion, detector timing, meter indication, and bypass as applicable. Distortion and noise measurements need a stated bandwidth, weighting, input termination, and test level. Save before/after readings.

## Result

Produce an annotated BOM/build plan or fault tree with test points, conditions, expected observations, and stopping conditions for abnormal voltage, current, temperature, or instability. Label anything that cannot be established remotely. Document proposed modifications separately from the original revision.
