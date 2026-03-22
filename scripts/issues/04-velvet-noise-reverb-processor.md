## Description

Add an efficient reverb processor based on velvet-noise sequences. Velvet noise is a sparse random signal with values restricted to {−1, 0, +1}, enabling convolution via simple additions and subtractions instead of multiplications. This yields a reverb with the perceptual quality of convolution reverb at a fraction of the computational cost — critical for real-time use with virtual threads.

## AES Research References

- [Efficient Velvet-Noise Convolution in Multicore Processors](docs/research/AES/Efficient_Velvet-Noise_Convolution_in_Multicore_Processors.pdf) (2024) — Presents parallelized velvet-noise convolution achieving significant speedup via segment-based multicore processing; directly applicable to Java virtual threads
- [Computationally-Efficient Simulation of Late Reverberation for Inhomogeneous Boundary Conditions and Coupled Rooms](docs/research/AES/Computationally-Efficient_Simulation_of_Late_Reverberation_for_Inhomogeneous_Boundary_Conditions_and_Coupled_Rooms.pdf) (2023) — Efficient late reverberation techniques complementary to velvet-noise approaches
- [The Effects of Reverberation on the Emotional Characteristics of Musical Instruments](docs/research/AES/The_Effects_of_Reverberation_on_the_Emotional_Characteristics_of_Musical_Instruments.pdf) (2015) — Perceptual validation that reverb characteristics significantly affect perceived instrument quality

## Implementation Approach

- New class `VelvetNoiseReverbProcessor implements AudioProcessor` in `daw-core/…/dsp/`
- Generate velvet-noise sequences with configurable density (pulses per second)
- Sparse convolution: only process non-zero impulse positions (additions/subtractions, no multiplications)
- Parallelizable segment processing using `Executors.newVirtualThreadPerTaskExecutor()` for late reverb tail
- Parameters: decay time, density, early/late mix, damping
- Complements the existing `ReverbProcessor` (Schroeder-Moorer) and `SpringReverbProcessor` as a third reverb algorithm
