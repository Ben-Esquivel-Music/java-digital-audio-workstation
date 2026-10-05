---
name: studio-acoustics
description: "Analyze studio room acoustics, monitor placement, treatment, isolation, subwoofer integration, and acoustic measurement/calibration. Use for listening environments and room-response diagnosis rather than equipment chassis design."
---

# Studio Acoustics

## Sources

Use [AES acoustics research](../../../docs/research/aes-research-papers.md#acoustics-and-room-modeling), [monitoring context](../../../docs/research/mastering-techniques.md#1-listening-environment-optimization), and the [AES catalog](../../../docs/research/aes-pdf-catalog.md).

- [Immersive calibration by spatial averaging](../../../docs/research/AES/Spatial_Average_Measurement_Methods_for_Calibration_of_Immersive_Sound_in_Small_Rooms.pdf): read for measurement geometry and averaging decisions.
- [Modular mastering-room treatments](../../../docs/research/AES/Modular,_Shippable_Acoustic_Treatments_for_High-End_Mastering_Rooms__A_Case_Study_with_Adam_Ayan.pdf): read for treatment construction and installation context.
- [Lightweight acoustic measurement room](../../../docs/research/AES/Design_of_a_lightweight_acoustical_measurement_room.pdf): distinguish its relaxed isolation and test-frequency scope from studio isolation requirements.

## Room and measurement model

Record room dimensions, construction, listening positions, speaker/subwoofer locations, ambient noise, and measurement constraints. Separate sound isolation between spaces, absorption inside a room, and vibration transmission through structure; treatment for one does not establish the others.

- For an ideal rectangular room, estimate modes with `f = (c/2) sqrt((nx/Lx)^2 + (ny/Ly)^2 + (nz/Lz)^2)`, using dimensions in meters, a stated speed of sound, and nonnegative integer mode indices not all zero. Explain the boundary assumptions before interpreting real-room peaks.
- Relate early reflections, speaker-boundary interference, modal decay, and listening geometry to measured symptoms. Do not prescribe a universal room ratio or placement percentage as a guaranteed result.
- Document microphone calibration, orientation, position, interface routing, test level, stimulus, clock/sample rate, and background noise. Avoid claiming calibrated SPL from an uncalibrated capture chain.
- Use impulse response, frequency response, decay/waterfall, and early-reflection timing as the question requires. State windowing, smoothing, averaging, and noise-floor limitations. Small-room low-frequency decay is not adequately described by a single diffuse-field reverberation time.
- Check speakers individually before combined measurements. Integrate subwoofers with crossover, delay, polarity, and seat-to-seat variation; a single-seat flat response does not prove useful spatial consistency.
- Prioritize geometry and treatment for deep cancellations or long decay. Limit EQ boost where it consumes headroom without fixing the acoustic cause.
- For isolation/HVAC proposals, use actual construction and path data, including flanking paths and fan/duct noise. Route load-bearing mounts and fabrication to [studio-mechanical-design](../studio-mechanical-design/SKILL.md).

## Result

Return a measurement-backed diagnosis and a room/monitor plan with placement, treatment purpose, expected change, and before/after verification. Mark simulation and empirical estimates separately from observed response. Use [studio-immersive](../studio-immersive/SKILL.md) for layout/channel conventions.
