"""Synthesize the two utterances the barge-in measurement scripts (T-14): raw 16 kHz mono PCM16.

The interruption has to be a *voice*, and it has to be the same voice every time - a human in the
room is neither. So the two things the scripted turn says are synthesized once, here, and injected
into the capture stream on the tablet: the question that starts the turn, and the sentence spoken
over the top of the answer.

16 kHz because that is what the capture path produces and what VoiceManager's STT expects - the TTS
engine speaks at 22.05 or 24 kHz, so it is resampled here rather than on the tablet. Normalized to a
fixed RMS, because the barge-in gate is an energy gate (-35 dBFS): a fixture that drifted quiet
would measure the fixture instead of the console.

The output is a build input for the instrumented test, not a source file - it is gitignored.

Run it with VoiceManager's own interpreter: it imports VoiceManager's async client, which the system
python does not have the dependencies for.

    VOICEMANAGER_SRC=/home/you/VoiceManager/src VM_API_KEY=... /home/you/VoiceManager/venv/bin/python \
        scripts/make-bargein-speech.py [--host localhost] [--port 8000]
"""
import argparse
import asyncio
import math
import os
import sys

import numpy as np

VOICEMANAGER_SRC = os.environ.get("VOICEMANAGER_SRC")
if not VOICEMANAGER_SRC:
    sys.exit("set VOICEMANAGER_SRC to the VoiceManager src directory, e.g. /home/you/VoiceManager/src")
sys.path.insert(0, VOICEMANAGER_SRC)
from voicemanager.client import VoiceManagerClient  # noqa: E402

ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "androidTest", "assets")

parser = argparse.ArgumentParser()
parser.add_argument("--host", default=os.environ.get("VM_HOST", "localhost"))
parser.add_argument("--port", type=int, default=int(os.environ.get("VM_PORT", "8000")))
parser.add_argument("--out-dir", default=ASSETS)
parser.add_argument("--only", help="synthesize just this fixture, leaving the others untouched")
args = parser.parse_args()

# Never compiled in, never logged (REQ-28).
API_KEY = os.environ["VM_API_KEY"]

CAPTURE_RATE = 16_000

# The console's frame: 20 ms of 16 kHz mono, and the unit the barge-in gate counts in.
FRAME = 320

# A person at the wall mount, as T-04 measured them: well clear of the -35 dBFS gate and nowhere
# near clipping.
TARGET_RMS_DBFS = -20.0

# Below this is the engine's leading silence, not the utterance. Nowhere near the -35 dBFS the
# barge-in gate is looking for - see trim_leading_silence().
SILENCE_FLOOR_DBFS = -60.0

UTTERANCES = {
    # The turn the console is answering when it gets interrupted. It asks for a long answer on
    # purpose: the echo canceller is armed by playback exposure (10 s of it, T-36), and an
    # interruption fired into a cold canceller is one the console is right to ignore.
    "bargein-question.pcm":
        "In ten sentences, with no preamble, describe what a starship warp core does.",
    # The interruption. Different words from the question and from any plausible answer to it, so
    # the transcript it produces cannot be mistaken for either - the test asserts that this, and
    # only this, is what turn N+1 was made of.
    "bargein-interruption.pcm": "Stop, and tell me about the shield frequency instead.",
    # T-15's turn-opener: one clause, one breath, no commas. A pause in the fixture is an
    # invitation for the endpoint to fire early - and then the fixture's own tail is a real voice
    # speaking over the reply, which the console rightly barges in for (REQ-04). That failure is
    # the harness talking over the console, not the console talking over itself, and the cheapest
    # fix is a sentence that gives the endpoint nowhere to land but the end.
    "t15-question.pcm": "Read me the complete evening status report for all decks and stations.",
}


def resample(pcm: bytes, src_rate: int) -> np.ndarray:
    """Linear resample to the capture rate. The fixture is speech, not a measurement signal."""
    samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float64)
    if src_rate == CAPTURE_RATE:
        return samples
    duration = len(samples) / src_rate
    target_len = int(duration * CAPTURE_RATE)
    src_t = np.arange(len(samples)) / src_rate
    dst_t = np.arange(target_len) / CAPTURE_RATE
    return np.interp(dst_t, src_t, samples)


def trim_leading_silence(samples: np.ndarray) -> np.ndarray:
    """Cut the silence the TTS engine puts in front of the utterance.

    It matters, and not because it is untidy. The requirement gives the console 500 ms from the
    moment a *person starts speaking*, and the fixture is standing in for the person - so a fixture
    that opens with half a second of digital silence (this one did: 440 ms) spends nearly all of the
    budget before the microphone has anything to hear, and then blames the console for it. The file
    starts where the sound does.

    The threshold is well below the barge-in gate's -35 dBFS: this trims silence, it does not hand
    the gate a head start on the quiet consonant the utterance opens with.
    """
    frames = samples[: len(samples) // FRAME * FRAME].reshape(-1, FRAME)
    rms = np.sqrt((frames**2).mean(axis=1)) / 32767.0
    db = 20 * np.log10(np.maximum(rms, 1e-9))
    audible = np.flatnonzero(db > SILENCE_FLOOR_DBFS)
    if len(audible) == 0:
        return samples
    onset = max(0, (audible[0] - 1)) * FRAME  # one frame of pre-roll, so nothing is clipped
    return samples[onset:]


def normalize(samples: np.ndarray) -> bytes:
    rms = math.sqrt(float(np.mean(samples**2))) / 32767.0
    gain = 10 ** (TARGET_RMS_DBFS / 20) / max(rms, 1e-9)
    scaled = np.clip(samples * gain, -32768, 32767).astype(np.int16)
    return scaled.tobytes()


async def main() -> None:
    os.makedirs(os.path.abspath(args.out_dir), exist_ok=True)
    async with VoiceManagerClient(host=args.host, port=args.port, api_key=API_KEY) as client:
        for name, text in UTTERANCES.items():
            if args.only and name != args.only:
                continue
            pcm, rate = await client.synthesize_tts(text)
            out_pcm = normalize(trim_leading_silence(resample(pcm, rate)))
            out = os.path.abspath(os.path.join(args.out_dir, name))
            with open(out, "wb") as f:
                f.write(out_pcm)
            seconds = len(out_pcm) / (CAPTURE_RATE * 2)
            print(f"wrote {out}: {seconds:.1f}s @ {CAPTURE_RATE} Hz mono s16le  <- {text!r}")


asyncio.run(main())
