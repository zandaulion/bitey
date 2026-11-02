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
| `PhotoCapture.swift` | `PlateCaptureStore.kt` |
| `BarcodeScanner.swift`, `OpenFoodFacts.swift` | `BarcodeScanActivity.kt` |
| `Zip.swift`, `LocalBackup.swift` | `LocalBackup.kt` |
| `BiteyWidget/`, `Shared/` | `TodayWidget.kt`, `PhotoTileService.kt` |

`bridge.js` defines `window.PlateNative` with the same method names and reply
callbacks, so the page needs no iOS branch. Only what this build implements is
defined; the page checks for each capability and hides what is missing.

Unlike Android, iOS keeps the page's own action bar instead of a native one.

## Build and test

The project is generated, so `Bitey.xcodeproj` is not tracked. On a Mac:

    brew install xcodegen xcbeautify
    ios/scripts/test.sh "iPhone 17"

GitHub runs the same script on every push that touches iOS, `web/` or `core/`, and
keeps the screens as a build artifact. The widget shares data through an app group,
which needs a signature even in the Simulator, so the build is signed ad hoc.

UI tests live in `BiteyUITests/`. Debug builds read these launch settings, and
release builds compile none of them:

| Setting | Effect |
|---|---|
| `BITEY_SELFTEST=1` | Runs every native service; results go to `tmp/page.log` |
| `BITEY_FAKE_AI=1` | Pretends AI is active, to reach the photo screens |
| `BITEY_TEST_PHOTO` | Uses this file instead of Apple's picker |
| `BITEY_TEST_BARCODE` | Uses this code instead of the camera |
| `BITEY_TEST_BACKUP` | Uses this path instead of the Files picker |
| `BITEY_RENDER_WIDGET` | Draws the widget to PNGs in this folder |

## Needs a real iPhone

The Simulator has no camera, so the camera and barcode scanner have never run.
Tapping a photo inside Apple's picker and the Files picker cannot be automated.

## Not built yet

Bitey AI (App Attest and in-app purchase), the rating prompt, and the Apple Watch
app. See `ROADMAP.md`.

## Apple's rules

- The subscription must use in-app purchase (guideline 3.1.1).
- Sharing a photo with the AI needs clear disclosure and explicit permission
  (5.1.2).
- Personal health information may not be stored in iCloud (5.1.3), so the diary
  is excluded from device backups.
