#!/usr/bin/env python3
""""Hey Tetra" without a trained model: MFCC + DTW against the user's own takes.

Pipeline, as the phone runs it: loudness gate -> speech onset -> FFT -> is it speech -> MFCC -> DTW -> threshold.
The phone's WakeWord.kt mirrors the numbers here; change both together.

  tools/wake-test.py [folder]        (default ~/.cache/tetra-wake, made by tools/wake-record.sh)
  tools/wake-test.py [folder] --noise a.mp3 b.mp3 ...   adds hours of other speech as false-wake material
"""
import sys, os, wave, subprocess
import numpy as np

SR, HOP, WIN, NFFT = 16000, 160, 400, 512        # 10 ms hop, 25 ms window
N_MEL, N_CEP = 26, 13                            # c0 (loudness) is dropped, c1..c12 kept
GATE_ABS_DB, GATE_OVER_FLOOR_DB = -55.0, 9.0     # loudness gate: above both
ONSET_SILENCE = 15                               # frames (150 ms) of quiet before a speech onset counts
WINDOW = 160                                     # frames (1.6 s) looked at after an onset
PRE = 5                                          # frames kept before the onset
MIN_SPEECH = 15                                  # frames over the gate: a lone "Tetra" is 0.4 s with a stop inside


def read_wav(p):
    with wave.open(p) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, p
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768


def read_any(p, seconds=None):
    if p.endswith('.wav'):
        return read_wav(p)
    cmd = ['ffmpeg', '-v', 'quiet', '-i', p] + (['-t', str(seconds)] if seconds else []) + \
          ['-ac', '1', '-ar', str(SR), '-f', 's16le', '-']
    return np.frombuffer(subprocess.run(cmd, capture_output=True).stdout, np.int16).astype(np.float32) / 32768


def frames_db(x):
    n = len(x) // HOP
    f = x[:n * HOP].reshape(n, HOP)
    e = f.var(1)                                                   # mean removed: a mic's DC offset is not loudness
    return 10 * np.log10(e + 1e-10)


def mel_bank():
    mel = lambda f: 2595 * np.log10(1 + f / 700)
    imel = lambda m: 700 * (10 ** (m / 2595) - 1)
    pts = imel(np.linspace(mel(60), mel(7600), N_MEL + 2))
    bins = np.floor((NFFT + 1) * pts / SR).astype(int)
    fb = np.zeros((N_MEL, NFFT // 2 + 1))
    for i in range(N_MEL):
        a, b, c = bins[i], bins[i + 1], bins[i + 2]
        fb[i, a:b] = (np.arange(a, b) - a) / max(b - a, 1)
        fb[i, b:c] = (c - np.arange(b, c)) / max(c - b, 1)
    return fb


FB = mel_bank()
DCT = np.cos(np.pi / N_MEL * (np.arange(N_MEL) + 0.5)[None, :] * np.arange(N_CEP)[:, None])  # DCT-II rows
HAM = np.hamming(WIN)
SPEECH_BAND = (np.fft.rfftfreq(NFFT, 1 / SR) >= 250) & (np.fft.rfftfreq(NFFT, 1 / SR) <= 3500)


def spectrum(x):
    x = np.append(x[0], x[1:] - 0.97 * x[:-1])                     # pre-emphasis
    n = max(0, (len(x) - WIN) // HOP + 1)
    idx = np.arange(WIN)[None, :] + HOP * np.arange(n)[:, None]
    return np.abs(np.fft.rfft(x[idx] * HAM, NFFT)) ** 2


def mfcc(power):
    c = np.log(power @ FB.T + 1e-10) @ DCT.T
    # c0 (loudness) dropped. No cepstral mean normalisation: the takes and the listening share one mic, and measured
    # on the user's takes (15 Nov) it blurred the line between them and other speech, as did liftering and deltas.
    return c[:, 1:]


def trim(x):
    """A take's speech: the stretch around its loudest frame, gaps under 250 ms bridged; the first 250 ms (the mic
    settling) left out. The takes are recorded in quiet."""
    db = frames_db(x)
    db[:25] = -100
    loud = db > max(GATE_ABS_DB, np.percentile(db[25:], 10) + 12)
    a = b = int(np.argmax(db))
    while True:
        nxt = [i for i in range(a - 25, a) if i >= 0 and loud[i]]
        if not nxt: break
        a = nxt[0]
    while True:
        nxt = [i for i in range(b + 1, b + 26) if i < len(loud) and loud[i]]
        if not nxt: break
        b = nxt[-1]
    return x[max(0, a - 3) * HOP:(b + 4) * HOP]


def dtw_open_end(t, q):
    """Distance of template t against a prefix of q (start pinned, end free), averaged over the template's frames.
    Each template frame moves q on by 1 or 2 frames, or 0 but never twice running: q may be said at most twice as fast or
    slow. (Unlimited 0-moves let a lone "Tetra" fold the whole "Hey" onto one frame and score like "Hey Tetra".)"""
    n, m = len(t), len(q)
    d = np.sqrt(((t[:, None, :] - q[None, :, :]) ** 2).sum(2))
    moved = np.full(m, np.inf)                                     # arrived by a move of q
    stayed = np.full(m, np.inf)                                    # arrived by q staying
    moved[0] = d[0, 0]
    for i in range(1, n):
        best = np.minimum(moved, stayed)
        mv = np.full(m, np.inf)
        mv[1:] = best[:-1]
        mv[2:] = np.minimum(mv[2:], best[:-2])
        stayed = moved + d[i]
        moved = mv + d[i]
    D = np.minimum(moved, stayed)
    lo, hi = int(n * 0.6), min(m - 1, int(n * 1.7))                # said 40% faster .. 70% slower
    return float(D[lo:hi + 1].min() / n) if hi >= lo else np.inf


def score(feat, templates):
    return min(dtw_open_end(t, feat) for t in templates)          # the nearest take (measured better than the two nearest)


def stream(x, phrases, log=None):
    """Runs a recording as the phone would; phrases: name -> (templates, threshold), a wake when any scores under its own.
    Returns (wakes as seconds, onsets checked, share of frames past the gate); log gets (seconds, {name: score})."""
    db = frames_db(x)
    floor, quiet, wakes, checked, passed, busy_until = db[0], 99, [], 0, 0, -1
    for i, e in enumerate(db):
        floor = floor + (0.05 if e < floor else 0.0015) * (e - floor)  # falls fast, rises ~1.5 dB/s
        loud = e > GATE_ABS_DB and e > floor + GATE_OVER_FLOOR_DB
        passed += loud
        if loud and quiet >= ONSET_SILENCE and i > busy_until:
            a, b = max(0, i - PRE), i + WINDOW
            seg = x[a * HOP:b * HOP]
            p = spectrum(seg)
            # is it speech: most of the loud frames' energy in the voice band, and 0.15 s or more of it
            ldb = db[a:b][:len(p)]
            lf = ldb > max(GATE_ABS_DB, floor + GATE_OVER_FLOOR_DB)
            band = p[lf][:, SPEECH_BAND].sum() / max(p[lf].sum(), 1e-10) if lf.any() else 0
            if band > 0.5 and lf.sum() >= MIN_SPEECH:
                checked += 1
                f = mfcc(p)
                s = {k: score(f, t) for k, (t, _) in phrases.items()}
                if log is not None:
                    log.append((i * HOP / SR, s))
                if any(s[k] < th for k, (_, th) in phrases.items()):
                    wakes.append(i * HOP / SR)
                    busy_until = b                                  # the sheet opens; no second wake for this phrase
        quiet = 0 if loud else quiet + 1
    return wakes, checked, passed / max(len(db), 1)


# phrase -> (its takes' file prefix, held-out recordings); the first N_TAKES takes are its templates, as on the phone
PHRASES = {'Hey Tetra': ('template', ['template5', 'held1', 'fast', 'slow', 'far', 'quiet']),
           'OK Tetra': ('ok', ['ok5']), 'Tetra': ('tetra', ['tetra5'])}
N_TAKES = 4


def main():
    args = sys.argv[1:]
    noise = args[args.index('--noise') + 1:] if '--noise' in args else []
    args = args[:args.index('--noise')] if '--noise' in args else args
    d = os.path.expanduser(args[0] if args else '~/.cache/tetra-wake')
    w = lambda n: os.path.join(d, n + '.wav')
    feats, loo, med = {}, {}, {}
    print(f'1. Each phrase\'s {N_TAKES} takes, each against the other {N_TAKES - 1} (leave one out): DTW distance to the nearest')
    for name, (prefix, _) in PHRASES.items():
        if not os.path.exists(w(f'{prefix}{N_TAKES}')):
            continue
        takes = [trim(read_wav(w(f'{prefix}{i}'))) for i in range(1, N_TAKES + 1)]
        feats[name] = [mfcc(spectrum(t)) for t in takes]
        loo[name] = [score(np.vstack([f, np.zeros((40, f.shape[1]))]), [g for j, g in enumerate(feats[name]) if j != k])
                     for k, f in enumerate(feats[name])]
        med[name] = float(np.median(loo[name]))
        print(f'   {name:9s} takes ' + ' '.join(f'{len(t) / SR:.2f}s' for t in takes) + '   leave-one-out ' +
              ' '.join(f'{v:.2f}' for v in loo[name]) + f'   median {med[name]:.2f}')
    phrases = {k: (v, -1) for k, v in feats.items()}

    # every score below is the phrase's DTW distance over its median: the phone's threshold is FACTOR times the median
    print('\n2. Held-out takes, the full stream; each phrase\'s best score in the recording, as x its median')
    pos = {k: [v / med[k] for v in loo[k]] for k in feats}
    for name, (_, held) in PHRASES.items():
        for n in held:
            if name in feats and os.path.exists(w(n)):
                log = []
                stream(read_wav(w(n)), phrases, log)
                best = {k: min((s[k] for _, s in log), default=np.inf) / med[k] for k in feats}
                pos[name].append(best[name])
                print(f'   {name:9s} {n:9s} ' + '  '.join(f'{k} {v:.2f}' for k, v in best.items()))

    neg = {}
    srcs = [w(n) for n in ('near', 'near2') if os.path.exists(w(n))] + noise
    print('\n3. Other speech: every onset checked')
    for p in srcs:
        x = read_any(p, seconds=3600)
        log = []
        _, checked, passed = stream(x, phrases, log)
        if p == w('near') and 'Tetra' in feats:     # it opens with a lone "Tetra" (15 Nov), a take now, not a miss
            pos['Tetra'].append(min(s['Tetra'] for t, s in log if t < 2.5) / med['Tetra'])
            log = [(t, s) for t, s in log if t >= 2.5]
        neg[p] = (log, len(x) / SR)
        print(f'   {os.path.basename(p)[:50]:50s} {len(x) / SR / 60:5.1f} min, {checked} onsets checked, {passed:.0%} of frames past the gate')
    hrs = sum(v[1] for v in neg.values()) / 3600

    for name in feats:
        r = {p: np.array([s[name] / med[name] for _, s in log]) for p, (log, _) in neg.items()}
        print(f'\n4. {name}: held-out and leave-one-out worst x{max(pos[name]):.2f}; other speech nearest ' +
              ', '.join(f'{os.path.basename(p)[:12]} x{v.min():.2f}' for p, v in r.items() if len(v)))
        for f in np.round(np.arange(1.2, 1.81, 0.05), 2):
            fw = {p: int((v < f).sum()) for p, v in r.items()}
            print(f'   x{f:.2f}: caught {sum(v < f for v in pos[name])}/{len(pos[name])}   false wakes ' +
                  ', '.join(f'{os.path.basename(p)[:12]} {n}' for p, n in fw.items()) + (f'   = {sum(fw.values()) / hrs:.1f}/hour' if hrs else ''))
        for p, (log, _) in neg.items():
            near = sorted(log, key=lambda t: t[1][name])[:3]
            print(f'   nearest in {os.path.basename(p)[:30]}: ' + ', '.join(f'{t:.1f}s (x{s[name] / med[name]:.2f})' for t, s in near))


if __name__ == '__main__':
    main()
