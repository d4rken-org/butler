#!/usr/bin/env bash
# Record the All-files-access (MANAGE_EXTERNAL_STORAGE) declaration screencast.
# Demonstrates core file management of ARBITRARY, NON-MEDIA files/folders that
# SAF/MediaStore cannot serve: browse a deep non-media folder, bulk-move logs/
# configs/docs across directories, and search the whole tree at once.
#
# Usage: ./record.sh [adb-serial]   (default emulator-5564 / butler-main-2)
#        NOREC=1 ./record.sh ...     validate taps without recording
SERIAL="${1:-emulator-5564}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# Output lands next to the script, in this permission's own folder. The .gitignore
# here keeps *.mp4 out of the repository, so the videos stay local artifacts that
# sit with the declaration they belong to rather than in /tmp, where a reboot eats
# them.
OUTDIR="${OUTDIR:-$HERE}"
source "$HERE/../_common.sh"

printf 'Butler — File Explorer\neu.darken.butler\n\nAll files access\n(MANAGE_EXTERNAL_STORAGE)' > "$OUTDIR/title.txt"
printf 'Browse and manage arbitrary\nfiles and folders across the device,\nin tabbed workspaces.\n\nOn-device only — never uploaded.' > "$OUTDIR/end.txt"

# ---- pre-state (off camera) -------------------------------------------------
echo "Pre-state: reset demo files, deny permission, clean tabs, reach picker…"
# restore a known tree (earlier moves relocate the non-media files into Documents)
"${ADB[@]}" shell rm -f /sdcard/Documents/build.log /sdcard/Documents/config.json /sdcard/Documents/README.md >/dev/null 2>&1
"$LIBDIR/seed_demo_data.sh" "$SERIAL" >/dev/null 2>&1 || true
# seed a recent search so the searcher has a one-tap re-run (\.txt across the tree)
"${ADB[@]}" shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE deny
"${ADB[@]}" shell settings put system show_touches 1
"${ADB[@]}" shell settings put system pointer_location 0
"${ADB[@]}" shell am force-stop "$PKG"
# clear stale search history so the searcher shows a clean slate (debug build is debuggable).
# NOTE: call run-as with rm directly — a nested `sh -c '...'` gets flattened by adb and fails.
"${ADB[@]}" shell run-as "$PKG" rm -f databases/search_history.db databases/search_history.db-shm databases/search_history.db-wal >/dev/null 2>&1 || true
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 3
# A fresh install gates the app behind the welcome carousel and a tour overlay,
# and a tour can still surface later over a recorded shot. Clear both, and turn
# tours off, before touching tabs.
dismiss_onboarding
clean_tabs_to_picker

# ---- recorded flow ----------------------------------------------------------
rec_start

cap "Open a file explorer workspace"
tap "Explorer"; pause 2

cap "Storage is blocked without the permission"
tap "Device" || true; pause 1.6
tap "Internal shared storage" 0 -c; pause 2

cap "Grant Butler all-files access"
tap "Grant permission" || tap "Grant access"; pause 1.8
tap "Grant access" || true; pause 1.8
tap "Allow access to manage all files"; pause 1.8
back; pause 2.2

cap "Browse arbitrary folders, not just media"
tap "Projects"; pause 1.6
tap "butler-notes"; pause 2.0              # non-media: build.log, config.json, README.md

cap "Select non-media files: logs, configs, docs"
longpress "build.log"; pause 1.3
tap "config.json"; pause 0.9
tap "README.md"; pause 1.3

cap "Move them across the device"
tap "Cut"; pause 1.4
back; pause 1.2                            # up to Projects
back; pause 1.2                            # up to /sdcard
tap "Documents"; pause 1.6
tap "Paste" 0 -c; pause 2.2

# Whole-volume recursive search in a Search workspace. A single query scans every
# directory at once and returns hits from unrelated folders (Documents/Work and
# Download) — something per-folder SAF grants cannot do.
cap "Search the entire device at once"
tap "Butler mascot performing various animations"; pause 1
tap "Tab manager"; pause 1.4
tap "Add tab" 0 -c; pause 1.8             # creates a New (picker) tab in the background
tap "New " 0 -c; pause 1.8                # open the New card -> New-tab type picker
tap "Search"; pause 2.2                    # Search workspace, path defaults to /storage/emulated/0
tap "Filename"; pause 0.8                  # focus the query field
type_slow "report"; pause 0.6             # reliable per-character typing under record load
"${ADB[@]}" shell input keyevent 66; pause 4.5   # ENTER -> hits in Documents/Work + Download, let them linger

cap "Every workspace previews live in the tab manager"
tap "Butler mascot performing various animations"; pause 1
tap "Tab manager"; pause 4.5               # Explorer + Search previews render correctly now
pause 1.5

rec_stop
