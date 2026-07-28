#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Freenet Android — One-command Build
# =============================================================================
# Builds the Rust core for aarch64 Android, then packages the Android app.
#
# Prerequisites:
#   - Android NDK installed (set ANDROID_NDK_HOME or detected from SDK)
#   - Rust targets installed:
#       rustup target add aarch64-linux-android
#   - cargo-ndk installed:
#       cargo install cargo-ndk
#   - JDK 17+ and Android SDK (for the Kotlin app)
#
# Usage:
#   ./build-android.sh              # Debug build
#   ./build-android.sh --release    # Release build
#   ./build-android.sh --test       # Build + run tests
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# --- Config ---
ANDROID_TARGET="aarch64-linux-android"
RELEASE_FLAG=""
BUILD_MODE="debug"
TEST_MODE=false

for arg in "$@"; do
  case "$arg" in
    --release) RELEASE_FLAG="--release"; BUILD_MODE="release" ;;
    --test)    TEST_MODE=true ;;
  esac
done

# --- 1. Detect NDK ---
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  # Try to find it from common SDK locations
  CANDIDATES=(
    "$HOME/Android/Sdk/ndk"
    "$HOME/Library/Android/sdk/ndk"
    "/usr/local/lib/android/sdk/ndk"
    "/opt/android-sdk/ndk"
  )
  for ndk_root in "${CANDIDATES[@]}"; do
    if [ -d "$ndk_root" ]; then
      # Find the latest version
      ANDROID_NDK_HOME=$(ls -d "$ndk_root"/*/ 2>/dev/null | sort -V | tail -1 | sed 's/\/$//')
      [ -n "$ANDROID_NDK_HOME" ] && break
    fi
  done
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ERROR: Cannot find Android NDK. Set ANDROID_NDK_HOME or install SDK."
  echo "  SDK: https://developer.android.com/studio#command-line-tools-only"
  echo "  Then: sdkmanager --install 'ndk;27.0.12077973'"
  exit 1
fi
echo "Using NDK: $ANDROID_NDK_HOME"

# --- 2. Cross-compile Rust ---
echo ""
echo "=== Building Rust core for $ANDROID_TARGET ($BUILD_MODE) ==="
cd "$SCRIPT_DIR/../brAInstorm/freenet-core"

# Export NDK path for cargo-ndk
export ANDROID_NDK_HOME

cargo ndk \
  --target "$ANDROID_TARGET" \
  --platform 26 \
  $RELEASE_FLAG \
  build -p freenet-android

# --- 3. Locate the built .so ---
CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-target}"
if [ "$BUILD_MODE" = "release" ]; then
  SO_PATH="$CARGO_TARGET_DIR/$ANDROID_TARGET/release/libfreenet_android.so"
else
  SO_PATH="$CARGO_TARGET_DIR/$ANDROID_TARGET/debug/libfreenet_android.so"
fi

if [ ! -f "$SO_PATH" ]; then
  echo "ERROR: Built library not found at $SO_PATH"
  exit 1
fi
echo "Built: $SO_PATH ($(du -h "$SO_PATH" | cut -f1))"

# --- 4. Copy .so into Android app ---
JNI_DIR="$SCRIPT_DIR/app/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
cp "$SO_PATH" "$JNI_DIR/libfreenet_android.so"
echo "Copied to $JNI_DIR/libfreenet_android.so"

# --- 5. Build Android app (APK) ---
echo ""
echo "=== Building Android APK ==="
cd "$SCRIPT_DIR/app"
if [ -x "./gradlew" ]; then
  chmod +x gradlew
  if [ "$BUILD_MODE" = "release" ]; then
    ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
  else
    ./gradlew assembleDebug
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
  fi
  echo "APK: $SCRIPT_DIR/app/$APK_PATH"
else
  echo "WARN: No gradlew found. Install the app manually from Android Studio."
fi

# --- 6. Run tests if requested ---
if [ "$TEST_MODE" = true ]; then
  echo ""
  echo "=== Running Rust tests ==="
  cd "$SCRIPT_DIR/../brAInstorm/freenet-core"
  cargo test -p freenet-android 2>&1

  echo ""
  echo "=== Running Android instrumentation tests ==="
  cd "$SCRIPT_DIR/app"
  if [ -x "./gradlew" ]; then
    ./gradlew connectedCheck
  fi
fi

echo ""
echo "=== Build complete! ==="
echo "APK: $(find "$SCRIPT_DIR/app" -name '*.apk' -path '*/outputs/*' | head -1)"
echo "Install: adb install -r $(find "$SCRIPT_DIR/app" -name '*.apk' -path '*/outputs/*' | head -1)"
