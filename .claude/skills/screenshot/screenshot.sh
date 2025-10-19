#!/bin/bash

# Screenshot capture script for debugging Butler UI via ADB
# Usage: ./screenshot.sh [filename] [-d device-serial]

set -e

# Parse arguments
FILENAME=""
DEVICE_SERIAL=""

while [[ $# -gt 0 ]]; do
    case $1 in
        -d)
            DEVICE_SERIAL="$2"
            shift 2
            ;;
        *)
            FILENAME="$1"
            shift
            ;;
    esac
done

# Find project root and create temp directory if it doesn't exist
PROJECT_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || echo "$HOME")
SCREENSHOT_DIR="${PROJECT_ROOT}/.claude/tmp"
mkdir -p "$SCREENSHOT_DIR"

# Generate filename
if [ -n "$FILENAME" ]; then
    # Add .png extension if not present
    [[ "$FILENAME" != *.png ]] && FILENAME="${FILENAME}.png"
else
    TIMESTAMP=$(date +"%Y-%m-%d-%H%M%S")
    FILENAME="screenshot-${TIMESTAMP}.png"
fi

FILEPATH="${SCREENSHOT_DIR}/${FILENAME}"

# Device selection logic
if [ -z "$DEVICE_SERIAL" ]; then
    # Get list of connected devices (exclude header and empty lines)
    DEVICES=$(adb devices | grep -v "List of devices" | grep -E "^[^\s]+\s+device$" | awk '{print $1}')
    DEVICE_COUNT=$(echo "$DEVICES" | grep -c . || true)

    if [ "$DEVICE_COUNT" -eq 0 ]; then
        echo "Error: No devices connected"
        exit 1
    elif [ "$DEVICE_COUNT" -eq 1 ]; then
        DEVICE_SERIAL="$DEVICES"
        echo "Using device: $DEVICE_SERIAL"
    else
        echo "Multiple devices connected:"
        echo "$DEVICES" | nl
        echo ""
        read -p "Select device number (or press Enter for first device): " DEVICE_NUM

        if [ -z "$DEVICE_NUM" ]; then
            DEVICE_SERIAL=$(echo "$DEVICES" | head -n 1)
        else
            DEVICE_SERIAL=$(echo "$DEVICES" | sed -n "${DEVICE_NUM}p")
        fi

        if [ -z "$DEVICE_SERIAL" ]; then
            echo "Error: Invalid device selection"
            exit 1
        fi

        echo "Using device: $DEVICE_SERIAL"
    fi
fi

# Get device model for display
DEVICE_MODEL=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "Unknown")

# Capture screenshot
echo "Capturing screenshot from $DEVICE_MODEL ($DEVICE_SERIAL)..."
adb -s "$DEVICE_SERIAL" shell screencap -p > "$FILEPATH"

# Output path
echo "Screenshot saved to: $FILEPATH"