#!/usr/bin/env bash
# Installs the Android command-line tools and the SDK pieces the build
# needs. Idempotent: a second run exits immediately.
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-/usr/local/android-sdk}"
# Pinned rather than "latest": an SDK that changes under a container
# rebuild is a build that breaks for reasons no commit explains.
CMDLINE_TOOLS_BUILD=13114758
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"

if [ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
	echo "Android SDK already installed at $SDK_ROOT"
	exit 0
fi

if ! command -v unzip >/dev/null; then
	sudo apt-get update
	sudo apt-get install -y --no-install-recommends unzip
fi

sudo mkdir -p "$SDK_ROOT"
sudo chown "$(id -u):$(id -g)" "$SDK_ROOT"

tmp="$(mktemp -d)"
curl -fsSL -o "$tmp/cmdline-tools.zip" \
	"https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
mkdir -p "$SDK_ROOT/cmdline-tools"
mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
rm -rf "$tmp"

# `yes` is killed by SIGPIPE the moment sdkmanager stops reading, which
# under pipefail is a failed pipeline and, under -e, the end of this
# script before a single package is installed. With pipefail off the
# pipeline reports sdkmanager's own status, which is the one that matters.
set +o pipefail
yes | "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
set -o pipefail
"$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "$PLATFORM" "$BUILD_TOOLS"
