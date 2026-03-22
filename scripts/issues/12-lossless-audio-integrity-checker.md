## Description

Add an analysis tool that detects whether a supposedly lossless audio file (WAV, FLAC) has been upconverted from a lossy source (MP3, AAC) or upsampled from a lower sample rate. This is essential for mastering engineers who need to verify source material integrity before processing.

## AES Research References

- [Lossless Audio Checker: A Software for the Detection of Upscaling, Upsampling, and Transcoding in Lossless Musical Tracks](docs/research/AES/Lossless_Audio_Checker__A_Software_for_the_Detection_of_Upscaling,_Upsampling,_and_Transcoding_in_Lossless_Musical_Tracks.pdf) (2015) — Presents detection algorithms for identifying upscaled, upsampled, and transcoded audio using spectral analysis; detects the characteristic spectral cutoff of lossy codecs and the spectral gaps from upsampling

## Implementation Approach

- New class `LosslessIntegrityChecker` in `daw-core/…/analysis/`
- Spectral cutoff detection: identify sharp high-frequency rolloff characteristic of lossy codecs (e.g., MP3 ~16 kHz, AAC ~18 kHz)
- Upsampling detection: detect mirrored spectral content or null energy above the original Nyquist frequency
- Bit-depth analysis: detect if lower bits are zero-padded (indicating upscaling from lower bit depth)
- Returns a report: likely original format, detected cutoff frequency, confidence score
- Uses the existing `FftUtils` and `SpectrumAnalyzer`

## Extends

`FftUtils`, `SpectrumAnalyzer`
