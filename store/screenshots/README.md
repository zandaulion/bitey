# Bitey screenshot inventory

The entry screenshots were refreshed for **1.0.13** on **10 October 2026**.
They show the running application UI with synthetic fixtures in an isolated
Chromium browser using the Android WebView layout. They are browser captures,
not captures from an Android phone or emulator. No screen artwork, device frame
or marketing overlay has been added.

## Current entry screens

| File | Pixels | Content and alt text |
| --- | --- | --- |
| `02-manual-entry.png` | 1080 × 2400 | “Describe a meal to estimate calories and macros with Bitey AI, or choose free food search or exact-number entry.” Replaces the old combined manual editor; the lock on Estimate meal shows the subscription requirement. |
| `05-text-review.png` | 1080 × 2400 | “Review an estimated meal, its assumed portions, calories and macros; adjust each food before saving.” |
| `06-exact-numbers.png` | 1080 × 2400 | “Enter a food's portion and nutrition values directly from a label or menu.” This route does not request AI access. |
| `tablet-7-01-manual-entry.png` | 1200 × 1920 | “Meal description, Bitey AI estimate action, food search and exact-number entry at a small tablet width.” |
| `tablet-10-01-manual-entry.png` | 1600 × 2560 | “Meal description, Bitey AI estimate action, food search and exact-number entry at a large tablet width.” |

The phone entry sequence is **02 → 05 → 06**: describe, review, or use the free
manual route. The tablet filenames retain the existing inventory's size labels;
they describe viewport presets, not measured physical screen sizes.

## Synthetic data and capture method

The description is “Two eggs and a slice of cheese”. The review uses a fixed
fixture of 100 g eggs and 30 g cheddar, totalling 266 kcal, with the note
“Assumed two large eggs and 30 g cheddar.” These values illustrate the review UI;
they are not a live model result or a nutrition accuracy benchmark. The exact
entry uses “Example yogurt”, 170 g, 150 kcal, 10 g protein, 4 g fat, 18 g carbs
and 0 g fiber. None of these records belongs to a person.

[`scripts/capture-entry-screenshots.cjs`](../../scripts/capture-entry-screenshots.cjs)
loads the repository's `web/` and `core/` files, intercepts every network request,
and supplies an in-memory native bridge fixture. It has no live purchase token,
profile, diary, device identifier, subscription or AI connection. The simulated
entitlement response is confined to this documentation harness; production
billing, App Check and server entitlement checks remain enforced. The script
also checks that exact-number entry makes no AI request and the review renders
the expected fixture total without JavaScript errors.

With Playwright and its Chromium browser available, run from the repository root:

```sh
node scripts/capture-entry-screenshots.cjs
```

If Playwright is installed outside the repository, set `PLAYWRIGHT_MODULE` to
its module directory when running the command. It rewrites only the five files
listed above. Phone captures use a 360 × 800 CSS-pixel viewport at 3×; tablet
captures use 600 × 960 and 800 × 1280 at 2×. All use English and the light theme.

## Older captures and Play listing use

`00-fresh.png`, `01-your-day.png`, `03-trends.png`, `04-private-backup.png`, and
the other tablet images predate the new Type entry flow. Keep them as historical
references, not as evidence of the 1.0.13 layout. The trends screenshot shows an
empty state; the fresh screenshot is an internal reference rather than a listing
recommendation. A barcode-consent screenshot is still missing.

Before replacing live Play listing images, verify the entry sequence on Android
and capture the final set from the running Android build, including native
controls, keyboard behaviour and system insets. Use synthetic diary data, a
clean status bar and no notifications. Review every image and its metadata for
personal information before publishing it. Capture each listing's language in
the app; do not attach these English images to translated listings.
