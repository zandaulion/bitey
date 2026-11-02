# Bitey for iOS: road to parity

The goal is the same app as Android, as far as Apple's rules allow. `web/`, `core/`
and the backend stay shared. Android-only wording is replaced as each feature lands,
not in a separate cleanup.

Size is the work involved: S, M or L. **You** marks what only the owner can do.

## Parity at a glance

| Feature | Android | iOS | Phase |
|---|---|---|---|
| Interface, screens, languages | Done | Done | 0 |
| Diary, weights, profile, trends | Done | Done | 0 |
| Food search on the device | Done | Done | 0 |
| Barcode cache | Done | Done | 0 |
| Gallery | Done | Done, picker tap unproven | 1 |
| Camera photo | Done | Built, needs an iPhone | 1 |
| Barcode scanner | Done | Done on an iPhone | 1 |
| Open Food Facts lookup | Done | Done | 1 |
| Backup and restore | Done | Done on iOS, needs an Android test | 1 |
| Back gesture, privacy link, icon, text size | Done | Done | 1 |
| Home screen widget | Done | Done in the Simulator | 1 |
| Long-press shortcuts | Done | Done in the Simulator | 1 |
| Ask for a rating | On a branch, not on `main` | Not yet | 1 |
| Quick log from the system panel | Quick Settings tile | Control Center, on an iPhone | 1 |
| Bitey AI: photo and text | Done | Server side written, not deployed | 3 |
| Subscription | Google Play | Written, unproven | 3 |
| Watch app, tiles, complications | Wear OS | Apple Watch | 4 |

## Phase 0: the app runs (done)

The shared interface runs in the iOS Simulator with the same native bridge. Local
storage and food search pass the debug self-test.

## Phase 1: everything that needs no Apple account

Built and tested in the Simulator on a rented Mac.

1. Gallery, with the `capturePhoto` bridge and `/local-capture/`. (M)
2. Camera, sharing that path. Only a real iPhone can test it. (S)
3. Barcode scanner with AVFoundation, on the same `scanBarcode` bridge. Only a real
   iPhone can test scanning. (M)
4. Open Food Facts lookup, direct from the device, behind the existing consent. (S)
5. Backup and restore through Files, in the same `plate.json` zip format, so a
   backup moves between Android and iPhone. Test with a real Android backup. (M)
6. App icon, launch screen, back gesture, privacy link, text size, keyboard. (M)
7. Rating prompt, once Android's `feature/in-app-review` branch reaches `main`. (S)
8. Long-press shortcuts with App Intents. (S)
9. Widget with WidgetKit, from the page's existing snapshot. (L)
10. Control Center control for a quick photo. (S)

As each lands, its welcome and settings text is corrected for iOS in every language.

**You:** nothing, except saying which feature comes first.

## Phase 2: Bitey on your iPhone

1. **You:** enrol in the Apple Developer Program, and tell Claude when approved.
2. **You:** in App Store Connect, create the app with bundle ID
   `com.zandaulion.bitey`. Accept any agreements it shows.
3. **You:** create an App Store Connect **Team** key with the Admin role, which
   signing is likely to need. Save the `.p8` file, the key ID, the issuer ID and your
   Team ID. Never paste them into a chat.
4. **You:** in the repo's GitHub settings, create an environment called `testflight`,
   limited to the `testflight` branch, with the secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`,
   `ASC_KEY` and `APPLE_TEAM_ID`.
5. Claude: push to the `testflight` branch, which uploads a build. Working since
   2 November 2026. (Done)
6. **You:** install TestFlight on your iPhone and add yourself as an internal tester.
7. **You:** install Bitey, then test the camera, the barcode scanner and the widget,
   which the Simulator cannot prove. Report what you find.

## Phase 3: Bitey AI and the subscription

This changes the live backend that Android users depend on. Each deployment needs
your go-ahead.

1. **You:** in App Store Connect, accept the Paid Apps agreement and fill in banking
   and tax details.
2. **You:** create the subscription, `bitey_ai`, with monthly and yearly prices and
   the trial, matching Google Play.
3. **You:** create an In-App Purchase API key for the server.
4. **You:** in the Firebase console, add the iOS app and turn on App Attest.
5. Claude: buy and restore with StoreKit 2. Written; its tests only run from Xcode
   because of an Apple bug, and they have not passed yet. (M)
6. Claude: App Check with App Attest in the app. Needs a real iPhone. (M)
7. Claude: `functions/apple.js` beside `play.js` is written and tested with fakes.
   Before the first deploy, create the `APPLE_IAP_KEY` secret and set
   `APPLE_IAP_KEY_ID`, `APPLE_ISSUER_ID` and `APPLE_APP_ID`, or the deploy fails.
8. **You:** approve the deployment. Claude then deploys, and you confirm Android
   still works.
9. Claude: the AI consent names the third-party AI, as guideline 5.1.2 requires,
   and all Google Play wording becomes App Store wording in every language. (M)
10. **You:** test buying, restoring and cancelling with a sandbox account on your
    iPhone.

A subscription bought on one platform will not carry over to the other: Bitey has no
accounts, and each store owns its own purchases.

## Phase 4: Apple Watch

Rebuilds `android/wear/` in SwiftUI: the day's total, quick bites, repeating a meal,
and complications. (L)

It needs a real Apple Watch for proper testing.

**You:** decide whether to do this before release, after it, or not at all.

## Phase 5: the App Store

1. **You:** decide about publishing. Your legal name becomes the public seller name,
   and if you are a trader under EU law, your address, phone and email appear on
   Bitey's EU page. Consider advice on trader status.
2. Claude: draft the listing, the App Privacy answers and App Store screenshots, in
   every language. (M)
3. **You:** review the updated privacy notice, and publish it.
4. **You:** enter the listing and privacy answers, or have Claude do it by API.
5. **You:** submit for review, and answer any questions from Apple.

## Apple's rules to keep in view

- The subscription must use in-app purchase (3.1.1).
- The app must be more than a repackaged website (4.2), which the native features
  above support.
- Sharing with third-party AI needs disclosure and explicit permission (5.1.2).
- Health information may not be stored in iCloud (5.1.3).
- The app must not mention other mobile platforms. Check this before review.
