#!/usr/bin/env bash
# Runs SftpEndToEndTest against a FOSS Butler build: starts the SFTP test server
# (tools/sftp-test-server.sh), which the emulator reaches as 10.0.2.2, installs both APKs like
# tools/e2e-test.sh and stops the server again. Fails unless the test ran and passed, since a skipped
# test would pass too. Wipes eu.darken.butler on the target device, which ANDROID_SERIAL must name.
#
# Usage: tools/sftp-e2e-test.sh <app.apk> <app-e2e.apk>
# Env: SFTP_TEST_PORT (default 2222)
# Results (instrumentation output, failure screenshots) land in app-e2e/build/outputs/sftp-e2e-test.
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
RESULTS=app-e2e/build/outputs/sftp-e2e-test
SERVER=$(dirname "$0")/sftp-test-server.sh
PORT=${SFTP_TEST_PORT:-2222}
export SFTP_TEST_PORT=$PORT

rm -rf "$RESULTS"
mkdir -p "$RESULTS"

trap '"$SERVER" stop' EXIT
"$SERVER" start

adb uninstall "$APP" >/dev/null 2>&1 || true
adb uninstall "$TEST_PKG" >/dev/null 2>&1 || true
adb install "$APP_APK"
adb install -t "$TEST_APK"
adb shell rm -rf "$DEVICE_OUT"
adb shell mkdir -p "$DEVICE_OUT"
# ATD images disable app drawing, which leaves failure screenshots black.
adb shell setprop debug.hwui.drawing_enabled 1

# -r reports each test's status code: 0 passed, -2 failed, -1 errored, -3 ignored, -4 assumption failed.
result=0
adb shell am instrument -w -r \
    -e class eu.darken.butler.e2e.SftpEndToEndTest \
    -e sftpHost 10.0.2.2 \
    -e sftpPort "$PORT" \
    -e additionalTestOutputDir "$DEVICE_OUT" \
    "$RUNNER" | tee "$RESULTS/instrumentation.txt" || result=$?
adb pull "$DEVICE_OUT/." "$RESULTS/" >/dev/null 2>&1 || echo "Could not pull $DEVICE_OUT" >&2

output=$(tr -d '\r' <"$RESULTS/instrumentation.txt")
if [ "$result" -ne 0 ] ||
    ! grep -qx 'INSTRUMENTATION_STATUS_CODE: 0' <<<"$output" ||
    grep -qE '^INSTRUMENTATION_STATUS_CODE: -[1-4]$' <<<"$output" ||
    ! grep -qE '^OK \([1-9][0-9]* tests?\)' <<<"$output"; then
    echo "SFTP e2e test failed or did not run, see $RESULTS" >&2
    exit 1
fi
