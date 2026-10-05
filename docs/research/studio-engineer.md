# Studio Engineer

Studio Engineer connects recording and production decisions to the electrical, acoustic, and mechanical behavior of studio systems. It uses this repository's research and equipment references for analysis, design, troubleshooting, documentation, and requested DAW implementation.

The project agent is available for Codex, Claude Code, and GitHub Copilot. Each copy selects the relevant topic files as needed; it does not load the entire research collection for each request.

## Agent locations

| Platform | Agent definition | Skill location |
| --- | --- | --- |
| Codex | [studio-engineer.agent.toml](../../.codex/agents/studio-engineer.agent.toml) | `.agents/skills/studio-*/SKILL.md` |
| Claude Code | [studio-engineer.agent.md](../../.claude/agents/studio-engineer.agent.md) | `.claude/skills/studio-*/SKILL.md` |
| GitHub Copilot | [studio-engineer.agent.md](../../.github/agents/studio-engineer.agent.md) | `.github/skills/studio-*/SKILL.md` |

The Claude and GitHub definitions use Markdown with YAML frontmatter and the agent identifier `studio-engineer`. Their skill folders contain the portable `SKILL.md` files and supporting references. The existing Java agent definitions are retained.

## Skills

| Skill | Scope |
| --- | --- |
| [studio-recording](../../.agents/skills/studio-recording/SKILL.md) | Microphone comparisons, placement, preamp/ADC gain, monitoring and multichannel capture. |
| [studio-signal-flow](../../.agents/skills/studio-signal-flow/SKILL.md) | Console/DAW routing, patchbays, levels, balanced interfaces, grounding and latency. |
| [studio-mixing](../../.agents/skills/studio-mixing/SKILL.md) | Balance, EQ, dynamics, sends, parallel paths, automation and translation. |
| [studio-mastering](../../.agents/skills/studio-mastering/SKILL.md) | Loudness, true peak, restoration, sequencing, conversion, dither, metadata and export checks. |
| [studio-acoustics](../../.agents/skills/studio-acoustics/SKILL.md) | Room response, monitor/subwoofer setup, treatment, isolation and measurements. |
| [studio-immersive](../../.agents/skills/studio-immersive/SKILL.md) | Speaker mapping, object-based audio, Ambisonics conventions, HRTFs and compatibility. |
| [studio-analog-circuits](../../.agents/skills/studio-analog-circuits/SKILL.md) | Schematic analysis, preamps, EQ, VCA dynamics, summing, drivers and supplies. |
| [studio-hardware-service](../../.agents/skills/studio-hardware-service/SKILL.md) | BOM reconciliation, assembly, substitutions, fault isolation and calibration. |
| [studio-mechanical-design](../../.agents/skills/studio-mechanical-design/SKILL.md) | Panels, chassis, racks, cabinets, mounts, tolerances, cooling and vibration. |
| [studio-dsp-modeling](../../.agents/skills/studio-dsp-modeling/SKILL.md) | Circuit/physical models, numerical stability, aliasing, measurement and real-time integration. |

Each skill has a `SKILL.md` entrypoint. The Codex copies also include `agents/openai.yaml` display metadata. The analog-circuits skill provides a hardware source catalog in all three locations: [Codex](../../.agents/skills/studio-analog-circuits/references/schematic-catalog.md), [Claude](../../.claude/skills/studio-analog-circuits/references/schematic-catalog.md), and [GitHub](../../.github/skills/studio-analog-circuits/references/schematic-catalog.md). It covers the 24 files currently in `Schematics/`: Neotek, Mackie, SSL/Gyraf, THAT VCAs, API-style 312, EZ1290, Pultec/GY-PD, AMEK, and the unidentified mixer image.

## Example requests

Ask Codex in this repository:

- "Use the Studio Engineer agent to trace the Neotek input-to-control-room path and explain the gain and monitor taps from the local drawings."
- "Use the Studio Engineer agent to compare the SSL clone's THAT 2180 and 2181 variants against the schematic notes and datasheets."
- "Use the Studio Engineer agent to draft a GY-PD panel and enclosure specification using the dimensioned image, marking missing manufacturing dimensions."
- "Use the Studio Engineer agent to design a measurement plan for cabinet vibration and explain how the AES cabinet-radiation paper informs validation."
- "Use the Studio Engineer agent to plan a level-matched microphone test and verify our multichannel recording path."
- "Use the Studio Engineer agent to turn the spring-reverb research into a bounded DSP prototype with a validation plan."

Skills can also be invoked directly, for example: `Use $studio-mechanical-design to review this chassis layout.`

In Claude Code, ask `Use the studio-engineer agent to review this chassis layout` or invoke a skill with `/studio-mechanical-design`. In GitHub Copilot, select the `studio-engineer` custom agent in a supported agent selector. The GitHub skills use the documented `.github/skills` discovery location; additional existing research guidance under `.github/instructions` can be read directly by the agent.

Codex identifies the agent by the `name` field, **Studio Engineer**. If the current chat does not expose the new agent, start a new chat in this repository to refresh its agent catalog. Skills are discovered from the repository's `.agents/skills` location; restart Codex if a new skill does not appear. The configuration inherits session settings, including the model and permissions.

## Source discipline

The research indexes route to primary papers; schematic graphics establish circuit details; manuals and datasheets establish device-specific procedures and limits. Summaries, clone drawings, proposed feature specs, and measurements of a physical unit serve different purposes. The skills preserve those distinctions and identify assumptions in designs and calculations.

Mechanical examples include the Gyraf front-panel layout and API-style assembly drawing, plus AES work on enclosure diffraction, cabinet radiation, measurement-room construction and spring dynamics. These references support targeted studio work; missing dimensions, materials, load ratings, and tolerances must be established for the actual design.

Current destination requirements, standards, manufacturer specifications and parts availability are verified when they affect a task. The local notes' loudness examples and architecture roadmap are not automatically current requirements or implemented DAW capabilities.

The file layout follows official OpenAI documentation for [custom agents](https://learn.chatgpt.com/docs/agent-configuration/subagents#custom-agents) and [local skills](https://learn.chatgpt.com/docs/build-skills#where-codex-loads-local-skills).

The mirror layouts follow Claude documentation for [subagents](https://code.claude.com/docs/en/sub-agents) and [skills](https://code.claude.com/docs/en/skills), and GitHub documentation for [custom agents](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/create-custom-agents) and [agent skills](https://docs.github.com/en/copilot/concepts/agents/about-agent-skills).
