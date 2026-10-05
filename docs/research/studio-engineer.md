# Studio Engineer

Studio Engineer connects recording and production decisions to the electrical, acoustic, and mechanical behavior of studio systems. It uses this repository's research and equipment references for analysis, design, troubleshooting, documentation, and requested DAW implementation.

This repository includes the GitHub Copilot agent definition and ten portable skills. The agent selects relevant topic files as needed; it does not load the entire research collection for each request.

## Agent locations

| Platform | Agent definition | Skill location |
| --- | --- | --- |
| GitHub Copilot | [studio-engineer.agent.md](../../.github/agents/studio-engineer.agent.md) | `.github/skills/studio-*/SKILL.md` |

The GitHub definition uses Markdown with YAML frontmatter and the agent identifier `studio-engineer`. Its skill folders contain the portable `SKILL.md` files and supporting references. The existing Java agent definitions are retained.

## Skills

| Skill | Scope |
| --- | --- |
| [studio-recording](../../.github/skills/studio-recording/SKILL.md) | Microphone comparisons, placement, preamp/ADC gain, monitoring and multichannel capture. |
| [studio-signal-flow](../../.github/skills/studio-signal-flow/SKILL.md) | Console/DAW routing, patchbays, levels, balanced interfaces, grounding and latency. |
| [studio-mixing](../../.github/skills/studio-mixing/SKILL.md) | Balance, EQ, dynamics, sends, parallel paths, automation and translation. |
| [studio-mastering](../../.github/skills/studio-mastering/SKILL.md) | Loudness, true peak, restoration, sequencing, conversion, dither, metadata and export checks. |
| [studio-acoustics](../../.github/skills/studio-acoustics/SKILL.md) | Room response, monitor/subwoofer setup, treatment, isolation and measurements. |
| [studio-immersive](../../.github/skills/studio-immersive/SKILL.md) | Speaker mapping, object-based audio, Ambisonics conventions, HRTFs and compatibility. |
| [studio-analog-circuits](../../.github/skills/studio-analog-circuits/SKILL.md) | Schematic analysis, preamps, EQ, VCA dynamics, summing, drivers and supplies. |
| [studio-hardware-service](../../.github/skills/studio-hardware-service/SKILL.md) | BOM reconciliation, assembly, substitutions, fault isolation and calibration. |
| [studio-mechanical-design](../../.github/skills/studio-mechanical-design/SKILL.md) | Panels, chassis, racks, cabinets, mounts, tolerances, cooling and vibration. |
| [studio-dsp-modeling](../../.github/skills/studio-dsp-modeling/SKILL.md) | Circuit/physical models, numerical stability, aliasing, measurement and real-time integration. |

Each skill has a `SKILL.md` entrypoint. The analog-circuits skill includes a [hardware source catalog](../../.github/skills/studio-analog-circuits/references/schematic-catalog.md). It covers the 24 files currently in `Schematics/`: Neotek, Mackie, SSL/Gyraf, THAT VCAs, API-style 312, EZ1290, Pultec/GY-PD, AMEK, and the unidentified mixer image.

## Example requests

Select the `studio-engineer` custom agent in a supported GitHub Copilot agent selector, then ask:

- "Use the Studio Engineer agent to trace the Neotek input-to-control-room path and explain the gain and monitor taps from the local drawings."
- "Use the Studio Engineer agent to compare the SSL clone's THAT 2180 and 2181 variants against the schematic notes and datasheets."
- "Use the Studio Engineer agent to draft a GY-PD panel and enclosure specification using the dimensioned image, marking missing manufacturing dimensions."
- "Use the Studio Engineer agent to design a measurement plan for cabinet vibration and explain how the AES cabinet-radiation paper informs validation."
- "Use the Studio Engineer agent to plan a level-matched microphone test and verify our multichannel recording path."
- "Use the Studio Engineer agent to turn the spring-reverb research into a bounded DSP prototype with a validation plan."

The GitHub skills use the documented `.github/skills` discovery location; additional research guidance under `.github/instructions` can be read directly by the agent.

## Source discipline

The research indexes route to primary papers; schematic graphics establish circuit details; manuals and datasheets establish device-specific procedures and limits. Summaries, clone drawings, proposed feature specs, and measurements of a physical unit serve different purposes. The skills preserve those distinctions and identify assumptions in designs and calculations.

Mechanical examples include the Gyraf front-panel layout and API-style assembly drawing, plus AES work on enclosure diffraction, cabinet radiation, measurement-room construction and spring dynamics. These references support targeted studio work; missing dimensions, materials, load ratings, and tolerances must be established for the actual design.

Current destination requirements, standards, manufacturer specifications and parts availability are verified when they affect a task. The local notes' loudness examples and architecture roadmap are not automatically current requirements or implemented DAW capabilities.

The included layout follows GitHub documentation for [custom agents](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/create-custom-agents) and [agent skills](https://docs.github.com/en/copilot/concepts/agents/about-agent-skills).
