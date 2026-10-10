---
name: studio-dsp-modeling
description: "Translate analog circuits, physical audio systems, and AES algorithms into validated DSP models or DAW processors. Use for circuit emulation, spring/rotary effects, nonlinear processing, measurement tools, and real-time implementation."
---

# Studio DSP Modeling

## Select the model and evidence

Read the relevant [AES effects research](../../../docs/research/aes-research-papers.md#audio-effects-modeling-and-dsp) and [feature specifications](../../../docs/research/aes-feature-enhancements.md). For circuit models, use [studio-analog-circuits](../studio-analog-circuits/SKILL.md) and the [schematic catalog](../studio-analog-circuits/references/schematic-catalog.md).

Useful starting papers include [spring reverb dynamics](../../../docs/research/AES/Physical_Modeling_of_a_Spring_Reverb_Tank_Incorporating_Helix_Angle,_Damping,_and_Magnetic_Bead_Coupling.pdf), [pseudo Leslie](../../../docs/research/AES/Analog_Pseudo_Leslie_Effect_with_High_Grade_of_Repeatability.pdf), and [levelling-amplifier matching](../../../docs/research/AES/Sound_Matching_an_Analogue_Levelling_Amplifier_Using_the_Newton-Raphson_Method.pdf). Read equations, limitations, and evaluation sections before attributing an implementation to a paper.

Choose an analytical, circuit, wave-digital, state-space, finite-difference, convolution, gray-box, or learned model from the behavior required, available measurements, and CPU/latency budget. Do not add physical detail that cannot be identified or that does not improve the target behavior.

## Modeling decisions

- Define amplitude/voltage scaling, operating point, sample rate, parameter units/ranges, state initialization, and the intended linear/nonlinear regime. Digital full scale has no inherent analog voltage.
- Preserve source/load interactions, detector state, feedback, saturation, and control response that matter to the reference. An ideal transfer function or static waveshaper alone cannot establish full hardware equivalence.
- Document continuous-time equations, discretization, stability/passivity assumptions, parameter mapping, and numerical tolerances. Bound iterative solvers and define behavior when convergence fails.
- Manage nonlinear aliasing with a justified method such as oversampling or antiderivative antialiasing. State filter latency and measure the residual; merely increasing sample rate does not prove artifacts are acceptable.
- For spring/rotary models, separate excitation, transducer coupling, propagation/dispersion, damping, boundary conditions, motion, and radiation. Validate against the reference's actual outputs, not only its title.
- Smooth parameters where discontinuities cause clicks or instability. Define reset, bypass, tails, silence, denormal handling, and sample-rate-change behavior.

## Repository integration

Inspect existing SDK processor contracts and module ownership before adding a class. Use research-features for the proposed design, dawg-annotations-reflection when registration/capabilities are involved, dawg-native-libs for native dependencies, and javafx-application-design for controls. Verify current code rather than treating the research roadmap as implemented behavior.

Keep callbacks bounded and free of blocking I/O or allocation. Prepare coefficients/resources off the audio thread, manage ownership during parameter updates, and state latency/tail/channel contracts. Prototype numerical behavior offline before integrating a costly solver or model.

## Validation and result

Select tests that distinguish meaningful behavior: impulse/frequency response, sine sweeps at multiple levels, gain/noise/distortion where applicable, detector attack/release, stereo linking, extreme parameters, sample rates, block sizes, silence, finite output, and real-time cost. Compare measurements under the same scaling, stimulus, load, and bandwidth. Use level-matched listening when audio evaluation is available.

Deliver the equations/model assumptions and requested implementation, with observed tests, accuracy limits, latency, and CPU budget. Mark unmeasured fidelity claims as hypotheses.
