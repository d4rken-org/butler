# Shared helpers for the declaration screencast recorders.
# Sourced by each permission folder's record.sh. The sourcing script must set
# OUTDIR (and may set SERIAL/PKG/SIZE/NOREC) BEFORE sourcing this file.
set -uo pipefail

SERIAL="${SERIAL:-emulator-5564}"
PKG="${PKG:-eu.darken.butler}"
SIZE="${SIZE:-720x1606}"
NOREC="${NOREC:-0}"
ADB=(adb -s "$SERIAL")
LIBDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # play-declarations root
UIX="$OUTDIR/ui.xml"; RAW="$OUTDIR/raw.mp4"; CAPS="$OUTDIR/captions.txt"
mkdir -p "$OUTDIR"; : > "$CAPS"

# ---- ui helpers -------------------------------------------------------------
# Dump the current UI hierarchy. uiautomator intermittently fails with "could
# not get idle state" during animations/transitions (worse under screenrecord
# load); on failure it writes nothing, leaving a stale dump. Retry a few times
# and only overwrite $UIX with a dump that actually contains nodes.
dump() {
  local i out
  for i in 1 2 3 4; do
    if "${ADB[@]}" shell uiautomator dump /sdcard/__ui.xml 2>&1 | grep -q "dumped to"; then
      out="$("${ADB[@]}" exec-out cat /sdcard/__ui.xml)"
      if [ -n "$out" ] && printf '%s' "$out" | grep -q "<node"; then
        printf '%s' "$out" > "$UIX"; return 0
      fi
    fi
    sleep 0.4
  done
  # All attempts failed: leave the previous valid $UIX untouched rather than
  # overwriting it with stale/partial content. Callers see unchanged nodes and
  # their tap()/_find retry loops handle the miss.
  [ -s "$UIX" ] && return 1
  # No prior dump exists yet — surface whatever the device has so the first
  # caller has something to parse.
  "${ADB[@]}" exec-out cat /sdcard/__ui.xml > "$UIX" 2>/dev/null || true
  return 1
}
_find() { python3 "$LIBDIR/find_node.py" "$@" 2>/dev/null; }

tap() {  # tap <needle> [idx] [-c]   (-c = substring match)
  local needle="$1" idx="${2:-0}" flag="${3:-}" t xy
  for t in $(seq 1 12); do
    dump
    if [ "$flag" = "-c" ]; then xy=$(_find -c "$UIX" "$needle" "$idx"); else xy=$(_find "$UIX" "$needle" "$idx"); fi
    if [ -n "$xy" ]; then "${ADB[@]}" shell input tap $xy; return 0; fi
    sleep 0.5
  done
  echo "  ! tap not found: '$needle'" >&2; return 1
}
longpress() {  # longpress <needle> [idx]
  local needle="$1" idx="${2:-0}" xy
  dump; xy=$(_find "$UIX" "$needle" "$idx")
  [ -z "$xy" ] && { echo "  ! longpress not found: '$needle'" >&2; return 1; }
  "${ADB[@]}" shell input swipe $xy $xy 900
}
swipe_up() { "${ADB[@]}" shell input swipe 640 1900 640 800 500; }   # scroll list down
back() { "${ADB[@]}" shell input keyevent BACK; }
pause() { sleep "${1:-1.6}"; }
# Type one character at a time; `input text` drops characters under screenrecord load.
type_slow() { local s="$1" i; for ((i=0; i<${#s}; i++)); do "${ADB[@]}" shell input text "${s:$i:1}"; sleep 0.18; done; }
# One line per caption in $CAPS, "seconds|text". The text reaches awk through the
# environment rather than -v, because -v processes escape sequences: a caption
# carrying \n to wrap onto a second line would become a real newline here and
# split into two lines, and the second one has no "seconds|" for postprocess.sh
# to parse. Left literal, postprocess.sh expands it with printf '%b'.
cap() { local now; now=$(date +%s.%N); s="$1" awk -v a="$now" -v b="$REC_T0" 'BEGIN{printf "%.2f|%s\n", a-b, ENVIRON["s"]}' >> "$CAPS"; }

# ---- first-run onboarding and tours -----------------------------------------
# A fresh install has to be walked through two separate gates before any recorder
# can drive the app, and a tour can still pop up later, on top of a shot that is
# already being recorded. Sequence, established on a clean install:
#
#   1. "Welcome to Butler" carousel (4 pages)  -> "Skip" jumps to page 4
#   2. page 4 "Privacy & data"                 -> "Accept"
#   3. tab onboarding with a tour overlay      -> "Skip"
#   4. "Skip the tour?" dialog                 -> "Disable all tours"
#   5. home screen with "Create tab"
#
# Step 4 matters beyond the first run: "Continue tour" and "Don't show this tour"
# both leave other tours armed, and one appearing mid-recording would cover a
# policy-critical shot. Only "Disable all tours" turns them all off.
#
# The loop is order-sensitive, most-modal first: the tour dialog sits over the
# overlay, which sits over the onboarding screen. It exits as soon as none of the
# gates is on screen, so it is a no-op on an already-configured install.
dismiss_onboarding() {
  local i
  for i in $(seq 1 10); do
    dump
    if _find "$UIX" "Disable all tours" >/dev/null; then
      tap "Disable all tours"; pause 1.6; continue
    fi
    if _find "$UIX" "Accept" >/dev/null; then
      tap "Accept"; pause 2.0; continue
    fi
    if _find "$UIX" "Skip" >/dev/null; then
      tap "Skip"; pause 1.6; continue
    fi
    if _find "$UIX" "Start with a tab" >/dev/null; then
      tap "Start with a tab"; pause 1.6; continue
    fi
    break                                 # no gate on screen, we are through
  done
}

# ---- reach a clean New-tab type picker --------------------------------------
# Robust to the app restoring a sub-screen (app detail / save-as) where the tab
# menu is not reachable: pop sub-screens with BACK until "Tab manager" appears.
clean_tabs_to_picker() {
  local i opened=0
  for i in 1 2 3 4 5 6; do
    tap "Butler mascot performing various animations" >/dev/null 2>&1
    pause 0.8; dump
    if _find "$UIX" "Tab manager" >/dev/null; then opened=1; break; fi
    back; pause 1.0                       # pop a sub-screen / dialog and retry
  done
  [ "$opened" = 1 ] || { "${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; pause 2; tap "Butler mascot performing various animations" >/dev/null 2>&1; pause 0.8; }
  tap "Tab manager" || true; pause 1.2
  local xy
  # close every existing tab
  for i in $(seq 1 12); do
    dump; xy=$(_find "$UIX" "Close tab"); [ -z "$xy" ] && break
    "${ADB[@]}" shell input tap $xy; pause 0.7
  done
  pause 1.2
  # create exactly ONE tab (never loop creation -> avoids the 5-tab free limit)
  dump
  if ! _find "$UIX" "Explorer" >/dev/null; then
    if   _find "$UIX" "Create tab" >/dev/null; then tap "Create tab"; pause 1.6
    elif _find "$UIX" "Add tab"    >/dev/null; then tap "Add tab";    pause 1.6
    fi
  fi
  # open the freshly created "New <hash>" card to reveal the picker (substring match)
  for i in 1 2 3 4 5; do
    dump; _find "$UIX" "Explorer" >/dev/null && break
    xy=$(_find -c "$UIX" "New "); [ -n "$xy" ] && "${ADB[@]}" shell input tap $xy
    pause 1.5
  done
}

# ---- recording control ------------------------------------------------------
rec_start() {
  if [ "$NOREC" = "0" ]; then
    echo "Recording → $RAW"
    "${ADB[@]}" shell rm -f /sdcard/__demo.mp4
    "${ADB[@]}" shell screenrecord --size "$SIZE" --bit-rate 8000000 --time-limit 180 /sdcard/__demo.mp4 &
    REC_CLIENT=$!
    sleep 1.2
  else
    echo "NOREC=1 → validating taps without recording"
  fi
  REC_T0=$(date +%s.%N)
}
rec_stop() {
  if [ "$NOREC" = "0" ]; then
    "${ADB[@]}" shell pkill -INT screenrecord 2>/dev/null || true
    wait "$REC_CLIENT" 2>/dev/null || true
    sleep 1
    "${ADB[@]}" pull /sdcard/__demo.mp4 "$RAW" >/dev/null && echo "Saved $RAW"
  fi
  echo "Captions:"; cat "$CAPS"
}
