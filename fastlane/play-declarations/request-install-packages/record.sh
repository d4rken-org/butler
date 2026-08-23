#!/usr/bin/env bash
# Record the APK-install (REQUEST_INSTALL_PACKAGES) declaration screencast.
# The permitted use requires BOTH halves of the core functionality on camera, so
# the video shows the package being received and managed as a file (an APK lands
# in a staging folder and the user copies it into Download) and then the
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
OUTDIR="${OUTDIR:-/tmp/butler-demo/request-install-packages}"
HERE="$(cd "$(dirname "$0")" && pwd)"
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

# ---- pre-state (off camera) -------------------------------------------------
echo "Pre-state: seed APK into the staging folder, verify pristine install-source grant, clean tabs, reach Download/incoming…"
"${ADB[@]}" shell pm uninstall "$DEMO_PKG" >/dev/null 2>&1
"${ADB[@]}" shell rm -f "/sdcard/Download/*.apk" >/dev/null 2>&1
"${ADB[@]}" shell rm -rf "/sdcard/Download/incoming" >/dev/null 2>&1
# The APK is seeded into a staging folder, NOT into Download: the first recorded
# beat is the user copying it out of there, which is the "receiving app packages"
# half of the permitted use. Pushing it straight to Download would put that step
# off camera and leave the video evidencing only the install half.
"${ADB[@]}" shell mkdir -p "/sdcard/Download/incoming" >/dev/null 2>&1
"${ADB[@]}" push "$DEMO_APK" "/sdcard/Download/incoming/$DEMO_APK_NAME" >/dev/null
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
# first-run onboarding (fresh emulator): Skip -> Accept; no-op when already set up
dump
if _find "$UIX" "Skip" >/dev/null; then
  tap "Skip"; pause 1.2
  tap "Accept"; pause 2.5
fi
# fresh home shows "Create tab" directly; otherwise clean up restored tabs
dump
if _find "$UIX" "Create tab" >/dev/null; then
  tap "Create tab"; pause 1.6
else
  clean_tabs_to_picker
fi
tap "Explorer"; pause 2.5
# navigate to the staging folder off camera so the recording starts on the APK
tap "Device"; pause 2.0
tap "Internal shared storage"; pause 2.0
tap "Download"; pause 2.0
tap "incoming"; pause 2.0

# ---- recorded flow ----------------------------------------------------------
rec_start

# Abort mid-recording without saving a bad video: stop screenrecord and exit
# non-zero, leaving the on-device clip unpulled so no invalid declaration.mp4 is
# produced. Used to enforce the policy-critical shots actually happened.
die_rec() {
  echo "ABORT (recording): $1" >&2
  "${ADB[@]}" shell pkill -INT screenrecord 2>/dev/null || true
  exit 1
}

# ---- receiving the package (the other half of the permitted use) ------------
# Google requires core functionality to cover BOTH receiving app packages AND
# enabling user-initiated installation. This beat is the receiving half: the APK
# sits in a staging folder and the user files it into Download with Butler's own
# file management, before anything is installed.
cap "An APK arrives in your storage"
pause 2.6
dump; sel=$(_find -c "$UIX" "CapOd")
[ -n "$sel" ] || die_rec "the seeded APK is not visible in Download/incoming"
"${ADB[@]}" shell input swipe $sel $sel 900   # long-press -> selection mode
pause 1.4
tap "Copy"; pause 1.6
back; pause 1.8                             # up to Download
tap "Paste" 0 -c; pause 2.6
# A dropped tap here would cost the "receiving" half of the evidence without any
# other symptom, so confirm the copy actually landed before filming the install.
for c in 1 2 3 4 5; do
  "${ADB[@]}" shell ls "/sdcard/Download/$DEMO_APK_NAME" 2>/dev/null | grep -q "$DEMO_APK_NAME" && break
  pause 1.2
done
"${ADB[@]}" shell ls "/sdcard/Download/$DEMO_APK_NAME" 2>/dev/null | grep -q "$DEMO_APK_NAME" \
  || die_rec "the APK was not copied into Download (receiving half would be missing)"

cap "An APK file in your file explorer"
pause 3.0

scr() { [ "${DBG:-0}" = 1 ] || return 0; dump; echo "  [dbg] $1 :: $(grep -oE 'text="[^"]+"' "$UIX" | grep -iE 'security|Install unknown|Allow from|install this app|SETTINGS|INSTALL|CANCEL|Open with|CapOd' | tr '\n' '|')" >&2; }

cap "Select the APK, choose 'Open with'"
tap "CapOd" 0 -c; pause 1.8                 # file options sheet
scr "after CapOd tap"
tap "Open with"; pause 3.0                  # hand-off to the system installer
scr "after Open with"

cap "Approve the install source"
# Entry path varies: either the "For your security…" CANCEL/SETTINGS dialog, or
# (if this source was visited before) straight to the settings toggle page.
pause 2.4
dump
if _find "$UIX" "SETTINGS" >/dev/null; then
  tap "SETTINGS"; pause 2.8                   # -> "Install unknown apps" settings page
fi
scr "after SETTINGS check"

cap "Allow APK installs from Butler"
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
    tap "Allow from this source"; pause 2.4
  elif _find "$UIX" "SETTINGS" >/dev/null; then
    tap "SETTINGS"; pause 2.6                   # bounced back to the block dialog
  else
    pause 1.2
  fi
done
scr "after toggle grant"
# The grant is the policy-critical shot — do not save a video without it.
granted || die_rec "per-source install grant did not take hold after retries"
pause 1.6                                       # hold on the enabled toggle

# Return to Butler and reopen the now-allowed APK. Enabling the toggle sometimes
# auto-resumes the pending install and sometimes not (build variance); reopening
# is the deterministic trigger and keeps the captions aligned. With the source
# now allowed (app-op = allow) this goes straight to the confirm dialog.
back; pause 1.6                                 # dismiss any auto-resumed dialog / leave settings
for j in 1 2 3 4 5; do dump; _find -c "$UIX" "$DEMO_APK_NAME" >/dev/null && break; back; pause 1.4; done

cap "Confirm in Android's installer"
tap "CapOd" 0 -c; pause 1.6                     # file options sheet
tap "Open with"; pause 3.0                      # -> confirm dialog (source now allowed)
for k in 1 2 3 4 5 6; do dump; _find "$UIX" "INSTALL" >/dev/null && break; pause 1.0; done
_find "$UIX" "INSTALL" >/dev/null || die_rec "system installer confirm dialog never appeared"
tap "INSTALL"; pause 5.0                        # OS performs the install
pause 1.8                                       # "App installed."
dump
_find "$UIX" "DONE" >/dev/null && tap "DONE"
pause 1.2
# Confirm the install actually completed before saving the video.
"${ADB[@]}" shell pm list packages | grep -q "$DEMO_PKG" || die_rec "install did not complete ($DEMO_PKG not present)"

rec_stop
