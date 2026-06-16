#!/usr/bin/env bash
# Seed a believable synthetic file tree on an emulator for the Play declaration
# screencast. Only touches a known demo subtree + standard media dirs; never
# deletes anything outside what it created.
#
# Usage: ./seed_demo_data.sh [adb-serial]   (default: emulator-5564 / butler-main-2)
set -euo pipefail

SERIAL="${1:-emulator-5564}"
ADB=(adb -s "$SERIAL")
SD=/sdcard

echo "Seeding demo data on $SERIAL …"

# --- clean only our own demo dirs (idempotent re-seed) -----------------------
for d in Documents/Work Documents Download Pictures/Trips Projects; do :; done
"${ADB[@]}" shell "rm -rf $SD/Projects/butler-notes $SD/Documents/Work $SD/Pictures/Trips" || true

# --- helper: make a file of a given KiB size with a readable text header ------
mkfile() { # path sizeKiB header
  local path="$1" kib="$2" header="$3"
  "${ADB[@]}" shell "mkdir -p \"\$(dirname $path)\"; { printf '%s\n' \"$header\"; head -c $((kib*1024)) /dev/urandom; } > \"$path\""
}
mktext() { # path content
  "${ADB[@]}" shell "mkdir -p \"\$(dirname $1)\"; cat > \"$1\"" <<<"$2"
}

# --- Documents ---------------------------------------------------------------
mkfile "$SD/Documents/Invoice-2026-04.pdf"     180 "%PDF-1.7 (demo invoice)"
mkfile "$SD/Documents/Travel-itinerary.pdf"     95 "%PDF-1.7 (demo itinerary)"
mktext "$SD/Documents/Meeting-notes.txt" "Project sync — 2026-04-12
- Ship closed test to Play
- TODO: record permission demo
- Owner: darken"
mktext "$SD/Documents/Budget.csv" "category,planned,actual
servers,40,38
design,15,12
misc,10,7"

# --- Documents/Work ----------------------------------------------------------
mkfile "$SD/Documents/Work/Annual-report.pdf"  420 "%PDF-1.7 (demo report)"
mktext "$SD/Documents/Work/changelog.md" "# Changelog
## 0.0.1
- First closed test
- TODO: screenshots"

# --- Download ----------------------------------------------------------------
mkfile "$SD/Download/butler-release.zip"      1536 "PK (demo archive)"
mkfile "$SD/Download/photo-backup.zip"        2048 "PK (demo archive)"
mkfile "$SD/Download/Quarterly-report.pdf"     260 "%PDF-1.7 (demo report)"

# --- Projects/butler-notes ---------------------------------------------------
mktext "$SD/Projects/butler-notes/README.md" "# Butler notes
A file explorer for Android.
See config.json for settings. TODO: polish onboarding."
mktext "$SD/Projects/butler-notes/config.json" "{
  \"theme\": \"material-you\",
  \"amoled\": true,
  \"defaultTab\": \"explorer\",
  \"showHidden\": false
}"
mktext "$SD/Projects/butler-notes/build.log" "BUILD START
> compile gplayDebug
> assemble OK
BUILD SUCCESSFUL in 42s"

# --- Pictures/Trips (generated images, pushed from host) ---------------------
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if command -v convert >/dev/null 2>&1; then
  convert -size 1280x960 gradient:skyblue-navy   -gravity center -pointsize 64 -fill white -annotate 0 'Trip 2026' "$TMP/sunset.jpg"
  convert -size 1280x960 gradient:lightgreen-darkgreen -gravity center -pointsize 64 -fill white -annotate 0 'Mountains' "$TMP/mountains.jpg"
  convert -size 1080x1080 gradient:orange-firebrick -gravity center -pointsize 64 -fill white -annotate 0 'City' "$TMP/city.png"
  "${ADB[@]}" shell "mkdir -p $SD/Pictures/Trips"
  for f in sunset.jpg mountains.jpg city.png; do "${ADB[@]}" push "$TMP/$f" "$SD/Pictures/Trips/$f" >/dev/null; done
else
  echo "  (ImageMagick 'convert' not found — skipping sample images)"
fi

# --- make the new files visible to the media scanner -------------------------
"${ADB[@]}" shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$SD >/dev/null 2>&1" || true

echo "Done. Demo tree under: Documents, Download, Projects/butler-notes, Pictures/Trips"
