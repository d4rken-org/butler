#!/usr/bin/env bash
# Record the App-manager (QUERY_ALL_PACKAGES) declaration screencast.
# QUERY_ALL_PACKAGES is install-time and always granted — there is no runtime
# grant to show; the video demonstrates the core feature that needs broad
# package visibility: an App Manager that lists and SEARCHES every installed
# app, then exports an APK that lands back in Butler's own file explorer.
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

printf 'Butler — File Explorer\neu.darken.butler\n\nApp manager\n(QUERY_ALL_PACKAGES)' > "$OUTDIR/title.txt"
printf 'Searches and manages every\ninstalled app on the device.\n\nNot collected, not used for\nanalytics or advertising.' > "$OUTDIR/end.txt"

# ---- pre-state (off camera) -------------------------------------------------
echo "Pre-state: clean prior exports, show touches, clean tabs, reach picker…"
"${ADB[@]}" shell rm -f /sdcard/Download/*.apk >/dev/null 2>&1
"${ADB[@]}" shell settings put system show_touches 1
"${ADB[@]}" shell settings put system pointer_location 0
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 3
# A fresh install gates the app behind the welcome carousel and a tour overlay,
# and a tour can still surface later over a recorded shot. Clear both, and turn
# tours off, before touching tabs.
dismiss_onboarding
clean_tabs_to_picker

# ---- recorded flow ----------------------------------------------------------
rec_start

cap "Open the App Manager"
tap "Apps"; pause 3.5                       # enumerating all installed packages

cap "Every installed app — user and system"
pause 2.8                                   # header: user + system app counts

cap "Search across all installed apps"
tap "Search apps"; pause 1.0
type_slow "auto"; pause 0.6                 # per-character typing is reliable under record load
"${ADB[@]}" shell input keyevent 66; pause 0.6   # ENTER
back; pause 1.6                             # hide keyboard, keep filtered results

cap "Inspect any app in depth"
tap "Android Auto"; pause 2.4
swipe_up; pause 2.2                         # package id, version, SDK, storage paths

# Export the selected app's APK. This opens a "Save as" file-manager workspace,
# the user saves it to shared storage, and the .apk then appears in Butler's own
# file explorer — tying the App Manager directly to the file-manager core.
cap "Export its APK to storage"
tap "Export APK"; pause 2.2                 # creates a Save-as workspace (new tab)
tap "Back to apps"; pause 2.2              # dismiss detail -> reveals the Save-as workspace
tap "Save"; pause 3.0                       # destination prefilled to Download -> "1 file saved"

cap "It lands in your file explorer"
tap "Open directory"; pause 3.4            # opens Explorer at Download, showing the .apk
pause 1.2

rec_stop
