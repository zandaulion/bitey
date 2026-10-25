# Bitey screenshot inventory

Redone for the production listing on **11 October 2026** from the running
Android app (the 1.0.15 code, debug build) on a Motorola Edge 30 Neo, with
synthetic data. They show the real native layout, including the bottom action
bar. No device frame, artwork or marketing overlay has been added. All are
English, light theme, 24-bit PNG without transparency.

## Files

Phones are portrait; tablets are **landscape**, because that is where the
tablet layout shows: at 840 dp and wider the day becomes two columns (totals,
weight and quick bites on the left, meals on the right) and sheets open as
centred dialogs. A 7″ tablet in portrait is 600 dp wide and gets the phone
layout by design, so a portrait tablet capture would look like a large phone.

| Phone, 1080 × 1920 | 7″ tablet, 1920 × 1200 | 10″ tablet, 2560 × 1600 | Shows |
| --- | --- | --- | --- |
| `phone-01-day.png` | `tablet-7-01-day.png` | `tablet-10-01-day.png` | The day: total, what you burn (measured), macros, Bitey, weight trend, quick bites; on tablets, the meals beside them |
| `phone-02-meals.png` | — | — | The day's meals, one estimated from text |
| `phone-03-describe.png` | `tablet-7-03-describe.png` | `tablet-10-03-describe.png` | Describing a meal for Bitey AI, with search and exact numbers below |
| `phone-04-estimate.png` | `tablet-7-04-estimate.png` | `tablet-10-04-estimate.png` | The estimate to check and adjust: 659 kcal, wrap and latte |
| `phone-05-search.png` | `tablet-7-05-search.png` | `tablet-10-05-search.png` | Food search ("salmon") in the bundled table |
| `phone-06-trends.png` | `tablet-7-06-trends.png` | `tablet-10-06-trends.png` | 30-day weight and intake trends against what you burn |
| `phone-07-privacy.png` | `tablet-7-07-privacy.png` | `tablet-10-07-privacy.png` | Settings: Bitey AI, the diet switch, privacy |
| `phone-08-welcome.png` | `tablet-7-08-welcome.png` | `tablet-10-08-welcome.png` | The welcome's last screen: no good or bad foods, and the profile kept on the phone |

The eighth set was added on **25 October 2026**, captured the same way but on
the Galaxy Fold4's cover screen (same 420 dpi density and font scale as the
Motorola; Samsung's Edge panel handle switched off for the capture). The
seeded profile is complete, so the app itself would show only "Done" there;
the capture sets the footer to the "Maybe later" / "Add my details" pair a
first install sees. Eight is Play's limit for phone screenshots.

The tablets have no separate meals shot: the two-column day already shows them.
The phone images are 9:16 because Play refuses a screenshot whose long side is
more than twice its short side, which ruled out the phone's native 1080 × 2400.

## Synthetic data

Nothing belongs to a person. `scripts/store-screenshots/seed.js` makes a profile
(172 cm, born 1987, light activity), five weeks of weigh-ins drifting from
74.2 kg to about 72.3 kg, and five weeks of ordinary meals from a fixed list.
The estimate is a fixed stand-in (`stub.js`): an active subscription and a
2.5-second "server" that returns a chicken salad wrap (260 g) and a latte
(300 g). No purchase token, AI request or network call is involved.

## How they were captured

On the Lenovo, with the debug app installed on the test phone and the files in
`scripts/store-screenshots/` copied to `/tmp` (`stub.js` as
`/tmp/bitey-shots-stub.js`):

1. Seed once: open the app, then evaluate `seed.js` in its WebView through the
   devtools socket (`node /tmp/cdp-eval.mjs "$(cat /tmp/seed.js)"`).
2. Run `SERIAL=<adb serial> capture.sh <prefix> <width> <height> <status-bar> <nav-bar>`. It sets
   the display to the screenshot size plus the system bars, captures the eight
   screens, and the bars are cropped off afterwards. `SHOTS="08"` takes only
   the listed ones; `DISPLAY_ID` picks the screen on a phone with two (the
   Fold4 folded: its cover display's physical id, bars 104 and 39). The
   devtools helper it calls, `/tmp/cdp-eval.mjs`, is not kept in the repo: any
   script that sends one `Runtime.evaluate` to the first page on
   `localhost:9222/json` and prints the value will do.
   - phone: `capture.sh phone 1080 1920 100 126`
   - 7″ landscape: `wm density 320`, then `capture.sh tablet7 1920 1200 88 0`
     (960 × 617 dp)
   - 10″ landscape: `wm density 320`, then `capture.sh tablet10 2560 1600 88 0`
     (1280 × 809 dp)

   A display wider than it is tall puts the app in landscape; the phone does not
   need to be turned.
3. Reset the phone: `wm size reset` and `wm density reset`.

Launch the app with `am start`, never `monkey -p … 1`: monkey sends one random
input event after launching, which once logged a quick bite into the sample data.

`scripts/capture-entry-screenshots.cjs` is the earlier browser-based harness
(1.0.13). It no longer matches these files and is kept only for reference.

## Before publishing

Look at every image before uploading. These are English captures: attach them
to the English listing, and capture other languages in the app rather than
reusing these for translated listings.
