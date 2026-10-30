# Installing Tailpark

Tailpark 1.104.4-byd.6 is the full Tailscale Android client with parked connectivity added and enabled by default. It uses BYD's Android wake and backlight interfaces and a supervised power helper. Built-in cellular data and Wi-Fi (including a separate hotspot) both work through Tailscale's normal network selection.

## Install and connect

1. Install `Tailpark-1.104.4-byd.6.apk` through the car's file manager. Its package is `dev.tailpark.byd`, so it installs alongside the official Tailscale app.
2. Open **Tailpark**, sign in, and grant Android VPN permission. Only one VPN can be active, so disconnect any other VPN app.
3. Keep Android debugging enabled on the head unit. When Android asks to allow debugging from `tailpark@headunit`, select **Always allow** and accept. The app uses this local connection to start its power helper.
4. Open **Settings → Start and connect with the car** to open BYD startup management and allow Tailpark to start. Electro's setup guide describes this as a restriction list: **uncheck Tailpark** to permit automatic startup. Confirm the wording on your firmware. Revisit this setting after app updates. If the shortcut is unavailable, open the car settings manually.
5. In Tailpark **Settings**, **Stay connected while parked** is on by default, and the parked status should read **Ready to stay connected when you park**. Wi-Fi retention is also on; turn it off if you want cellular only while parked.
6. Switch the car off and connect to it from another device on your tailnet. Android should stay awake with the display dark. When you switch the car back on, the app restores the backlight and releases its display lock.

Disconnecting, signing out, or turning off **Stay connected while parked** stops the helper and its watchdog and releases the power locks. A persistent stop marker prevents the helper from restarting the VPN after a crash or retry. Reconnecting turns parked operation back on. After a reboot, the VPN only comes back if you had connected before and VPN permission is still granted.

## Automatic startup and connection

After sign-in, Tailpark automatically asks you to turn **Disable auto-start OFF** for Tailpark. **Open BYD settings** opens the restriction list. Return to Tailpark and select **I checked — it’s off** after checking it. The reminder appears again whenever the app returns to the foreground, including after an app update, with a specific update reminder if an older build was confirmed. Back or tapping outside the reminder dismisses it for the current foreground session.

Startup status is not automatically verified: no readable OEM status interface has been confirmed on this firmware. The saved confirmation records the user's check and app version, not an observed BYD setting. The settings shortcut remains available for manual access. The launcher uses the original Tailscale nine-dot artwork on a blue background to distinguish Tailpark.

After you sign in, grant VPN permission and connect once, Tailpark remembers that you want the VPN connected. Its boot and app-update receiver starts the VPN in the background; it does not need to open the main screen. An explicit disconnect or sign-out clears that request. Turning off **Stay connected while parked** only disables parked power management; it does not disable reconnecting the VPN after boot.

If cached login readiness is false at startup, Tailpark initializes the backend and preserves the connection request. The backend readiness transition can resume it when the saved login is available. Revoked VPN permission still requires an explicit reconnect and permission grant. This does not bypass BYD startup restrictions, Android force-stop, sign-in requirements, or firmware changes after an OTA.

The startup-settings shortcut follows Electro 1.14.0's recovered `n/r0.n(Context)` flow: `ACTION_MAIN` / `CATEGORY_LAUNCHER` targeted at `com.byd.appstartmanagement`. Existing local reverse-engineering notes document Electro's OEM restriction-list instructions, boot receiver and sticky service in `../electronica/analysis/FEATURES-AND-DATA.md` (Auto start), `DECODED-NETWORK.md` (Ping entitlement and service persistence), and `recovered-smali/br/com/rory/electro/n/r0.smali`. These are static observations of Electro, not runtime proof that a particular firmware permits Tailpark. No Electro code or privileged debugger bootstrap is copied.

The new startup shortcut and reconnect changes still need an actual head-unit reboot test, including after an OTA. Tailpark does not currently add a persisted periodic restart job as a fallback if the firmware suppresses boot delivery or kills the app before initialization completes.

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
adb -s YOUR_ADB_SERIAL install -r Tailpark-1.104.4-byd.6.apk
```

The app keeps retrying its own local connection, so once its debugging key is authorized it can bring the helper back after a reboot.

## Power use

The app controller and detached power helper poll every five seconds during steady operation, with 500 ms checks while waiting for the display to turn off or completing the wake-to-dark transition. The watchdog checks helper liveness every five seconds. Detecting ignition changes or a crashed helper can therefore take up to five seconds, plus scheduling delays. The Wi-Fi lock uses normal full mode rather than high-performance mode; Wi-Fi retention remains optional.

These changes reduce polling overhead; battery savings have not been measured. Parked connectivity still holds a CPU wake lock and keeps the head unit running. Turning off **Stay connected while parked** allows normal firmware sleep while preserving boot reconnect. Reliable remote access while the head unit sleeps is not established.

## Diagnostics

The helper writes `last-status.json` every five seconds to `/data/local/tmp/tailpark-dev.tailpark.byd/` and keeps the previous run's copy as `previous-status.json`. After a dropped connection or reboot, read them over ADB to see where parked operation stopped. They contain power level, screen state, sample age and errors, and no login credentials or helper token. The helper log in the same folder is capped at 5 MiB.

## Requirements

The firmware must keep the head unit powered after ACC off, grant the helper `DEVICE_POWER`, and the car needs an internet connection where it's parked. Tailpark doesn't change BYD's autostart policy, hidden-API policy, or debugging-key expiry. On Android 11 or later, if the firmware allows it, helper setup grants `WRITE_SECURE_SETTINGS` so the app can turn debugging back on after BYD clears it; Android 10 doesn't need this.

## Rebuild

See [Build](../README.md#build) in the README.
