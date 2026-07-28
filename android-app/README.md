# Freenet for Android

Port of the [Freenet](https://freenet.org) decentralized P2P platform to Android.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  Android Kotlin App                                  │
│  ┌────────────────┐  ┌────────────────────────┐      │
│  │   MainActivity  │  │   FreenetService        │      │
│  │   (Dashboard)   │  │   (Foreground Service)  │      │
│  └───────┬─────────┘  └───────────┬────────────┘      │
│          │ WebSocket              │ JNI                │
├──────────┼────────────────────────┼────────────────────┤
│          ▼                        ▼                     │
│  ┌─────────────────────────────────────────────┐      │
│  │  Rust Native Library (libfreenet_android.so) │      │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐   │      │
│  │  │ WS API   │  │ Local    │  │ Network  │   │      │
│  │  │ Server   │  │ Executor │  │ Node     │   │      │
│  │  └──────────┘  └──────────┘  └──────────┘   │      │
│  │  ┌──────────┐  ┌──────────┐                 │      │
│  │  │ Keystore │  │ Contract │                 │      │
│  │  │ Bridge   │  │ Runtime  │                 │      │
│  │  └──────────┘  └──────────┘                 │      │
│  └─────────────────────────────────────────────┘      │
└─────────────────────────────────────────────────────┘
```

## Project Structure

```
freenet-android/
├── app/                          # Android Kotlin app
│   ├── app/
│   │   ├── build.gradle.kts      # App build config
│   │   ├── proguard-rules.pro    # Keep JNI methods
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/freenet/android/
│   │       │   ├── FreenetNode.kt       # JNI bridge
│   │       │   ├── FreenetService.kt     # Foreground service
│   │       │   ├── MainActivity.kt       # Dashboard
│   │       │   └── FreenetApplication.kt
│   │       └── res/
│   ├── build.gradle.kts          # Root build config
│   └── settings.gradle.kts
├── crates/android/               # Rust native lib (inside freenet-core workspace)
│   ├── Cargo.toml
│   ├── build.rs
│   ├── src/lib.rs                # JNI exports + node lifecycle
│   └── tests/integration.rs      # Unit + integration tests
├── scripts/
│   ├── test.sh                   # Test runner
│   └── e2e-test.sh               # Android device e2e test
└── build-android.sh              # One-command build
```

## Prerequisites

1. **Rust toolchain** with Android targets:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi
   cargo install cargo-ndk
   ```

2. **Android SDK + NDK** (v26+):
   ```bash
   sdkmanager --install 'ndk;27.0.12077973' 'platforms;android-34'
   export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973
   ```

3. **JDK 17+** for the Kotlin app

## Build

```bash
# Full build (Rust .so + Android APK)
./build-android.sh

# Release build
./build-android.sh --release

# Build + run tests
./build-android.sh --test
```

## Run Tests

```bash
# Rust unit + integration tests
cd crates/android && cargo test -- --test-threads=1

# Full test suite (no device needed for most tests)
./scripts/test.sh

# Android e2e (requires device/emulator)
./scripts/e2e-test.sh
```

## Install on Device

```bash
# After building:
adb install -r app/app/build/outputs/apk/debug/app-debug.apk

# Or use the e2e test script:
./scripts/e2e-test.sh
```

## How It Works

### Node Lifecycle
1. **`FreenetService`** starts as an Android foreground service
2. On start, it initializes the Android Keystore KEK and calls JNI's `FreenetNode.start()`
3. The Rust library starts the Freenet core (headless, no GUI deps)
4. WebSocket API is served on `127.0.0.1:<auto-port>`
5. The Android UI connects via WebSocket to the local node
6. On stop, `FreenetNode.stop()` gracefully shuts down the node

### Key Differences from Desktop
| Desktop | Android |
|---------|---------|
| `tao`/`wry` GUI | No GUI — headless + WebView |
| `keyring` for KEK | Android Keystore (JNI bridge) |
| System tray | Foreground service notification |
| Desktop data dirs | App-internal storage |
| Always-online | Background sync scheduling |
| Unlimited memory | 128 MB WASM cache limit |
| 200+ connections | 50 max connections |

### KEK (Key Encryption Key)
- Generated in Android Keystore (hardware-backed on TEE devices)
- Passed to Rust via `provideKek()` JNI call
- Fallback: software-generated KEK stored in app-private dir
- Used to derive per-delegate DEKs via HKDF

## Test Plan

| Test | Scope | Status |
|------|-------|--------|
| Config validation | Rust unit | ✓ |
| KEK roundtrip | Rust unit | ✓ |
| Thread safety | Rust unit | ✓ |
| Node start/stop | Rust integration | ✓ |
| WS API reachable | Rust integration | ✓ |
| Start/stop cycles | Rust integration | ✓ |
| Contract CRUD | Rust integration (loopback) | TODO |
| APK install | Device e2e | ✓ |
| Node process lifecycle | Device e2e | ✓ |
| Background sync | Device e2e | TODO |
| 100-contract stress | Device e2e | TODO |
