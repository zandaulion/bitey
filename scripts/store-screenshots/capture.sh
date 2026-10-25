#!/usr/bin/env bash
# Captures the Play screenshots from the debug app on a test phone, run on lenovo.
# Usage: SERIAL=... [DISPLAY_ID=...] [SHOTS="08 ..."] capture.sh <prefix> <width> <height> <status-bar-px> <nav-bar-px>
# SERIAL picks the phone (its adb serial) and DISPLAY_ID the screen to
# capture on a phone with two (the Fold4); SHOTS limits the run to the listed
# numbers, and without it all eight are taken.
set -u
A="$HOME/Android/Sdk/platform-tools/adb -s ${SERIAL:?set SERIAL to the adb serial of the test phone}"
P=$1; W=$2; H=$3; TOP=$4; NAV=$5
SHOTS=${SHOTS:-01 02 03 04 05 06 07 08}
OUT=/tmp/bitey-shots; mkdir -p $OUT
js() { node /tmp/cdp-eval.mjs "$1" >/dev/null; }
shot() { sleep ${2:-1.5}; $A exec-out screencap -p ${DISPLAY_ID:+-d $DISPLAY_ID} > "$OUT/$P-$1.png"; }
want() { [[ " $SHOTS " == *" $1 "* ]]; }

$A shell wm size ${W}x$((H + TOP + NAV)) >/dev/null
$A shell am force-stop com.zandaulion.bitey.debug
$A shell am start -W -n com.zandaulion.bitey.debug/com.zandaulion.bitey.MainActivity >/dev/null
sleep 8
PID=$($A shell pidof com.zandaulion.bitey.debug)
$A forward --remove tcp:9222 >/dev/null 2>&1; $A forward tcp:9222 localabstract:webview_devtools_remote_$PID >/dev/null

# A build with the welcome opens it once on launch over the seeded diary
# (the one-screen "what's new"); close it so the day is what shows.
js "if (!document.getElementById('welcome').hidden) document.getElementById('welcome-next').click()"; sleep 1

if want 01; then shot 01-day 2; fi
if want 02; then
  js "document.getElementById('entries-heading').scrollIntoView({block:'center'})"
  shot 02-meals
  js "window.scrollTo(0,0)"; sleep 1
fi

# Describe a meal: stand-in subscription and a fixed estimate, nothing sent.
if want 03 || want 04; then
  js "$(cat /tmp/bitey-shots-stub.js)"
  js "document.getElementById('add-manual').click()"; sleep 1.5
  js "const d=document.getElementById('meal-description'); d.value='Chicken salad wrap and a latte'; d.dispatchEvent(new Event('input')); d.blur()"
  if want 03; then shot 03-describe; fi
  if want 04; then
    js "document.getElementById('estimate-text').click()"
    shot 04-estimate 9
  fi
  js "history.back()"; sleep 1.5
fi

if want 05; then
  js "document.getElementById('add-manual').click()"; sleep 1.5
  js "document.getElementById('text-search').click()"; sleep 1
  js "const q=document.getElementById('food-q'); q.value='salmon'; q.dispatchEvent(new Event('input')); q.blur()"
  shot 05-search 3
  js "history.back()"; sleep 1.5
fi

if want 06; then
  js "document.getElementById('open-trends').click()"
  shot 06-trends 2.5
  js "history.back()"; sleep 1.5
fi

if want 07; then
  js "document.getElementById('open-settings').click()"; sleep 1.5
  js "document.getElementById('native-ai-purchases')?.scrollIntoView({block:'start'})"
  shot 07-privacy 1.5
  js "history.back()"; sleep 1.5
fi

# The welcome's last screen, as a first install sees it. The seeded profile is
# complete, so the app would offer only "Done"; the footer is set to the
# first-run pair it shows before any details are in.
if want 08; then
  js "document.getElementById('open-settings').click()"; sleep 1.5
  js "document.getElementById('welcome-again').click()"; sleep 1
  js "document.getElementById('welcome-next').click()"; sleep 0.8
  js "document.getElementById('welcome-next').click()"; sleep 0.8
  js "document.getElementById('welcome-later').hidden=false; document.getElementById('welcome-next').textContent='Add my details'"
  shot 08-welcome 1.5
  js "history.go(-4)"
fi
