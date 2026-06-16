#!/usr/bin/env bash
# Assemble a declaration video from a recorder's output dir:
#   <OUTDIR>/raw.mp4 + captions.txt + title.txt + end.txt  ->  <OUTDIR>/declaration.mp4
#
# Usage: ./postprocess.sh [OUTDIR]   (default the MES output dir)
set -euo pipefail

OUTDIR="${1:-/tmp/butler-demo/manage-external-storage}"
RAW="$OUTDIR/raw.mp4"; CAPS="$OUTDIR/captions.txt"; OUT="$OUTDIR/declaration.mp4"
TITLE="$OUTDIR/title.txt"; END="$OUTDIR/end.txt"

FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
FONTB=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
W=720; H=1606; FPS=30; BG=0x0E3B2E

[ -f "$RAW" ] || { echo "missing $RAW — run record.sh first"; exit 1; }
[ -f "$TITLE" ] || printf 'Butler — File Explorer\neu.darken.butler' > "$TITLE"
[ -f "$END" ]   || printf 'Files are managed on-device\nand never leave the device.' > "$END"
TXTDIR="$(mktemp -d)"; trap 'rm -rf "$TXTDIR"' EXIT

dur=$(ffprobe -v error -select_streams v:0 -show_entries format=duration -of csv=p=0 "$RAW")

# --- timed-caption drawtext chain from captions.txt --------------------------
chain=""
if [ -f "$CAPS" ]; then
  mapfile -t lines < "$CAPS"
  n=${#lines[@]}
  for ((i=0;i<n;i++)); do
    start=${lines[i]%%|*}; text=${lines[i]#*|}
    if (( i+1 < n )); then end=${lines[i+1]%%|*}; else end=$dur; fi
    printf '%s' "$text" > "$TXTDIR/c$i.txt"
    chain+=",drawtext=fontfile=${FONT}:textfile=${TXTDIR}/c$i.txt:fontcolor=white:fontsize=32:box=1:boxcolor=0x000000C0:boxborderw=20:x=(w-text_w)/2:y=h-180:enable='between(t,${start},${end})'"
  done
fi

ffmpeg -y -loglevel error -stats \
  -f lavfi -i "color=c=${BG}:s=${W}x${H}:d=3:r=${FPS}" \
  -i "$RAW" \
  -f lavfi -i "color=c=${BG}:s=${W}x${H}:d=3.5:r=${FPS}" \
  -filter_complex "
    [0:v]format=yuv420p,drawtext=fontfile=${FONTB}:textfile=${TITLE}:fontcolor=white:fontsize=33:line_spacing=18:x=(w-text_w)/2:y=(h-text_h)/2[t];
    [1:v]fps=${FPS},scale=${W}:${H},setsar=1,format=yuv420p${chain}[m];
    [2:v]format=yuv420p,drawtext=fontfile=${FONTB}:textfile=${END}:fontcolor=white:fontsize=38:line_spacing=18:x=(w-text_w)/2:y=(h-text_h)/2[e];
    [t][m][e]concat=n=3:v=1:a=0[v]
  " -map "[v]" -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -movflags +faststart "$OUT"

echo "Wrote $OUT"
ffprobe -v error -select_streams v:0 -show_entries format=duration,size:stream=width,height -of default=noprint_wrappers=1 "$OUT"
