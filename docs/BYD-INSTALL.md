# Tailscale BYD installation

Tailscale BYD 1.104.1 byd.1 is a complete Tailscale client with parked connectivity enabled by default. It uses BYD's Android wake and backlight interfaces and a detached power helper. Both built-in cellular data and an independent Wi-Fi hotspot are supported through Tailscale's existing network selection.

## Install and connect

1. Install `Tailscale-BYD-1.104.1-byd.1.apk` through the car file manager. Its package is `dev.tailscale.byd`, so it installs alongside the official client.
2. Open **Tailscale BYD**, sign in, and grant Android VPN permission. Only one VPN can be active; connect the BYD client.
3. Keep Android debugging enabled on the head unit. If Android asks to allow debugging from `tailscale-byd@headunit`, select **Always allow** and accept. The app uses this local connection to start its power helper automatically.
4. Open **Settings**. **Stay connected while parked** is enabled by default. The parked status should report **Ready to stay connected when you park**. Wi-Fi retention is enabled; turn it off if you want cellular-only parked operation.
5. Turn ACC off and try connecting to the car from another Tailscale device. The expected behavior is that Android remains awake and the display remains dark. On ACC on, the app stops controlling the backlight.

Disconnecting Tailscale, signing out, or turning off **Stay connected while parked** stops the helper and releases its power locks. Reconnecting enables parked operation again. The boot receiver and helper recover the VPN only when it was previously requested and VPN permission is still granted.

## Optional setup from the Mac

If the head unit does not show or accept the app's local debugging request, start the same helper through an existing authorized ADB connection. Connect the BYD VPN first, then run:

```sh
python3 scripts/setup-byd-helper.py --serial YOUR_ADB_SERIAL
```

Run this command from the source repository. In a packaged delivery folder, use `python3 setup-byd-helper.py` instead. The APK retries its own local connection so it can re-establish the helper after a reboot once its debugging key is authorized.

For installation through ADB:

```sh
adb -s YOUR_ADB_SERIAL install -r Tailscale-BYD-1.104.1-byd.1.apk
```

## Implementation

The helper reads BYD's OEM power state every 500 ms. At power level zero it waits for display sleep, requests `PowerManager.wakeUp(uptimeMillis, 9, "ACC_ON")`, waits 500 ms, and invokes `TurnBacklightOff(uptimeMillis)` or `turnBacklightOff`. It repeats backlight-off every three seconds while ACC stays off and restores Wi-Fi after three seconds when enabled. CPU and Wi-Fi locks keep the process and active network available. Tailscale handles tunnel setup, encryption, login, and network changes.

The shell helper runs separately from the VPN process because BYD's ACC-off policy kills ordinary app packages. When app heartbeats stop, it requests recovery through a receiver restricted by Android's `DUMP` permission and an installation-specific secret. Control listens only on `127.0.0.1:41642`, requires that secret, and accepts fixed status/configuration/stop commands. It does not offer a remote shell or share Tailscale credentials.

Firmware must retain head-unit electrical power, grant the helper `DEVICE_POWER`, and keep at least one internet connection available. This build has been compiled and checked on the Mac; its behavior on the head unit still needs the first vehicle test. Parked operation keeps the head unit awake, so power consumption should be measured during that test.

## Rebuild

From the source repository with JDK 21, Android SDK 36 / build-tools 36.0.0, and NDK 23.1.7779620 installed:

```sh
bash scripts/build-byd.sh
```

The build fetches the pinned Tailscale Go toolchain, runs formatting checks and unit tests, assembles the release APK, and verifies its package and signing certificate. Signing material is retained locally in `.signing/`, excluded from Git and the delivery folder. Preserve that directory to install future BYD builds as updates.
