#!/usr/bin/env bash
set -euo pipefail

# Maven Central can transiently rate-limit fresh GitHub-hosted Android emulator
# runners (HTTP 429). Retry complete, idempotent Gradle tasks with increasing
# delays instead of reporting a transient dependency-resolution error as a code failure.
run_gradle_with_retry() {
  local attempt=1
  while [[ "$attempt" -le 4 ]]; do
    if gradle "$@" --no-daemon; then
      return 0
    fi
    if [[ "$attempt" -lt 4 ]]; then
      sleep "$((attempt * 20))"
    fi
    attempt=$((attempt + 1))
  done
  return 1
}

# Install both APKs first; appops cannot target a package before Android
# has registered its UID on the emulator.
run_gradle_with_retry installDebug installDebugAndroidTest
adb shell pm path com.blvckson.flyawayindicator
adb shell pm path com.blvckson.flyawayindicator.test
adb shell appops set com.blvckson.flyawayindicator SYSTEM_ALERT_WINDOW allow
adb shell appops get com.blvckson.flyawayindicator SYSTEM_ALERT_WINDOW

# The APKs are already installed, so avoid another install that could disturb
# the overlay permission while the instrumentation runner starts.
set +e
run_gradle_with_retry connectedDebugAndroidTest -x installDebug -x installDebugAndroidTest
test_result=$?
set -e

mkdir -p app/build/reports/androidTests/connected
adb pull /sdcard/aviator-replay-failure.png app/build/reports/androidTests/connected/aviator-replay-failure.png || true
exit "$test_result"
