#!/usr/bin/env bash
# Runs :app-common-io's connected tests against the SFTP test server (tools/sftp-test-server.sh),
# which the emulator reaches as 10.0.2.2, and prints the throughput test's numbers from logcat.
# Fails unless every SFTP device test class ran with no skipped or failed test (see
# tools/sftp-device-results.py): without the server arguments they would skip and pass silently.
# With --class, exactly the requested classes must have run that way.
#
# Usage: ANDROID_SERIAL=<emulator> tools/sftp-device-test.sh [--class <fqcn>[,<fqcn>...]]
# Env: SFTP_TEST_PORT (default 2222)
set -euo pipefail

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to an emulator started for this run}"
cd "$(dirname "$0")/.."

CLASSES=
while [ $# -gt 0 ]; do
    case "$1" in
        --class)
            CLASSES=${2:?--class needs a value}
            shift 2
            ;;
        *)
            echo "Usage: ANDROID_SERIAL=<emulator> $0 [--class <fqcn>[,<fqcn>...]]" >&2
            exit 2
            ;;
    esac
done

PORT=${SFTP_TEST_PORT:-2222}
export SFTP_TEST_PORT=$PORT
RESULTS=app-common-io/build/outputs/androidTest-results/connected

trap 'tools/sftp-test-server.sh stop' EXIT
tools/sftp-test-server.sh start

args=(
    -Pandroid.testInstrumentationRunnerArguments.sftpHost=10.0.2.2
    -Pandroid.testInstrumentationRunnerArguments.sftpPort="$PORT"
)
if [ -n "$CLASSES" ]; then
    args+=(-Pandroid.testInstrumentationRunnerArguments.class="$CLASSES")
fi

# Stale XML from an earlier run would otherwise count as executed.
rm -rf "$RESULTS"
since=$(adb shell "date '+%m-%d %H:%M:%S.000'")
gradle=0
./gradlew :app-common-io:connectedFossDebugAndroidTest "${args[@]}" || gradle=$?

echo "SFTP throughput (logcat):"
adb logcat -d -T "$since" -s SftpThroughput:I | grep -v '^-' || true

if [ "$gradle" -ne 0 ]; then
    tools/sftp-device-results.py "$RESULTS" --class "$CLASSES" || true
    echo "Gradle failed with exit code $gradle" >&2
    exit "$gradle"
fi
tools/sftp-device-results.py "$RESULTS" --class "$CLASSES"
