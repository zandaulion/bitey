#!/usr/bin/env bash
# Deploys Bitey Beta's analysis function (functions/beta.js) as its own
# codebase, `bitey-ai-beta`, from a temporary copy of this checkout.
#
# Production's `analyse` (codebase `bitey-ai`) is never part of this deploy,
# and nothing in the checkout is changed: the copy gets its own firebase.json
# and package.json "main", and the tester token goes only into the copy's
# git-ignored functions/.env.
#
# The token is read from $BITEY_BETA_TOKEN_FILE (default
# ~/.config/plate/beta-tester.token), one token per line; the beta app is
# built with the same token in android/beta.properties.
#
# Usage: scripts/deploy-beta-function.sh [--project <firebase-project>]
set -euo pipefail

project=plate-cc703
[[ "${1:-}" == "--project" ]] && project="$2"
root="$(cd "$(dirname "$0")/.." && pwd)"
token_file="${BITEY_BETA_TOKEN_FILE:-$HOME/.config/plate/beta-tester.token}"
[[ -s "$token_file" ]] || { echo "No tester token at $token_file" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/scripts"
cp -r "$root/core" "$work/core"
cp "$root/scripts/sync-functions-core.mjs" "$work/scripts/"
# Dependencies are installed afresh below rather than copied.
tar -C "$root" --exclude=node_modules --exclude='.env*' -cf - functions | tar -C "$work" -xf -

node -e '
  const fs = require("fs");
  const pkg = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
  pkg.main = "beta.js";
  fs.writeFileSync(process.argv[1], JSON.stringify(pkg, null, 2) + "\n");
' "$work/functions/package.json"

cat > "$work/firebase.json" <<'JSON'
{
  "functions": [
    {
      "source": "functions",
      "codebase": "bitey-ai-beta",
      "ignore": ["node_modules", ".git", "*.local"],
      "predeploy": ["node scripts/sync-functions-core.mjs"]
    }
  ]
}
JSON

( umask 077; printf 'BETA_TESTER_TOKENS=%s\n' "$(grep -v '^\s*$' "$token_file" | paste -sd, -)" > "$work/functions/.env" )

(cd "$work/functions" && npm ci --silent)
(cd "$work" && firebase deploy --only functions:bitey-ai-beta --project "$project" --non-interactive)
