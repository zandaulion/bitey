# Bitey for Android

This module is the local-first Android host for Bitey. It packages the existing
`web/` interface and `core/` calculations inside the APK and serves them from
`https://plate.local`; it does not load the interface from the Express server.

Implemented local-first slices:

1. The PWA's diary API is local to the installed app. Profile, weigh-ins, and
   diary-entry metadata live in an app-private Room database. Attached photo
   bytes remain in the WebView's private IndexedDB, keyed by their entry, so
   the bridge never copies a large image payload into Room.
2. CameraX + zxing-cpp decode EAN, UPC and Code 128 barcodes on-device, with
   no network access and no telemetry (it replaced Google's ML Kit).
3. Barcode cache records live in an app-private Room database, outside the
   WebView. A local cache hit works offline. On a miss, each direct Open Food Facts
   lookup has an explicit consent prompt; no request is relayed through Firebase
   and nutrition results (not product images) are cached only on the device.
4. Typed generic-food search uses the bundled SQLite table through a narrow
   native bridge; it has no network dependency.

The AI photo and meal-description paths are visibly marked as premium. The Android client now uses
Google Play Billing 9.1 to restore and acknowledge the `bitey_ai` subscription
on the user's Google Play account. All camera, gallery, correction, leftovers,
shared-photo and text-description paths ask the same native entitlement gate first. The rest of
the log continues to work locally without an account.

## Quick ways in

- **Long-press shortcuts** on the launcher icon: Photo, Gallery, Barcode and
  Type (`LaunchActions.kt`). They are dynamic shortcuts, published whenever the
  page sends its action-bar labels, so they follow the language chosen in
  Bitey, and the debug build (a different package) gets its own.
- **A "Meal photo" Quick Settings tile** (`PhotoTileService.kt`), drawn as
  Bitey's head (`ic_tile_bitey.xml`), opens Bitey ready for a photo. It reuses a running Bitey through `onNewIntent` and asks
  for an unlock first on a locked phone. Its name comes from the app's string
  resources, so it follows the phone's language.
- **Settings → Quick Settings** asks Android to add the tile
  (`StatusBarManager.requestAddTileService`, Android 13 and later; Android
  shows its own prompt). On older versions the section stays hidden.

- **A "Today in Bitey" home-screen widget** (`TodayWidget.kt`, 4x2 by
  default): today's total as a large number against what you burn, a bar of
  protein, carbs and fat by energy, a legend with fibre, and Photo, Barcode
  and Type buttons. It hands the launcher four layouts (buttons only, a
  tightened medium, full, and full with Bitey's advice), each in a narrow
  version with icon-only buttons and a wide one with labels, and the launcher
  shows the largest that fits (Android 12 and later). Launchers report sizes
  differently or not at all, so the widget does not choose for itself; rows
  measured so far are about 223dp for two on One UI (Fold4 cover), 180dp on
  HyperOS's 4x6 grid and 280dp on the Moto launcher.
  The burn estimate and the advice live in the page, so the page sends a
  snapshot (figures and words, already translated) whenever it draws today,
  plus Bitey's face as a PNG when his mood changes. Meals are only logged in
  Bitey, so after midnight the widget shows 0 for the new day on its own
  (it is refreshed every 30 minutes) and the greeting instead of yesterday's
  advice. No entries or foods reach the widget, only the day's totals.
- **Settings → Home screen widget** asks the launcher to place it
  (`requestPinAppWidget`; the launcher shows its own prompt) and says so if
  one is already there.

Every route hands its action to the page exactly as the native action bar
does, so Photo and Gallery still pass the Bitey AI entitlement gate. An action
that arrives before the page has loaded waits for the labels message and runs
once; a rotation or a process restore does not repeat it.

## Play Billing launch checklist

`bitey_ai` is deliberately a subscription: photo analysis has a continuing
model cost, so a permanent unlock would not be sustainable. Before enabling it
for sale in Play Console:

1. Create a **subscription** with product ID `bitey_ai`, then add these two
   active base plans with the exact IDs the packaged app expects:
   - `monthly` at **€5.99 per month**
   - `yearly` at **€49.99 per year**
   The interface asks Google Play for fresh localised prices and lets the
   person choose explicitly; no introductory or promotional offer is selected
   accidentally.
2. Install the AAB from a Play testing track on a licence-test account. ADB
   installs do not have the Play purchase context needed to exercise the real
   billing flow.
3. Test a completed purchase, a cancellation, a pending payment, reinstall,
   restore, and subscription management from Settings.
4. Do **not** activate the product for real customers until the Firebase AI
   endpoint verifies a purchase token with the Google Play Developer API,
   checks its expiry / entitlement state, and applies the 20-call daily
   server-side usage limit. The client gate is good UX and supports restore, but it cannot
   protect paid Gemini capacity from a modified APK.

The billing bridge does not expose Play account details or purchase tokens to
the WebView, and it does not send diary data or photos to Google Play.

## Local release signing

Debug APKs use `com.zandaulion.bitey.debug` and the launcher name **Bitey Debug**,
so they install alongside the Play version with separate app data. The Play
version's subscription does not carry over to this package. Production AI
entitlement checks remain enforced, and live text analysis also requires the
updated analysis function to be deployed.

Shared debug builds can use the local sideload key through the ignored
`android/debug-keystore.properties`, with `storeFile`, `storePassword`,
`keyAlias`, and `keyPassword` entries. Without this file, Gradle uses its
ordinary debug key. Never put the Play upload key in this file.

Before `bundleRelease`, copy `keystore.properties.example` to the ignored
`keystore.properties` file and replace the three `REPLACE_*` values using the
local upload-keystore credentials. The `storeFile` example already points at
the local `globe-release.jks` after the project move. Release builds now fail
instead of creating an unsigned AAB when those private inputs are absent.

The interface assets are synchronised automatically by the Gradle
`syncPlateAssets` task, so UI tuning in `web/` remains the source of truth.

Release builds run **R8** (shrinking, optimisation and obfuscation) since
1.1.3; the bundle went from 12.2 MB to 7.95 MB. `proguard-rules.pro` keeps the
bridge's `@JavascriptInterface` methods, which the page calls by name. The
mapping file travels inside the bundle
(`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`) and Play
reads it on upload, so there is no separate mapping upload.

## Toolchain

The Gradle wrapper in `android/gradle/wrapper/` pins **Gradle 9.5.0**, which is
the distribution AGP 9.3.3 expects. Build with `gradlew` rather than a system
Gradle so the pairing is the same everywhere; the two versions are declared
together in `android/build.gradle.kts` and neither moves alone.

`settings.gradle.kts` applies the Foojay toolchain resolver, so Gradle fetches a
matching JDK instead of failing when the machine has none.

An Android SDK is still required and is not part of the wrapper. Open `android/`
in Android Studio and install the requested API 36 SDK if prompted, or point
`android/local.properties` at an existing SDK for command-line builds.
