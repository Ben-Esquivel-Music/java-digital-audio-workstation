#!/usr/bin/env bash
#
# create-github-issues.sh
#
# Creates GitHub issues from user story markdown files in docs/user-stories/.
# Each .md file (except README.md) becomes a GitHub issue. Labels are parsed
# from the YAML front matter and created if they don't already exist.
#
# Usage:
#   ./docs/scripts/create-github-issues.sh [--dry-run]
#
# Prerequisites:
#   - gh CLI installed and authenticated (https://cli.github.com/)
#   - Run from the repository root directory
#
# Options:
#   --dry-run    Print what would be created without actually creating issues
#

set -euo pipefail

STORIES_DIR="docs/user-stories"
DRY_RUN=false

# ── Parse arguments ─────────────────────────────────────────────────────────

if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
    echo "=== DRY RUN MODE — no issues will be created ==="
    echo ""
fi

# ── Verify prerequisites ───────────────────────────────────────────────────

if ! command -v gh &>/dev/null; then
    echo "Error: gh CLI is not installed. Install it from https://cli.github.com/"
    exit 1
fi

if ! $DRY_RUN && ! gh auth status &>/dev/null 2>&1; then
    echo "Error: gh CLI is not authenticated. Run 'gh auth login' first."
    exit 1
fi

if [[ ! -d "$STORIES_DIR" ]]; then
    echo "Error: User stories directory not found at '$STORIES_DIR'."
    echo "Run this script from the repository root."
    exit 1
fi

# ── Color definitions for labels ────────────────────────────────────────────

declare -A LABEL_COLORS
LABEL_COLORS=(
    ["enhancement"]="a2eeef"
    ["ui"]="7057ff"
    ["arrangement-view"]="1d76db"
    ["editing"]="d4c5f9"
    ["automation"]="c5def5"
    ["transport"]="fbca04"
    ["mixer"]="e99695"
    ["audio-engine"]="f9d0c4"
    ["core"]="d73a4a"
    ["recording"]="b60205"
    ["file-io"]="006b75"
    ["dsp"]="5319e7"
    ["usability"]="0e8a16"
    ["export"]="0075ca"
    ["mastering"]="c2e0c6"
    ["project"]="bfdadc"
    ["midi"]="fef2c0"
    ["editor"]="d4c5f9"
    ["metering"]="e4e669"
    ["analysis"]="cfd3d7"
    ["plugins"]="ff9f1c"
    ["spatial-audio"]="2ec4b6"
    ["immersive"]="011627"
    ["monitoring"]="bfd4f2"
    ["session"]="c5def5"
    ["interoperability"]="006b75"
    ["navigation"]="76d7c4"
    ["design"]="f8a4d0"
    ["performance"]="eb6420"
    ["persistence"]="bfdadc"
    ["undo"]="d4c5f9"
    ["instruments"]="fef2c0"
    ["browser"]="76d7c4"
    ["telemetry"]="2ec4b6"
    ["mixing"]="e99695"
)

# ── Label descriptions ──────────────────────────────────────────────────────

declare -A LABEL_DESCRIPTIONS
LABEL_DESCRIPTIONS=(
    ["enhancement"]="New feature or request"
    ["ui"]="User interface changes"
    ["arrangement-view"]="Arrangement/timeline view"
    ["editing"]="Audio or MIDI editing features"
    ["automation"]="Parameter automation"
    ["transport"]="Transport controls and playback"
    ["mixer"]="Mixer view and routing"
    ["audio-engine"]="Core audio engine and processing"
    ["core"]="Core module changes"
    ["recording"]="Recording workflow and features"
    ["file-io"]="File import/export and I/O"
    ["dsp"]="Digital signal processing"
    ["usability"]="Usability and workflow improvements"
    ["export"]="Audio export and delivery"
    ["mastering"]="Mastering workflow and tools"
    ["project"]="Project management and structure"
    ["midi"]="MIDI recording, editing, and playback"
    ["editor"]="Clip editor view"
    ["metering"]="Metering and level display"
    ["analysis"]="Audio analysis tools"
    ["plugins"]="Plugin system and hosting"
    ["spatial-audio"]="Spatial and 3D audio"
    ["immersive"]="Immersive audio formats (Atmos, Ambisonics)"
    ["monitoring"]="Monitoring and preview modes"
    ["session"]="Session management and interchange"
    ["interoperability"]="Cross-DAW compatibility"
    ["navigation"]="Navigation and zoom"
    ["design"]="Visual design and theming"
    ["performance"]="Performance and CPU optimization"
    ["persistence"]="Data persistence and project storage"
    ["undo"]="Undo/redo system"
    ["instruments"]="Virtual instruments and SoundFonts"
    ["browser"]="File/sample browser panel"
    ["telemetry"]="Sound wave telemetry and room simulation"
    ["mixing"]="Mixing workflow and tools"
)

# ── Helper functions ────────────────────────────────────────────────────────

# Extract the title from YAML front matter
extract_title() {
    local file="$1"
    sed -n '/^---$/,/^---$/p' "$file" | grep '^title:' | sed 's/^title: *"//;s/" *$//'
}

# Extract labels from YAML front matter as a newline-separated list
extract_labels() {
    local file="$1"
    sed -n '/^---$/,/^---$/p' "$file" \
        | grep -oP '(?<=\[)[^\]]+' \
        | tr ',' '\n' \
        | sed 's/^ *"//;s/" *$//;s/^ *//;s/ *$//'
}

# Extract the issue body (everything after the second ---)
extract_body() {
    local file="$1"
    awk 'BEGIN{n=0} /^---$/{n++; next} n>=2{print}' "$file"
}

# Ensure a label exists, creating it if necessary
ensure_label() {
    local label="$1"
    local color="${LABEL_COLORS[$label]:-ededed}"
    local description="${LABEL_DESCRIPTIONS[$label]:-}"

    if $DRY_RUN; then
        echo "  [dry-run] Would ensure label: '$label' (color: #$color)"
        return
    fi

    # Check if label exists; create it if not
    if ! gh label list --limit 200 | grep -qw "$label"; then
        echo "  Creating label: '$label' (color: #$color)"
        if [[ -n "$description" ]]; then
            gh label create "$label" --color "$color" --description "$description" 2>/dev/null || true
        else
            gh label create "$label" --color "$color" 2>/dev/null || true
        fi
    else
        echo "  Label exists: '$label'"
    fi
}

# ── Main ────────────────────────────────────────────────────────────────────

echo "=== Creating GitHub Issues from User Stories ==="
echo "Stories directory: $STORIES_DIR"
echo ""

# Collect all story files (exclude README.md)
mapfile -t story_files < <(find "$STORIES_DIR" -maxdepth 1 -name '*.md' ! -name 'README.md' | sort)

if [[ ${#story_files[@]} -eq 0 ]]; then
    echo "No user story files found in $STORIES_DIR"
    exit 1
fi

echo "Found ${#story_files[@]} user story files."
echo ""

# ── Phase 1: Ensure all labels exist ───────────────────────────────────────

echo "--- Phase 1: Ensuring labels exist ---"
echo ""

# Collect all unique labels across all files
declare -A all_labels
for file in "${story_files[@]}"; do
    while IFS= read -r label; do
        [[ -n "$label" ]] && all_labels["$label"]=1
    done < <(extract_labels "$file")
done

for label in $(echo "${!all_labels[@]}" | tr ' ' '\n' | sort); do
    ensure_label "$label"
done

echo ""

# ── Phase 2: Create issues ─────────────────────────────────────────────────

echo "--- Phase 2: Creating issues ---"
echo ""

created=0
skipped=0

for file in "${story_files[@]}"; do
    title=$(extract_title "$file")

    if [[ -z "$title" ]]; then
        echo "⚠ Skipping '$file': no title found in front matter"
        skipped=$((skipped + 1))
        continue
    fi

    echo "Processing: $title"
    echo "  File: $file"

    # Build label arguments
    label_args=()
    while IFS= read -r label; do
        [[ -n "$label" ]] && label_args+=("--label" "$label")
    done < <(extract_labels "$file")

    body=$(extract_body "$file")

    if $DRY_RUN; then
        echo "  [dry-run] Would create issue:"
        echo "    Title:  $title"
        echo "    Labels: ${label_args[*]:-none}"
        echo "    Body:   $(echo "$body" | grep -m1 '.' | head -c 80)..."
        echo ""
        created=$((created + 1))
        continue
    fi

    # Check if an issue with this exact title already exists
    existing=$(gh issue list --state all --search "\"$title\" in:title" --limit 5 --json title -q ".[].title" 2>/dev/null || true)
    if echo "$existing" | grep -qFx "$title"; then
        echo "  ⏭ Issue already exists, skipping."
        skipped=$((skipped + 1))
        echo ""
        continue
    fi

    # Create the issue
    gh issue create \
        --title "$title" \
        --body "$body" \
        "${label_args[@]}" \
        2>/dev/null

    echo "  ✅ Issue created."
    echo ""
    created=$((created + 1))

    # Small delay to avoid rate limiting
    sleep 1
done

# ── Summary ─────────────────────────────────────────────────────────────────

echo "=== Summary ==="
echo "Created: $created"
echo "Skipped: $skipped"
echo "Total:   ${#story_files[@]}"

if $DRY_RUN; then
    echo ""
    echo "This was a dry run. No issues were actually created."
    echo "Run without --dry-run to create the issues."
fi
