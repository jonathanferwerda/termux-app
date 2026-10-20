#!/bin/sh
# Build the Tailscale Android Go AAR used by app/libs/libtailscale.aar.
# The AAR is a build input (gitignored); run this once on a build machine.
# Needs: git, Go, Android SDK, NDK 23.1.7779620, JDK 17.
set -eu

SRC="${TAILSCALE_ANDROID_SRC:-$HOME/tailscale-android}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
JAVA_HOME="${JAVA_HOME:-$HOME/jdk/jdk-17.0.20.1+1}"
DEST="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/app/libs/libtailscale.aar"

if [ ! -d "$SRC" ]; then
    git clone --depth 1 https://github.com/tailscale/tailscale-android "$SRC"
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
export GOMAXPROCS=4

make -C "$SRC" -j4 PWD="$SRC" ANDROID_HOME="$SDK" androidsdk
make -C "$SRC" -j4 PWD="$SRC" ANDROID_HOME="$SDK" GOMAXPROCS=4 libtailscale

mkdir -p "$(dirname "$DEST")"
cp "$SRC/android/libs/libtailscale.aar" "$DEST"
echo "staged $DEST"
