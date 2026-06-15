#!/usr/bin/env bash
set -euo pipefail

REPO="${REPO:-GERnguyen/android_station_image_processing}"
TAG="${TAG:-models-v1}"
ASSET_DIR="${ASSET_DIR:-app/src/main/assets}"

MODELS=(
  "yolo26n.tflite"
  "yolo11n.tflite"
  "yolov8n.tflite"
  "yolo26s.tflite"
)

mkdir -p "$ASSET_DIR"

download() {
  local name="$1"
  local url="https://github.com/${REPO}/releases/download/${TAG}/${name}"
  echo "Downloading ${name}"
  curl -L --fail --retry 3 --retry-delay 2 -o "${ASSET_DIR}/${name}" "$url"
}

for model in "${MODELS[@]}"; do
  download "$model"
done

echo "Downloading SHA256SUMS.txt"
curl -L --fail --retry 3 --retry-delay 2 -o "${ASSET_DIR}/SHA256SUMS.txt" \
  "https://github.com/${REPO}/releases/download/${TAG}/SHA256SUMS.txt"

if command -v shasum >/dev/null 2>&1; then
  (
    cd "$ASSET_DIR"
    shasum -a 256 -c SHA256SUMS.txt
  )
else
  echo "shasum not found; skipped checksum verification"
fi

echo "Models are ready in ${ASSET_DIR}"
