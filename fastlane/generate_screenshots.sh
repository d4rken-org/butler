#!/usr/bin/env bash
set -euo pipefail

# Generates localized Play Store screenshots in batches to work around the layoutlib
# ImagePoolImpl memory leak (rendered images accumulate without being released).
#
# Locales come from fastlane/screenshots/locales.txt, the single source of truth.
# Each locale renders 24 images (8 screens x 3 form factors).
#
# Usage:
#   ./fastlane/generate_screenshots.sh              # All locales
#   ./fastlane/generate_screenshots.sh --english    # en-US only, the commit-refresh path
#   ./fastlane/generate_screenshots.sh --smoke      # 6 locales covering LTR, RTL, CJK
#   ./fastlane/generate_screenshots.sh --batch-size 2

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOCALES_LIST="$SCRIPT_DIR/screenshots/locales.txt"
LOCALES_FILE="$PROJECT_DIR/app/src/screenshotTest/kotlin/eu/darken/butler/screenshots/PlayStoreLocales.kt"
REF_DIR="$PROJECT_DIR/app/src/screenshotTestGplayDebug/reference"

# 8 screens x 3 form factors.
RENDERS_PER_LOCALE=24

# One locale per batch. 24 renders already hold a few hundred MB of leaked pixels in the
# render JVM, so do not raise this without checking memory first.
BATCH_SIZE=1
SMOKE=false
ENGLISH_ONLY=false

# Fastlane directories of the smoke subset.
SMOKE_TARGETS=("en-US" "de-DE" "ja-JP" "ar" "zh-CN" "pt-BR")

while [[ $# -gt 0 ]]; do
    case "$1" in
        --smoke) SMOKE=true; shift ;;
        --english) ENGLISH_ONLY=true; shift ;;
        --batch-size) BATCH_SIZE="${2:-}"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

if $SMOKE && $ENGLISH_ONLY; then
    echo "ERROR: --smoke and --english are mutually exclusive" >&2
    exit 1
fi

if ! [[ "$BATCH_SIZE" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: --batch-size must be a positive integer, got '$BATCH_SIZE'" >&2
    exit 1
fi

# --- Read and validate the locale list before touching any source file ------------------

if [[ ! -f "$LOCALES_LIST" ]]; then
    echo "ERROR: Locale list not found: $LOCALES_LIST" >&2
    exit 1
fi

ALL_QUALIFIERS=()
ALL_DIRS=()
INVALID=0
LINE_NO=0

while IFS= read -r raw_line || [[ -n "$raw_line" ]]; do
    LINE_NO=$(( LINE_NO + 1 ))
    line="${raw_line%%#*}"

    fields=()
    read -r -a fields <<< "$line"
    if (( ${#fields[@]} == 0 )); then
        continue
    fi
    if (( ${#fields[@]} != 2 )); then
        echo "ERROR: $LOCALES_LIST:$LINE_NO expected 2 fields, got ${#fields[@]}: '$line'" >&2
        INVALID=1
        continue
    fi

    ALL_QUALIFIERS+=("${fields[0]}")
    ALL_DIRS+=("${fields[1]}")
done < "$LOCALES_LIST"

if (( ${#ALL_QUALIFIERS[@]} == 0 )); then
    echo "ERROR: $LOCALES_LIST contains no locales" >&2
    exit 1
fi

DUPE_QUALIFIERS="$(printf '%s\n' "${ALL_QUALIFIERS[@]}" | sort | uniq -d)"
if [[ -n "$DUPE_QUALIFIERS" ]]; then
    echo "ERROR: duplicate android qualifiers in $LOCALES_LIST:" >&2
    echo "$DUPE_QUALIFIERS" >&2
    INVALID=1
fi

DUPE_DIRS="$(printf '%s\n' "${ALL_DIRS[@]}" | sort | uniq -d)"
if [[ -n "$DUPE_DIRS" ]]; then
    echo "ERROR: duplicate fastlane directories in $LOCALES_LIST:" >&2
    echo "$DUPE_DIRS" >&2
    INVALID=1
fi

EN_COUNT="$(printf '%s\n' "${ALL_DIRS[@]}" | grep -c '^en-US$' || true)"
if (( EN_COUNT != 1 )); then
    echo "ERROR: $LOCALES_LIST must contain exactly one en-US entry, found $EN_COUNT" >&2
    INVALID=1
fi

if (( INVALID != 0 )); then
    exit 1
fi

# --- Select the locales for this run ----------------------------------------------------

QUALIFIERS=()
DIRS=()

select_dirs() {
    local wanted
    for wanted in "$@"; do
        local found=false
        local i
        for (( i = 0; i < ${#ALL_DIRS[@]}; i++ )); do
            if [[ "${ALL_DIRS[$i]}" == "$wanted" ]]; then
                QUALIFIERS+=("${ALL_QUALIFIERS[$i]}")
                DIRS+=("${ALL_DIRS[$i]}")
                found=true
                break
            fi
        done
        if ! $found; then
            echo "ERROR: '$wanted' is not listed in $LOCALES_LIST" >&2
            exit 1
        fi
    done
}

if $ENGLISH_ONLY; then
    select_dirs "en-US"
elif $SMOKE; then
    select_dirs "${SMOKE_TARGETS[@]}"
else
    QUALIFIERS=("${ALL_QUALIFIERS[@]}")
    DIRS=("${ALL_DIRS[@]}")
fi

TOTAL=${#DIRS[@]}
NUM_BATCHES=$(( (TOTAL + BATCH_SIZE - 1) / BATCH_SIZE ))
EXPECTED=$(( TOTAL * RENDERS_PER_LOCALE ))

echo "=== Localized Screenshot Generation ==="
echo "Locales: $TOTAL | Renders per locale: $RENDERS_PER_LOCALE | Batch size: $BATCH_SIZE | Batches: $NUM_BATCHES"
echo ""

# --- Serialize with peer agents ---------------------------------------------------------

# Both the generated source file and the reference output directory are rewritten per run,
# and `gradlew --stop` reaches every daemon of this user, so runs must not overlap.
LOCK_FILE="${TMPDIR:-/tmp}/butler-screenshot-generation.lock"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
    echo "Another screenshot generation is running, waiting for it to finish..."
    flock 9
fi

# --- Back up the tracked source we are about to rewrite ---------------------------------

# The annotation file is generated in place: the checked-in PlayStoreLocales.kt declares the
# same annotation classes, so a generated copy on an extra source root would be a duplicate
# declaration. Backup lives in a unique temp dir so a peer run cannot collide with it.
BACKUP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/butler-screenshots.XXXXXXXX")"
BACKUP_FILE="$BACKUP_DIR/PlayStoreLocales.kt"
cp -p "$LOCALES_FILE" "$BACKUP_FILE"

# The in-progress codegen temp file, tracked so a signal cannot leave it in the tracked
# source directory.
TMP_LOCALES_FILE=""

cleanup() {
    local status=$?
    # Clear EXIT, but only ignore the terminating signals: restoring the tracked source is a
    # multi-step operation and a second signal must not kill the script in the middle of it.
    trap - EXIT
    trap '' HUP INT TERM

    if [[ -n "$TMP_LOCALES_FILE" ]]; then
        rm -f "$TMP_LOCALES_FILE"
        TMP_LOCALES_FILE=""
    fi

    # No backup means the restore already ran; a repeated call is a no-op.
    if [[ -f "$BACKUP_FILE" ]]; then
        local restore_tmp=""
        # Restore through a temp file beside the target so the replacement itself is a single
        # rename: the tracked file is never observed truncated or half written.
        if restore_tmp="$(mktemp "$(dirname "$LOCALES_FILE")/.PlayStoreLocales.kt.XXXXXX")" &&
            cp -p "$BACKUP_FILE" "$restore_tmp" &&
            mv -f "$restore_tmp" "$LOCALES_FILE"; then
            rm -rf "$BACKUP_DIR"
            echo "Restored original PlayStoreLocales.kt"
        else
            if [[ -n "$restore_tmp" ]]; then
                rm -f "$restore_tmp"
            fi
            echo "" >&2
            echo "ERROR: Failed to restore $LOCALES_FILE" >&2
            echo "The original file is preserved at: $BACKUP_FILE" >&2
            echo "Restore it manually: cp '$BACKUP_FILE' '$LOCALES_FILE'" >&2
            if (( status == 0 )); then
                status=1
            fi
        fi
    fi

    exit "$status"
}

# Signals only set the exit status; the restore itself happens once, in the EXIT trap. Without
# the explicit exits a signal would return into the batch loop and rewrite the source file again.
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

rm -rf "$REF_DIR"
echo "Cleaned reference directory"

# --- Codegen ----------------------------------------------------------------------------

generate_locales_file() {
    local start="$1"
    local count="$2"
    local target_dir
    target_dir="$(dirname "$LOCALES_FILE")"
    local tmp_file
    tmp_file="$(mktemp "$target_dir/.PlayStoreLocales.kt.XXXXXX")"
    TMP_LOCALES_FILE="$tmp_file"

    {
        cat << 'HEADER'
package eu.darken.butler.screenshots

import androidx.compose.ui.tooling.preview.Preview

/**
 * Locale previews rendered on the phone device spec.
 *
 * The three annotation classes below differ only in the device spec they pin - the form factor of a
 * screenshot comes from the test function that carries the annotation, never from the preview
 * [name]. [name] stays the plain fastlane metadata directory so the copy script can keep reading
 * the locale straight out of the file name.
 *
 * Generated by `fastlane/generate_screenshots.sh`; the checked-in state is en-US only.
 */
HEADER
        emit_previews "$start" "$count" "DS_PHONE"
        echo "annotation class PlayStoreLocalesPhone"
        echo ""
        echo "/** Locale previews rendered on the 7\" tablet device spec. See [PlayStoreLocalesPhone]. */"
        emit_previews "$start" "$count" "DS_SEVEN"
        echo "annotation class PlayStoreLocalesSeven"
        echo ""
        echo "/** Locale previews rendered on the 10\" tablet device spec. See [PlayStoreLocalesPhone]. */"
        emit_previews "$start" "$count" "DS_TEN"
        echo "annotation class PlayStoreLocalesTen"
    } > "$tmp_file"

    mv -f "$tmp_file" "$LOCALES_FILE"
    TMP_LOCALES_FILE=""
}

emit_previews() {
    local start="$1"
    local count="$2"
    local spec="$3"
    local i
    for (( i = start; i < start + count; i++ )); do
        echo "@Preview(locale = \"${QUALIFIERS[$i]}\", name = \"${DIRS[$i]}\", device = $spec, showSystemUi = true)"
    done
}

# --- Render -----------------------------------------------------------------------------

cd "$PROJECT_DIR"

for (( batch = 0; batch < NUM_BATCHES; batch++ )); do
    start=$(( batch * BATCH_SIZE ))
    end=$(( start + BATCH_SIZE ))
    if (( end > TOTAL )); then
        end=$TOTAL
    fi
    batch_num=$(( batch + 1 ))

    echo "--- Batch $batch_num/$NUM_BATCHES (locales $((start + 1))-$end of $TOTAL) ---"

    generate_locales_file "$start" "$(( end - start ))"

    # Release the memory the previous batch leaked
    echo "Stopping Gradle daemon..."
    ./gradlew --stop > /dev/null 2>&1 || true

    echo "Generating screenshots..."
    # --rerun-tasks: after the reference directory is wiped the update task still considers
    # itself up to date and would silently render nothing.
    if ! ./gradlew :app:updateGplayDebugScreenshotTest --no-daemon --rerun-tasks 2>&1; then
        echo "ERROR: Batch $batch_num failed! Check output above." >&2
        echo "Images generated so far are preserved in: $REF_DIR" >&2
        exit 1
    fi

    count=$(find "$REF_DIR" -name "*.png" 2>/dev/null | wc -l)
    batch_expected=$(( end * RENDERS_PER_LOCALE ))
    if (( count != batch_expected )); then
        echo "ERROR: Batch $batch_num produced $count images, expected $batch_expected." >&2
        echo "Check $REF_DIR for details." >&2
        exit 1
    fi
    echo "Batch $batch_num complete. Total images so far: $count"
    echo ""
done

FINAL_COUNT=$(find "$REF_DIR" -name "*.png" 2>/dev/null | wc -l)

echo "=== Generation Complete ==="
echo "Generated: $FINAL_COUNT images (expected: $EXPECTED)"

if (( FINAL_COUNT != EXPECTED )); then
    echo "ERROR: Count mismatch, some screenshots are missing." >&2
    exit 1
fi

echo ""
echo "Next step: run ./fastlane/copy_screenshots.sh to sort into fastlane directories."
