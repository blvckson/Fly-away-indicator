#!/usr/bin/env bash
set -euo pipefail

# Only retry transient Maven Central HTTP 429s. A failed instrumentation assertion
# is a real test failure and must be reported immediately, not rerun four times.
run_gradle_with_retry() {
  local attempt=1
  local log_file
  log_file="$(mktemp)"
  while [[ "$attempt" -le 4 ]]; do
    if gradle "$@" --no-daemon 2>&1 | tee "$log_file"; then
      rm -f "$log_file"
      return 0
    fi
    if ! grep -Eqi 'HTTP 429|status code 429|Too Many Requests' "$log_file"; then
      rm -f "$log_file"
      return 1
    fi
    if [[ "$attempt" -lt 4 ]]; then
      sleep "$((attempt * 20))"
    fi
    attempt=$((attempt + 1))
  done
  rm -f "$log_file"
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
