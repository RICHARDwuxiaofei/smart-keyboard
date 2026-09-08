#!/usr/bin/env bash
set -euo pipefail
source_root="${1:?APK evidence directory required}"
output="${2:?Output directory required}"
mkdir -p "$output"
trap 'adb logcat -d > "$output/logcat.txt" || true' EXIT
mapfile -t apps < <(find "$source_root" -type f -name '*debug-x86_64.apk')
mapfile -t tests < <(find "$source_root" -type f -path '*/androidTest/*' -name '*.apk')
test "${#apps[@]}" -eq 1
test "${#tests[@]}" -eq 1
sha256sum "${apps[0]}" "${tests[0]}" | tee "$output/apk-sha256.txt"
adb install -r -t "${apps[0]}"
adb install -r -t "${tests[0]}"
adb shell pm list instrumentation | tee "$output/instrumentation.txt"
adb logcat -c
adb shell am instrument -w -r -e class org.fcitx.fcitx5.android.extension.ai.AiRoomAndEditorTest,org.fcitx.fcitx5.android.extension.ai.AiNativeAndOcrTest org.fcitx.fcitx5.android.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$output/device-tests.log"
if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$output/device-tests.log"; then exit 1; fi
grep -Fq 'OK (5 tests)' "$output/device-tests.log"
