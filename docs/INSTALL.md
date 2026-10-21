# Installing Tailpark

Tailpark 1.104.1-byd.5 is the full Tailscale Android client with parked connectivity added and enabled by default. It uses BYD's Android wake and backlight interfaces and a supervised power helper. Built-in cellular data and Wi-Fi (including a separate hotspot) both work through Tailscale's normal network selection.

## Install and connect

1. Install `Tailpark-1.104.1-byd.5.apk` through the car's file manager. Its package is `dev.tailpark.byd`, so it installs alongside the official Tailscale app.
2. Open **Tailpark**, sign in, and grant Android VPN permission. Only one VPN can be active, so disconnect any other VPN app.
3. Keep Android debugging enabled on the head unit. When Android asks to allow debugging from `tailpark@headunit`, select **Always allow** and accept. The app uses this local connection to start its power helper.
4. Open **Settings**. **Stay connected while parked** is on by default, and the parked status should read **Ready to stay connected when you park**. Wi-Fi retention is also on; turn it off if you want cellular only while parked.
5. Switch the car off and connect to it from another device on your tailnet. Android should stay awake with the display dark. When you switch the car back on, the app restores the backlight and releases its display lock.

Disconnecting, signing out, or turning off **Stay connected while parked** stops the helper and its watchdog and releases the power locks. A persistent stop marker prevents the helper from restarting the VPN after a crash or retry. Reconnecting turns parked operation back on. After a reboot, the VPN only comes back if you had connected before and VPN permission is still granted.

## Moving from "Tailscale BYD"

Builds up to byd.4 were called **Tailscale BYD** (`dev.tailscale.byd`). Tailpark has a different package ID and signing key, so it can't update that app in place.

1. In Tailscale BYD, disconnect so its helper stops.
2. Uninstall Tailscale BYD.
3. Install Tailpark and follow the steps above. You'll sign in again and accept a new debugging prompt.

The old app's runtime folder, `/data/local/tmp/tailscale-byd-dev.tailscale.byd/`, is left behind. It's harmless; remove it with `adb shell rm -rf` if you want.

## Optional setup over ADB

If the head unit doesn't show or accept the app's debugging prompt, start the helper through an existing authorized ADB connection. Connect Tailpark first, then run from the source repository:

```sh
python3 scripts/setup-byd-helper.py --serial YOUR_ADB_SERIAL
```

To install over ADB instead of the file manager:

```sh
adb -s YOUR_ADB_SERIAL install -r Tailpark-1.104.1-byd.5.apk
```

The app keeps retrying its own local connection, so once its debugging key is authorized it can bring the helper back after a reboot.

## Diagnostics

The helper writes `last-status.json` every five seconds to `/data/local/tmp/tailpark-dev.tailpark.byd/` and keeps the previous run's copy as `previous-status.json`. After a dropped connection or reboot, read them over ADB to see where parked operation stopped. They contain power level, screen state, sample age and errors, and no login credentials or helper token. The helper log in the same folder is capped at 5 MiB.

## Requirements

The firmware must keep the head unit powered after ACC off, grant the helper `DEVICE_POWER`, and the car needs an internet connection where it's parked. Tailpark doesn't change BYD's autostart policy, hidden-API policy, or debugging-key expiry. On Android 11 or later, if the firmware allows it, helper setup grants `WRITE_SECURE_SETTINGS` so the app can turn debugging back on after BYD clears it; Android 10 doesn't need this.

## Rebuild

See [Build](../README.md#build) in the README.
