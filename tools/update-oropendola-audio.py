#!/usr/bin/env python3
"""Rebuild the real oropendola notification excerpt from the documented CC0 source."""
import argparse
from array import array
import hashlib
import math
from pathlib import Path
import subprocess
import sys
import wave

ROOT = Path(__file__).resolve().parent.parent
SOURCE_SHA256 = "35ca15c48a811081bfa0e9615da5495cc7016f77ab0c15237ae15db1087fa545"
SAMPLE_RATE = 48_000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Freesound 512109 high-quality MP3 preview")
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/sounds/oropendola.pcm")
    parser.add_argument("--preview", type=Path, default=ROOT / "build/audio-previews/oropendola-bloop.wav")
    args = parser.parse_args()
    if hashlib.sha256(args.source.read_bytes()).hexdigest() != SOURCE_SHA256:
        parser.error("Source checksum differs from the documented recording; check its provenance before updating")
    # Keep the original bird's pitch and timing. Remove rumble and soften the higher harmonics.
    result = subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(args.source),
        "-af", "atrim=start=6.15:end=7.35,asetpts=PTS-STARTPTS,highpass=f=250,lowpass=f=3500",
        "-ar", str(SAMPLE_RATE), "-ac", "1", "-c:a", "pcm_s16le", "-f", "s16le", "pipe:1",
    ], check=True, stdout=subprocess.PIPE)
    samples = array("h", result.stdout)
    if sys.byteorder != "little":
        samples.byteswap()
    if len(samples) != 57_600:
        raise ValueError("Expected the complete 1.2-second excerpt")
    peak = max(abs(sample) for sample in samples)
    if peak == 0:
        raise ValueError("Source excerpt is silent")
    gain = 0.90 * 32767 / peak
    fade_in = round(0.015 * SAMPLE_RATE)
    fade_out = round(0.035 * SAMPLE_RATE)
    for i, sample in enumerate(samples):
        attack = (1 - math.cos(math.pi * min(i / fade_in, 1))) / 2
        release = (1 - math.cos(math.pi * min((len(samples) - 1 - i) / fade_out, 1))) / 2
        samples[i] = round(sample * gain * min(attack, release))
    if sys.byteorder != "little":
        samples.byteswap()
    payload = samples.tobytes()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)
    args.preview.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(args.preview), "wb") as preview:
        preview.setnchannels(1)
        preview.setsampwidth(2)
        preview.setframerate(SAMPLE_RATE)
        preview.writeframes(payload)
    print(f"Wrote {len(payload)} bytes: {args.output}")
    print(f"Preview: {args.preview}")
    print(f"PCM SHA-256: {hashlib.sha256(payload).hexdigest()}")


if __name__ == "__main__":
    main()
