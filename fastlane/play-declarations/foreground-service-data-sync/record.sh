#!/usr/bin/env bash
# Record the foreground-service (FOREGROUND_SERVICE_DATA_SYNC / dataSync)
# declaration screencast.
#
# The Play form asks for a video demonstrating the permission "for the tasks
# you've selected", and two tasks are ticked, so the video covers both:
#
#   Local processing > Other              a copy the user started keeps running
#                                         after they leave the app
#   Local processing > Importing, exporting   a file another app shares into
#                                         Butler is written where the user picks
#
# Both halves show the same three things, which is what justifies the type: the
# service exists ONLY while the user is away, it reports live progress, and it
# can be cancelled from the notification.
#
# Timing is the hard part on an emulator, and the obvious levers both fail.
# Raw storage here runs at about 1.3 GB/s, so bulk alone does nothing: 512 MB
# copies in 0.6 s with `cp`. Sparse files are worse than useless, because they
# read back as zeros. Sheer file count looked promising (4000 files kept the
# service alive 25 s, 9000 kept it 49 s) but those were cold-cache numbers, and
# on any repeat run the guest page cache made even 30000 files finish in
# seconds.
#
# What holds up is Butler's own throughput. It copies at roughly 53 MB/s through
# its gateway and progress tracking, an order of magnitude below raw `cp`, and
# that rate does not care about the page cache. Measured on this emulator it is
# about 240 MB/s, so 3000 files of 1 MB is roughly 12 s of copying. Real files,
# real bytes, a real copy; only the folder itself is staged.
#
# 12 s is not much, and the disk cannot hold a tree big enough to buy more (the
# copy needs room for a second full tree). So the beat is written for minimum
# latency instead: Home, shade, assert, in that order, with nothing avoidable in
# between. Every UIAutomator dump costs a second or more, and each one spent
# before the shade is open is a second of the window gone. The save side is the opposite case: the Saver
# streams one file at roughly 125 MB/s, so a 2 GB file gives about 16 s. Both
# numbers are real work on real bytes; nothing here is staged.
#
# Usage: ./record.sh [adb-serial]   (default emulator-5584)
#        NOREC=1 ./record.sh ...     validate taps without recording
SERIAL="${1:-emulator-5584}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# Output lands next to the script, in this permission's own folder. The .gitignore
# here keeps *.mp4 out of the repository, so the videos stay local artifacts that
# sit with the declaration they belong to rather than in /tmp, where a reboot eats
# them.
OUTDIR="${OUTDIR:-$HERE}"
source "$HERE/../_common.sh"

DEMO_ROOT=/sdcard/FGSdemo
COPY_SRC="$DEMO_ROOT/Project-archive"
COPY_DEST="$DEMO_ROOT/Backup"
IMPORT_SRC=/sdcard/Documents/Field-recording.wav
COPY_FILES="${COPY_FILES:-3000}"
COPY_FILE_SIZE="${COPY_FILE_SIZE:-1M}"
IMPORT_SIZE_MB="${IMPORT_SIZE_MB:-2048}"

printf 'Butler — File Explorer\neu.darken.butler\n\nFinishing your file operations\nafter you leave the app\n(FOREGROUND_SERVICE_DATA_SYNC)' > "$OUTDIR/title.txt"
printf 'The service runs only while\nan operation you started is\nstill finishing.\n\nThese file operations run\nentirely on your device.' > "$OUTDIR/end.txt"

die_rec() {
  echo "ABORT (recording): $1" >&2
  "${ADB[@]}" shell pkill -INT screenrecord 2>/dev/null || true
  exit 1
}

# ---- shade helpers ----------------------------------------------------------
# Whether the shade is open has to come from the window manager, never from the
# UI dump. Labels like "Collapse" and "Clear all" also exist inside Butler
# itself, so matching them against a dump confuses the app's own screen for the
# shade, and acting on that mistake means tapping the app's controls. The
# NotificationShade window's mViewVisibility is unambiguous: 0x0 visible,
# 0x4 invisible.
shade_open() {
  "${ADB[@]}" shell dumpsys window windows 2>/dev/null \
    | grep -A12 "NotificationShade" | grep -q "mViewVisibility=0x0"
}
open_shade() {
  local i
  for i in 1 2 3; do
    "${ADB[@]}" shell cmd statusbar expand-notifications >/dev/null 2>&1 \
      || "${ADB[@]}" shell input swipe 360 1 360 1450 500
    pause 1.0
    shade_open && return 0
  done
  return 1
}
close_shade() {
  local i
  for i in 1 2 3 4; do
    "${ADB[@]}" shell cmd statusbar collapse >/dev/null 2>&1 || back
    pause 1.0
    shade_open || return 0
  done
  return 1
}
# There is deliberately no "clear all notifications" step. Butler's own stale
# notifications go away with the force-stop in the pre-state, and everything
# else in a real device's shade is ongoing system state (charging, Bluetooth,
# Wi-Fi) that no tap dismisses and no adb command cancels: `service call
# notification 1` is not a valid transaction on Android 12. Earlier revisions
# tried to tap "Clear all" out of a UI dump, which is worse than useless,
# because Butler has a "Clear all" of its own and the tap landed on the app.
# A reviewer's own phone has a populated shade too; what matters is that
# Butler's operation notification is in it.
# Read the notification from the system, not from the screen. `uiautomator dump`
# returns the FOCUSED window, and with the shade pulled down the focused window
# is still the launcher on a real device, so the shade's contents are invisible
# to it (they were visible on an emulator, which is what made this look fine for
# a while). The recording captures the shade regardless; only the assertions
# needed a different source. dumpsys carries the real title, text and subText.
notif_text() {
  "${ADB[@]}" shell dumpsys notification --noredact 2>/dev/null
}
# No pipe into grep here, deliberately. _common.sh sets `pipefail`, and `grep -q`
# exits the moment it matches, which SIGPIPEs dumpsys and makes the pipeline
# report failure precisely when the text WAS found. Match in the shell instead.
notif_has() {
  local out; out="$(notif_text)"
  case "$out" in *"$1"*) return 0 ;; *) return 1 ;; esac
}
fgs_up() {
  "${ADB[@]}" shell dumpsys activity services "$PKG" 2>/dev/null | grep -q "isForeground=true"
}

# ---- pre-state (off camera) -------------------------------------------------
echo "Pre-state: seed the demo tree, clear tabs, land on the copy source…"
close_shade || die_rec "the notification shade would not close before recording"

# Stop the app and let any operation from an earlier run drain BEFORE touching
# the tree. A copy still running holds the destination open, so `rm -rf` fails
# with "Directory not empty" and the next copy then resolves as a conflict
# instead of starting.
"${ADB[@]}" shell am force-stop "$PKG"
for i in $(seq 1 30); do fgs_up || break; pause 1.0; done
fgs_up && die_rec "an operation from an earlier run is still going; wait for it to finish"
# The destination must start empty every run: a second copy over an existing
# tree resolves as conflicts and finishes instantly, which silently destroys the
# whole point of the shot.
"${ADB[@]}" shell rm -rf "$COPY_DEST"
"${ADB[@]}" shell mkdir -p "$COPY_SRC" "$COPY_DEST" /sdcard/Documents

# Many small files, created in ONE device-side shell: an adb round trip per file
# would take minutes. These are real files with real bytes, not sparse stubs,
# because a sparse file reads back as zeros and copies in no time at all.
# Seeding dominates the runtime, so an already-correct tree is reused; set
# RESEED=1 to force it.
seeded="$("${ADB[@]}" shell "ls $COPY_SRC 2>/dev/null | wc -l" | tr -d '\r')"
if [ "${RESEED:-0}" = 1 ] || [ "${seeded:-0}" -lt "$COPY_FILES" ]; then
  echo "Seeding $COPY_FILES files (this takes a few minutes)…"
  "${ADB[@]}" shell "rm -rf $COPY_SRC; mkdir -p $COPY_SRC"
  "${ADB[@]}" shell "for i in \$(seq 1 $COPY_FILES); do dd if=/dev/zero of=$COPY_SRC/clip-\$i.wav bs=$COPY_FILE_SIZE count=1 2>/dev/null; done"
  seeded="$("${ADB[@]}" shell "ls $COPY_SRC | wc -l" | tr -d '\r')"
fi
[ "${seeded:-0}" -ge "$COPY_FILES" ] \
  || die_rec "only $seeded of $COPY_FILES source files were seeded"
# The copy writes a second full tree, so the free space has to cover the source
# ONCE more (the source itself is already on disk), plus a little headroom.
# Running out mid-copy would end the shot on an error dialog.
avail_kb="$("${ADB[@]}" shell "df /sdcard | tail -1 | awk '{print \$4}'" | tr -d '\r')"
need_kb=$(( COPY_FILES * 1024 * 11 / 10 ))
[ "${avail_kb:-0}" -gt "$need_kb" ] \
  || die_rec "only ${avail_kb}kB free on /sdcard, need about ${need_kb}kB to copy the tree"

# One large real file for the import half.
want_bytes=$(( IMPORT_SIZE_MB * 1024 * 1024 ))
have_bytes="$("${ADB[@]}" shell "stat -c %s $IMPORT_SRC 2>/dev/null" | tr -d '\r')"
if [ "${have_bytes:-0}" != "$want_bytes" ]; then
  "${ADB[@]}" shell "dd if=/dev/zero of=$IMPORT_SRC bs=1M count=$IMPORT_SIZE_MB 2>/dev/null"
fi
"${ADB[@]}" shell "ls -l $IMPORT_SRC" >/dev/null 2>&1 || die_rec "the import source was not seeded"

"${ADB[@]}" shell settings put system show_touches 1
"${ADB[@]}" shell settings put system pointer_location 0
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 4
dismiss_onboarding
clean_tabs_to_picker
# Tours are not only a first-run thing: opening the tab picker raises one of its
# own on a device where they are still enabled, and it covers the tile grid.
dismiss_onboarding
tap "Explorer" || die_rec "the tab picker offered no Explorer tile"
pause 3
# Navigate to the demo root off camera so the recording opens on the source.
tap "Device"; pause 2.0
tap "Internal shared storage"; pause 2.2
tap "FGSdemo" 0 -c || die_rec "the seeded demo folder is not visible in Butler"
pause 2.2
dump
_find -c "$UIX" "Project-archive" >/dev/null \
  || die_rec "the copy source is not visible in Butler"

# ---- recorded flow ----------------------------------------------------------
rec_start

# ===== Scenario A: a copy keeps running after the user leaves ================
cap "Start a copy of a large folder"
dump
sel=$(_find -c "$UIX" "Project-archive")
[ -n "$sel" ] || die_rec "the copy source vanished before selection"
"${ADB[@]}" shell input swipe $sel $sel 900     # long-press -> selection mode
pause 1.6
tap "Copy" || die_rec "selection mode offered no Copy action"
pause 1.6
tap "Backup" 0 -c || die_rec "the destination folder is not visible"
pause 2.2
tap "Paste" || die_rec "the destination offered no Paste action"

# Leave IMMEDIATELY. Everything between Paste and Home is time the copy spends
# finishing while still on screen, and a UIAutomator dump alone costs a second or
# more. An earlier revision asserted the in-app operation state here and lost
# most of the window doing it. There is deliberately no foreground service to
# check yet either: while Butler is on screen the work runs in the in-app
# operations bar and posts no notification at all. The service is what takes
# over on leaving, which is exactly what the next beat films.
cap "Leave the app while it is still running"
pause 0.5
# Home and the shade in ONE adb invocation. Each round trip costs a few hundred
# milliseconds and the copy is finishing the whole time, so the two are chained
# device-side rather than issued separately.
"${ADB[@]}" shell "input keyevent KEYCODE_HOME; cmd statusbar expand-notifications"
pause 0.6

cap "Progress and Cancel in the notification"
# The shade is already open, chained onto the Home keyevent above.
open_shade || die_rec "the notification shade would not open"
# The service starts on backgrounding and posts its notification a beat later,
# so wait for it rather than sampling once. Waiting is free here: the shade is
# already on screen and this is the shot we want to hold anyway.
# On a real device the service can take a good few seconds to come up and post,
# noticeably longer than on an emulator, so this waits generously.
for i in $(seq 1 30); do
  notif_has "Copy operation" && break
  pause 0.7
done
notif_has "Copy operation" \
  || die_rec "no Butler copy notification while the copy was running"
notif_has "Project-archive" \
  || die_rec "the copy notification does not name what is being copied"
notif_has "android.subText=String (" \
  || die_rec "the copy notification carries no item counter"
fgs_up || die_rec "no foreground service while the copy was still running"
# Hold on the shade: this is the shot that justifies the permission, so give a
# viewer time to read the operation name, the counter and the Cancel action.
pause 6.0

cap "Back in the app, the service stands down"
close_shade || die_rec "the notification shade would not close"
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 2.5
# With the app on screen again the service must retire, and the copy must carry
# on in the in-app operations bar instead. That boundary is the whole claim:
# the service exists only while the user is away.
for i in $(seq 1 25); do fgs_up || break; pause 1.0; done
fgs_up && die_rec "the foreground service outlived the app returning to the foreground"
pause 2.5

# ===== Scenario B: importing a file another app shares in ====================
# Optional, because it depends on the system Files app, whose package name and
# activity differ between builds (AOSP ships com.android.documentsui, a Pixel
# ships com.google.android.documentsui, and "Files by Google" is a third thing
# again). Set SCENARIO_B=0 to record the copy scenario alone, which on its own
# covers the "Local processing > Other" task.
if [ "${SCENARIO_B:-1}" = 1 ]; then
cap "Another app shares a large file to Butler"
"${ADB[@]}" shell am start -n com.android.documentsui/.files.FilesActivity >/dev/null 2>&1
pause 2.6
dev_root="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
dump
if _find "$UIX" "Show roots" >/dev/null; then tap "Show roots"; pause 1.6; fi
dump
if   _find -c "$UIX" "$dev_root" >/dev/null;        then tap "$dev_root" 0 -c; pause 2.4
elif _find -c "$UIX" "Internal storage" >/dev/null; then tap "Internal storage" 0 -c; pause 2.4
else die_rec "the system Files app offered no internal-storage root"
fi
# "Documents" is ambiguous between the by-type root chip and the folder row, and
# the by-type root is a MediaProvider view that would not list this file.
docs_ok=0
for di in 0 1 2 3; do
  dump
  xy=$(_find "$UIX" "Documents" "$di")
  [ -z "$xy" ] && break
  "${ADB[@]}" shell input tap $xy; pause 2.2
  dump
  if _find -c "$UIX" "Field-recording" >/dev/null; then docs_ok=1; break; fi
  "${ADB[@]}" shell am start -n com.android.documentsui/.files.FilesActivity >/dev/null 2>&1
  pause 2.0
  dump
  if _find "$UIX" "Show roots" >/dev/null; then tap "Show roots"; pause 1.6; fi
  tap "$dev_root" 0 -c; pause 2.2
done
[ "$docs_ok" = 1 ] || die_rec "the shared file is not reachable in the system Files app"

dump
sel=$(_find -c "$UIX" "Field-recording")
[ -n "$sel" ] || die_rec "the import source is not visible"
"${ADB[@]}" shell input swipe $sel $sel 900
pause 1.6
dump
if _find "$UIX" "Share" >/dev/null; then
  tap "Share"; pause 2.0
elif _find "$UIX" "More options" >/dev/null; then
  tap "More options"; pause 1.4
  tap "Share" || die_rec "the system Files app offered no Share action"
  pause 2.0
else
  die_rec "the system Files app offered no Share action"
fi
# Only a chooser needs a tap; with Butler the sole handler the system goes
# straight to Butler's arrival dialog.
dump
if _find -c "$UIX" "Save as" >/dev/null; then :
elif _find -c "$UIX" "Butler" >/dev/null; then tap "Butler" 0 -c; pause 2.2
else die_rec "the share reached neither a chooser nor Butler's arrival dialog"
fi

cap "Save it with Butler, then leave"
tap "Save as" 0 -c || die_rec "Butler's arrival dialog offered no Save as action"
pause 2.2
dump
_find "$UIX" "Destination" >/dev/null || die_rec "Butler's Saver did not open"
tap "Save" || die_rec "the Saver's Save action was not found"
pause 1.2
"${ADB[@]}" shell input keyevent KEYCODE_HOME
pause 1.8
fgs_up || die_rec "no foreground service while the import was still saving"

cap "The same service covers the import"
open_shade
# Same reasoning as scenario A: read the notification from dumpsys, not from the
# screen, and hold long enough for a viewer to read it.
for i in $(seq 1 30); do
  notif_has "Save files" && break
  pause 0.7
done
notif_has "Save files" \
  || die_rec "no Butler save notification while the import was running"
fgs_up || die_rec "no foreground service while the import was still saving"
pause 6.0
close_shade

cap "Back in the app, the save finishes"
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 2.5
# The service must be gone now that the app is on screen again, and the save
# must actually have completed.
for i in $(seq 1 20); do fgs_up || break; pause 1.0; done
fgs_up && die_rec "the foreground service outlived the app returning to the foreground"
for i in $(seq 1 20); do
  dump
  _find -c "$UIX" "Successful" >/dev/null && break
  _find -c "$UIX" "1 file saved" >/dev/null && break
  pause 1.0
done
dump
if ! _find -c "$UIX" "Successful" >/dev/null && ! _find -c "$UIX" "1 file saved" >/dev/null; then
  die_rec "the import never reported success in the app"
fi
pause 2.0
fi

# ---- duration budget --------------------------------------------------------
# Same arithmetic as the request-install recorder: screenrecord is already up
# about 1.2s before REC_T0, rec_stop adds its stop and pull, and postprocess.sh
# concatenates 3s of title and 3.5s of end card. 108s of flow lands the finished
# file near 116s against Google's 120s ceiling. Abort rather than save a video
# that cannot be submitted.
flow_elapsed=$(
  awk -v now="$(date +%s.%N)" -v start="$REC_T0" \
    'BEGIN { printf "%.3f", now - start }'
)
awk -v elapsed="$flow_elapsed" 'BEGIN { exit !(elapsed < 108) }' \
  || die_rec "recorded flow took ${flow_elapsed}s; final video would risk exceeding 2 minutes"

rec_stop
