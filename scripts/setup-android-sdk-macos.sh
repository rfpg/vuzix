#!/usr/bin/env bash
set -euo pipefail

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required. Install from https://brew.sh and rerun."
  exit 1
fi

if ! command -v sdkmanager >/dev/null 2>&1; then
  echo "Installing Android command-line tools via Homebrew..."
  brew install --cask android-commandlinetools
fi

ANDROID_SDK_ROOT_DEFAULT="$HOME/Library/Android/sdk"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK_ROOT_DEFAULT}"

mkdir -p "$ANDROID_SDK_ROOT"

if ! grep -q 'ANDROID_SDK_ROOT' "$HOME/.zshrc" 2>/dev/null; then
  {
    echo ''
    echo '# Android SDK'
    echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
    echo 'export ANDROID_HOME=$ANDROID_SDK_ROOT'
    echo 'export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin'
  } >> "$HOME/.zshrc"
fi

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$PATH:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "Android SDK setup complete."
echo "Run: source ~/.zshrc"