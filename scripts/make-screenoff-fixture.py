"""Synthesize the screen-off turn's wake phrase (T-29): raw 16 kHz mono PCM16 at
app/src/androidTest/assets/screenoff-wake.pcm.

The screen-off test (ScreenOffTurnTest) splices its utterances into the real capture stream the
way the barge-in measurement does (T-14) - but unlike T-14 it runs the SHIPPING keyword spotter,
because what T-29 is proving is that the wake-word engine still computes with the display off and
the CPU held only by the anchor's wake lock.

The voice is synthesized (Piper, local, no keys), and that is a measured choice, not a shortcut.
The obvious fixture - a T-33/T-34 range-session utterance cut - scores ZERO on the shipping
spotter config even replayed offline at any gain: those cuts are not shipping-path audio, and
what the spotter detected live in those sessions is not what those files contain. The Piper
phrase detects across a 20 dB plateau (-14 to -34 dBFS RMS, measured offline against the
shipping encoder/decoder/joiner at CAPTURE_GAIN 6x, score 1.5, threshold 0.15). The spotter's
performance on a HUMAN voice through the air is T-34's measurement, screen on, and stays one;
this fixture measures the engine running in the dark, not the room.

The level matters more than the voice: the detector applies 6x makeup gain because the
voice-communication path delivers a 2 m voice near -25 dBFS (WakeWordDetector.CAPTURE_GAIN).
A fixture normalized to studio level then hits that gain and saturates - the first cut of this
script did exactly that, and scored zero on-device. -28 dBFS RMS sits mid-plateau and mimics
what the stream actually carries.

    pip install piper-tts && python3 -m piper.download_voices en_US-lessac-medium
    python3 scripts/make-screenoff-fixture.py [--wav <your own hey-computer recording>]

The output is a build input for the instrumented test, not a source file - it is gitignored.
"""
import argparse
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(REPO, "app", "src", "androidTest", "assets")

CAPTURE_RATE = 16_000

# Mid-plateau for the shipping spotter behind its 6x gain: loud enough to detect, nowhere near
# saturating. Measured, not chosen - see the module docstring.
TARGET_RMS_DBFS = -28.0

parser = argparse.ArgumentParser()
parser.add_argument(
    "--wav",
    help="use this WAV of the wake phrase instead of synthesizing one (any rate, mono PCM16)",
)
parser.add_argument("--voice", default="en_US-lessac-medium", help="Piper voice to synthesize with")
args = parser.parse_args()

if args.wav:
    wav_path = args.wav
else:
    wav_path = os.path.join(tempfile.gettempdir(), "screenoff-wake-synth.wav")
    subprocess.run(
        [sys.executable, "-m", "piper", "-m", args.voice, "-f", wav_path, "--", "Hey computer!"],
        check=True,
    )

with wave.open(wav_path) as w:
    assert w.getnchannels() == 1 and w.getsampwidth() == 2, "expected mono PCM16"
    src_rate = w.getframerate()
    samples = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64)

if src_rate != CAPTURE_RATE:  # linear resample: the fixture is speech, not a measurement signal
    duration = len(samples) / src_rate
    src_t = np.arange(len(samples)) / src_rate
    dst_t = np.arange(int(duration * CAPTURE_RATE)) / CAPTURE_RATE
    samples = np.interp(dst_t, src_t, samples)

rms = np.sqrt(((samples / 32767.0) ** 2).mean())
samples = samples * (10 ** (TARGET_RMS_DBFS / 20) / max(rms, 1e-9))

out = os.path.join(ASSETS, "screenoff-wake.pcm")
os.makedirs(ASSETS, exist_ok=True)
with open(out, "wb") as f:
    f.write(samples.astype(np.int16).tobytes())
print(
    f"wrote {out}: {len(samples) / CAPTURE_RATE:.1f}s @ {CAPTURE_RATE} Hz mono s16le, "
    f"{TARGET_RMS_DBFS} dBFS RMS  <- {wav_path}"
)
