#!/usr/bin/env bash
# Captures the Play screenshots from the debug app on the Motorola, run on lenovo.
# Usage: capture.sh <prefix> <width> <height> <status-bar-px> <nav-bar-px>
set -u
A="$HOME/Android/Sdk/platform-tools/adb -s ZY22G9C4WG"
P=$1; W=$2; H=$3; TOP=$4; NAV=$5
OUT=/tmp/bitey-shots; mkdir -p $OUT
js() { node /tmp/cdp-eval.mjs "$1" >/dev/null; }
shot() { sleep ${2:-1.5}; $A exec-out screencap -p > "$OUT/$P-$1.png"; }

$A shell wm size ${W}x$((H + TOP + NAV)) >/dev/null
$A shell am force-stop com.zandaulion.bitey.debug
$A shell am start -W -n com.zandaulion.bitey.debug/com.zandaulion.bitey.MainActivity >/dev/null
sleep 8
PID=$($A shell pidof com.zandaulion.bitey.debug)
$A forward --remove tcp:9222 >/dev/null 2>&1; $A forward tcp:9222 localabstract:webview_devtools_remote_$PID >/dev/null

shot 01-day 2
js "document.getElementById('entries-heading').scrollIntoView({block:'center'})"
shot 02-meals
js "window.scrollTo(0,0)"; sleep 1

# Describe a meal: stand-in subscription and a fixed estimate, nothing sent.
js "$(cat /tmp/bitey-shots-stub.js)"
js "document.getElementById('add-manual').click()"; sleep 1.5
js "const d=document.getElementById('meal-description'); d.value='Chicken salad wrap and a latte'; d.dispatchEvent(new Event('input')); d.blur()"
shot 03-describe
js "document.getElementById('estimate-text').click()"
shot 04-estimate 9
js "history.back()"; sleep 1.5

js "document.getElementById('add-manual').click()"; sleep 1.5
js "document.getElementById('text-search').click()"; sleep 1
js "const q=document.getElementById('food-q'); q.value='salmon'; q.dispatchEvent(new Event('input')); q.blur()"
shot 05-search 3
js "history.back()"; sleep 1.5

js "document.getElementById('open-trends').click()"
shot 06-trends 2.5
js "history.back()"; sleep 1.5

js "document.getElementById('open-settings').click()"; sleep 1.5
js "document.getElementById('native-ai-purchases')?.scrollIntoView({block:'start'})"
shot 07-privacy 1.5
js "history.back()"
