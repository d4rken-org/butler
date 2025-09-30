#!/bin/bash

# Screenshot capture script for debugging Butler UI via ADB
# Usage: ./screenshot.sh [filename]

set -e

# Create temp directory if it doesn't exist
SCREENSHOT_DIR=".claude/tmp"
mkdir -p "$SCREENSHOT_DIR"

# Generate filename
if [ -n "$1" ]; then
    FILENAME="$1"
    # Add .png extension if not present
    [[ "$FILENAME" != *.png ]] && FILENAME="${FILENAME}.png"
else
    TIMESTAMP=$(date +"%Y-%m-%d-%H%M%S")
    FILENAME="screenshot-${TIMESTAMP}.png"
fi

FILEPATH="${SCREENSHOT_DIR}/${FILENAME}"

# Capture screenshot
echo "Capturing screenshot..."
adb shell screencap -p > "$FILEPATH"

# Output path
echo "Screenshot saved to: $FILEPATH"