#!/usr/bin/env bash
set -euo pipefail

# Copies generated screenshot PNGs from the Compose Preview reference directory into the
# fastlane metadata directories for Play Store upload.
#
# Two phases, fail closed: nothing is written until the whole set has been parsed and
# validated, and nothing is deleted until every copy has been staged successfully.
#
# Usage:
#   ./fastlane/copy_screenshots.sh           # Copy all screenshots
#   ./fastlane/copy_screenshots.sh --clean   # Replace the image dirs of the copied locales

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REF_DIR="$PROJECT_DIR/app/src/screenshotTestGplayDebug/reference/eu/darken/butler/screenshots/PlayStoreScreenshotsKt"
FASTLANE_DIR="$PROJECT_DIR/fastlane/metadata/android"

CLEAN=false
if [[ "${1:-}" == "--clean" ]]; then
    CLEAN=true
elif [[ $# -gt 0 ]]; then
    echo "Unknown option: $1" >&2
    exit 1
fi

# Maps a rendered composable function name to "<form factor>:<order>_<label>".
# The order controls the Play Store ordering, the label aids humans.
# The form factor is read from here, never parsed out of the locale in the file name.
declare -A SCREEN_MAP=(
    [ExplorerHomePhone]="phone:1_explorer_home"
    [ExplorerDirectoryPhone]="phone:2_explorer_directory"
    [SearcherResultsPhone]="phone:3_searcher_results"
    [EditorViewPhone]="phone:4_editor"
    [AppsManagerPhone]="phone:5_apps"
    [WorkspaceManagerPhone]="phone:6_workspace_manager"
    [MultiPanePhone]="phone:7_multi_pane"
    [TemplatesPickerPhone]="phone:8_templates"
    [ExplorerHomeSeven]="seven:1_explorer_home"
    [ExplorerDirectorySeven]="seven:2_explorer_directory"
    [SearcherResultsSeven]="seven:3_searcher_results"
    [EditorViewSeven]="seven:4_editor"
    [AppsManagerSeven]="seven:5_apps"
    [WorkspaceManagerSeven]="seven:6_workspace_manager"
    [MultiPaneSeven]="seven:7_multi_pane"
    [TemplatesPickerSeven]="seven:8_templates"
    [ExplorerHomeTen]="ten:1_explorer_home"
    [ExplorerDirectoryTen]="ten:2_explorer_directory"
    [SearcherResultsTen]="ten:3_searcher_results"
    [EditorViewTen]="ten:4_editor"
    [AppsManagerTen]="ten:5_apps"
    [WorkspaceManagerTen]="ten:6_workspace_manager"
    [MultiPaneTen]="ten:7_multi_pane"
    [TemplatesPickerTen]="ten:8_templates"
)

declare -A FORM_FACTOR_DIR=(
    [phone]="phoneScreenshots"
    [seven]="sevenInchScreenshots"
    [ten]="tenInchScreenshots"
)

# Rendered pixel size per form factor, must match the device specs in ScreenshotContent.kt.
declare -A FORM_FACTOR_SIZE=(
    [phone]="1080x2400"
    [seven]="1200x1920"
    [ten]="2560x1600"
)

SCREENS_PER_FORM_FACTOR=8

if [[ ! -d "$REF_DIR" ]]; then
    echo "ERROR: Reference directory not found: $REF_DIR" >&2
    echo "Run ./fastlane/generate_screenshots.sh first." >&2
    exit 1
fi

png_size() {
    # Width and height are big-endian uint32 at byte offsets 16 and 20 of a PNG.
    od -An -j16 -N8 -tu4 --endian=big "$1" | awk '{print $1 "x" $2}'
}

# --- Phase 1: parse and validate everything before writing anything ----------------------

MANIFEST=()
declare -A SEEN_DESTINATION=()
declare -A LOCALE_FORM_FACTOR_COUNT=()
declare -A TOUCHED_LOCALES=()
ERRORS=0

shopt -s nullglob
SOURCES=("$REF_DIR"/*.png)
shopt -u nullglob

if (( ${#SOURCES[@]} == 0 )); then
    echo "ERROR: No PNG files found in $REF_DIR" >&2
    exit 1
fi
echo "Found ${#SOURCES[@]} screenshot images"

for png in "${SOURCES[@]}"; do
    filename="$(basename "$png")"

    # Filename format: FunctionName_previewName_hash_index.png
    # Function names carry no underscore, so the first one separates them from the locale.
    stem="${filename%.png}"
    stem="${stem%_[0-9]*}"
    stem="${stem%_[a-f0-9]*}"
    func_name="${stem%%_*}"
    locale_name="${stem#*_}"

    if [[ -z "${SCREEN_MAP[$func_name]+x}" ]]; then
        echo "ERROR: Unknown function name '$func_name' in $filename" >&2
        ERRORS=$(( ERRORS + 1 ))
        continue
    fi

    mapped="${SCREEN_MAP[$func_name]}"
    form_factor="${mapped%%:*}"
    screen_name="${mapped#*:}"

    if [[ -z "${FORM_FACTOR_DIR[$form_factor]+x}" ]]; then
        echo "ERROR: Unknown form factor '$form_factor' mapped for '$func_name'" >&2
        ERRORS=$(( ERRORS + 1 ))
        continue
    fi

    actual_size="$(png_size "$png")"
    if [[ "$actual_size" != "${FORM_FACTOR_SIZE[$form_factor]}" ]]; then
        echo "ERROR: $filename is ${actual_size}, expected ${FORM_FACTOR_SIZE[$form_factor]} for $form_factor" >&2
        ERRORS=$(( ERRORS + 1 ))
        continue
    fi

    destination="$locale_name/${FORM_FACTOR_DIR[$form_factor]}/${screen_name}.png"
    if [[ -n "${SEEN_DESTINATION[$destination]+x}" ]]; then
        echo "ERROR: $filename and ${SEEN_DESTINATION[$destination]} both map to $destination" >&2
        ERRORS=$(( ERRORS + 1 ))
        continue
    fi
    SEEN_DESTINATION["$destination"]="$filename"

    key="$locale_name/$form_factor"
    LOCALE_FORM_FACTOR_COUNT["$key"]=$(( ${LOCALE_FORM_FACTOR_COUNT[$key]:-0} + 1 ))
    TOUCHED_LOCALES["$locale_name"]=1

    MANIFEST+=("$png|$destination")
done

for locale in "${!TOUCHED_LOCALES[@]}"; do
    for form_factor in "${!FORM_FACTOR_DIR[@]}"; do
        count="${LOCALE_FORM_FACTOR_COUNT[$locale/$form_factor]:-0}"
        if (( count != SCREENS_PER_FORM_FACTOR )); then
            echo "ERROR: locale '$locale' has $count/$SCREENS_PER_FORM_FACTOR $form_factor screenshots" >&2
            ERRORS=$(( ERRORS + 1 ))
        fi
    done
done

if (( ERRORS > 0 )); then
    echo "" >&2
    echo "Aborting with $ERRORS problem(s), nothing was copied." >&2
    exit 1
fi

# --- Phase 2: stage every copy, then commit ---------------------------------------------

STAGING_DIR="$(mktemp -d "${TMPDIR:-/tmp}/butler-screenshot-copy.XXXXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT HUP INT TERM

for entry in "${MANIFEST[@]}"; do
    source_png="${entry%%|*}"
    destination="${entry#*|}"
    mkdir -p "$STAGING_DIR/$(dirname "$destination")"
    cp "$source_png" "$STAGING_DIR/$destination"
done

COPIED=0
for locale in "${!TOUCHED_LOCALES[@]}"; do
    for form_factor in "${!FORM_FACTOR_DIR[@]}"; do
        image_dir="${FORM_FACTOR_DIR[$form_factor]}"
        target_dir="$FASTLANE_DIR/$locale/images/$image_dir"
        if $CLEAN; then
            rm -rf "$target_dir"
        fi
        mkdir -p "$target_dir"
        for staged in "$STAGING_DIR/$locale/$image_dir"/*.png; do
            cp "$staged" "$target_dir/$(basename "$staged")"
            COPIED=$(( COPIED + 1 ))
        done
    done
done

echo ""
echo "=== Copy Complete ==="
echo "Locales: ${#TOUCHED_LOCALES[@]} | Copied: $COPIED images"
