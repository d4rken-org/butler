#!/usr/bin/env bash
# Installs a Butler build and an :app-e2e APK and runs the e2e tests against them, except the
# two-phase UpgradeTest (see tools/upgrade-test.sh). Runs without Gradle, so APKs built elsewhere,
# for example by another CI job, are tested as they are. Wipes eu.darken.butler on the target
# device, which ANDROID_SERIAL must name.
#
# Usage: tools/e2e-test.sh <app.apk> <app-e2e.apk>
# Results (instrumentation output, failure screenshots) land in app-e2e/build/outputs/e2e-test.
set -euo pipefail

if [ $# -ne 2 ]; then
    echo "Usage: $0 <app.apk> <app-e2e.apk>" >&2
    exit 2
fi
APP_APK=$1
TEST_APK=$2
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to an emulator started for this run}"

APP=eu.darken.butler
TEST_PKG=eu.darken.butler.e2e
RUNNER=$TEST_PKG/androidx.test.runner.AndroidJUnitRunner
DEVICE_OUT=/sdcard/Android/media/$TEST_PKG/additional_test_output
RESULTS=app-e2e/build/outputs/e2e-test

rm -rf "$RESULTS"
mkdir -p "$RESULTS"

adb uninstall "$APP" >/dev/null 2>&1 || true
adb uninstall "$TEST_PKG" >/dev/null 2>&1 || true
adb install "$APP_APK"
adb install -t "$TEST_APK"
adb shell rm -rf "$DEVICE_OUT"
adb shell mkdir -p "$DEVICE_OUT"

result=0
adb shell am instrument -w \
    -e notClass eu.darken.butler.e2e.UpgradeTest \
    -e additionalTestOutputDir "$DEVICE_OUT" \
    "$RUNNER" | tee "$RESULTS/instrumentation.txt" || result=$?
adb pull "$DEVICE_OUT/." "$RESULTS/" >/dev/null 2>&1 || echo "Could not pull $DEVICE_OUT" >&2

if [ "$result" -ne 0 ] || ! grep -qE '^OK \([1-9][0-9]* tests?\)' "$RESULTS/instrumentation.txt"; then
    echo "E2E tests failed, see $RESULTS" >&2
    exit 1
fi
