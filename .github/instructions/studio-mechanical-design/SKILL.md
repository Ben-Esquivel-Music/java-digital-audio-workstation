---
name: studio-mechanical-design
description: "Design and review studio equipment panels, racks, chassis, loudspeaker cabinets, mounts, vibration isolation, cooling, fabrication, and tolerance stacks. Use for mechanical or thermal design of studio hardware and acoustic assemblies."
---

# Studio Mechanical Design

## Local references

Use the [schematic catalog's mechanical references](../studio-analog-circuits/references/schematic-catalog.md#mechanical-and-assembly-references) for the dimensioned Gyraf front panel, API-style PCB assembly drawing, SSL placements, and EZ1290 build guide. These are equipment-specific geometry references; they do not establish loads, material specifications, tolerances, or structural certification.

For loudspeaker and acoustic structures, read the relevant source:

- [Direct radiator enclosures](../../../docs/research/AES/Direct_Radiator_Loudspeaker_Enclosures.pdf): cabinet geometry, enclosure loading, and diffraction; historical theory rather than a modern driver specification.
- [Cabinet acoustic power radiation](../../../docs/research/AES/Predicting_the_Acoustic_Power_Radiation_from_Loudspeaker_Cabinets__A_Numerically_Efficient_Approach.pdf): material/damping identification, structural modeling, joints, and vibration validation.
- [Lightweight measurement room](../../../docs/research/AES/Design_of_a_lightweight_acoustical_measurement_room.pdf): framing/absorbent construction within its stated isolation and frequency limits.
- [Spring reverb physical model](../../../docs/research/AES/Physical_Modeling_of_a_Spring_Reverb_Tank_Incorporating_Helix_Angle,_Damping,_and_Magnetic_Bead_Coupling.pdf): coupled vibration and transducer boundary conditions when modeling a tank.

## Geometry and fabrication

Establish envelope, material/process, mass, load cases, mounting, environment, connectors/controls, service access, thermal constraints, and acoustic objectives. Use one explicit coordinate system, units, and datum scheme.

- Read annotated dimensions; do not measure a resized scan's pixels as manufacturing geometry. Note missing units, hole diameters, depths, and tolerances. Confirm component drawings and fit before releasing a cut/drill file.
- Specify hole centers, diameters, cutouts, bend lines, radii, thickness, fasteners, standoffs, shaft engagement, and connector clearance as relevant. Distinguish nominal dimensions from tolerance and manufacturing allowance.
- Include knob envelopes, adjacent cable bends, washer/nut access, PCB underside clearance, tool access, panel labeling, and assembly sequence. Use worst-case tolerance stacks for required fit; justify statistical stacks with process data.
- Derive loads, deflection, buckling, fastener engagement, center of gravity, and stability from supported material and boundary data. Suspended monitors and load-bearing studio structures require applicable rated hardware and qualified verification; document analysis alone cannot certify them.

## Thermal and vibration behavior

- Build a heat budget from actual electrical losses. For a linear regulator, start with `P = (Vin - Vout) I`, then account for worst-case input/current and quiescent losses. Estimate junction temperature from the applicable thermal-resistance network with ambient conditions and mounting assumptions.
- Balance ventilation, fan noise, dust, shielding, and service access. Specify derating and measure component temperatures in the closed assembly; open-bench results do not establish enclosed performance.
- For an ideal single-degree-of-freedom mode, `fn = sqrt(k/m)/(2 pi)`. Isolation depends on forcing frequency, damping, travel, load, and coupling paths; a soft mount can amplify response near resonance.
- For cabinets, account for panel stiffness, bracing, joint compliance, damping, baffle diffraction, leaks, and driver displacement from internal volume. Use actual driver parameters and excursion limits for alignment decisions. Do not present a generic box-volume formula as a validated design.

## Result

Provide a dimensioned layout or fabrication specification with BOM, tolerance/clearance assumptions, calculations, and fit/thermal/vibration validation. Mark concept dimensions and assumptions explicitly. Route circuit changes to [studio-analog-circuits](../studio-analog-circuits/SKILL.md) and room response to [studio-acoustics](../studio-acoustics/SKILL.md).
