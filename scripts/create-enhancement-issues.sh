#!/usr/bin/env bash
# =============================================================================
# create-enhancement-issues.sh
#
# Creates GitHub issues for all 22 enhancement proposals in docs/enhancements/.
# Each issue is created with the full markdown body from the enhancement file,
# labeled by priority, and assigned the "enhancement" label.
#
# Prerequisites:
#   1. Install the GitHub CLI: https://cli.github.com/
#   2. Authenticate: gh auth login
#   3. Run from the repository root: ./scripts/create-enhancement-issues.sh
#
# Options:
#   --dry-run    Print what would be created without actually creating issues
# =============================================================================

set -euo pipefail

REPO="Ben-Esquivel-Music/java-digital-audio-workstation"
ENHANCEMENTS_DIR="docs/enhancements"
DRY_RUN=false

if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
    echo "=== DRY RUN MODE — no issues will be created ==="
    echo ""
fi

# Verify gh CLI is available and authenticated
if ! command -v gh &> /dev/null; then
    echo "Error: GitHub CLI (gh) is not installed. Install from https://cli.github.com/"
    exit 1
fi

if ! gh auth status &> /dev/null; then
    echo "Error: Not authenticated with GitHub CLI. Run: gh auth login"
    exit 1
fi

# Ensure labels exist (create if missing)
create_label_if_missing() {
    local name="$1"
    local color="$2"
    local description="$3"
    if ! gh label view "$name" --repo "$REPO" &> /dev/null; then
        if [[ "$DRY_RUN" == true ]]; then
            echo "[dry-run] Would create label: $name ($description)"
        else
            gh label create "$name" --repo "$REPO" --color "$color" --description "$description"
            echo "Created label: $name"
        fi
    fi
}

echo "Ensuring labels exist..."
create_label_if_missing "enhancement"        "a2eeef" "New feature or request"
create_label_if_missing "priority:immediate"  "b60205" "Immediate priority — core engine"
create_label_if_missing "priority:high"       "d93f0b" "High priority — core mastering and mixing"
create_label_if_missing "priority:near-term"  "e4e669" "Near-term priority — enhanced features"
create_label_if_missing "priority:medium"     "0e8a16" "Medium priority — spatial and workflow"
create_label_if_missing "priority:future"     "c5def5" "Future priority — advanced capabilities"
create_label_if_missing "area:dsp"            "f9d0c4" "Digital signal processing"
create_label_if_missing "area:spatial"        "d4c5f9" "Spatial and immersive audio"
create_label_if_missing "area:integration"    "bfdadc" "External tool/library integration"
create_label_if_missing "area:ai"             "fef2c0" "AI and machine learning features"
create_label_if_missing "area:architecture"   "c2e0c6" "Core architecture and engine"
echo ""

# Map each enhancement file to its priority label and area labels
get_labels() {
    local file="$1"
    local labels="enhancement"
    local basename
    basename=$(basename "$file")

    # Priority label based on file content
    local priority_line
    priority_line=$(tail -1 "$file")
    case "$priority_line" in
        *Immediate*) labels="$labels,priority:immediate" ;;
        *High*)      labels="$labels,priority:high" ;;
        *Near-Term*) labels="$labels,priority:near-term" ;;
        *Medium*)    labels="$labels,priority:medium" ;;
        *Future*)    labels="$labels,priority:future" ;;
    esac

    # Area labels based on file number
    case "$basename" in
        001*|002*|003*|004*|005*|006*|007*) labels="$labels,area:dsp" ;;
        008*|009*|010*|011*)                labels="$labels,area:spatial" ;;
        012*|013*|014*|020*)                labels="$labels,area:integration" ;;
        015*)                               labels="$labels,area:architecture" ;;
        016*|017*|021*)                     labels="$labels,area:ai" ;;
        018*)                               labels="$labels,area:spatial,area:integration" ;;
        019*)                               labels="$labels,area:dsp" ;;
        022*)                               labels="$labels,area:dsp" ;;
    esac

    echo "$labels"
}

# Create issues from enhancement files
CREATED=0
SKIPPED=0

for file in "$ENHANCEMENTS_DIR"/0*.md; do
    # Extract title from first line (strip "# Enhancement: " prefix)
    title=$(head -1 "$file" | sed 's/^# Enhancement: //')

    # Use the full file content as the issue body
    body=$(cat "$file")

    # Get labels
    labels=$(get_labels "$file")

    # Check if issue already exists (by title)
    existing=$(gh issue list --repo "$REPO" --search "\"$title\" in:title" --state all --json number --jq 'length' 2>/dev/null || echo "0")
    if [[ "$existing" -gt 0 ]]; then
        echo "SKIP: Issue already exists for: $title"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi

    if [[ "$DRY_RUN" == true ]]; then
        echo "[dry-run] Would create issue:"
        echo "  Title:  $title"
        echo "  Labels: $labels"
        echo "  Body:   $(echo "$body" | wc -l) lines"
        echo ""
    else
        issue_url=$(gh issue create \
            --repo "$REPO" \
            --title "$title" \
            --body "$body" \
            --label "$labels")
        echo "CREATED: $title"
        echo "  URL: $issue_url"
        echo ""
        # Brief pause to avoid rate limiting
        sleep 1
    fi
    CREATED=$((CREATED + 1))
done

echo "========================================"
echo "Done! Created: $CREATED | Skipped: $SKIPPED"
if [[ "$DRY_RUN" == true ]]; then
    echo "(Dry run — no issues were actually created)"
    echo "Run without --dry-run to create issues."
fi
