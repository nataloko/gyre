#!/usr/bin/env bash
# Installs the exact SDK packages app/build.gradle.kts asks for.
#
# Shared by both workflows rather than duplicated, because the API level is the one thing here most
# likely to move, and it should move in one place.
set -euo pipefail

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    echo "Neither ANDROID_HOME nor ANDROID_SDK_ROOT is set; no Android SDK to install into." >&2
    exit 1
fi

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$sdkmanager" ]; then
    sdkmanager=$(command -v sdkmanager || true)
fi
if [ -z "$sdkmanager" ]; then
    echo "No sdkmanager under $ANDROID_HOME or on PATH." >&2
    exit 1
fi

# Accepting licences exits non-zero once there is nothing left to accept, and `yes` closing the pipe
# is not a failure either. Neither says anything about whether the packages install.
yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true

"$sdkmanager" 'platform-tools' 'platforms;android-37.0' 'build-tools;37.0.0'

# sdkmanager reports success for a package it silently did not install, so check for the artefacts
# the build and tools/package_release.py actually reach for.
for path in \
    "$ANDROID_HOME/platforms/android-37.0/android.jar" \
    "$ANDROID_HOME/build-tools/37.0.0/aapt2" \
    "$ANDROID_HOME/build-tools/37.0.0/apksigner"
do
    if [ ! -e "$path" ]; then
        echo "Expected $path after installing the SDK packages, but it is not there." >&2
        echo "API 37 may not be available in this runner image's SDK channel yet." >&2
        exit 1
    fi
done

echo "Android SDK ready: platforms;android-37.0, build-tools;37.0.0"
