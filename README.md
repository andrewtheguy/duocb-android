# duocb-android

The Android peer of [duocb](https://github.com/andrewtheguy/duocb), the
end-to-end encrypted clipboard-sharing app, and the sibling of
[duocb-ios](../duocb-ios). A native Kotlin/Jetpack Compose app around the Rust
core (`libduocb.so`, built from the `duocb` repo's `duocb-ffi` crate): each
device holds its own application keypair and a signed identity card; two
devices come to trust each other by trading cards over a rotating PIN (card
setup); for a clipboard session both select the other from their trusted list
and the core assigns the listening and dialing halves from the two keys. The
networking runs in-process (iroh QUIC, with the rendezvous over mDNS and/or
nostr relays from the Rust core).

## Layout

| Path | What |
|---|---|
| `app/src/main/kotlin/com/andrewtheguy/duocb/` | `DuocbNative` (the JNI binding — its package and name are fixed by the symbols in `libduocb.so`), `SessionController` (the FFI handle, the event pump, the state machine), persistence (`SecretStore.kt`, `ConfigStore.kt`), the card/channel/clip models. |
| `app/src/main/kotlin/com/andrewtheguy/duocb/ui/` | The Compose screens: setup wizard, hub, trusted-device picker, card setup (PIN / pairing / confirm), the clipboard session, settings. |
| `app/src/debug/…/DebugAutostart.kt` | Debug-only E2E hook driven by launch-intent extras; `app/src/release/…` holds the no-op twin. |
| `app/src/test/` | JVM unit tests for the pure parts (clip fingerprints, the config document, the channel model). |

The Rust side of the boundary is documented in the core repo:
`crates/duocb-ffi/src/android.rs` (the JNI surface) and `ios/duocb.h` (the
config / event / card-info JSON shapes both mobile apps share).

## Requirements

- JDK 17, Android SDK with platform 37 (the Gradle wrapper brings Gradle
  itself; AGP 9 with built-in Kotlin). `local.properties` (git-ignored) points
  Gradle at the SDK: `sdk.dir=/path/to/Android/sdk`.
- An Android 10+ (`minSdk` 29) arm64 device or emulator (the app is arm64-v8a
  only). The app **targets Android 17 (SDK 37)**, where local-network traffic —
  the core's mDNS, the unicast side channel, a direct LAN path — needs the
  `ACCESS_LOCAL_NETWORK` runtime permission; the app asks for it before the
  first session on a LAN channel. The permission exists only from API 37; below
  that `INTERNET` grants local access implicitly and nothing is asked. On an
  API 37 device a scripted run wants it pre-granted:
  `adb shell pm grant com.andrewtheguy.duocb android.permission.ACCESS_LOCAL_NETWORK`.
- For FFI work: the sibling `../duocb` checkout, the Android NDK and
  `cargo-ndk` (see that repo's `build-android.sh`).

## Building

By default the app downloads the pinned `libduocb-android.zip` release asset of
the core repo (tag + sha256 in `gradle.properties`) and unpacks the
`jniLibs/<abi>/libduocb.so` tree into `app/build/duocb-jnilibs`:

```bash
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
ANDROID_SERIAL=<serial> ./gradlew :app:installDebug
```

Pin a newer core release with `scripts/bump-jnilibs.sh <tag>` (rewrites the
tag, the sha256, and the app version in `gradle.properties`). Until a duocb
release carries the Android asset the pin is empty and only the local build
below works.

CI (`.github/workflows/ci.yml`) runs the tests and uploads the debug APK of
every push/PR as the `app-debug` workflow artifact.

### Local FFI development

To run against a local build of the core instead of the pinned release, build
it in the sibling checkout and set `DUOCB_LOCAL_JNILIBS=1` (only the exact
value `1` opts in):

```bash
(cd ../duocb && ./build-android.sh release)           # arm64-v8a by default
DUOCB_LOCAL_JNILIBS=1 ANDROID_SERIAL=<serial> ./gradlew :app:installDebug
```

`scripts/run-device.sh` does all of it — builds the core for the device's ABI,
installs the debug APK, launches the app, and tails `logcat` for the `duocb`
tag (`--pinned` uses the release core, `--no-core` skips the rebuild). It
targets `ADB_SERIAL`, else `EMULATOR_SERIAL`, else the single attached device.

### Release APK

```bash
scripts/build-release-apk.sh            # → dist/duocb-android-<version>.apk
scripts/build-release-apk.sh --bundle   # → dist/duocb-android-<version>.aab
scripts/build-release-apk.sh --unsigned
RELEASE_DEVICE_SERIAL=<serial> scripts/install-release-apk.sh --launch
```

The release key is a keystore outside the repo (default
`~/.config/duocb-android/release.jks`, override with `DUOCB_KEYSTORE`; password
from `DUOCB_KEYSTORE_PASSWORD` or prompted). The script creates it on first
use — back it up. A release build cannot be installed over a debug build of
the app (different signature); uninstall the other one first.

## Using the app

1. **Set up this device**: create an identity (or restore a saved private key
   onto a replacement phone), then name it. The name plus a permanent suffix
   is what other devices see, e.g. `pixel_a7B2c3D4`.
2. **Trade cards** with your other device: show a PIN on one, type it on the
   other, and confirm the pairing code reads identically on both screens
   before trusting. (Or paste the other device's card from the trusted-device
   list — the copy-and-paste half of trust bootstrapping.)
3. **Connect**: on **both** devices pick the other from the trusted list. The
   order does not matter; the core decides who hosts. Send the clipboard or
   typed text; received items show size and CRC until you peek or copy them.

**Settings › How devices find each other** is the desktop's `--lan-only` /
`--nostr-only`: local network then internet (default), local network only
(fully offline, same Wi-Fi), or internet only. Both devices must share a
channel. Received text is never auto-copied to the clipboard.

## End-to-end test against the desktop app

Give the desktop its own config path (only one process may hold a config file):

```bash
cd ../duocb && cargo run -p duocb -- --config /tmp/duocb-desktop.json
```

Set up both sides, choose **Trade cards** on both — show the PIN on one, type
it on the other, and confirm the pairing code — then press Connect on both and
send text both ways, comparing the CRC readouts. `--lan-only` / `--nostr-only`
on the desktop must match the channel in the app's Settings.

### Driving the app from a terminal

Every CTA has a Compose `testTag` exposed as a `resource-id`, so
`uiautomator dump` finds it without coordinates:

```bash
adb shell 'uiautomator dump /sdcard/ui.xml >/dev/null; cat /sdcard/ui.xml' | grep -o 'resource-id="[a-z_]*"[^>]*bounds="[^"]*"'
adb shell input tap X Y                 # centre of the bounds
adb shell input text 'hello%sworld'     # %s for a space
adb exec-out screencap -p > shot.png
adb logcat -s duocb                     # Kotlin and the Rust core both log here
```

Tags: `create_identity`, `restore_identity`, `identity_field`, `use_key`,
`name_field`, `save_name`, `connect`, `trade_cards`, `trusted_devices`,
`settings`, `copy_card`, `rename`, `peer_<name>`, `paste_card`, `card_field`,
`trust_pasted`, `show_pin`, `pin_field`, `ip_field`, `join_pin`,
`pin_display`, `new_pin`, `cancel_session`, `pairing_code`,
`trust_incoming`, `reject_incoming`, `send_clipboard`, `compose_field`,
`send_text`, `copy_item`, `conn_path`, `stop`, `retry`, `reconnect`,
`channel_<wire>`, `show_key`, `reset_identity`, `confirm_reset`, `back`.

Debug builds also accept an **autostart** from the launch intent, so a harness
can set up an identity and start a session without the UI (the Android form of
the iOS `DUOCB_AUTOSTART_*` hook; see `app/src/debug/…/DebugAutostart.kt` for
every extra):

```bash
adb shell am start -n com.andrewtheguy.duocb/.MainActivity \
    --es duocb.autostart.role card_host --es duocb.autostart.name pixel
adb shell am start -n com.andrewtheguy.duocb/.MainActivity \
    --es duocb.autostart.role connect --es duocb.autostart.peer <hex public key> \
    --es duocb.autostart.peer_card "$(cat other-card.json)" --es duocb.autostart.send 'hello'
```

The debug config is readable with
`adb shell run-as com.andrewtheguy.duocb cat files/duocb/config.json`.

## Emulator networking

The stock Android Studio emulator sits behind QEMU user-mode NAT, so a device
outside it never sees its mDNS and every session goes over the relays (fine for
UI checks; the default channel falls back to nostr on its own). For direct
paths use a device bridged onto the LAN, or start the emulator with
`-feature -WiFiPacketStream -vmnet-bridged <host iface>` (sudo, Apple silicon)
as described in the ezvpn-android README.
