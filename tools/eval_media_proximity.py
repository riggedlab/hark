#!/usr/bin/env python3
"""Replay the Android media context pass on labeled phone exports."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path, help="Phone export directory containing texts/")
    parser.add_argument("--media", nargs="+", required=True, metavar="RECORDING")
    parser.add_argument("--self", nargs="+", required=True, metavar="RECORDING")
    parser.add_argument("--runaway", metavar="RECORDING", help="Optional known ASR runaway to replay")
    args = parser.parse_args()
    names = args.media + args.self + ([args.runaway] if args.runaway else [])
    if len(names) != len(set(names)) or any(not re.fullmatch(r"\d{8}-\d{6}", name) for name in names):
        parser.error("Supply unique recording names in YYYYMMDD-HHMMSS format")
    texts = args.data.resolve() / "texts"
    missing = [name for name in names if not (texts / f"{name}.opus.json").is_file()]
    if missing:
        parser.error(f"Missing transcripts: {', '.join(missing)}")

    android = Path(__file__).resolve().parents[1] / "android"
    with tempfile.TemporaryDirectory(prefix="hark-media-replay-") as directory:
        manifest = Path(directory) / "manifest.json"
        result = Path(directory) / "result.txt"
        manifest.write_text(json.dumps({
            "texts": str(texts),
            "media": args.media,
            "self": args.self,
            "runaway": args.runaway or "",
            "result": str(result),
        }), encoding="utf-8")
        subprocess.run(
            ["./gradlew", ":app:testDebugUnitTest", "--tests", "*.MediaReplayTest", "--rerun"],
            cwd=android,
            env={**os.environ, "HARK_MEDIA_REPLAY_MANIFEST": str(manifest)},
            check=True,
        )
        print(result.read_text(encoding="utf-8"), end="")


if __name__ == "__main__":
    main()
