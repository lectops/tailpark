# Tailpark

Tailpark keeps your BYD head unit on your tailnet after you switch the car off, so you can reach it remotely while it's parked.

It is an unofficial fork of the [Tailscale Android client](https://github.com/tailscale/tailscale-android) 1.104.4 with a parked-connectivity controller added. Tailpark is not affiliated with or endorsed by Tailscale Inc. or BYD. "Tailscale" is a trademark of Tailscale Inc.

## What it does

- Keeps Android awake after ACC off while the display stays dark.
- Rechecks and restores Wi-Fi while parked. Built-in cellular data also works.
- Runs a supervised helper that brings the VPN back when BYD's power policy kills the app.
- Stops parked operation when you disconnect, sign out, revoke VPN permission, or turn the setting off.
- Optionally lets the car sleep after remote activity stops, while active transfers renew the timeout. A Keep awake override covers quiet sessions.
- Resumes after a reboot or app update if you had connected before.
- Keeps a bounded local [connection timeline](docs/DIAGNOSTICS.md) to inspect, export or clear after an outage.

The package ID is `dev.tailpark.byd`, so it installs alongside the official Tailscale app. Android allows only one active VPN at a time.

## Tested on

| | |
| --- | --- |
| Vehicle | BYD Sealion 6 |
| Head unit | DiLink 3.0, Android 10 |
| Build | 1.104.1-byd.4 (pre-rename) for a week of daily use; 1.104.1-byd.5 for the release |

Other DiLink versions and models may work but have not been tested. Reports are welcome.

## Install

Download the APK from [Releases](../../releases), install it through the car's file manager, sign in, and grant VPN permission. Keep Android debugging enabled and accept the debugging prompt from `tailpark@headunit`, which lets the app start its power helper.

Full steps, including the optional ADB setup and moving from the pre-rename "Tailscale BYD" build, are in [docs/INSTALL.md](docs/INSTALL.md).

## Limitations

- **It needs internet where you park.** An underground basement with no cellular or Wi-Fi signal means no remote access until the car has signal again.
- **It keeps the head unit running while parked.** 12 V battery drain has not been measured. Don't leave parked mode on for days without checking your battery.
- Switching the car off interrupts the connection for roughly 35 seconds while the app recovers.
- Reboot recovery and Wi-Fi/cellular handover while parked have not been tested in a structured way.
- Android debugging must stay enabled on the head unit.

See [docs/TESTING.md](docs/TESTING.md) for what has and hasn't been verified.

## How it works

BYD's ACC-off policy kills ordinary app processes. Tailpark starts a small helper as the shell user through a loopback ADB connection. That helper holds the wake and Wi-Fi locks, keeps the display dark, and restarts the VPN through a protected activity when it disappears. A separate watchdog restarts the helper if it crashes. Details are in [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md).

## Build

Required tools:

- JDK 21
- Android SDK platform 36 and build-tools 36.0.0
- Android NDK 23.1.7779620
- Python 3, Git, Make, and the Android SDK command-line tools

Set `JAVA_HOME` and `ANDROID_HOME`, then run:

```sh
bash scripts/build-byd.sh
```

The build fetches the pinned Tailscale Go toolchain, builds the networking library, checks formatting, runs unit tests, watchdog process tests and release lint, and writes a signed APK to `outputs/tailpark/`.

Stable upstream updates are checked every six hours and built, tested, signed and published by GitHub Actions. See [automated releases](docs/RELEASING.md) for configuration and recovery steps.

The app also checks stable releases in **Settings → Updates**, with an optional **Automatically update while parked** setting that is off by default. See [in-app updates](docs/UPDATES.md) for validation, parked-state requirements and Android install confirmation fallback.

A signing key is generated in `.signing/` on first build. Keep that directory: Android only installs updates signed with the same key. Your own builds can't update the published release APK, and the reverse is also true.

## License

BSD-3-Clause, the same as upstream. Tailscale copyright and license notices are preserved; see [LICENSE](LICENSE). WireGuard is a registered trademark of Jason A. Donenfeld.
