# Studio Hardware Source Catalog

Sources live in `docs/research/Schematics/` at the repository root. Use this catalog to select documents; open the relevant pages/images before making claims about a circuit, pin, value, dimension, or procedure. Filenames and groupings do not establish compatibility with a particular production unit.

## Console signal paths

| Source | Read for |
| --- | --- |
| [Neotek input A](../../../../docs/research/Schematics/Neotek_Elite_Input_A.gif) | Input-channel sheet A; trace its off-sheet connections with sheet B. |
| [Neotek input B](../../../../docs/research/Schematics/Neotek_Elite_Input_B.gif) | Input-channel sheet B and its switching/control connections. |
| [Neotek mix amplifiers](../../../../docs/research/Schematics/Neotek_Elite_Mixamps.gif) | Mix-bus amplification and connected references. |
| [Neotek auxiliary mix amplifiers](../../../../docs/research/Schematics/Neotek_Elite_Aux_Mixamps.gif) | Auxiliary-bus summing/output paths. |
| [Neotek balanced outputs](../../../../docs/research/Schematics/Neotek_Elite_Bal_Outputs.gif) | Output-driver topology, connector and load behavior. |
| [Neotek control room, studio and solo](../../../../docs/research/Schematics/Neotek_Elite_CR-Studio-solo.gif) | Monitor-source selection and solo/control-room paths. |
| [Neotek PSU](../../../../docs/research/Schematics/Neotek_Elite_PSU.gif) | Supply and distribution context for the matching console revision. |
| [Mackie CR1604 VLZ](../../../../docs/research/Schematics/Mackie%20CR1604%20VLZ.pdf) | Eleven pages of console drawings; select the actual board/revision and connected sheets. |
| [Allen_heat mixer image](../../../../docs/research/Schematics/Allen_heat%20mixer.gif) | Mixer drawing with a nonspecific local filename; identify its title/model before attributing a design to an Allen & Heath product. |

## SSL/Gyraf dynamics and VCA devices

| Source | Read for |
| --- | --- |
| [SSL schematic](../../../../docs/research/Schematics/ssl_schematic.pdf) | One-page Gyraf SSL mixbus-compressor clone, revision 9, dated 06-2013. Includes audio VCAs, sidechain, timing, metering, power and variant notes. |
| [SSL component placements](../../../../docs/research/Schematics/ssl_component_placements.pdf) | Three pages of placement/board graphics; confirm correspondence to the schematic and actual PCB. |
| [SSL component list](../../../../docs/research/Schematics/SSL-Comp-Component-List.pdf) | Three-page parts list, including listed VCA alternatives and supply/control parts. Resolve alternatives against schematic notes and datasheets. |
| [THAT 2180 datasheet](../../../../docs/research/Schematics/2180data.pdf) | Twelve-page pre-trimmed VCA device reference; electrical limits, gain control, applications and trim-related restrictions. |
| [THAT 2181 datasheet](../../../../docs/research/Schematics/2181data.pdf) | Twelve-page externally trimmable VCA reference. Package, symmetry adjustment, and application details matter to substitutions. |
| [AMEK System 9098 compressor/limiter manual](../../../../docs/research/Schematics/amek-9098-cl-manual.pdf) | Twenty-four-page user guide for operation and published specifications. Do not assume it is a complete internal service schematic. |

The local SSL drawing explicitly distinguishes 2180 and 2181 distortion-trim connections. Verify the complete variant notes before converting the design. A parts-list alternative alone is insufficient.

## Microphone preamplifiers

| Source | Read for |
| --- | --- |
| [API-style 312 schematic](../../../../docs/research/Schematics/312schematic.pdf.png) | Raster circuit drawing with input/output transformers, discrete op-amp, gain, instrument input, pad, output selection and power connections. |
| [312 BOM](../../../../docs/research/Schematics/312BOM.pdf.pdf) | One-page component list, including transformer and discrete-op-amp identifiers; preserve the doubled extension when locating it. |
| [API-312 clone PCB assembly drawing](../../../../docs/research/Schematics/API-312-Clone-Mic-Preamp-PCB-Assembly-Drawing.pdf) | One-page board geometry, placements, connector labels and hole pattern; see mechanical notes below. |
| [EZ1290 V2.6 assembly guide](../../../../docs/research/Schematics/ez1290_build_guide.pdf) | Twenty-eight-page guide identifying the V2.6 design and assembly context. Its introduction describes a 1073-like preamp without EQ/line input and explicitly distinguishes it from an exact original 1290/1272 clone. |
| [EZ1290 V2.6 BOM](../../../../docs/research/Schematics/ez1290_bom_26.xls) | Legacy Excel parts workbook. Use a compatible reader and preserve sheets/columns; inspect it when reconciling parts. |

Do not assume identical revisions merely because the schematic, BOM, and drawing share a family name. Confirm reference designators, transformer versions, connector numbering, supply requirements and actual board markings.

## Gyraf/Pultec equalizer

| Source | Read for |
| --- | --- |
| [GY-PD schematic](../../../../docs/research/Schematics/gy_pd_sch.gif) | Gyraf revision 3, dated 09-02-2002: passive EQ network, ECC88 SRPP buffer, output transformer, heater and high-voltage supplies. The drawing marks a +250 V HT rail. |
| [GY-PD PDF](../../../../docs/research/Schematics/gy_pd.pdf) | Six pages of associated drawing material; inspect the relevant page to identify its role and revision. |
| [Gyraf Pultec BOM](../../../../docs/research/Schematics/Gyraf-Pultec-BOM.pdf) | Three-page parts reference for the EQ project, including inductors, switches, capacitors and supply components. Historic supplier codes need current verification for procurement. |
| [GY-PD front-panel measurements](../../../../docs/research/Schematics/gy_pd_front_measures.gif) | Dimensioned control layout and labeling; read the annotations, not the image's pixel scale. |

The HT and heater systems make electrical clearance, thermal design, stored energy, and service access material design constraints. Use the hardware-service skill for any physical commissioning or troubleshooting.

## Mechanical and assembly references

- **GY-PD front panel:** the image includes a `70mm` annotation and control-position/row dimensions. Establish the actual datums and missing cutout/tolerance information before generating fabrication geometry. It is not a complete production drawing.
- **API-style assembly drawing:** printed outline dimensions are 116 by 78, with a 108 by 70 mounting-center pattern and 4 edge offsets. Verify units, hole diameters, revision and orientation from the physical board or authoritative component documentation before machining. Component envelopes and underside clearance also need confirmation.
- **SSL placement sheets:** useful for component location and board correspondence; do not treat artwork scale as a dimensional specification.
- **EZ1290 build guide:** inspect the assembly/wiring pages relevant to the enclosure and the actual transformer, switch and connector variants being used.

For loads, bracing, panel vibration, cabinet acoustics or spring dynamics, follow the AES references in [studio-mechanical-design](../../studio-mechanical-design/SKILL.md). This source set contains useful studio examples, but not a comprehensive mechanical design handbook or a certification basis.

## Working with the files

GIF/PNG drawings and several PDFs are raster-based. Render or inspect the complete relevant sheet, then zoom/crop without losing the title block and off-sheet labels. Empty PDF text extraction does not mean a page is blank. Prefer original images to screenshots of downscaled previews for component reading.

Preserve source files. Keep derived OCR, rendered pages and calculations in a task output/scratch directory and label them as derived material. OCR characters, terminal labels and crossing lines require visual confirmation. Use document title, revision, page/sheet and designator as the evidence locator.
