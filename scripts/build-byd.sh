#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
    export ANDROID_HOME="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
fi
# Keep local build paths out of the native library.
export GOFLAGS="${GOFLAGS:+$GOFLAGS }-trimpath"
if [[ "${CI:-}" == "true" ]]; then
    test -s .signing/byd.jks && test -s .signing/password
fi
python3 scripts/prepare-byd-signing.py
mkdir -p outputs/tailpark
python3 scripts/test-byd-watchdog.py --report outputs/tailpark/watchdog-tests.json
export NDK_ROOT="$ANDROID_HOME/ndk/23.1.7779620"
make libtailscale
(cd android && ./gradlew ktfmtCheck test lintVitalRelease assembleRelease)
mkdir -p outputs/tailpark
APK_NAME=$(python3 scripts/release_config.py)
cp android/build/outputs/apk/release/android-release.apk "outputs/tailpark/$APK_NAME"
python3 scripts/package-byd.py
