#!/bin/bash
# Records the takes tools/wake-test.py needs: "Hey Tetra" takes and a minute of other speech.
# Usage: tools/wake-record.sh [folder]   (default ~/.cache/tetra-wake; recordings stay out of git)
set -e
D=${1:-$HOME/.cache/tetra-wake}
mkdir -p "$D"
rec() { # name seconds prompt
  read -rp "$3 — press Enter, then speak ($2 s)… " _
  arecord -q -f S16_LE -r 16000 -c 1 -d "$2" "$D/$1.wav"
  echo "  saved $1.wav"
}
echo "Recordings go to $D"
for i in 1 2 3 4 5; do rec "template$i" 3 "Take $i of 5: say \"Hey Tetra\" normally"; done
rec held1 3 "Once more, normally: \"Hey Tetra\""
rec fast 3 "Say \"Hey Tetra\" FAST"
rec slow 3 "Say \"Hey Tetra\" SLOWLY"
rec far 3 "Step back 2–3 metres and say \"Hey Tetra\""
rec quiet 3 "Back at the computer: say \"Hey Tetra\" quietly"
rec near 60 "Now a minute of normal talk — Greek and English, anything; include \"hey\", \"Tetra\", \"Πέτρα\", \"τετράδιο\", \"hey there\", \"Hey Siri\", but NOT \"Hey Tetra\""
echo "Done. Tell Claude it's recorded."
