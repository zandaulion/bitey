# Bitey — Private Food Log: Privacy Policy

**Last updated:** 25 September 2026
**Developer:** Zandaulion
**Privacy contact:** **Replace this line with a working privacy email address
before publishing.**

This Privacy Notice applies to the Android app **Bitey — Private Food Log**
(“Bitey”, “the app”). Bitey is designed as an on-device food diary: it does
not require an account and does not operate a server that stores your diary.

## The short version

Your diary stays on your device. Bitey does not sell your data, show ads, or
use third-party analytics or advertising SDKs. Two features send something off
your device, and only when you use them:

- **Barcode lookups** send a barcode you scanned directly to Open Food Facts.
- **Bitey AI**, an optional paid feature, sends a food photograph you choose —
  and any correction you type — to Bitey’s analysis server, where Google’s
  Gemini reads it. Bitey asks for your consent before the first photograph is
  sent.

Nothing else leaves your device: not your diary, weight, profile, or other
photos.

## Information Bitey stores on your device

Bitey stores the following information in Android app-private storage on the
device where you use it:

- Food diary entries, including food names, portions, meal times, nutrition
  values, and whether an item was entered manually, from a barcode, or as a
  quick bite.
- Weigh-ins and the trend calculations made from them.
- Profile details you choose to enter, such as height, weight, birth year,
  activity level, dietary preference, and nutrition goal. These are health-
  related details.
- Food photographs that you choose to save with diary entries.
- Cached food and nutrition information for barcodes you have looked up.
- Your app language and your preference for whether future Open Food Facts
  lookups may proceed without being asked again.

This information is used only to provide Bitey’s diary, nutrition, trend,
search, language, and backup features on your device. We do not receive a
copy of it.

## Camera and photographs

Bitey requests camera access only when you choose a feature that needs it,
such as scanning a barcode or taking a food photograph. Barcode reading happens
on-device. Food photographs are stored on your device when you save the
associated diary entry. A photograph leaves your device only if you send it to
Bitey AI, as described next.

## Bitey AI photo analysis

Bitey AI is an optional paid subscription, bought and managed through Google
Play. When you use it, Bitey estimates what is on your plate from a photograph.
Before the first photograph is sent, Bitey shows what will be sent and asks for
your consent. You can withdraw consent at any time in Bitey’s Settings; the
next photograph will ask again.

**What is sent.** Each time you ask Bitey AI to read a photograph, your device
sends, over HTTPS, to Bitey’s analysis server (a Google Cloud function operated
by the developer):

- The photograph you took or chose.
- Any correction you typed about it, such as “it is vegetarian”.
- Your app language, so the answer comes back in it.
- A Google Play purchase token: an opaque code that identifies your
  subscription purchase. It is not your Google account and does not name you.
- Standard connection information that any internet service receives, such as
  your IP address and request time.

Your diary, weight, profile, other photographs and backups are not sent.

**What happens to it.** The server asks Google Play whether the purchase token
belongs to an active Bitey AI subscription, then passes the photograph and your
correction to Google’s Gemini, which returns an estimate of the foods and
nutrition. Google processes the photograph on the developer’s behalf under the
Gemini API’s terms for paid services, which do not permit Google to use it to
improve Google’s products. Google may keep it for a limited period to detect
abuse, as those terms describe. The estimate comes back to your device, where
you review and save it like any other diary entry.

**What is kept.** Bitey’s server does not store the photograph, your
correction, or the estimate. To enforce the daily limit of readings, it keeps a
counter for each subscription and day: a one-way hash of the purchase token,
the date, and the number of readings used. The token itself is not stored. Each
counter is deleted automatically seven days after its day ends. The server’s
logs record technical details — the hash, token counts, and whether a request
succeeded — but not the photograph, your correction, or the estimate.

## Optional Open Food Facts barcode lookups

Bitey includes a packaged food database and first checks food data already
stored on your device. If a scanned barcode is not cached, Bitey asks before
contacting Open Food Facts. You may allow that one lookup, decline it and enter
the food manually, or choose “always allow” for future cache misses. You can
turn that setting off in Bitey’s Settings at any time.

When you allow a lookup, Bitey sends the barcode directly from your device to
Open Food Facts. Open Food Facts receives:

- The barcode you scanned.
- Standard connection information that any internet service receives, such as
  your IP address, request time, and a non-personal Bitey app identifier.

Bitey does not send your diary, profile, weight, food photographs, account
information, or a persistent device identifier with this request. If Open Food
Facts returns a product, Bitey saves its nutrition data only on your device for
later offline use. Open Food Facts is an independent service; its handling of
the request is governed by its own privacy information.

## Search, analytics, advertising, and tracking

Manual food search uses a food table packaged with the app. It does not send
your search terms to the developer or a search provider.

Bitey does not include advertising, advertising identifiers, behavioral
analytics, or third-party analytics SDKs. It does not sell, rent, or share
your personal or health-related information for advertising or marketing.

## Backups and exports

When you choose to export a backup, Bitey creates a ZIP file containing the
diary data and, if selected, your photos. The app writes the file only to the
location you choose through Android’s file picker. Bitey does not upload or
sync the backup. If you choose a cloud folder, email it, or otherwise share it,
the provider or recipient you choose handles that copy under its own terms.

Backups can contain sensitive health-related and photo data. Keep them private.

## Retention and deletion

Your data remains on your device until you delete it, replace it by restoring a
backup, clear Bitey’s storage in Android Settings, or uninstall Bitey. Deleting
or uninstalling the app removes the app’s local copy; because Bitey does not
operate a diary server, there is no remote diary copy for us to delete.

Exported backups are separate files. Deleting the app does not delete backups
you saved elsewhere; delete those files yourself if you no longer want them.
Cached barcode foods are removed with Bitey’s app storage.

For Bitey AI, the daily reading counters described above are deleted
automatically seven days after their day ends. Your subscription itself is held
by Google Play; cancel it in Google Play’s subscription settings.

## Security

Bitey keeps diary data in Android app-private storage and limits its packaged
WebView to the app’s own local interface. Optional Open Food Facts requests and
Bitey AI requests use HTTPS. The Google Play purchase token is handled only by
Bitey’s native code and is never exposed to the app’s web interface. No method of storage or transmission is completely secure, so protect
your device and any backups with the security controls available to you.

## Children and health information

Bitey is not a medical device and does not provide medical advice. Do not use
it to diagnose, treat, cure, or prevent a medical condition. The app is not
directed to children.

## Changes to this notice

If Bitey’s data practices change, for example if a future version adds
account-based syncing, this notice will be updated before or when that change
is released. Material new handling of sensitive data will also be explained in
the app where required.

## Contact

For privacy questions, requests, or concerns, contact the developer at the
privacy contact shown at the top of this notice.

---

## Publication checklist — not part of the public notice

1. Replace the bold privacy-contact placeholder with a working monitored email
   address and make the same contact available in the Google Play listing.
2. Publish this notice at a public, non-geofenced, non-editable HTTPS URL
   (not a PDF), then add that URL to Play Console and an in-app Privacy Notice
   link.
3. Complete Play Console’s Data safety form from the released binary, not from
   this document alone. Audit the optional Open Food Facts barcode transfer,
   the camera permission, and — from 1.0.5 — the Bitey AI photo and purchase
   token transfer in particular.
4. Before 1.0.5 reaches testers: enable the Firestore TTL policy on collection
   group `ai_usage`, field `expireAt`. The seven-day retention stated above
   depends on it; without it the counters are never deleted.
5. Update this notice, the Data safety form, and the in-app disclosure before
   enabling cloud sync, analytics, or any other new third-party data flow.
