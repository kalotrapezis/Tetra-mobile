# The Pills reminder's "ding ding" (the user, 7 Nov): two soft bell strokes, written to res/raw/pill_chime.wav.
# Usage: python3 tools/pill-chime.py app/src/main/res/raw/pill_chime.wav
import math, struct, sys, wave
RATE = 22050
def stroke(freq, length):
    out = []
    for i in range(int(RATE * length)):
        t = i / RATE
        attack = min(1.0, t / 0.004)
        v = sum(a * math.sin(2 * math.pi * freq * k * t) * math.exp(-t * d) for k, a, d in [(1, 1.0, 4.0), (2.0, 0.35, 7.0), (3.01, 0.12, 11.0)])
        out.append(attack * v)
    return out
s = stroke(1046.5, 0.55) + stroke(1318.5, 1.1)  # C6, then E6 left to ring
peak = max(abs(v) for v in s)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes(b"".join(struct.pack("<h", int(v / peak * 0.8 * 32767)) for v in s))
