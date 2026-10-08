# Bitey — Google Play release package

This folder is the single home for the materials needed to publish Bitey on
Google Play.

## Contents

- `LISTINGS.md` — title, tagline, short and full descriptions, and first
  release notes in all twelve launch languages.
- `PRIVACY_NOTICE.md` — the public Privacy Policy text. Replace its marked
  privacy-contact placeholder before publishing it.
- `MANUAL_RELEASE_GUIDE.md` — the manual Play Console, privacy, billing, and
  later Firebase/Gemini steps, in the order to perform them.
- `assets/feature-graphic-base.png` — 1280 × 720 feature-graphic artwork.
- `assets/feature-graphic-play.png` — flattened 1024 × 500 feature graphic
  ready for Google Play upload.
- `screenshots/` — authentic Android screenshots and their capture guide.
- `releases/` — local signed Play App Bundles ready for Play Console upload.
  Do not commit keystores or signed bundles here or anywhere else in the
  repository.

## Upload artifacts

The next upload is **1.0.12** (`versionCode` 13): the first Bitey AI reading
after opening the app no longer waits for, or fails on, the app's start-up
checks, and each reading logs where its time went. Its release notes are in
`RELEASE_NOTES_1.0.12.md`, with the paste-ready tagged form in
`release-notes-1.0.12.xml`. Deploy the `analyse` function too
(`firebase deploy --only functions:analyse`) for its timing logs.

- 1.0.12 file SHA-256: `13C77B0EA2E3E00C1BE2D7CF746106A1F4A7941C5EE674677B24F6A29675A59C`
  (built 8 October 2026, 09:02)

The latest upload is **1.0.11** (`versionCode` 12), sent for review on 5 October 2026: a far
larger food table named in every app language, Bitey AI counting loose foods,
and an opt-in switch that lets Bitey AI use a vegetarian, vegan or pescatarian
diet. The updated `plate-privacy.html` (5 October 2026) and the Data safety
row for the diet (*Personal info → Other info*) went with it. Its release
notes are in `RELEASE_NOTES_1.0.11.md`, with the paste-ready tagged form in
`release-notes-1.0.11.xml`.

- 1.0.11 file SHA-256: `9818948C3F5BCCDC4B1011382A5B37136F4B016217036BF42815E40D431DA066`
  (built 5 October 2026, 17:59)

Before it, **1.0.10** (`versionCode` 11) was sent for review on 4
October 2026: a clearer "Not what you ate?" flow, and a progress bar with
playful messages while Bitey AI works. Its release notes are in
`RELEASE_NOTES_1.0.10.md`, with the paste-ready tagged form in
`release-notes-1.0.10.xml`.

- 1.0.10 file SHA-256: `2EB6D3BE99F33B8C02AA992475FDE536C81883473817331F6F0C5FA3554D2314`
  (built 4 October 2026, 15:59)

Before that, **1.0.9** (`versionCode` 10) was sent for review on 3 October
2026 and is now in closed testing: the first month free, and what Bitey
AI saw shown straight under the photo. Its release notes are in
`RELEASE_NOTES_1.0.9.md`, with the paste-ready tagged form in
`release-notes-1.0.9.xml`.

- 1.0.9 file SHA-256: `6DCF62CF31BD6BC38BDB2CEDB8112D2A9B129FF94FE48B7019FB5B96CC604B94`
  (built 3 October 2026, 22:46)

The latest upload is **1.0.8** (`versionCode` 9), sent for review on 3 October
2026. It answers PrimeTestLab's first closed-testing report (No 7991): a
Gallery button beside Photo, a side rail for the logging actions in
landscape, a confirmation on the profile's Save button, and correct
singulars for weigh-ins. Its release notes are in `RELEASE_NOTES_1.0.8.md`,
with the paste-ready tagged form in `release-notes-1.0.8.xml`.

- 1.0.8 file SHA-256: `070E07502FD96D9C67E430FADB0F7903DFF2D71D0FA09DED284143E65E7453E8`
  (built 3 October 2026, 16:30)

Previously uploaded: 1.0.7 (`versionCode` 8), sent for review on 27 September
2026 and tested on closed testing by PrimeTestLab from 1 October, which wired
"Not what you ate?" and "Photograph what's left" on saved meals and moved the
page's confirmations to native dialogs
(SHA-256 `50D5C8DAABDC8E53FC25F5A542065FD51940E47F28A3DBF82120D34A685995DF`,
built 27 September 2026, 18:17); 1.0.6 (`versionCode` 7), approved on Play on 26 September
2026, which brought Firebase App Check (now enforced), zxing-cpp in place of
ML Kit, and Play Billing contacted only once someone uses Bitey AI
(SHA-256 `FA951065536E86B8D0F6B9D1CA06EA80195BDECED16FB1F1CD33AB7337912AC5`,
built 26 September 2026, 17:11); 1.0.5 (`versionCode` 6), approved for closed testing on 26
September 2026, the first build in which Bitey AI reads a photograph end to end
(SHA-256 `B0C3214D82442337CD20E68DE5198E1646192F55F43D29728FFBA129C4686BC0`,
signed with `key0`, built 25 September 2026); and, all closed testing, 1.0.4 (`versionCode` 5), in which the
camera and gallery first opened; 1.0.3 (`versionCode` 4), which made the Bitey
AI plans reachable; 1.0.2 (`versionCode` 3), which declared
`com.android.vending.BILLING` and so unlocked Play Console's Subscriptions page
and let `bitey_ai` be created; and 1.0.0 (`versionCode` 1).

- 1.0.0 file SHA-256: `25D7FA1A970F215EA02EB3CAEEF2ACA22CA1F9272F016AC4D6B217C0425FF42F`
- Upload certificate SHA-256:
  `4A:27:74:1E:3B:D3:A6:5F:30:C2:FA:EE:AA:06:4E:E9:67:BF:2B:79:9D:D2:B3:22:1B:6F:2B:50:22:E1:E2:BE`
- Play app signing certificate SHA-256 for **Android 16 (SDK 36) and older**
  -- the APK Signature Scheme v3.0 signer, which is what almost every phone
  verifies, and so what Play Integrity attests there:
  `21:19:1D:34:C0:51:1D:54:DA:17:11:E9:73:48:99:CC:B5:BA:44:D4:32:0E:8E:88:6E:19:6A:53:B7:06:31:26`
- Play app signing certificate SHA-256 for **Android 17 (SDK 37) and newer**
  (the classical half of the v3.2 hybrid signer; the one Play Console shows):
  `C8:BF:12:29:F2:DA:F6:D4:AD:9B:9C:15:DF:FC:40:D3:AC:39:17:02:EA:4E:60:22:52:44:FB:97:EA:AD:45:80`
- Play app signing certificate SHA-256 (post-quantum half of the v3.2 signer,
  beta):
  `DB:B7:DE:A4:25:E2:7F:22:95:3C:E0:7E:49:6F:6C:DA:C9:C4:82:2D:E2:56:0C:17:4D:D8:7A:C1:60:C1:93:0B`

All five fingerprints -- these four and the debug key's -- are registered on
the Bitey Android app in Firebase project `plate-cc703` for App Check. The
SDK 36 one was missing at first: App Check then refused every Play-installed
phone below Android 17 with a 403 on ExchangePlayIntegrityToken, and the
function logged `absent`. To read the certificates a Play install really
carries, pull its APK (`adb shell pm path com.zandaulion.bitey`) and run
`apksigner verify --print-certs`; each signer lists the SDK range it covers.

## Store identity

- Play title: **Bitey — Private Food Log**
- Tagline: **Nutrition, privately.**
- Android package and namespace: `com.zandaulion.bitey`
- Launcher label: **Bitey**
- First release version: **1.0.0** (`versionCode` 1)
- Current source version: **1.0.12** (`versionCode` 13), not yet uploaded
- Next upload artifact: `bitey-private-food-log-1.0.12.aab`

The package name is permanent after the first Play upload. It is deliberately
separate from the pre-release `app.plate` package, so testing installations do
not accidentally become the store identity.

## Before the first upload

1. Create the Bitey app in Play Console with the title and tagline above.
2. Enrol it in Play App Signing and create an upload key kept outside Git.
3. Build a signed release AAB with version code 1.
4. Complete the store listing, privacy policy, Data safety form, content rating,
   camera permission declaration, and testing track requirements.
