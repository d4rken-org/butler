#!/usr/bin/env bash
# Record the APK-install (REQUEST_INSTALL_PACKAGES) declaration screencast.
# The permitted use requires BOTH halves of the core functionality on camera, so
# the video shows the package being received (the system Files app shares an APK
# into Butler, and Butler's save-as files it into Download) and then the
# user-gated install flow: picking the APK and choosing "Open with", allowing
# Butler as an install source (Android's per-source "Install unknown apps" grant,
# the policy-critical shot), and confirming in the system installer.
#
# Runs against a bare AOSP emulator (no Play Protect dialogs). The install-source
# grant MUST start pristine so the grant flow is captured; the pre-state VERIFIES
# this and ABORTS otherwise (it cannot be reset via `appops set` — reinstall
# Butler or wipe the emulator to restore it). The installed app is CapOd — same
# developer, pinned URL + SHA-256, so no third-party brand appears. No self-update
# fallback: if the APK can't be verified, we abort instead of recording a
# confusing "update existing app?" dialog.
#
# Usage: ./record.sh [adb-serial]   (default emulator-5578 / butler-install-demo)
#        NOREC=1 ./record.sh ...     validate taps without recording
SERIAL="${1:-emulator-5578}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# Output lands next to the script, in this permission's own folder. The .gitignore
# here keeps *.mp4 out of the repository, so the videos stay local artifacts that
# sit with the declaration they belong to rather than in /tmp, where a reboot eats
# them.
OUTDIR="${OUTDIR:-$HERE}"
source "$HERE/../_common.sh"

DEMO_PKG="eu.darken.capod"
DEMO_APK="${DEMO_APK:-/tmp/butler-demo/capod.apk}"
DEMO_APK_URL="https://github.com/d4rken-org/capod/releases/download/v5.2.1-rc0/eu.darken.capod-v5.2.1-rc0-50201000-FOSS-RELEASE.apk"
DEMO_APK_SHA256="e64ffdb7705bc111cb1023b523b322b00d537c104ea0eb95b10104897990fa5c"
DEMO_APK_NAME="CapOd-5.2.1.apk"

printf 'Butler — File Explorer\neu.darken.butler\n\nOpening a user-selected APK\nin Android'"'"'s package installer\n(REQUEST_INSTALL_PACKAGES)' > "$OUTDIR/title.txt"
printf 'Butler only opens\nAPKs you selected,\nin Android'"'"'s installer.\n\nApproval and install\nconfirmation stay\nunder your control.' > "$OUTDIR/end.txt"

# ---- demo APK: pinned download, verified, no fallback -----------------------
if [ ! -f "$DEMO_APK" ]; then
  echo "Downloading demo APK → $DEMO_APK"
  curl -sL -o "$DEMO_APK" "$DEMO_APK_URL" || { echo "download failed — aborting"; exit 1; }
fi
echo "$DEMO_APK_SHA256  $DEMO_APK" | sha256sum -c - >/dev/null 2>&1 \
  || { echo "SHA-256 mismatch for $DEMO_APK — aborting"; exit 1; }

# ---- abort helper -----------------------------------------------------------
# Abort without saving a bad video: stop screenrecord and exit non-zero, leaving
# the on-device clip unpulled so no invalid declaration.mp4 is produced. Used to
# enforce that the policy-critical shots actually happened. Defined before the
# pre-state so the off-camera setup can use it too; the pkill is a no-op while
# nothing is recording yet.
die_rec() {
  echo "ABORT (recording): $1" >&2
  "${ADB[@]}" shell pkill -INT screenrecord 2>/dev/null || true
  exit 1
}

# ---- pre-state (off camera) -------------------------------------------------
echo "Pre-state: seed APK for the sharing app, verify pristine install-source grant, clean tabs, open the system Files app…"
"${ADB[@]}" shell pm uninstall "$DEMO_PKG" >/dev/null 2>&1
"${ADB[@]}" shell rm -f "/sdcard/Download/*.apk" >/dev/null 2>&1
"${ADB[@]}" shell rm -f "/sdcard/Documents/$DEMO_APK_NAME" >/dev/null 2>&1
# The APK is seeded into Documents, NOT into Download: the first recorded beat is
# another app sharing it into Butler, which then saves it into Download. That is
# the "receiving app packages" half of the permitted use. Seeding it straight to
# Download would put that step off camera, and Butler's save would collide with
# the already-present file instead of writing a new one.
"${ADB[@]}" shell mkdir -p "/sdcard/Documents" >/dev/null 2>&1
"${ADB[@]}" push "$DEMO_APK" "/sdcard/Documents/$DEMO_APK_NAME" >/dev/null
# The REQUEST_INSTALL_PACKAGES app-op MUST start ungranted so the source-approval
# shot (the policy-critical moment) is captured. It cannot be reset back to the
# first-prompt state with `appops set …`: once the source has responded to the
# prompt, `default` mode is denied without re-prompting. Reinstalling Butler
# (`pm uninstall` + `install`) DOES restore the pristine "No operations" state;
# a data-wiped emulator also works. ABORT (don't warn-and-continue) if the op is
# already granted/handled — otherwise we would silently record a video missing
# the grant, which fails the declaration.
grant_state="$("${ADB[@]}" shell appops get "$PKG" REQUEST_INSTALL_PACKAGES)"
case "$grant_state" in
  *"No operations"*)
    : ;;                                                     # pristine: the grant dialog will show
  *)
    echo "ABORT: REQUEST_INSTALL_PACKAGES is not pristine (got: ${grant_state%%$'\n'*})." >&2
    echo "       The source-approval shot would be skipped. Reset first, e.g.:" >&2
    echo "         adb -s $SERIAL uninstall $PKG && adb -s $SERIAL install -g <butler.apk>" >&2
    echo "       then re-run this recorder." >&2
    exit 1 ;;
esac
# all-files access is NOT what this video is about — grant it off camera
"${ADB[@]}" shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
"${ADB[@]}" shell settings put system show_touches 1
"${ADB[@]}" shell settings put system pointer_location 0
"${ADB[@]}" shell am force-stop "$PKG"
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 3
# First-run onboarding and tours. Tours must be turned OFF, not just skipped:
# one popping up mid-recording would sit on top of a policy-critical shot.
dismiss_onboarding
# fresh home shows "Create tab" directly; otherwise clean up restored tabs
dump
if _find "$UIX" "Create tab" >/dev/null; then
  tap "Create tab"; pause 1.6
else
  clean_tabs_to_picker
fi
tap "Explorer"; pause 2.5                   # leave Butler on a fresh Explorer tab
# Hand over to the system Files app off camera, so the recording opens inside the
# OTHER app and the share into Butler is visible as a real inter-app handoff.
docui="$("${ADB[@]}" shell am start -n com.android.documentsui/.files.FilesActivity 2>&1)"
case "$docui" in
  *Error*|*Exception*|*"does not exist"*)
    die_rec "system Files app (com.android.documentsui/.files.FilesActivity) did not start: ${docui%%$'\n'*}" ;;
esac
pause 3.0
# Reach /sdcard/Documents through the storage volume. The roots drawer also has a
# by-type "Documents" root, which lists documents from MediaProvider and would
# NOT show an APK, so go through internal storage.
dump
if _find "$UIX" "Show roots" >/dev/null; then
  tap "Show roots"; pause 1.8
fi
# Reach /sdcard/Documents in the system Files app, off camera.
#
# Two things make this fiddly, and both were found on a live emulator rather
# than assumed. DocumentsUI labels the internal-storage root with the DEVICE
# MODEL (ro.product.model), not "Internal storage": here the row reads "Android
# SDK built for x86_64". And "Documents" is ambiguous, because the by-type root
# chip in the top bar and the folder row in the file list carry the same exact
# text, with no stable ordering between them. The by-type root is a MediaProvider
# view and lists no APK at all, so picking the wrong one silently dead-ends.
#
# So: re-navigate from the roots drawer on every attempt and keep the "Documents"
# whose listing actually contains the seeded APK. Backing out of a wrong guess is
# not reliable (BACK from the by-type root lands on Recent, not on the storage
# root), which is why each attempt restarts from the drawer instead.
dev_root="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
docs_ok=0
for di in 0 1 2 3; do
  "${ADB[@]}" shell am force-stop com.android.documentsui
  "${ADB[@]}" shell am start -n com.android.documentsui/.files.FilesActivity >/dev/null 2>&1
  pause 2.6
  dump
  if _find "$UIX" "Show roots" >/dev/null; then tap "Show roots"; pause 1.8; fi
  dump
  if   _find -c "$UIX" "$dev_root" >/dev/null;        then tap "$dev_root" 0 -c; pause 2.6
  elif _find -c "$UIX" "Internal storage" >/dev/null; then tap "Internal storage" 0 -c; pause 2.6
  else die_rec "the system Files app offered no internal-storage root (looked for '$dev_root')"
  fi
  dump
  xy=$(_find "$UIX" "Documents" "$di")
  [ -z "$xy" ] && break                     # no further candidate to try
  "${ADB[@]}" shell input tap $xy; pause 2.4
  dump
  if _find -c "$UIX" "$DEMO_APK_NAME" >/dev/null; then docs_ok=1; break; fi
done
[ "$docs_ok" = 1 ] \
  || die_rec "could not reach a Documents folder listing the seeded APK in the system Files app"

# ---- recorded flow ----------------------------------------------------------
rec_start

# ---- the app being opened ---------------------------------------------------
# The form asks for a video "which shows your app being opened, and the core
# feature you've described being used". The rest of this recording starts inside
# ANOTHER app (the system Files app), so without this beat Butler is never seen
# being opened at all. Launching from the home screen rather than resuming keeps
# it unambiguous. The pre-state already ran onboarding, so this is a warm start
# and stays short.
cap "Opening Butler, a file explorer"
"${ADB[@]}" shell input keyevent HOME; pause 1.0
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
pause 2.2
dump
_find -c "$UIX" "Butler" >/dev/null || _find "$UIX" "Create tab" >/dev/null \
  || die_rec "Butler did not come up for the opening shot"
pause 0.8
# Back to the system Files app, where the receiving beat starts.
"${ADB[@]}" shell am start -n com.android.documentsui/.files.FilesActivity >/dev/null 2>&1
pause 1.8

# ---- receiving the package (the other half of the permitted use) ------------
# Google requires core functionality to cover BOTH receiving app packages AND
# enabling user-initiated installation. This beat is the receiving half: another
# app hands an APK to Butler, and Butler's save-as writes it where the user picks
# it, before anything is installed.
#
# UNTESTED: this beat drives a SECOND app's UI by label and could not be tried in
# the run that wrote it. Every label here is unverified and must be checked on the
# first real recording run: the system Files app's "Share" action (and the "More
# options" overflow it may hide behind), the share sheet's entry for Butler,
# Butler's arrival dialog and its "Save as…" action, the Saver's "Destination" /
# "Select this folder" / "Save" / "Open directory" / "Save to new location"
# controls, and DocumentsUI's own navigation labels used in the pre-state
# ("Show roots", "Internal storage", "Documents").
cap "An APK arrives from another app"
pause 1.4
dump; sel=$(_find -c "$UIX" "CapOd")
[ -n "$sel" ] || die_rec "the seeded APK is not visible in the system Files app"
"${ADB[@]}" shell input swipe $sel $sel 900   # long-press -> selection mode
pause 1.3
dump
if _find "$UIX" "Share" >/dev/null; then
  tap "Share"; pause 1.6
elif _find "$UIX" "More options" >/dev/null; then
  tap "More options"; pause 1.6      # Share can sit in the overflow menu
  tap "Share" || die_rec "the system Files app offered no Share action for the APK"
  pause 2.6
else
  die_rec "the system Files app offered no Share action for the APK"
fi
# The share sheet only appears when more than one app handles ACTION_SEND. On a
# bare AOSP image Butler is the only handler, so the system goes straight to
# Butler's arrival dialog and there is no chooser to tap. Handle both.
dump
if _find -c "$UIX" "Save as" >/dev/null; then
  :                                         # already in Butler's arrival dialog
elif _find -c "$UIX" "Butler" >/dev/null; then
  tap "Butler" 0 -c; pause 1.6
else
  die_rec "the share went neither to a chooser offering Butler nor to Butler's arrival dialog"
fi
# A single-file ACTION_SEND does NOT open the Saver directly: MainActivity routes
# ShareRoute.SingleFile to onExternalFile, which raises Butler's arrival dialog
# (View / Show in Explorer / Save as…). "Save as…" is the action that opens the
# Saver on the shared file; the substring match covers the trailing ellipsis.
pause 2.2
tap "Save as" 0 -c || die_rec "Butler's arrival dialog offered no Save as action"
pause 2.0
dump
_find "$UIX" "Destination" >/dev/null \
  || die_rec "Butler's Saver did not open after Save as"

# The Saver defaults its destination to Download; pick it explicitly if it does
# not, then save under the shared file's own name (the prefilled filename).
dump
if ! _find -c "$UIX" "Download" >/dev/null; then
  tap "Destination" || die_rec "the Saver showed no destination to pick"
  pause 2.4
  tap "Device"; pause 2.0
  tap "Internal shared storage"; pause 2.0
  tap "Download"; pause 2.0
  tap "Select this folder"; pause 2.4
fi
tap "Save" || die_rec "the Saver's Save action was not found"
pause 1.2
# A dropped tap here would cost the "receiving" half of the evidence without any
# other symptom, so confirm the save actually landed before filming the install.
# The destination path exists as soon as SaveFilesOperation creates the file, before
# its contents are written, so existence alone does not prove the save finished.
# "Open directory" cannot prove it either: the Saver renders that button while the
# save is still running and merely disables it, and tap()/_find match on text only,
# so a disabled button looks exactly like an enabled one. "Save to new location"
# (saver_save_again_action) is rendered only in the success state, so gate the poll
# on that label plus the pinned checksum. The checksum doubles as proof that the
# shared content URI was read correctly end to end.
# Hash on the device, not by streaming the file out: `exec-out cat` moves the
# whole APK over adb on every poll, and this runs inside a retry loop while
# screenrecord is capturing.
saved_complete() {
  local hash
  hash=$("${ADB[@]}" shell sha256sum "/sdcard/Download/$DEMO_APK_NAME" 2>/dev/null \
    | awk '{print $1}' | tr -d '\r')
  [ "$hash" = "$DEMO_APK_SHA256" ]
}

saved=0
for c in 1 2 3 4 5 6 7 8 9 10 11 12; do
  dump
  if _find "$UIX" "Save to new location" >/dev/null && saved_complete; then saved=1; break; fi
  pause 1.2
done
[ "$saved" = 1 ] || die_rec "the shared APK did not finish saving correctly into Download"

# Butler is showing the Saver's result, so get to Download on camera. The APK's
# filename alone does not prove the tap landed: the Saver's own SourceFileCard shows
# it as well, so a tap dropped under recording load would still pass that check while
# stranded on the Saver. Require the filename AND the absence of both Saver-only
# controls, which can only hold once the Saver screen has actually been left.
tap "Open directory" || die_rec "the Saver did not offer Open directory after the save"
pause 1.6
opened=0
for c in 1 2 3 4 5 6 7 8 9 10 11 12; do
  dump
  if _find -c "$UIX" "$DEMO_APK_NAME" >/dev/null \
    && ! _find "$UIX" "Open directory" >/dev/null \
    && ! _find "$UIX" "Save to new location" >/dev/null; then
    opened=1
    break
  fi
  pause 0.8
done
[ "$opened" = 1 ] || die_rec "Open directory did not reach Butler's Download explorer"

cap "An APK file in your file explorer"
pause 1.6

scr() { [ "${DBG:-0}" = 1 ] || return 0; dump; echo "  [dbg] $1 :: $(grep -oE 'text="[^"]+"' "$UIX" | grep -iE 'security|Install unknown|Allow from|install this app|SETTINGS|INSTALL|CANCEL|Open with|CapOd' | tr '\n' '|')" >&2; }

# Both Butler and the system package installer handle ACTION_VIEW on an APK, so
# "Open with" raises a chooser offering "Butler" and "Package installer". Pick
# the installer: that hand-off is the whole point of the permission. On an image
# where only one target handles it, no chooser appears and this is a no-op.
pick_installer() {
  dump
  if _find -c "$UIX" "Package installer" >/dev/null; then
    tap "Package installer" 0 -c; pause 2.0
  fi
}

cap "Open with: Butler offers the APK,\nyou pick Android's installer"
tap "CapOd" 0 -c; pause 1.2                 # file options sheet
scr "after CapOd tap"
tap "Open with"; pause 2.2                  # hand-off to the system installer
pick_installer
scr "after Open with"

cap "Android checks Butler's permission\nto be an install source"
# Entry path varies: either the "For your security…" CANCEL/SETTINGS dialog, or
# (if this source was visited before) straight to the settings toggle page.
pause 1.8
dump
if _find "$UIX" "SETTINGS" >/dev/null; then
  tap "SETTINGS"; pause 2.2                   # -> "Install unknown apps" settings page
fi
scr "after SETTINGS check"

cap "You allow Butler as an install\nsource: the permission's grant"
# Enable the per-source toggle. A single input tap can be DROPPED under
# screenrecord load, so verify the grant took hold via the app-op (reliable from
# a pristine start: it is "allow" only once the toggle is actually on) and retry
# if not. Checking the op BEFORE each tap means we never re-tap an already-on
# switch (which would flip it back off).
# Anchor the match to this op's own line so it cannot false-positive on other text.
granted() { "${ADB[@]}" shell appops get "$PKG" REQUEST_INSTALL_PACKAGES | grep -Eq '^REQUEST_INSTALL_PACKAGES:[[:space:]]+allow([;[:space:]]|$)'; }
for i in 1 2 3 4 5; do
  granted && break
  dump
  if _find "$UIX" "Allow from this source" >/dev/null; then
    tap "Allow from this source"; pause 2.0
  elif _find "$UIX" "SETTINGS" >/dev/null; then
    tap "SETTINGS"; pause 2.6                   # bounced back to the block dialog
  else
    pause 1.2
  fi
done
scr "after toggle grant"
# The grant is the policy-critical shot — do not save a video without it.
granted || die_rec "per-source install grant did not take hold after retries"
pause 1.4                                       # hold on the enabled toggle

# Return to Butler and reopen the now-allowed APK. Enabling the toggle sometimes
# auto-resumes the pending install and sometimes not (build variance); reopening
# is the deterministic trigger and keeps the captions aligned. With the source
# now allowed (app-op = allow) this goes straight to the confirm dialog.
back; pause 1.3                                 # dismiss any auto-resumed dialog / leave settings
for j in 1 2 3 4 5; do dump; _find -c "$UIX" "$DEMO_APK_NAME" >/dev/null && break; back; pause 1.4; done

cap "You confirm in Android's installer;\nButler never installs anything"
tap "CapOd" 0 -c; pause 1.3                     # file options sheet
tap "Open with"; pause 2.2                      # -> confirm dialog (source now allowed)
pick_installer
for k in 1 2 3 4 5 6; do dump; _find "$UIX" "INSTALL" >/dev/null && break; pause 1.0; done
_find "$UIX" "INSTALL" >/dev/null || die_rec "system installer confirm dialog never appeared"
tap "INSTALL"; pause 3.0                        # OS performs the install
pause 1.2                                       # "App installed."
dump
_find "$UIX" "DONE" >/dev/null && tap "DONE"
pause 1.2
# Confirm the install actually completed before saving the video.
"${ADB[@]}" shell pm list packages | grep -q "$DEMO_PKG" || die_rec "install did not complete ($DEMO_PKG not present)"

# ---- duration budget --------------------------------------------------------
# Google caps the declaration video at 2 minutes, and the recorded flow is not
# the whole video: screenrecord has been running about 1.2s before REC_T0 (the
# sleep in rec_start), rec_stop adds its own stop and pull delay, and
# postprocess.sh concatenates a 3s title card and a 3.5s end card. A 108s flow
# therefore lands the finished file near 116s against the 120s ceiling, so 108s
# is the budget. This ABORTS instead of warning, matching the rest of the
# script: leaving the clip on the device unpulled is better than saving a video
# that is over the limit README states and cannot be submitted. bash has no
# floating point, so the subtraction and the comparison go through awk, like
# cap() does in _common.sh.
flow_elapsed=$(
  awk -v now="$(date +%s.%N)" -v start="$REC_T0" \
    'BEGIN { printf "%.3f", now - start }'
)
awk -v elapsed="$flow_elapsed" 'BEGIN { exit !(elapsed < 108) }' \
  || die_rec "recorded flow took ${flow_elapsed}s; final video would risk exceeding 2 minutes"

rec_stop
