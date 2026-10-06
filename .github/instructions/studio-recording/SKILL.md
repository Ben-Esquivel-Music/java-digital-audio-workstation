---
name: studio-recording
description: "Plan and diagnose studio recording sessions, microphone placement, preamp gain, monitoring, and multichannel capture. Use for tracking and capture decisions; use studio-mixing for post-recording processing."
---

# Studio Recording

## Sources

Read the recording section of [AES research](../../../docs/research/aes-research-papers.md#recording-techniques) and locate relevant papers through the [AES catalog](../../../docs/research/aes-pdf-catalog.md).

- [Vocalist and phrase dependent microphone preferences](../../../docs/research/AES/Investigating_Phrase_and_Vocalist_Dependent_Microphone_Preferences_for_Male_Hip-Hop_Vocal_Recording.pdf) informs controlled microphone comparisons, with conclusions bounded by its participants and stimuli.
- [Analog and digital preamp gain](../../../docs/research/AES/Analog_and_Digital_Gain_in_Microphone_Preamplifier_Design.pdf) is a starting point for preamp/ADC gain questions.
- [Direct versus rendered binaural guitar capture](../../../docs/research/AES/Direct_vs._Rendered_Binaural_Capture_of_Guitar_Amplifier__A_Comparative_Study.pdf) applies when evaluating spatial recording methods.

## Session decisions

Establish the source, intended sound, room, available microphones and interfaces, channel count, sample rate, and monitor path. Infer routine choices from context; seek missing facts only when they change routing, feasibility, or capture quality.

- Evaluate polar pattern, placement, proximity effect, off-axis coloration, source SPL, room pickup, and preamp noise together. Avoid universal microphone choices based solely on genre.
- Compare microphones with the same performance and documented geometry when possible. Match playback levels; note when separate takes confound the comparison.
- Choose analog gain from expected source peaks, preamp headroom, converter calibration, and noise. Digital gain cannot recover clipping upstream of the ADC.
- Confirm microphone compatibility before enabling phantom power. Route phantom switching, pads, polarity, and channel assignments explicitly.
- For multi-mic capture, distinguish polarity inversion from time alignment. Use geometry and measured delay to diagnose comb filtering; preserve useful acoustic differences rather than automatically aligning every channel.
- Specify direct versus DAW monitoring, latency, cue sends, talkback, and feedback prevention. Record a short test through the complete path and verify the resulting file's channels, peaks, duration, and playback mapping.

For capture software, inspect the actual backend channel map and frame/interleaving contract. Verify with distinct signals per input channel, including an input beyond the first stereo pair. Account for clocking, dropout detection, file finalization, and recovery requirements when relevant.

## Deliverable

Provide an input list and capture plan with source, microphone/DI, placement, preamp/ADC channel, routing, monitor destination, and test criteria. Separate proposed settings from measured values. Use [studio-signal-flow](../studio-signal-flow/SKILL.md) for patchbay, grounding, or console tracing.
