#!/usr/bin/env bash
# Builds Bitey and runs its UI tests on a Simulator. Used on a Mac and in CI.
#   ios/scripts/test.sh ["iPhone 17"] [results path]
# The Simulator build is signed ad hoc, because the widget's app group needs a
# signature even there. Screens and renders land in /tmp/bitey-*.
set -euo pipefail

device="${1:-iPhone 17}"
results="${2:-build/results.xcresult}"
cd "$(dirname "$0")/.."

xcodegen generate > /dev/null

udid="$(xcrun simctl list devices available -j |
  jq -r --arg name "$device" '[.devices[][] | select(.name == $name)][0].udid // empty')"
if [ -z "$udid" ]; then
  echo "No Simulator called $device" >&2
  exit 1
fi
xcrun simctl boot "$udid" 2> /dev/null || true
xcrun simctl bootstatus "$udid" -b > /dev/null
xcrun simctl uninstall "$udid" com.zandaulion.bitey 2> /dev/null || true

# The photo test feeds this in place of Apple's picker.
sips -s format jpeg Bitey/Assets.xcassets/AppIcon.appiconset/icon-1024.png --out /tmp/sample.jpg > /dev/null

rm -rf "$results" /tmp/bitey-shots /tmp/bitey-tour /tmp/bitey-widget
mkdir -p "$(dirname "$results")"
# BiteyTests needs Xcode itself: see the note in AiPurchasesTests.swift.
xcodebuild test -project Bitey.xcodeproj -scheme Bitey \
  -destination "id=$udid" -resultBundlePath "$results" -skip-testing:BiteyTests \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO | xcbeautify --quiet
