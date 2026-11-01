# Bitey for iOS

The iOS host for Bitey, the counterpart of `android/`. It packages `web/` and
`core/` inside the app and serves them from a private `bitey://app` origin, so
the interface is the same code as Android's. `web/` and `core/` are copied in at
build time and are never edited here.

## How it maps to Android

| iOS | Android |
|---|---|
| `AppSchemeHandler.swift` | `PlateAssetClient` and `https://plate.local` |
| `bridge.js`, `NativeBridge.swift` | `PlateNative` and `@JavascriptInterface` |
| `LocalStore.swift` | Room, `LocalPhotoStore.kt` |
| `FoodSearch.swift` | `GenericFoodSearch.kt` |

`bridge.js` defines `window.PlateNative` with the same method names and reply
callbacks, so the page needs no iOS branch. Only what this build implements is
defined; the page checks for each capability and hides what is missing.

Unlike Android, iOS keeps the page's own action bar instead of a native one.

## Build

The project is generated, so `Bitey.xcodeproj` is not tracked:

    cd ios
    xcodegen generate
    xcodebuild -project Bitey.xcodeproj -scheme Bitey \
      -destination 'platform=iOS Simulator,name=iPhone 17' \
      CODE_SIGNING_ALLOWED=NO build

Launching a debug build with `SIMCTL_CHILD_BITEY_SELFTEST=1` runs every native
service through the page's callbacks and writes the result to `tmp/page.log` in
the app's data container.

## Not built yet

Camera and gallery, barcode scanning, Open Food Facts lookup, Bitey AI (App Attest
and in-app purchase), backup, widget, shortcuts, and the Apple Watch app.

## Apple's rules

- The subscription must use in-app purchase (guideline 3.1.1).
- Sharing a photo with the AI needs clear disclosure and explicit permission
  (5.1.2).
- Personal health information may not be stored in iCloud (5.1.3), so the diary
  is excluded from device backups.
