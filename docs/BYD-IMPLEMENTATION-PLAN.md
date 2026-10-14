# Tailscale BYD implementation

Tailscale BYD combines the complete Tailscale 1.104.1 Android client with a controller for parked connectivity. A detached helper keeps Android awake while the display stays dark and recovers the VPN when BYD kills ordinary app processes.

## Upstream baseline

The base is [tailscale/tailscale-android](https://github.com/tailscale/tailscale-android), tag `1.104.1-t7f4efe814-g6d75bd403`, commit `6d75bd403bb948ce97c1943e88019a91b1c49add`. The repository contains the Kotlin Android application and the Go `libtailscale/` binding layer. The networking dependency is [tailscale/tailscale](https://github.com/tailscale/tailscale).

Tailscale's existing `IPNService` provides foreground operation, sticky restart, and Android Always-on VPN support. Its network callback selects the underlying internet connection and reacts to cellular/Wi-Fi changes.

## Power sequence

The helper polls the OEM power state every 500 ms. When power level is zero:

1. Wait for the display to report off, for up to 40 seconds.
2. Request `PowerManager.wakeUp(uptimeMillis, 9, "ACC_ON")`.
3. After 500 ms, call `TurnBacklightOff(uptimeMillis)` or `turnBacklightOff`.
4. Repeat backlight-off every three seconds while ACC stays off.
5. Restore Wi-Fi after three seconds when Wi-Fi retention is enabled.

The wake reason string is passed to Android; it does not establish a change to physical ignition. Android wakefulness and backlight illumination are controlled separately. Unknown or invalid OEM power states cancel display control. ACC on cancels pending backlight calls before they execute.

`BydPlatform` reflects the installed OEM framework without shipping replacement OEM classes. Its Context wrapper adapts local permission checks. The actual process UID must still satisfy Binder permission enforcement for power operations.

## Detached helper and recovery

The APK includes an `app_process` entrypoint. A loopback ADB client with RSA authentication starts it as shell after Android debugging is authorized. The ADB private key remains in the application's private preferences. The helper uses shell's Context for framework attribution; this does not grant permissions that shell lacks.

The helper holds CPU and active Wi-Fi locks and listens only on `127.0.0.1:41642`. It requires an installation-specific secret and accepts fixed configuration, heartbeat, status, and stop commands. It does not accept arbitrary shell commands or hold Tailscale login credentials.

After 30 seconds without app heartbeats, it requests VPN recovery through a receiver protected by Android's `DUMP` permission and the secret. The receiver checks persisted connection intent, previous enrollment, and VPN permission before starting the VPN. The helper exits after uninstall or APK replacement.

Disconnect, logout, revoked VPN permission, and disabled parked mode stop the helper and release resources. Requested connection intent is stored separately from transient connection health so recovery does not undo an intentional disconnect.

## Code layout

| Location | Purpose |
| --- | --- |
| `android/src/main/java/com/tailscale/ipn/byd/` | OEM bridge, timed power state, app controller, helper, ADB client, preferences, and recovery receiver |
| `android/src/main/java/com/tailscale/ipn/IPNService.kt` | VPN lifecycle integration |
| `android/src/main/java/com/tailscale/ipn/ui/view/SettingsView.kt` | Parked mode, Wi-Fi retention, and status |
| `android/src/main/AndroidManifest.xml` | Permissions and boot/update recovery |
| `android/src/test/kotlin/com/tailscale/ipn/byd/` | Power sequence and RSA authentication tests |
| `android/src/test/java/com/tailscale/ipn/byd/` | ADB handshake against a fake daemon |
| `scripts/setup-byd-helper.py` | Optional host ADB setup |
| `scripts/build-byd.sh` | Signed release build and verification |

## Verification

Host checks cover the power timing, ACC-on cancellation, disconnect cleanup, unavailable power state, Wi-Fi-disabled operation, repeated parked cycles, permission failures, ADB key encoding and signatures, and authentication/shell framing. The fake ADB test does not contact a head unit.

Vehicle tests should measure remote tunnel connectivity across ACC cycles, extended parking, process death, reboot, and cellular/Wi-Fi changes. Verify that the display stays dark while parked and returns to normal operation on ACC on. Power consumption and compatibility with other display controllers also require vehicle testing.

Firmware must retain head-unit electrical power, permit the helper's power calls, and keep at least one internet connection available. This version runs parked mode until the user disconnects or disables it; it does not impose a timer or battery cutoff.

See [installation instructions](BYD-INSTALL.md).
