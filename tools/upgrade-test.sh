#!/usr/bin/env bash
# Installs an older Butler build, sets up a user's state through the UI, upgrades that install in
# place to the current build, and checks the state survived (UpgradeTest in :app-e2e).
# Both app APKs are re-signed with this machine's debug key first, because an in-place install needs
# matching keys. Wipes eu.darken.butler on the target device; set ANDROID_SERIAL to an emulator.
#
# Usage: tools/upgrade-test.sh <old-app.apk> <new-app.apk> <app-e2e.apk>
# Results (instrumentation output, failure screenshots) land in app-e2e/build/outputs/upgrade-test.
set -euo pipefail

if [ $# -ne 3 ]; then
    echo "Usage: $0 <old-app.apk> <new-app.apk> <app-e2e.apk>" >&2
    exit 2
fi
OLD_APK=$1
NEW_APK=$2
TEST_APK=$3

APP=eu.darken.butler
RUNNER=eu.darken.butler.e2e/androidx.test.runner.AndroidJUnitRunner
DEVICE_OUT=/sdcard/Android/media/eu.darken.butler.e2e/additional_test_output
RESULTS=app-e2e/build/outputs/upgrade-test

run_phase() {
    echo "== UpgradeTest#$1"
    adb shell am instrument -w \
        -e class "eu.darken.butler.e2e.UpgradeTest#$1" \
        -e additionalTestOutputDir "$DEVICE_OUT" \
        "$RUNNER" | tee "$RESULTS/$1.txt"
    grep -q '^OK (1 test)' "$RESULTS/$1.txt"
}

BUILD_TOOLS=$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME}}"/build-tools/*/ | sort -V | tail -1)
DEBUG_KEYSTORE=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

certificate() {
    "$BUILD_TOOLS/apksigner" verify --print-certs "$1" | grep -m1 'SHA-256 digest' | sed 's/.*: //'
}

# Prints the key each APK was built with, so a build that signs unexpectedly shows up in the log.
resign() {
    echo "$2 APK built with key $(certificate "$1")"
    cp "$1" "$WORK/$2.apk"
    "$BUILD_TOOLS/apksigner" sign --ks "$DEBUG_KEYSTORE" --ks-pass pass:android \
        --ks-key-alias androiddebugkey --key-pass pass:android "$WORK/$2.apk"
}

resign "$OLD_APK" old
resign "$NEW_APK" new
echo "Both re-signed with key $(certificate "$WORK/new.apk")"

rm -rf "$RESULTS"
mkdir -p "$RESULTS"

adb uninstall "$APP" >/dev/null 2>&1 || true
adb install "$WORK/old.apk"
adb install -r -t "$TEST_APK"
adb shell rm -rf "$DEVICE_OUT"
adb shell mkdir -p "$DEVICE_OUT"

result=0
if run_phase beforeUpgrade; then
    adb install -r "$WORK/new.apk"
    run_phase afterUpgrade || result=1
else
    result=1
fi

adb pull "$DEVICE_OUT/." "$RESULTS/" >/dev/null 2>&1 || true
if [ "$result" -ne 0 ]; then
    echo "Upgrade test failed, see $RESULTS" >&2
fi
exit "$result"
