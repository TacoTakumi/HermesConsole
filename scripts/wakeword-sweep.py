#!/usr/bin/env python3
"""Replay a recorded wake-word session through sherpa-onnx, across models and thresholds.

A live measurement costs a human 90 seconds of standing in a room saying one word to a tablet.
Tuning a detector by guess-and-re-run spends the scarcest thing in this project. So the range
harness records what the microphone actually heard, and this replays that recording - the same
AEC-processed 16 kHz PCM the console listens on - through any model and any sensitivity, as many
times as the tuning takes.

What this CANNOT do is decide the acceptance. It scores one recording of one person on one day;
a config that wins here has only earned the right to be measured live (REQ-01: 8 of 10 at 2 m).
Tuning on the recording and then quoting the recording's own score as the result would be marking
your own homework.

Usage:
  scripts/wakeword-sweep.py build/wakeword/wakeword-session.pcm \
      --marks build/wakeword/wakeword-session.marks \
      --model <dir with encoder/decoder/joiner/tokens> [--model <dir> ...]
"""
from __future__ import annotations

import argparse
import pathlib
import sys

import numpy as np
import sherpa_onnx

SAMPLE_RATE = 16_000
FRAME_BYTES = 640  # 20 ms, the capture chunk - the marks file counts in these


def load_pcm(path: pathlib.Path) -> np.ndarray:
    raw = np.fromfile(path, dtype="<i2")
    return raw.astype(np.float32) / 32768.0


def load_marks(path: pathlib.Path) -> list[tuple[int, int, int, bool]]:
    """(trial, start_sample, end_sample, detected_live) per utterance window."""
    marks = []
    for line in path.read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        trial, start_frame, end_frame, live = line.split()
        marks.append(
            (
                int(trial),
                int(start_frame) * FRAME_BYTES // 2,
                int(end_frame) * FRAME_BYTES // 2,
                live == "true",
            )
        )
    return marks


def spotter_for(model: pathlib.Path, keywords: pathlib.Path, score: float, threshold: float):
    def one(*names: str) -> str:
        for name in names:
            hit = sorted(model.glob(name))
            if hit:
                return str(hit[0])
        sys.exit(f"{model}: no file matching {names}")

    return sherpa_onnx.KeywordSpotter(
        encoder=one("encoder*.int8.onnx", "encoder*.onnx"),
        decoder=one("decoder*.onnx"),
        joiner=one("joiner*.int8.onnx", "joiner*.onnx"),
        tokens=one("tokens.txt"),
        keywords_file=str(keywords),
        keywords_score=score,
        keywords_threshold=threshold,
        num_threads=2,
        provider="cpu",
    )


def score_run(spotter, samples: np.ndarray, marks) -> tuple[int, list[int], int]:
    """Detections per utterance window, replaying the session exactly as it was captured.

    The stream is fed in 20 ms chunks and never reset between windows, because that is what the
    console does: one continuously-open stream, all day. Resetting per window would measure a
    detector the product does not have.

    Returns hits AND false accepts - anything that fired outside an utterance window, where nobody
    was saying the wake word. A detector tuned until it hears "computer" everywhere would ace the
    hit count and be unusable, so the two numbers only mean anything together.
    """
    stream = spotter.create_stream()
    fired_at: list[int] = []
    for start in range(0, len(samples), FRAME_BYTES // 2):
        chunk = samples[start : start + FRAME_BYTES // 2]
        if len(chunk) == 0:
            break
        stream.accept_waveform(SAMPLE_RATE, chunk)
        while spotter.is_ready(stream):
            spotter.decode_stream(stream)
            if spotter.get_result(stream):
                spotter.reset_stream(stream)
                fired_at.append(start + len(chunk))

    hits = []
    for trial, lo, hi, _live in marks:
        if any(lo <= at <= hi for at in fired_at):
            hits.append(trial)
    inside = lambda at: any(lo <= at <= hi for _t, lo, hi, _l in marks)
    false_accepts = sum(1 for at in fired_at if not inside(at))
    return len(hits), hits, false_accepts


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("pcm", type=pathlib.Path)
    ap.add_argument("--marks", type=pathlib.Path, required=True)
    ap.add_argument("--model", type=pathlib.Path, action="append", required=True)
    ap.add_argument("--keywords", type=pathlib.Path, action="append", required=True,
                    help="keywords file; pair one per --model, in order")
    ap.add_argument("--score", type=float, nargs="*", default=[1.5, 3.0, 4.5])
    ap.add_argument("--threshold", type=float, nargs="*", default=[0.25, 0.15, 0.10, 0.05])
    args = ap.parse_args()

    samples = load_pcm(args.pcm)
    marks = load_marks(args.marks)
    live = sum(1 for *_, hit in marks if hit)
    print(f"{args.pcm.name}: {len(samples) / SAMPLE_RATE:.1f} s, {len(marks)} utterances "
          f"(live run detected {live})\n")

    if len(args.keywords) != len(args.model):
        sys.exit("pass one --keywords per --model, in the same order")

    best = None
    for model, keywords in zip(args.model, args.keywords):
        print(f"=== {model.name}  (keywords: {keywords.name})")
        for threshold in args.threshold:
            row = []
            for score in args.score:
                spotter = spotter_for(model, keywords, score, threshold)
                found, hits, false_accepts = score_run(spotter, samples, marks)
                row.append(f"score={score:<4} {found:2d}/{len(marks)} fa={false_accepts}")
                # A hit bought with a false accept is not a better detector. Rank on hits, but
                # never promote a config that starts hearing the wake word in an empty room.
                key = (found, -false_accepts)
                if best is None or key > best[0]:
                    best = (key, model.name, keywords.name, score, threshold, hits, false_accepts)
            print(f"  threshold={threshold:<5}  " + "   ".join(row))
        print()

    _key, model, keywords, score, threshold, hits, false_accepts = best
    print(f"BEST on this recording: {len(hits)}/{len(marks)} with {false_accepts} false accept(s)  "
          f"model={model} keywords={keywords} score={score} threshold={threshold}")
    print(f"  utterances detected: {hits}")
    print("\nThis is a tuning result, not an acceptance result. Confirm it live: "
          "scripts/wakeword-range.sh")


if __name__ == "__main__":
    main()
