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

The next upload is **1.0.6** (`versionCode` 7), which carries Firebase App
Check: every Bitey AI request brings a Play Integrity attestation. Its release
notes are in `RELEASE_NOTES_1.0.6.md`, with the paste-ready tagged form in
`release-notes-1.0.6.xml`. Record its hash here once built.

The latest upload is **1.0.5** (`versionCode` 6), approved for closed testing
on 26 September 2026: the first build in which Bitey AI reads a photograph end
to end. Its Play release notes, in all twelve launch languages, are in
`RELEASE_NOTES_1.0.5.md`, with the paste-ready tagged form in
`release-notes-1.0.5.xml`.

- 1.0.5 file SHA-256: `B0C3214D82442337CD20E68DE5198E1646192F55F43D29728FFBA129C4686BC0`
  (signed with `key0`; built 25 September 2026)

Previously uploaded, all closed testing: 1.0.4 (`versionCode` 5), in which the
camera and gallery first opened; 1.0.3 (`versionCode` 4), which made the Bitey
AI plans reachable; 1.0.2 (`versionCode` 3), which declared
`com.android.vending.BILLING` and so unlocked Play Console's Subscriptions page
and let `bitey_ai` be created; and 1.0.0 (`versionCode` 1).

- 1.0.0 file SHA-256: `25D7FA1A970F215EA02EB3CAEEF2ACA22CA1F9272F016AC4D6B217C0425FF42F`
- Upload certificate SHA-256:
  `4A:27:74:1E:3B:D3:A6:5F:30:C2:FA:EE:AA:06:4E:E9:67:BF:2B:79:9D:D2:B3:22:1B:6F:2B:50:22:E1:E2:BE`
- Play app signing certificate SHA-256 (classical; what installed copies carry
  and what Play Integrity attests):
  `C8:BF:12:29:F2:DA:F6:D4:AD:9B:9C:15:DF:FC:40:D3:AC:39:17:02:EA:4E:60:22:52:44:FB:97:EA:AD:45:80`
- Play app signing certificate SHA-256 (post-quantum, beta):
  `DB:B7:DE:A4:25:E2:7F:22:95:3C:E0:7E:49:6F:6C:DA:C9:C4:82:2D:E2:56:0C:17:4D:D8:7A:C1:60:C1:93:0B`

All four fingerprints -- these three and the debug key's -- are registered on
the Bitey Android app in Firebase project `plate-cc703` for App Check.

## Store identity

- Play title: **Bitey — Private Food Log**
- Tagline: **Nutrition, privately.**
- Android package and namespace: `com.zandaulion.bitey`
- Launcher label: **Bitey**
- First release version: **1.0.0** (`versionCode` 1)
- Current source version: **1.0.6** (`versionCode` 7), not yet uploaded
- Next upload artifact: `bitey-private-food-log-1.0.6.aab`

The package name is permanent after the first Play upload. It is deliberately
separate from the pre-release `app.plate` package, so testing installations do
not accidentally become the store identity.

## Before the first upload

1. Create the Bitey app in Play Console with the title and tagline above.
2. Enrol it in Play App Signing and create an upload key kept outside Git.
3. Build a signed release AAB with version code 1.
4. Complete the store listing, privacy policy, Data safety form, content rating,
   camera permission declaration, and testing track requirements.
