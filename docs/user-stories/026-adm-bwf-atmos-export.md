---
title: "ADM BWF Export for Dolby Atmos Deliverables"
labels: ["enhancement", "spatial-audio", "export", "immersive"]
---

# ADM BWF Export for Dolby Atmos Deliverables

## Motivation

The `AdmBwfExporter` class exists in the core export module, and the `ObjectBasedRenderer`, `AudioObject`, `BedChannel`, and `AtmosSessionValidator` classes provide the Atmos session infrastructure. However, there is no UI workflow for configuring Atmos sessions and exporting ADM BWF files. ADM BWF (Audio Definition Model Broadcast Wave Format) is the standard delivery format for Dolby Atmos content accepted by streaming platforms. The research documents identify this as a medium-priority format support feature. Completing this workflow would make the DAW one of very few open-source tools capable of Atmos deliverables.

## Goals

- Add an Atmos session configuration dialog for setting up bed channels and audio objects
- Allow assigning tracks as bed channels or audio objects
- Validate the Atmos session configuration using `AtmosSessionValidator`
- Export the project as an ADM BWF file with embedded spatial metadata
- Include 3D position metadata for each audio object in the exported file
- Show export validation results (errors, warnings) before writing the file
- Support up to 7.1.4 bed + 118 objects as per the Atmos specification

## Non-Goals

- Dolby Atmos renderer certification or licensing
- DAMF (.atmos) master file format (proprietary Dolby format)
- Apple Spatial Audio encoding (requires separate Apple tools)
