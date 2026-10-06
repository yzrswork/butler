#!/usr/bin/env bash
# Fetches the Vosk Japanese small model and the Julius Dictation Kit acoustic model files.
# Large model files are not committed to this repository.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

VOSK_CACHE_DIR="$ROOT_DIR/.tools/vosk"
VOSK_MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
VOSK_MODEL_SHA256="efa092d280153a77615e9e0c7d7283e93e600de3d19d3bec686c57ef19d52eac"
VOSK_MODEL_NAME="vosk-model-small-ja-0.22.zip"
VOSK_MODEL_DIR="vosk-model-small-ja-0.22"

JULIUS_DICTATION_KIT_COMMIT="1ceb4dec245ef482918ca33c55c71d383dce145e"
JULIUS_REPO="https://github.com/julius-speech/dictation-kit.git"
JULIUS_CACHE_DIR="$ROOT_DIR/.tools/julius"
JULIUS_CHECKOUT_DIR="$JULIUS_CACHE_DIR/dictation-kit"

JULIUS_MODEL_1="jnas-tri-3k16-gid.binhmm"
JULIUS_MODEL_1_SHA256="5f427fa11189a4d49de9a9ef51e8b1159971ee1cf5e3656f42081cf69dbf0c98"

JULIUS_MODEL_2="logicalTri-3k16-gid.bin"
JULIUS_MODEL_2_SHA256="bb4673040fd2691b53dea5bc411dead26732336a5c997fb0f86f93818bbfac66"

sha256_of() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(sha256_of "$file")"
  [ "$actual" = "$expected" ]
}

fetch() {
  local url="$1"
  local dest="$2"
  local expected_sha256="$3"

  if [ -f "$dest" ] && verify_sha256 "$dest" "$expected_sha256"; then
    echo "fetch-deps: using cached $(basename "$dest")"
    return
  fi

  echo "fetch-deps: downloading $(basename "$dest")"
  curl -fL --retry 3 -o "$dest" "$url"

  if ! verify_sha256 "$dest" "$expected_sha256"; then
    echo "fetch-deps: sha256 mismatch for $dest" >&2
    exit 1
  fi
}

ensure_julius_checkout() {
  command -v git >/dev/null 2>&1 || {
    echo "fetch-deps: git is required for Julius Dictation Kit" >&2
    exit 1
  }

  git lfs version >/dev/null 2>&1 || {
    echo "fetch-deps: Git LFS is required for Julius Dictation Kit" >&2
    exit 1
  }

  local needs_checkout=1

  if [ -d "$JULIUS_CHECKOUT_DIR/.git" ]; then
    local current
    current="$(git -C "$JULIUS_CHECKOUT_DIR" rev-parse HEAD 2>/dev/null || true)"

    if [ "$current" = "$JULIUS_DICTATION_KIT_COMMIT" ]; then
      needs_checkout=0
      echo "fetch-deps: using pinned Julius checkout $JULIUS_DICTATION_KIT_COMMIT"
    fi
  fi

  if [ "$needs_checkout" -eq 1 ]; then
    rm -rf "$JULIUS_CHECKOUT_DIR"

    echo "fetch-deps: cloning pinned Julius Dictation Kit"

    GIT_LFS_SKIP_SMUDGE=1 git clone \
      --filter=blob:none \
      --no-checkout \
      "$JULIUS_REPO" \
      "$JULIUS_CHECKOUT_DIR"

    git -C "$JULIUS_CHECKOUT_DIR" sparse-checkout init --cone
    git -C "$JULIUS_CHECKOUT_DIR" sparse-checkout set model/phone_m

    git -C "$JULIUS_CHECKOUT_DIR" fetch \
      --depth 1 \
      origin \
      "$JULIUS_DICTATION_KIT_COMMIT"

    GIT_LFS_SKIP_SMUDGE=1 git -C "$JULIUS_CHECKOUT_DIR" checkout \
      --detach \
      FETCH_HEAD
  fi

  local current
  current="$(git -C "$JULIUS_CHECKOUT_DIR" rev-parse HEAD)"

  if [ "$current" != "$JULIUS_DICTATION_KIT_COMMIT" ]; then
    echo "fetch-deps: Julius checkout is not at pinned commit" >&2
    exit 1
  fi

  echo "fetch-deps: pulling Julius Git LFS model objects"

  git -C "$JULIUS_CHECKOUT_DIR" lfs pull \
    --include="model/phone_m/*" \
    --exclude=""
}

copy_julius_model() {
  local name="$1"
  local expected="$2"
  local source="$JULIUS_CHECKOUT_DIR/model/phone_m/$name"
  local destination="$JULIUS_ASSETS_DIR/$name"

  if [ ! -f "$source" ]; then
    echo "fetch-deps: missing Julius model file: $name" >&2
    exit 1
  fi

  if ! verify_sha256 "$source" "$expected"; then
    echo "fetch-deps: sha256 mismatch for Julius model: $name" >&2
    exit 1
  fi

  echo "fetch-deps: verified $name"
  cp "$source" "$destination"

  # Clean up pointer files left by the old raw-download implementation.
  rm -f "$JULIUS_CACHE_DIR/$name"
}

mkdir -p "$VOSK_CACHE_DIR"
fetch "$VOSK_MODEL_URL" "$VOSK_CACHE_DIR/$VOSK_MODEL_NAME" "$VOSK_MODEL_SHA256"

VOSK_ASSETS_DIR="$ROOT_DIR/app/src/main/assets/vosk"
mkdir -p "$VOSK_ASSETS_DIR"
rm -rf "$VOSK_ASSETS_DIR/$VOSK_MODEL_DIR"
unzip -q -o "$VOSK_CACHE_DIR/$VOSK_MODEL_NAME" -d "$VOSK_ASSETS_DIR"

mkdir -p "$JULIUS_CACHE_DIR"
ensure_julius_checkout

JULIUS_ASSETS_DIR="$ROOT_DIR/app/src/main/assets/julius/model"
mkdir -p "$JULIUS_ASSETS_DIR"

copy_julius_model "$JULIUS_MODEL_1" "$JULIUS_MODEL_1_SHA256"
copy_julius_model "$JULIUS_MODEL_2" "$JULIUS_MODEL_2_SHA256"

cd "$ROOT_DIR"

verify_sum_file() {
  local sumfile="$1"
  local tmp
  tmp="$(mktemp)"

  # SHA256SUMS files may use CRLF line endings.
  # Normalize them before passing paths to shasum/sha256sum.
  tr -d '\r' < "$sumfile" > "$tmp"

  if command -v shasum >/dev/null 2>&1; then
    if ! shasum -a 256 -c "$tmp"; then
      rm -f "$tmp"
      return 1
    fi
  else
    if ! sha256sum -c "$tmp"; then
      rm -f "$tmp"
      return 1
    fi
  fi

  rm -f "$tmp"
}

verify_sum_file third_party/vosk/SHA256SUMS
verify_sum_file third_party/julius/SHA256SUMS

echo "fetch-deps: OK - the Vosk and Julius models are present and verified"
