---
name: studio-analog-circuits
description: "Analyze studio hardware schematics, microphone preamps, transformers, passive and active equalizers, VCA dynamics, console summing, balanced drivers, and power supplies. Use for component-level circuit reasoning and hardware-to-DSP analysis."
---

# Studio Analog Circuits

## Choose the exact source

Read the relevant entry in the [schematic catalog](references/schematic-catalog.md). It maps the local SSL/Gyraf, API-style, EZ1290, Neotek, Mackie, and AMEK references, including BOMs, placements, manuals, and VCA datasheets. These documents have different roles and are not interchangeable hardware revisions.

Inspect the actual schematic page or image at sufficient resolution. Use text extraction to find a section, not to decide whether lines connect. Identify title, revision, supply rails, reference nodes, connector orientation, component designators, and off-sheet connections. Mark unreadable labels as unknown.

## Circuit analysis

- Separate audio path, feedback, sidechain/control, metering, switching, and power. Draw a functional block diagram before deriving a detailed model.
- For preamps, trace input transformer ratio/loading, gain switching, feedback networks, bias, output stage, and phantom routing where present. Reflect impedance through the transformer with the explicitly defined turns ratio; do not infer a transformer specification from its appearance.
- For passive EQ, include source/load impedances, switch positions, inductors/capacitors, loss, and makeup stage. Pultec-style boost/cut interactions require analysis of the combined network, not independent ideal shelf filters.
- For dynamics, distinguish detector, rectification/envelope, ratio/knee network, attack/release, control-voltage polarity, and audio VCA. Read the exact datasheet for gain law, operating conditions, pinout, and trim procedure.
- THAT 2180 is factory pre-trimmed; THAT 2181 provides an external symmetry adjustment. A clone BOM listing either device is not a drop-in substitution instruction. Check the schematic's variant notes and the corresponding datasheet before proposing changes.
- For console stages, include bus source impedance, summing gain, noise gain, stability compensation, and balanced-driver behavior. Consider the load and cable, not only unloaded transfer functions.
- For supplies, distinguish AC input, rectification, filtering, regulation, grounding, rail sequencing, heater supplies, phantom supply, and stored energy. Derive current and dissipation from the actual circuit and component data.
- Bound ideal calculations by op-amp bandwidth/slew, input/output headroom, bias currents, device noise, tolerances, temperature, and transformer/inductor behavior when those effects matter. Simulation results depend on the model; they are not physical measurements.

## Evidence and output

Return a source/revision identifier, annotated path, governing equations or simulation, expected operating values with conditions, and a validation plan. Keep measured, derived, datasheet, and assumed values distinct. Do not claim an exact branded hardware response from a clone schematic.

Use [studio-hardware-service](../studio-hardware-service/SKILL.md) for assembly, probing, repair, and calibration; use [studio-dsp-modeling](../studio-dsp-modeling/SKILL.md) for discrete-time implementations. Analysis of mains or high-voltage circuits does not establish a unit is safe to energize.
