#!/usr/bin/env python3
"""Export YOLO26 weights to TFLite for the Android app."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", default="tools/yolo26n.pt", help="Path to YOLO .pt weights")
    parser.add_argument("--imgsz", type=int, default=320, help="Export input size")
    parser.add_argument("--output", default="", help="Output .tflite path. Defaults to app assets using weight stem.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    weights = Path(args.weights)
    output = Path(args.output) if args.output else Path("app/src/main/assets") / f"{weights.stem}.tflite"
    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise SystemExit("Install ultralytics first: pip install ultralytics") from exc

    model_source = str(weights) if weights.exists() else weights.name
    model = YOLO(model_source)
    exported = Path(
        model.export(
            format="tflite",
            imgsz=args.imgsz,
            int8=False,
            half=False,
            nms=False,
        )
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(exported, output)
    print(f"Exported {output}")


if __name__ == "__main__":
    main()
