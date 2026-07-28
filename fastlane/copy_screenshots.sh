#!/usr/bin/env bash
set -euo pipefail

# Copies generated screenshot PNGs from the Compose Preview reference directory into the
# fastlane metadata directories for Play Store upload.
#
# Two phases, fail closed: nothing is written until the whole set has been parsed and
# validated, and each destination directory is then replaced by a single rename of a complete
# staged directory, with every completed swap rolled back if a later one fails.
#
# Usage:
#   ./fastlane/copy_screenshots.sh           # Copy all screenshots
#   ./fastlane/copy_screenshots.sh --clean   # Replace the image dirs of the copied locales

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REF_DIR="$PROJECT_DIR/app/src/screenshotTestGplayDebug/reference/eu/darken/butler/screenshots/PlayStoreScreenshotsKt"
FASTLANE_ROOT="$PROJECT_DIR/fastlane"
FASTLANE_DIR="$FASTLANE_ROOT/metadata/android"

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
    # Requires GNU coreutils `od` (--endian); this script is Linux-only, like CI. On BSD/macOS
    # `od` this fails loudly instead of reporting a wrong size.
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

# --- Phase 2: build complete replacement directories, then swap them in ------------------

# The staging area lives beside the metadata tree, on the same filesystem as the destinations,
# so the commit is a rename, not a copy: a rename either happens or it does not, there is no
# half-written destination directory. Staying outside metadata/android/ also keeps it out of
# everything that walks the metadata tree.
STAGING_DIR=""
SWAPS=()
ROLLBACK_ARMED=false

# Undoes the swaps recorded in SWAPS, newest first. Never destroys a live directory before its
# replacement is in place: the live one is renamed into the staging dir first, so a failure at
# any point leaves every previous state recoverable from STAGING_DIR. Returns non-zero on the
# first failed operation, leaving the caller to preserve the staging dir.
rollback() {
    local i entry target backup aside
    for (( i = ${#SWAPS[@]} - 1; i >= 0; i-- )); do
        entry="${SWAPS[$i]}"
        target="${entry%%|*}"
        backup="${entry#*|}"

        # A recorded backup path that does not exist means the swap never got past recording,
        # so the original directory is still live and must be left alone.
        if [[ -n "$backup" && ! -d "$backup" ]]; then
            continue
        fi

        if [[ -d "$target" ]]; then
            aside="$STAGING_DIR/replaced/$i"
            mkdir -p "$STAGING_DIR/replaced" || return 1
            mv -T "$target" "$aside" || return 1
        fi

        # An empty backup path means the target did not exist before this run, so moving it
        # aside is the whole rollback for that entry.
        if [[ -n "$backup" ]]; then
            mv -T "$backup" "$target" || return 1
        fi
    done
    SWAPS=()
}

cleanup() {
    local status=$?
    # Clear EXIT, but only ignore the terminating signals: the rollback below is a sequence of
    # renames and a second signal must not kill the script in the middle of it.
    trap - EXIT
    trap '' HUP INT TERM

    local rollback_failed=false
    if $ROLLBACK_ARMED; then
        ROLLBACK_ARMED=false
        if rollback; then
            echo "Restored the previous screenshot directories" >&2
        else
            rollback_failed=true
        fi
    fi

    if $rollback_failed; then
        echo "" >&2
        echo "ERROR: Rollback failed, the screenshot directories are in a mixed state." >&2
        echo "The previous directories are preserved, do NOT delete them:" >&2
        echo "  $STAGING_DIR" >&2
        if (( status == 0 )); then
            status=1
        fi
    elif [[ -n "$STAGING_DIR" ]]; then
        rm -rf "$STAGING_DIR"
        STAGING_DIR=""
    fi

    exit "$status"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

STAGING_DIR="$(mktemp -d "$FASTLANE_ROOT/.screenshot-staging.XXXXXXXX")"

for locale in "${!TOUCHED_LOCALES[@]}"; do
    for form_factor in "${!FORM_FACTOR_DIR[@]}"; do
        image_dir="${FORM_FACTOR_DIR[$form_factor]}"
        replacement="$STAGING_DIR/new/$locale/$image_dir"
        mkdir -p "$replacement"
        # Without --clean the replacement starts as a copy of the current directory, so files
        # that this run does not produce survive the swap.
        if ! $CLEAN && [[ -d "$FASTLANE_DIR/$locale/images/$image_dir" ]]; then
            cp -a "$FASTLANE_DIR/$locale/images/$image_dir/." "$replacement/"
        fi
    done
done

for entry in "${MANIFEST[@]}"; do
    source_png="${entry%%|*}"
    destination="${entry#*|}"
    cp -f "$source_png" "$STAGING_DIR/new/$destination"
done

ROLLBACK_ARMED=true
for locale in "${!TOUCHED_LOCALES[@]}"; do
    for form_factor in "${!FORM_FACTOR_DIR[@]}"; do
        image_dir="${FORM_FACTOR_DIR[$form_factor]}"
        target_dir="$FASTLANE_DIR/$locale/images/$image_dir"
        backup_dir=""
        if [[ -d "$target_dir" ]]; then
            backup_dir="$STAGING_DIR/backup/$locale/$image_dir"
            mkdir -p "$(dirname "$backup_dir")"
        fi
        # Recorded before the moves so a failure between them is still rolled back.
        SWAPS+=("$target_dir|$backup_dir")
        mkdir -p "$FASTLANE_DIR/$locale/images"
        # -T on every directory rename: a destination that unexpectedly exists must fail the
        # swap, never turn into a directory nested inside it.
        if [[ -n "$backup_dir" ]]; then
            mv -T "$target_dir" "$backup_dir"
        fi
        mv -T "$STAGING_DIR/new/$locale/$image_dir" "$target_dir"
    done
done

# Everything is in place; the backups inside the staging dir are no longer needed.
ROLLBACK_ARMED=false
SWAPS=()

echo ""
echo "=== Copy Complete ==="
echo "Locales: ${#TOUCHED_LOCALES[@]} | Copied: ${#MANIFEST[@]} images"
