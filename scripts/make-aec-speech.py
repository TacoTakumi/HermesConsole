"""Synthesize the AEC gate's speech fixture via a running VoiceManager: raw PCM16 mono.

This is the exact audio the console will emit (the live TTS engine, at the rate it declares on
the wire), so the hardware gate (T-04) measures the real failure mode rather than a stand-in tone.
The output is a build input for the instrumented gate, not a source file - it is gitignored.

    VM_TOKEN=... python3 scripts/make-aec-speech.py [--host localhost] [--port 8255]
"""
import argparse
import asyncio
import os
import sys

VOICEMANAGER_SRC = os.environ.get("VOICEMANAGER_SRC")
if not VOICEMANAGER_SRC:
    sys.exit("set VOICEMANAGER_SRC to the VoiceManager src directory, e.g. /home/you/VoiceManager/src")
sys.path.insert(0, VOICEMANAGER_SRC)
from voicemanager.client import VoiceManagerClient  # noqa: E402

parser = argparse.ArgumentParser()
parser.add_argument("--host", default=os.environ.get("VM_HOST", "localhost"))
parser.add_argument("--port", type=int, default=int(os.environ.get("VM_PORT", "8255")))
parser.add_argument(
    "--out",
    default=os.path.join(os.path.dirname(__file__), "..", "app", "src", "androidTest", "assets", "tts60.pcm"),
)
args = parser.parse_args()

# Never compiled in, never logged (REQ-28).
TOKEN = os.environ["VM_TOKEN"]
TARGET_SECONDS = 62

# Continuous, varied speech: plosives and sibilants are the parts an echo canceller struggles with.
SENTENCES = [
    "Computer, display the current sensor readings for the forward deflector array.",
    "The library computer database contains four hundred thirty two entries matching that query.",
    "Warp core efficiency is holding steady at ninety eight point six percent of rated output.",
    "Please specify the star system, the stardate range, and the classification level required.",
    "Sensors detect an unusual subspace distortion approximately point six parsecs off the port bow.",
    "Transferring the requested schematics to the engineering console for your review now.",
    "The structural integrity field is compensating for the increased gravimetric shear.",
    "Six seven eight nine ten. She sells seashells by the seashore on a Thursday afternoon.",
    "Acknowledged. Rerouting auxiliary power from environmental systems to the main sensor grid.",
    "That request requires command level authorization from a senior officer on the bridge.",
]


async def main() -> None:
    async with VoiceManagerClient(host=args.host, port=args.port, token=TOKEN) as client:
        pcm = bytearray()
        rate = None
        i = 0
        while True:
            text = SENTENCES[i % len(SENTENCES)]
            i += 1
            chunk, rate = await client.synthesize_tts(text)
            pcm += chunk
            seconds = len(pcm) / (rate * 2)
            print(f"  [{i:2d}] total {seconds:5.1f}s  rate={rate}", flush=True)
            if seconds >= TARGET_SECONDS:
                break
            pcm += b"\x00\x00" * int(rate * 0.25)  # a beat of silence, like a real turn

    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(pcm)
    print(f"\nwrote {out}: {len(pcm)/(rate*2):.1f}s @ {rate} Hz mono s16le")
    if rate != 24000:
        print(f"NOTE: the gate harness expects {rate} Hz - update TTS_RATE in AecHardwareGateTest.")


asyncio.run(main())
