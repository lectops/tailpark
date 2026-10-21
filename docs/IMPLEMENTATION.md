# Tailpark implementation

Tailpark combines the complete Tailscale 1.104.1 Android client with a controller for parked connectivity. A supervised helper keeps Android awake while the display stays dark and recovers the VPN when BYD kills ordinary app processes.

## Upstream baseline

The base is [tailscale/tailscale-android](https://github.com/tailscale/tailscale-android), tag `1.104.1-t7f4efe814-g6d75bd403`, commit `6d75bd403bb948ce97c1943e88019a91b1c49add`. The repository contains the Kotlin Android application and the Go `libtailscale/` binding layer. The networking dependency is [tailscale/tailscale](https://github.com/tailscale/tailscale).

Tailscale's existing `IPNService` provides foreground operation, sticky restart, and Android Always-on VPN support. Its network callback selects the underlying internet connection and reacts to cellular/Wi-Fi changes.

## Power sequence

The helper polls the OEM power state every 500 ms. When power level is zero:

1. Wait for the display to report off, for up to 40 seconds.
2. Request `PowerManager.wakeUp(uptimeMillis, 9, "ACC_ON")`.
3. After 500 ms, call `TurnBacklightOff(uptimeMillis)` or `turnBacklightOff`.
4. Verify display state every three seconds while ACC stays off and darken it when necessary.
5. Restore Wi-Fi after three seconds when Wi-Fi retention is enabled, then repeat checks every 30 seconds.

The wake reason string is passed to Android; it does not establish a change to physical ignition. Android wakefulness and backlight illumination are controlled separately. Unknown or invalid OEM power states cancel new display control. ACC on cancels pending darkening and restores the helper's backlight changes. Failed wake requests retry every five seconds without progressing to darkening.

`BydPanelControl` verifies plain backlight writes with `getPowerScreenStatus` where available. Only a confirmed still-lit panel can escalate to a vendor Binder-token display lock, and both acquire/release methods must exist. The same token is retained for release; ambiguous acquire failures keep release reachable without issuing a second acquire. Unknown screen status uses plain backlight control, and unknown ACC defers plain restoration. The controller does not call `goToSleep`, change brightness, or issue guessed Binder transaction codes.

`BydPlatform` reflects the installed OEM framework without shipping replacement OEM classes. Its Context wrapper adapts local permission checks. The actual process UID must still satisfy Binder permission enforcement for power operations.

## Detached helper and recovery

The APK includes installer and helper `app_process` entrypoints. A loopback ADB client with RSA authentication starts the installer as shell after Android debugging is authorized. The installer extracts the watchdog asset using Java ZIP APIs, so setup needs neither an external unzip command nor oversized ADB packets. Its file lock serializes concurrent setup. The ADB private key remains in the application's private preferences. The helper uses shell's Context for framework attribution; this does not grant permissions that shell lacks.

The watchdog uses singleton ownership, re-resolves the APK path, checks five-second helper heartbeats, and restarts a dead or unresponsive helper. It checks APK/script changes every 15 seconds and bounds its log in place to 5 MiB. Retries start at three seconds, grow to 60 seconds, and reset after five minutes of healthy uptime. A surviving helper can be adopted by a replacement watchdog without dropping its locks. Process-name checks protect against signaling an unrelated recycled PID.

The helper holds CPU and active Wi-Fi locks and listens only on `127.0.0.1:41642`. It requires an installation-specific secret and accepts fixed configuration, heartbeat, status, and stop commands. It does not accept arbitrary shell commands or hold Tailscale login credentials.

After 30 seconds without controller heartbeats, or 90 seconds without an active VPN despite controller heartbeats, it requests VPN recovery through a receiver protected by Android's `DUMP` permission and the secret. The receiver acknowledges delivery; shell exit zero with no receiver result is treated as a discarded request. A discarded or timed-out broadcast falls back to a dedicated, equally protected `Theme.NoDisplay` activity. The activity authenticates the request, checks persisted connection intent, enrollment and VPN permission, starts our own service, and immediately finishes. It also permits a new helper to verify saved preferences when cold broadcast startup is blocked. Explicit disabled, revoked or rejected replies never trigger fallback.

Vehicle testing found BYD's `ssc_skip` policy blocking cold service and broadcast starts while the detached power helper and Android remained awake. Activity startup is outside that particular policy gate. The firmware's per-UID exemption API requires `ACCESS_APPOPSDATA`, denied to shell on the tested head unit; no global policy changes or guessed Binder transactions are used. In vehicle testing, the dedicated activity recovered the killed client at ACC off. Remote ADB and relay connectivity were verified again after leaving the car, with fresh ACC-off and screen-off readings. Connectivity was interrupted for roughly 35 seconds during shutdown; extended parking and reboot recovery remain unverified.

VPN health combines the Android tunnel callback with Tailscale's Running state; losing an underlying network alone is not a reason to restart the service. Recovery attempts back off to five minutes and reset after a healthy VPN heartbeat. APK replacement and uninstall checks run independently of heartbeat freshness.

Disconnect, logout, revoked VPN permission, and disabled parked mode persist the stopped intent, write a durable shell-owned disable marker, and stop the helper and watchdog. If the helper is absent, the app attempts to write the marker over its already-authorized ADB connection without prompting for a new key. Every new helper checks persisted intent through the protected receiver before acquiring locks, covering stop/setup races. Requested connection intent is stored separately from transient connection health.

Authorized shell setup grants the app `WRITE_SECURE_SETTINGS` when supported. On Android 11+, a requested parked connection can then restore existing `adb_wifi_enabled`/`adb_enabled` settings after BYD clears them. Unsupported keys are not created. No global debugging-key timeout, hidden-API exemption, or phantom-process setting is changed.

## Code layout

| Location | Purpose |
| --- | --- |
| `android/src/main/java/com/tailscale/ipn/byd/` | OEM bridge, timed power state, app controller, helper, ADB client, preferences, and recovery receiver |
| `android/src/main/java/com/tailscale/ipn/IPNService.kt` | VPN lifecycle integration |
| `android/src/main/java/com/tailscale/ipn/ui/view/SettingsView.kt` | Parked mode, Wi-Fi retention, and status |
| `android/src/main/AndroidManifest.xml` | Permissions and boot/update recovery |
| `android/src/test/kotlin/com/tailscale/ipn/byd/` | Power sequence, panel ownership, VPN recovery policy, and RSA authentication tests |
| `android/src/test/java/com/tailscale/ipn/byd/` | ADB handshake against a fake daemon |
| `scripts/setup-byd-helper.py` | Optional host ADB setup |
| `android/src/main/assets/byd-watchdog.sh` | Detached, update-aware helper supervision |
| `scripts/test-byd-watchdog.py` | Host process tests for the actual watchdog asset |
| `scripts/build-byd.sh` | Signed release build and verification |

## Verification

Host checks cover recurring Wi-Fi recovery, failed-wake retries, ACC-on restoration, display-lock ownership, missing vendor APIs, recovery backoff, discarded-broadcast fallback, acknowledged disconnect/revocation, interrupted recovery, sanitized command failures, RSA authentication, bounded subprocess output, and authentication/shell framing. Watchdog process tests cover crash recovery, stale heartbeats, duplicate supervisors, surviving-helper adoption, APK/script updates, uninstall, and durable disconnect during backoff. These tests do not contact a head unit.

Vehicle results are recorded in [TESTING.md](TESTING.md). Further vehicle tests should cover reboot recovery and cellular/Wi-Fi handover. Verify that the display stays dark while parked and returns to normal operation on ACC on. Power consumption and compatibility with other display controllers also require vehicle testing.

Firmware must retain head-unit electrical power, permit the helper's power calls, and keep at least one internet connection available. This version runs parked mode until the user disconnects or disables it; it does not impose a timer or battery cutoff.

See [installation instructions](INSTALL.md), [test results](TESTING.md), and the [roadmap](ROADMAP.md).
