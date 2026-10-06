---
name: studio-mixing
description: "Develop and evaluate studio mixes using balance, EQ, dynamics, parallel processing, effects, automation, and translation checks. Use for multitrack production and mix diagnostics; use studio-mastering for final delivery."
---

# Studio Mixing

## References

Search the mixing and effects sections of [AES research](../../../docs/research/aes-research-papers.md) and the [audio tool catalog](../../../docs/research/audio-development-tools.md) for the relevant technique. [Mastering research](../../../docs/research/mastering-techniques.md) provides listening, EQ, dynamics, and stereo-imaging context; its example chain is a starting point, not a required mix order.

For reference-matching or assisted mixing, locate the differentiable mix-graph and human-feedback papers in the AES index and read them before recommending their methods. Keep research prototypes separate from available production features.

## Mixing decisions

Start from the arrangement, intended focus, reference tracks, available stems, and playback context. Assess source quality and static balance before proposing a long processing chain. If audio is unavailable, produce a conditional plan and state what must be heard or measured.

- Address masking with level, arrangement, panning, and time-dependent automation as well as EQ. Specify the musical role of an EQ change and distinguish measured resonance from a stylistic choice.
- Set compression from envelope behavior and the desired change in crest factor, punch, sustain, or consistency. Attack/release labels and detector definitions differ across processors; settings alone do not define the sound.
- Distinguish feed-forward/feedback detection, peak/RMS sensing, knee, ratio, sidechain filtering, and makeup gain when they explain behavior. Match processed and bypass levels when judging a change.
- For parallel paths, inspect latency compensation, polarity, and phase response. Mixing dry and processed signals can change tone even when their nominal delays match.
- Specify send versus insert effects, wet/dry ownership, tempo-related timing, predelay, decay, and bus routing. Check whether effect returns feed their own sends.
- Preserve headroom at inserts, groups, and the mix bus. Avoid assuming floating-point internal headroom prevents clipping at converters, integer exports, or nonlinear plugins.
- Evaluate mono fold-down, stereo correlation, spectral balance, low-frequency translation, and intelligibility at useful listening levels. Loudness-match references and distinguish the reference's mastering from its mix balance.

## Result

Provide a prioritized mix plan or revised session with reasons for each material change, routing, and comparison criteria. Preserve editable versions when processing or rendering audio. Route object-based or Ambisonic production to [studio-immersive](../studio-immersive/SKILL.md) and final export checks to [studio-mastering](../studio-mastering/SKILL.md).
