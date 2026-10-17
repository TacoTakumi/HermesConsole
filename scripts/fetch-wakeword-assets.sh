#!/usr/bin/env bash
# Fetches the sherpa-onnx AAR and KWS model files for the :wakeword module (not committed:
# ~62 MB of binaries). Run once per clone, before the first ./gradlew assembleDebug.
#
# SHA-256 pinned to artifacts vetted on 2026-06-12 in TVAgent, where this model was proven on
# real hardware. keywords.txt is NOT fetched - it is committed, and it must stay pre-tokenized:
# the KWS API does no runtime text->BPE encoding, so raw text silently never matches.
set -euo pipefail
cd "$(dirname "$0")/.."

AAR_VERSION=1.13.2
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${AAR_VERSION}/sherpa-onnx-${AAR_VERSION}.aar"
AAR_SHA256=aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245
AAR_DEST="wakeword/libs/sherpa-onnx-${AAR_VERSION}.aar"

MODEL_NAME=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${MODEL_NAME}.tar.bz2"
MODEL_SHA256=f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a
ASSETS_DIR=wakeword/src/main/assets/kws
# int8 encoder/joiner for Shield CPU headroom. NOT the -mobile tarball: its
# encoder is a broken export (Reshape 17-vs-16 crash on every runtime; verified 2026-06-12).
MODEL_FILES=(
  encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx
  decoder-epoch-12-avg-2-chunk-16-left-64.onnx
  joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx
  tokens.txt
)

fetch() { # url sha dest
  local url=$1 sha=$2 dest=$3
  if [[ -f $dest ]] && echo "$sha  $dest" | sha256sum -c --status; then
    echo "ok (cached): $dest"
    return
  fi
  curl -fSL -o "$dest.tmp" "$url"
  echo "$sha  $dest.tmp" | sha256sum -c -
  mv "$dest.tmp" "$dest"
}

mkdir -p wakeword/libs "$ASSETS_DIR"
fetch "$AAR_URL" "$AAR_SHA256" "$AAR_DEST"

# Check if all model files are already present (offline idempotent).
all_present=true
for f in "${MODEL_FILES[@]}"; do
  if [[ ! -f "${ASSETS_DIR}/${f}" ]] || [[ ! -s "${ASSETS_DIR}/${f}" ]]; then
    all_present=false
    break
  fi
done
if [[ $all_present == true ]]; then
  echo "ok (cached): $ASSETS_DIR"
  echo "Done. AAR in wakeword/libs/, model in ${ASSETS_DIR}/."
  exit 0
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
tarball="${tmpdir}/model.tar.bz2"
fetch "$MODEL_URL" "$MODEL_SHA256" "$tarball"
for f in "${MODEL_FILES[@]}"; do
  tar -xjf "$tarball" -C "$tmpdir" "${MODEL_NAME}/${f}"
  mv "$tmpdir/${MODEL_NAME}/${f}" "${ASSETS_DIR}/${f}"
done

echo "Done. AAR in wakeword/libs/, model in ${ASSETS_DIR}/."
