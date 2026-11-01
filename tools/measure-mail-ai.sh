#!/usr/bin/env bash
# Mail's and AI Assist's openings on a device, from the lines OpenTiming writes to writer.log (the desktop's
# scripts/measure-mail-ai.js is its pair; results in the Tetra repo's desktop/PERF_MAIL_AI.md). Usage:
#   tools/measure-mail-ai.sh [runs]        PHONE=<serial> for another device (default the phone, 208c8192)
# Each run: Tetra stopped, then Mail opened from cold; Mail again from Tetra's Home; AI Assist from cold, and its
# History tab. Nothing in the mail is opened, so nothing is marked read.
set -uo pipefail # a missing line or button is reported empty, not fatal
ADB=~/Android/Sdk/platform-tools/adb
PHONE=${PHONE:-208c8192}
RUNS=${1:-5}
PKG=com.kalotrapezis.drive
LOG=/sdcard/Android/data/$PKG/files/logs/writer.log
a() { "$ADB" -s "$PHONE" "$@"; }
applet() { a shell am start -n $PKG/.MainActivity --es $PKG.APPLET "$1" >/dev/null; }
# the newest line about [what] after [mark] lines of the log
line() { local from=$1; [ "$(count)" -lt "$from" ] && from=0; a shell "tail -n +$(( from + 1 )) $LOG" | grep -a "$2: opened" | tail -1 | sed 's/^.* I //'; } # the log turned over: all of it
count() { a shell "wc -l < $LOG" | tr -d '\r '; }
# taps the first node whose text or description is [1] (uiautomator), or returns 1
tap() {
  a shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1
  local b; b=$(a shell cat /sdcard/window.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$1\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -n "$b" ] || return 1
  set -- $b; a shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
for i in $(seq "$RUNS"); do
  a shell am force-stop $PKG; sleep 2
  m=$(count); applet Mail; sleep 9; echo "mail cold: $(line "$m" Mail)"
  # back to Tetra's Home (the list → its mailboxes → Home), then Mail's tile
  a shell input keyevent BACK; sleep 1; tap "Πίσω στην Αρχική"; sleep 2
  m=$(count); tap "Αλληλογραφία"; sleep 8; echo "mail again: $(line "$m" Mail)"
  # Tetra opened on its Home, Mail tapped a few seconds later (what the read-ahead is for)
  a shell am force-stop $PKG; sleep 2
  a shell am start -n $PKG/.MainActivity >/dev/null; sleep 6
  m=$(count); tap "Αλληλογραφία"; sleep 8; echo "mail after start: $(line "$m" Mail)"; echo "  $(a shell "tail -n +$(( m - 3 )) $LOG" | grep -a 'index read' | tail -1 || true | sed 's/^.* I //')"
  a shell am force-stop $PKG; sleep 2
  m=$(count); applet Assistant; sleep 9; echo "ai cold: $(line "$m" 'AI Assist')"
  # the phone's island: its handle opens the tabs (HISTORY is the tab's description, the handle has none: tapped over the field)
  m=$(count); tap "${HISTORY:-Ιστορικό}" || { a shell input tap "${HANDLE:-600 2331}"; sleep 1; tap "${HISTORY:-Ιστορικό}" || true; }; sleep 6
  echo "ai history: $(line "$m" 'AI History')"
done
a shell am force-stop $PKG
