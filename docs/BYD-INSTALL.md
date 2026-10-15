# Tailscale BYD installation

Tailscale BYD 1.104.1 byd.4 adds parked-connectivity support to the full Tailscale client, enabled by default. It uses BYD's Android wake and backlight interfaces and a supervised power helper. Both built-in cellular data and an independent Wi-Fi hotspot are supported through Tailscale's existing network selection. On a DiLink3.0 Android 10 head unit, byd.4 automatically recovered from BYD's ACC-off process kill and remained reachable through Tailscale's internet relay after the driver left the car. Remote ADB, ACC-off power state and a dark screen were verified about ten minutes after shutdown. The shutdown transition interrupted connectivity for roughly 35 seconds; extended parking and power consumption still need measurement.

## Install and connect

1. Install `Tailscale-BYD-1.104.1-byd.4.apk` through the car file manager. Its package is `dev.tailscale.byd`, so it installs alongside the official client. Existing BYD installations can be updated with this APK.
2. Open **Tailscale BYD**, sign in, and grant Android VPN permission. Only one VPN can be active; connect the BYD client.
3. Keep Android debugging enabled on the head unit. If Android asks to allow debugging from `tailscale-byd@headunit`, select **Always allow** and accept. The app uses this local connection to start its power helper automatically.
4. Open **Settings**. **Stay connected while parked** is enabled by default. The parked status should report **Ready to stay connected when you park**. Wi-Fi retention is enabled; turn it off if you want cellular-only parked operation.
5. Turn ACC off and try connecting to the car from another Tailscale device. The expected behavior is that Android remains awake and the display remains dark. On ACC on, the app restores backlight changes it made and releases its display lock.

Disconnecting Tailscale, signing out, or turning off **Stay connected while parked** stops the helper and its watchdog and releases power locks. A persistent stop marker prevents automatic resurrection during a crash or retry. Reconnecting enables parked operation again. The boot receiver and helper recover the VPN only when it was previously requested and VPN permission is still granted.

An internal VPN restart preserves the parked-power request. If you disconnect while that restart is pending, the app respects the disconnect and leaves the VPN stopped.

## Optional setup from the Mac

If the head unit does not show or accept the app's local debugging request, start the same helper through an existing authorized ADB connection. Connect the BYD VPN first, then run:

```sh
python3 scripts/setup-byd-helper.py --serial YOUR_ADB_SERIAL
```

Run this command from the source repository. In a packaged delivery folder, use `python3 setup-byd-helper.py` instead. The APK retries its own local connection so it can re-establish the helper after a reboot once its debugging key is authorized.

For installation through ADB:

```sh
adb -s YOUR_ADB_SERIAL install -r Tailscale-BYD-1.104.1-byd.4.apk
```

## Implementation

The helper reads BYD's OEM power state every 500 ms. At power level zero it waits for display sleep, requests `PowerManager.wakeUp(uptimeMillis, 9, "ACC_ON")`, then darkens the panel after 500 ms. Every three seconds it checks the panel and applies backlight control when needed. Available OEM screen-status calls verify the result. A vendor display-lock fallback is used only when the screen remains lit and matching acquire/release methods exist; the helper retains its Binder token and does not stack acquisitions. ACC on restores its display changes.

Wi-Fi retention checks repeat every 30 seconds, starting three seconds after wake. The shell helper can use `svc wifi enable` if the framework refuses its Wi-Fi request. Cellular-only mode does not enable Wi-Fi. CPU and connected-Wi-Fi locks maintain availability; connection checks do not require location permission. Tailscale handles tunnel setup, encryption, login, and network changes.

The shell helper runs separately from the VPN process because BYD's ACC-off policy kills ordinary app packages. A separate shell watchdog restarts crashed or stuck helpers, reloads code after APK updates, and adopts a surviving helper if the watchdog was killed. It stores private runtime state under `/data/local/tmp/tailscale-byd-dev.tailscale.byd/`; its log is bounded to 5 MiB. Each fresh helper confirms the app's saved connection preference before acquiring locks.

Recovery distinguishes missing service heartbeats from a live service without an active Tailscale VPN. Retries back off to five minutes. A successful shell broadcast alone does not establish delivery: the receiver must acknowledge its request. When BYD discards a cold receiver, the helper launches a dedicated `Theme.NoDisplay` activity. It checks the same installation-specific secret and saved connection intent, requests our own VPN service, and finishes in `onCreate`. Both entrypoints require Android's `DUMP` permission. No Tailscale window or recent-app task is created. The same fallback lets a fresh helper verify preferences after an ACC-off crash instead of repeatedly exiting on a discarded broadcast.

The firmware's per-app background-policy API requires a signature permission denied to shell on the tested head unit. This client does not change its global autostart policy. Control listens only on `127.0.0.1:41642` and accepts fixed commands. It does not offer a remote shell or share Tailscale credentials.

The helper reports its observed power level, screen state, sample age and tick errors. It also saves `last-status.json` every five seconds in its private runtime directory, retaining it as `previous-status.json` when a new helper starts. After a connection drop or reboot, authorized ADB can retrieve these snapshots to help identify where parked operation stopped. They contain no login credentials or helper token.

During authorized helper setup, the shell grants this app `WRITE_SECURE_SETTINGS` when the firmware permits it. On Android 11 or later, the app can then reopen existing debugging settings that BYD cleared after shutdown while parked connectivity is requested. Android 10 does not need that recovery path. The app does not disable debugging-key expiry, change hidden-API policies, or alter global process limits.

Firmware must retain head-unit electrical power, grant the helper `DEVICE_POWER`, and keep at least one internet connection available. On 2026-10-15, byd.3 retained its shell helper and CPU lock at ACC off with Android reporting `Awake`, while `ssc_skip` logs showed the VPN recovery receiver being discarded. In byd.4, the authenticated recovery activity recreated the killed client and immediately finished. Twenty-four consecutive samples over the first two minutes of confirmed parked operation showed VPN and remote TCP availability; a later check, about ten minutes after shutdown and after leaving the car, verified relay connectivity, authenticated remote ADB, internet access, fresh power level zero and screen off. Monitoring between those checks was interrupted, so this is not evidence of continuous ten-minute availability. Extended parking, reboot recovery and power consumption remain unverified.

## Rebuild

From the source repository with JDK 21, Android SDK 36 / build-tools 36.0.0, and NDK 23.1.7779620 installed:

```sh
bash scripts/build-byd.sh
```

The build fetches the pinned Tailscale Go toolchain, runs formatting checks, unit tests, watchdog process tests, and critical release lint, assembles the release APK, and verifies its package and signing certificate. The watchdog tests use local fake Android commands and never contact the car. Signing material is retained locally in `.signing/`, excluded from Git and the delivery folder. Preserve that directory to install future BYD builds as updates.
