#!/usr/bin/env bash
# Archives Bitey for iPhone and uploads it to TestFlight, with no Apple login.
# Signing is managed by Xcode through an App Store Connect team API key.
#
# Needs: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY (the .p8 file's contents), TEAM_ID.
# Optional: BUILD_NUMBER, which must rise with every upload.
# Optional: GOOGLE_SERVICE_INFO, the Firebase plist. Without it, Bitey AI is refused.
#
# Not proven yet: this cannot run until a Developer Program account exists.
set -euo pipefail

: "${ASC_KEY_ID:?}" "${ASC_ISSUER_ID:?}" "${ASC_KEY:?}" "${TEAM_ID:?}"
build_number="${BUILD_NUMBER:-$(date +%s)}"
cd "$(dirname "$0")/.."

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
(umask 077; printf '%s\n' "$ASC_KEY" > "$work/key.p8")
auth=(
  -allowProvisioningUpdates
  -authenticationKeyPath "$work/key.p8"
  -authenticationKeyID "$ASC_KEY_ID"
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"
)

xcodegen generate > /dev/null

# Archived unsigned: a signed archive would ask for a development profile, and
# those need a registered device. An ad hoc signature then carries each target's
# entitlements into the export, which signs for real.
xcodebuild archive -project Bitey.xcodeproj -scheme Bitey -configuration Release \
  -destination generic/platform=iOS -archivePath "$work/Bitey.xcarchive" \
  DEVELOPMENT_TEAM="$TEAM_ID" CODE_SIGNING_ALLOWED=NO \
  CURRENT_PROJECT_VERSION="$build_number" | xcbeautify --quiet

app="$work/Bitey.xcarchive/Products/Applications/Bitey.app"
codesign -f -s - --entitlements BiteyWidget/BiteyWidget.entitlements "$app/PlugIns/BiteyWidget.appex"
codesign -f -s - --entitlements Bitey/Bitey.entitlements "$app"

cat > "$work/ExportOptions.plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>teamID</key><string>$TEAM_ID</string>
  <key>signingStyle</key><string>automatic</string>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict>
</plist>
PLIST

xcodebuild -exportArchive -archivePath "$work/Bitey.xcarchive" \
  -exportOptionsPlist "$work/ExportOptions.plist" -exportPath "$work/export" "${auth[@]}" | xcbeautify --quiet

echo "Uploaded build $build_number. Apple processes it before TestFlight offers it."
