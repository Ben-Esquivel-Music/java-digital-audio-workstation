#!/usr/bin/env bash
#
# Creates GitHub issues for all 27 AES research-driven feature enhancements.
#
# Prerequisites:
#   - gh CLI installed and authenticated (https://cli.github.com/)
#   - Run from the repository root directory
#
# Usage:
#   ./scripts/create-aes-issues.sh
#
# Labels are created automatically if they don't exist.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ISSUES_DIR="$SCRIPT_DIR/issues"
REPO="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"

echo "Creating AES feature enhancement issues in $REPO"
echo "================================================="

# --- Ensure labels exist ---------------------------------------------------

ensure_label() {
    local name="$1" color="$2" description="$3"
    if ! gh label list --repo "$REPO" --search "$name" --json name -q '.[].name' | grep -qx "$name"; then
        gh label create "$name" --repo "$REPO" --color "$color" --description "$description" 2>/dev/null || true
        echo "  Created label: $name"
    fi
}

echo ""
echo "Ensuring labels exist..."
ensure_label "category: dsp"       "1d76db" "Digital signal processing effects and processors"
ensure_label "category: analysis"  "0e8a16" "Audio analysis and metering tools"
ensure_label "category: spatial"   "d93f0b" "Spatial audio processing"
ensure_label "category: utility"   "c5def5" "Utility tools and generators"
ensure_label "category: mastering" "f9d0c4" "Mastering and export features"
ensure_label "priority: high"      "b60205" "High priority feature"
ensure_label "priority: medium"    "fbca04" "Medium priority feature"
ensure_label "priority: low"       "c2e0c6" "Low priority feature"
ensure_label "aes-research"        "5319e7" "Derived from AES research paper analysis"
ensure_label "enhancement"         "a2eeef" "New feature or request"
echo "  Labels ready."

# --- Helper to create a single issue --------------------------------------

create_issue() {
    local number="$1" title="$2" body_file="$3" category_label="$4" priority_label="$5"

    echo ""
    echo "[$number/27] $title"

    if ! [ -f "$body_file" ]; then
        echo "  ERROR: body file not found: $body_file"
        return 1
    fi

    gh issue create \
        --repo "$REPO" \
        --title "$title" \
        --body-file "$body_file" \
        --label "enhancement" \
        --label "aes-research" \
        --label "$category_label" \
        --label "$priority_label"

    echo "  Created."
}

# --- Create all 27 issues -------------------------------------------------

create_issue  1 "Graphic Equalizer Processor (Octave / Third-Octave)" \
    "$ISSUES_DIR/01-graphic-equalizer-processor.md" "category: dsp" "priority: high"

create_issue  2 "Oversampled Nonlinear Waveshaper" \
    "$ISSUES_DIR/02-oversampled-nonlinear-waveshaper.md" "category: dsp" "priority: high"

create_issue  3 "Antiderivative Antialiasing for Distortion Effects" \
    "$ISSUES_DIR/03-antiderivative-antialiasing.md" "category: dsp" "priority: medium"

create_issue  4 "Velvet-Noise Reverb Processor" \
    "$ISSUES_DIR/04-velvet-noise-reverb-processor.md" "category: dsp" "priority: high"

create_issue  5 "Directional Feedback Delay Network Reverb" \
    "$ISSUES_DIR/05-directional-fdn-reverb.md" "category: dsp" "priority: medium"

create_issue  6 "Perceptual Bass Extension Processor" \
    "$ISSUES_DIR/06-perceptual-bass-extension.md" "category: dsp" "priority: medium"

create_issue  7 "Air Absorption Filter for Distance Modeling" \
    "$ISSUES_DIR/07-air-absorption-filter.md" "category: dsp" "priority: medium"

create_issue  8 "Non-Ideal Op-Amp Distortion Model" \
    "$ISSUES_DIR/08-non-ideal-opamp-distortion.md" "category: dsp" "priority: low"

create_issue  9 "Audio Peak Reduction via Chirp Spreading" \
    "$ISSUES_DIR/09-chirp-peak-reduction.md" "category: dsp" "priority: medium"

create_issue 10 "Sines / Transients / Noise Decomposition" \
    "$ISSUES_DIR/10-stn-decomposition.md" "category: analysis" "priority: high"

create_issue 11 "Phase Alignment and Polarity Detection" \
    "$ISSUES_DIR/11-phase-alignment-polarity-detection.md" "category: analysis" "priority: high"

create_issue 12 "Lossless Audio Integrity Checker" \
    "$ISSUES_DIR/12-lossless-audio-integrity-checker.md" "category: analysis" "priority: medium"

create_issue 13 "Lossy Compression Artifact Detection" \
    "$ISSUES_DIR/13-lossy-compression-artifact-detection.md" "category: analysis" "priority: medium"

create_issue 14 "Multitrack Mix Feature Analysis" \
    "$ISSUES_DIR/14-multitrack-mix-feature-analysis.md" "category: analysis" "priority: medium"

create_issue 15 "Fractional-Octave Spectrum Smoothing" \
    "$ISSUES_DIR/15-fractional-octave-spectrum-smoothing.md" "category: analysis" "priority: medium"

create_issue 16 "Coherence-Based Distortion Indicator" \
    "$ISSUES_DIR/16-coherence-based-distortion-indicator.md" "category: analysis" "priority: low"

create_issue 17 "Transient Detection for Adaptive Block Switching" \
    "$ISSUES_DIR/17-transient-detection-block-switching.md" "category: analysis" "priority: medium"

create_issue 18 "Binaural Externalization Processing" \
    "$ISSUES_DIR/18-binaural-externalization.md" "category: spatial" "priority: high"

create_issue 19 "Stereo-to-Binaural Conversion" \
    "$ISSUES_DIR/19-stereo-to-binaural-conversion.md" "category: spatial" "priority: high"

create_issue 20 "2D-to-3D Ambience Upmixer" \
    "$ISSUES_DIR/20-2d-to-3d-ambience-upmixer.md" "category: spatial" "priority: medium"

create_issue 21 "Ambisonic Enhancement via Time-Frequency Masking" \
    "$ISSUES_DIR/21-ambisonic-enhancement-tf-masking.md" "category: spatial" "priority: medium"

create_issue 22 "Spatial Room Impulse Response Tail Resynthesis" \
    "$ISSUES_DIR/22-spatial-rir-tail-resynthesis.md" "category: spatial" "priority: low"

create_issue 23 "Panning Table Synthesis for Irregular Speaker Layouts" \
    "$ISSUES_DIR/23-panning-table-synthesis.md" "category: spatial" "priority: medium"

create_issue 24 "Stereo-to-Mono Down-Mix Optimizer" \
    "$ISSUES_DIR/24-stereo-to-mono-downmix-optimizer.md" "category: spatial" "priority: medium"

create_issue 25 "Audio Test Signal Generator Suite" \
    "$ISSUES_DIR/25-audio-test-signal-generator.md" "category: utility" "priority: medium"

create_issue 26 "Hearing Loss Simulation for Accessible Monitoring" \
    "$ISSUES_DIR/26-hearing-loss-simulation.md" "category: utility" "priority: low"

create_issue 27 "Intelligent Gap Filling / Bandwidth Extension" \
    "$ISSUES_DIR/27-bandwidth-extension.md" "category: mastering" "priority: low"

echo ""
echo "================================================="
echo "All 27 AES feature enhancement issues created."
echo "See: https://github.com/$REPO/issues"
