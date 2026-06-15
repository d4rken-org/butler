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
OUTDIR="${OUTDIR:-/tmp/butler-demo/query-all-packages}"
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../_common.sh"

printf 'Butler — File Explorer\neu.darken.butler\n\nApp manager\n(QUERY_ALL_PACKAGES)' > "$OUTDIR/title.txt"
printf 'Searches and manages every\ninstalled app on the device.\n\nPackage data stays on-device\nand is never shared.' > "$OUTDIR/end.txt"

type_text() { "${ADB[@]}" shell input text "$1"; }

# ---- pre-state (off camera) -------------------------------------------------
echo "Pre-state: clean prior exports, show touches, clean tabs, reach picker…"
"${ADB[@]}" shell rm -f /sdcard/Download/*.apk >/dev/null 2>&1
"${ADB[@]}" shell settings put system show_touches 1
"${ADB[@]}" shell settings put system pointer_location 0
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 3
clean_tabs_to_picker

# ---- recorded flow ----------------------------------------------------------
rec_start

cap "Open the App Manager"
tap "Apps"; pause 3.5                       # enumerating all installed packages

cap "Every installed app — user and system"
pause 2.8                                   # header: user + system app counts

cap "Search across all installed apps"
tap "Search apps"; pause 1.0
type_text "auto"; pause 0.6
"${ADB[@]}" shell input keyevent 66; pause 0.6   # ENTER
back; pause 1.6                             # hide keyboard, keep filtered results

cap "Inspect any app in depth"
tap "Android Auto"; pause 2.4
swipe_up; pause 2.0                         # package id, version, SDK, storage paths

cap "Manage it: export APK, share, uninstall"
swipe_up; pause 2.2                         # the action buttons + components
swipe_up; pause 2.2

rec_stop
