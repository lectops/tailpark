# Tailscale BYD

An unofficial Tailscale Android client for BYD head units, with connectivity after ACC off while the display stays dark.

Based on [Tailscale Android](https://github.com/tailscale/tailscale-android) 1.104.1. This project is independent of Tailscale Inc. and BYD.

## Parked connectivity

- Keep Android awake after ACC off while controlling the display backlight separately.
- Recheck and restore Wi-Fi throughout parking, with built-in cellular data also supported.
- Supervise the power helper, recover a missing VPN, and reload helper code after APK updates.
- Stop parked operation when the user disconnects, signs out, revokes VPN permission, or disables the setting.
- Recover after reboot or app replacement when the connection was previously requested.

The application ID is `dev.tailscale.byd`, so it installs alongside the official client. Only one Android VPN can be active at a time. **Stay connected while parked** is enabled by default.

## Build

Required tools:

- JDK 21
- Android SDK platform 36 and build-tools 36.0.0
- Android NDK 23.1.7779620
- Python 3, Git, Make, and the Android SDK command-line tools

Set `JAVA_HOME` and `ANDROID_HOME` for your installation, then run:

```sh
bash scripts/build-byd.sh
```

The build fetches the pinned Tailscale Go toolchain, builds the networking library, checks formatting, runs unit tests, watchdog process tests and release lint, and creates a signed APK in `outputs/tailscale-byd/`.

The signing key and its password are generated locally in `.signing/`. Preserve that directory for future APK updates. Signing material, credentials, generated APKs, and local build artifacts are excluded from Git.

## Install

Install the APK, sign in, and grant VPN permission. Keep Android debugging enabled and accept the app's local debugging authorization request so it can start the power helper. An optional host ADB setup command is available if local authorization is unavailable.

See [installation instructions](docs/BYD-INSTALL.md) and [implementation details](docs/BYD-IMPLEMENTATION-PLAN.md).

## Compatibility and verification

The initial build targets DiLink 3.0 on Android 10. The application and helper are compiled and covered by host tests; ACC-off behavior still requires verification on the head unit. Parked access depends on retained head-unit power, the helper's Android power permission, and an available internet connection. Parked power consumption should be measured on the vehicle.

## License

BSD-3-Clause. Upstream Tailscale copyright and license notices are preserved; see [LICENSE](LICENSE). WireGuard is a registered trademark of Jason A. Donenfeld.
