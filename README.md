# Bitey — Private Food Log

Photograph a meal, correct the portion, and log calories and macros. Bitey also
supports typed foods, barcodes, recent foods, weight history, trends and export.
Photo estimates need review; the app’s design does not make a photograph a reliable
measurement of portion size or hidden ingredients.

This repository contains a **self-hosted PWA** and a **local-first Android client**.
They share UI/calculation code but have different storage, accounts, network and
AI/billing paths. The project was previously called Plate; internal package names
and compatibility routes may retain that name.

[Project overview](https://zandaulion.com/plate.html) · [PWA setup](#run-the-pwa-locally)
· [Android guide](android/README.md) · [Design and implementation](DESIGN.md)
· [Security](SECURITY.md)

## Choose a platform

| | Self-hosted PWA | Android |
| --- | --- | --- |
| Setup | Node.js server and private configuration | Android SDK/Gradle; see the native guide |
| Data | SQLite and photos on your server | App-private local data; see native storage boundaries |
| Access | Device invitations and account recovery | Local diary; separate optional premium AI entitlement |
| AI | Optional server-side provider key | Native entitlement/backend path described in the Android guide |
| Distribution | Deploy your own HTTPS origin | Source and store materials; follow native release prerequisites |

Do not assume a PWA account/database migrates automatically into the Android app.
The native guide records implemented functionality and outstanding release work;
this README does not claim a public Android release is available.

## Run the PWA locally

Use Node.js 24 or newer (`node:sqlite`), npm and `openssl`. In a fresh checkout:

```bash
npm ci
npm test
umask 077
printf 'ADMIN_TOKEN=%s\nPUBLIC_BASE_URL=http://127.0.0.1:3000\nPORT=3000\nCOOKIE_INSECURE=1\nDATA_DIR=./data\n' \
  "$(openssl rand -hex 32)" > .env
set -a
. ./.env
set +a
npm start
```

This creates an ignored private `.env`. Do not overwrite an existing deployment’s
configuration. In another terminal in the same checkout:

```bash
set -a
. ./.env
set +a
curl --fail http://127.0.0.1:3000/api/admin/invites \
  -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data '{"label":"Demo device"}'
```

Open the returned invitation URL and retain the account’s recovery code privately.
The API listens on loopback by default. `COOKIE_INSECURE=1` is only for this HTTP
trial; remove it for HTTPS. Configure a production proxy to block public admin
routes and strip caller-supplied admin headers. The shared invite console is
optional; direct token-authenticated admin calls work without it.

## PWA configuration

| Variable | Purpose |
| --- | --- |
| `ADMIN_TOKEN` | Required secret for private invitation/device administration. |
| `PUBLIC_BASE_URL` | Exact origin used for invitation links. |
| `DATA_DIR` | Database and photo directory; keep persistent and private. |
| `BIND_HOST`, `PORT` | Listener; use loopback unless a private container/proxy setup requires otherwise. |
| `COOKIE_INSECURE` | `1` permits local HTTP cookies; leave unset for HTTPS. |
| `GEMINI_API_KEY` | Optional credential for photo/description analysis. Without it AI requests fail while the basic app can run. |
| `GEMINI_MODEL` | Optional model override; see `server/` for the current default. |
| `USDA_API_KEY` | Optional key for generic food search. |

Photo/description analysis can send food input to the configured model provider;
read the [data-flow and design notes](DESIGN.md) before enabling it. Keep provider
keys out of the browser and Git. The Android client’s provider and billing setup
is documented separately in [android/README.md](android/README.md).

## Data, export and recovery

The PWA’s account owns history/profile data; devices are sessions for that account.
Settings supports device management and export. `/api/export.json`,
`/api/export.csv` and `/api/export.zip` provide records; the ZIP includes photos.
Exports are sensitive personal data, not public bug-report attachments.

For an operational backup, stop the server and copy the complete data directory
and private configuration to protected storage. Keep account recovery codes
separately. Restore into a stopped instance before restarting. Browser cache
clearing is not a server backup strategy.

## Development

```bash
npm test
npm run i18n:check
```

`core/` contains shared nutrition/day/analysis logic; `server/` is the Express API;
`web/` is the PWA; `android/` hosts the native app; `functions/` contains its cloud
integration. The detailed rationale and UI behavior live in [DESIGN.md](DESIGN.md).

Use Issues for reproducible bugs or focused proposals. Include version/runtime,
expected behavior and a minimal synthetic example. Never attach personal food
logs, photographs, account/device identifiers, credentials or live configuration.
Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License

[GNU GPL v3 or later](LICENSE). Third-party components retain their own terms.
