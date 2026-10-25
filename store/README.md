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
- `screenshots/` — screenshot inventory and capture guide, including the
  1.0.13 entry flow rendered from the app with synthetic fixtures. The guide
  distinguishes browser captures from older Android captures.
- `releases/` — local signed Play App Bundles ready for Play Console upload.
  Do not commit keystores or signed bundles here or anywhere else in the
  repository.

## Upload artifacts

The next upload is **1.1.8** (`versionCode` 25): a welcome for new users (a
three-screen tour of the ways to log, the widget and Quick Settings tile with
one-tap add buttons, repeating meals and quick bites, and the
no-good-or-bad-foods promise beside an optional profile prompt), a one-screen
"what's new" for existing users, kinder Bitey messages, and the app
retranslated in ten languages. Its release notes are in
`RELEASE_NOTES_1.1.8.md`, with the paste-ready tagged form in
`release-notes-1.1.8.xml`.

- 1.1.8 file SHA-256: `2D8401B0F3D978D7394308AB731D766CE281DF3A2DFC17B86E767C46003ECDEA`
  (built 25 October 2026, 11:39, on the Lenovo from 859798d; signed with the
  Play upload key). Its R8 mapping travels inside the bundle. R8 kept the tile
  service and widget class names and every bridge method the page calls.

Before it, **1.1.7** (`versionCode` 24) went live in production: stronger light-theme
contrast (secondary text and the green buttons now pass WCAG AA), in the page
and in the native action bar, widget and shortcuts, plus Gradle 9.8.2, Room
2.8.5 and KSP 2.3.12. Its release notes are in `RELEASE_NOTES_1.1.7.md`, with
the paste-ready tagged form in `release-notes-1.1.7.xml`.

- 1.1.7 file SHA-256: `B23AEE4CED831F98FDBE3938252CAA347D30DB94529471DE84DC92EA061B4C36`
  (built 23 October 2026, 19:29, on the Lenovo from a155640; signed with the
  Play upload key). Its R8 mapping travels inside the bundle. R8 kept Room's
  generated database classes, the tile and widget classes, and every bridge
  method the page calls.

Before it, **1.1.6** (`versionCode` 23) went live in production: the Today widget fits every
launcher and grid. It hands the launcher all its layouts and the launcher
shows the largest that fits, with icon-only buttons when narrow; on HyperOS's
4x6 grid it had overflowed, and on the Moto launcher shrunk to buttons only.
Its release notes are in `RELEASE_NOTES_1.1.6.md`, with the paste-ready tagged
form in `release-notes-1.1.6.xml`.

- 1.1.6 file SHA-256: `406E4554CEFC19C0952B2651ABA74EFC8236E7E7CEAD954A22F15B3F06D5B8D4`
  (built 22 October 2026, 10:18, on the Lenovo from fe814c7; signed with the
  Play upload key). Its R8 mapping travels inside the bundle. R8 kept the tile
  service and widget class names and every bridge method the page calls.

Before it, **1.1.5** (`versionCode` 22) went live in production: the "Today in Bitey"
home-screen widget (today's calories against the burn, macros and fibre,
Photo/Barcode/Type buttons, and Bitey's advice when made taller), a Settings
button that places it, and Bitey's head on the Meal photo tile. Its release
notes are in `RELEASE_NOTES_1.1.5.md`, with the paste-ready tagged form in
`release-notes-1.1.5.xml`.

- 1.1.5 file SHA-256: `8FF8223C78B7C665DE4C97D88E78C117E70523A3C16F944612FDCAA454F76FC4`
  (built 21 October 2026, 16:13, on the Lenovo from f30ea51; signed with the
  Play upload key). Its R8 mapping travels inside the bundle. R8 kept the tile
  service and widget class names and every bridge method the page calls.

Before it, **1.1.4** (`versionCode` 21) went live in production: long-press shortcuts on the
launcher icon (Photo, Gallery, Barcode, Type), a "Meal photo" Quick Settings
tile, and a Settings button that adds the tile. Its release notes are in
`RELEASE_NOTES_1.1.4.md`, with the paste-ready tagged form in
`release-notes-1.1.4.xml`.

- 1.1.4 file SHA-256: `3571CCB5AE3CD71976B43B0458499FA35CC5226287F0A8A5F0E3B59F545BFE5E`
  (built 19 October 2026, 23:02, on the Lenovo from 4f06a67; signed with the
  Play upload key). Its R8 mapping travels inside the bundle
  (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`), which
  Play reads on upload. R8 kept the tile service's name and both new bridge
  methods (`canAddQuickTile`, `addQuickTile`).

Before it, **1.1.3** (`versionCode` 20) went live in production: a Bitey AI refusal stays on
screen instead of being replaced by a waiting joke, and the first release
shrunk with R8 (bundle 12.2 MB → 7.95 MB). Its R8 mapping travels inside the
bundle and Play reads it on upload; nothing is uploaded separately. The `analyse` function that logs why the model refused a reading was
deployed on 19 October 2026 (`firebase deploy --only functions:bitey-ai:analyse`;
the codebase prefix is required, plain `functions:analyse` matches nothing). Its release notes are in
`RELEASE_NOTES_1.1.3.md`, with the paste-ready tagged form in
`release-notes-1.1.3.xml`.

- 1.1.3 file SHA-256: `4D1693A4C14DE9294A9AA5BF351FA7913DC815D6B9745C6B8CA263B0436C3B32`
  (built 19 October 2026, 17:36, on the Lenovo from 19d0010; signed with the
  Play upload key), with its R8 mapping inside the bundle.

Before it, **1.1.2** (`versionCode` 19) was live in production from 14 October 2026:
a steadier burn estimate.
The weight trend is fitted over up to six weeks instead of four, the profile
formula is weighed in while the evidence is thin, today is left out until it is
over, and the weight chart in You draws the same trend as the estimate. Its
release notes are in `RELEASE_NOTES_1.1.2.md`, with the paste-ready tagged
form in `release-notes-1.1.2.xml`.

- 1.1.2 file SHA-256: `B7C7BD2897428A37E6B2E1C66BAEF57D402731BB75C0DA37FE617BBA377992EF`
  (built 14 October 2026, 18:56, on the Lenovo from 5bccc65; signed with the
  Play upload key).

Before it, **1.1.1** (`versionCode` 18) was live in production from 12 October 2026. It
replaces 1.1.0, the first production release, as the
**production release**: a subscription the server no longer honours is dropped
at once, so the padlocks return and the plans are offered instead of a
dead-end error; the refusal is remembered across restarts, and if Play then
refuses a new purchase as "already owned" (its copy on the phone is behind),
Bitey opens Play's subscriptions page and says why. Its release notes keep
1.1.0's launch note and are in
`RELEASE_NOTES_1.1.1.md`, with the paste-ready tagged form in
`release-notes-1.1.1.xml`.

- 1.1.1 file SHA-256: `B339F874BBCAADB8F85D0A7A7CC9AD5D001DA2E7420B9CAF7960D6F51B386D51`
  (built 12 October 2026, 16:42, on the Lenovo from 4e95600; signed with the
  Play upload key, `CN=Daniel Marin`). It replaces an earlier 1.1.1 build from
  9ac37c5 that was never uploaded.

Before it, **1.1.0** (`versionCode` 17) went to closed testing on 11 October
2026, waiting for production access to be granted:
the remaining English translated (profile form, empty charts, backup texts,
waits), with the production listing (`LISTINGS.md`, `listing-import.txt`) and
the new screenshots (`screenshots/`). Its release notes, written as a launch
note, are in `RELEASE_NOTES_1.1.0.md`, with the paste-ready tagged form in
`release-notes-1.1.0.xml`.

- 1.1.0 file SHA-256: `F97FEDD8B74AA0D02F4BD6C4D0E16267122C1E4BECD77A7298CEED90CB08168F`
  (built 11 October 2026, 16:49, on the Lenovo from 7dad136; signed with the
  Play upload key, `CN=Daniel Marin`)

Before it, **1.0.15** (`versionCode` 16) was sent for review on 11 October 2026: Estimate meal gets the same
lively wait as a photo, and a failed estimate says why instead of always
blaming the connection. Its release notes are in `RELEASE_NOTES_1.0.15.md`,
with the paste-ready tagged form in `release-notes-1.0.15.xml`.

- 1.0.15 file SHA-256: `60DD2BBE4266F221A9F7B2F0FF450BCEB8470EA1FCD3F6786E36C32874EBE341`
  (built 11 October 2026, 15:14, on the Lenovo from c466952; signed with the
  Play upload key, `CN=Daniel Marin`)

Before it, **1.0.14** (`versionCode` 15): the profile card on the day
view can be closed, and stays closed. Its release notes are in
`RELEASE_NOTES_1.0.14.md`, with the paste-ready tagged form in
`release-notes-1.0.14.xml`. It goes with the updated `plate-privacy.html`
(10 October 2026, meal descriptions in their own paragraph).

- 1.0.14 file SHA-256: `3C305DD29F0A6F0CF877C1BC34179725492FBE4BE63A9D029C8B2AE60B47B8C6`
  (built 10 October 2026, 20:50, on the Lenovo; signed with the Play upload key,
  `CN=Daniel Marin`)

Before it, **1.0.13** (`versionCode` 14): describe a meal to estimate
calories and macros with Bitey AI, then review and adjust portions before
saving. Text estimates require the same subscription and share the daily
allowance with photo estimates. Food search and entering nutrition numbers
remain free. Release notes for all twelve languages are in
`RELEASE_NOTES_1.0.13.md`, with the paste-ready tagged form in
`release-notes-1.0.13.xml`.

Before rolling out 1.0.13, deploy the updated `analyse` function
(`firebase deploy --only functions:bitey-ai:analyse`) for text analysis, and publish
the updated `plate-privacy.html` dated 10 October 2026. Building this bundle
does not deploy the backend or publish the privacy page.

- 1.0.13 file SHA-256: `16B76B0CCF9DF09F83BF5C33301BD8AA3E1F97C1965F00A74BB54E92EBAF36A5`
  (built 10 October 2026; Play upload signature verified)

The preceding build was **1.0.12** (`versionCode` 13): the first Bitey AI reading
after opening the app no longer waits for, or fails on, the app's start-up
checks, and each reading logs where its time went. Its release notes are in
`RELEASE_NOTES_1.0.12.md`, with the paste-ready tagged form in
`release-notes-1.0.12.xml`. Deploy the `analyse` function too
(`firebase deploy --only functions:bitey-ai:analyse`) for its timing logs.

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
- Current source version: **1.1.8** (`versionCode` 25), not yet uploaded
- Production: **1.1.7** (`versionCode` 24)
- Next upload artifact: `bitey-private-food-log-1.1.8.aab`

The package name is permanent after the first Play upload. It is deliberately
separate from the pre-release `app.plate` package, so testing installations do
not accidentally become the store identity.

## Before the first upload

1. Create the Bitey app in Play Console with the title and tagline above.
2. Enrol it in Play App Signing and create an upload key kept outside Git.
3. Build a signed release AAB with version code 1.
4. Complete the store listing, privacy policy, Data safety form, content rating,
   camera permission declaration, and testing track requirements.
