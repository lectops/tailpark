# Tailpark implementation

Tailpark combines the complete Tailscale 1.104.4 Android client with a controller for parked connectivity. A supervised helper keeps Android awake while the display stays dark and recovers the VPN when BYD kills ordinary app processes.

## Upstream baseline

The base is [tailscale/tailscale-android](https://github.com/tailscale/tailscale-android), tag `1.104.4-t938213198-gb9d487fe6`, commit `b9d487fe6d7203aeac889aeec2faccb9d32bb701`. The repository contains the Kotlin Android application and the Go `libtailscale/` binding layer. The networking dependency is [tailscale/tailscale](https://github.com/tailscale/tailscale).

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

The app and detached helper also keep a bounded local [connection timeline](DIAGNOSTICS.md). Only allowlisted state changes and timing/session/build metadata are recorded; there is no new periodic polling. The helper records non-VPN network inventory changes through a shell-attributed callback and imports into the app through authenticated bounded pages. Journal identities and durable cursors handle helper recreation and interrupted imports. Existing last/previous status snapshots remain available. An unclosed previous recording is labelled “Recording stopped; cause unknown,” never a claimed reboot or power loss. Settings provides explicit view, export and clear actions, including a durable pending clear when the helper is offline.

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

Firmware must retain head-unit electrical power, permit the helper's power calls, and keep at least one internet connection available. Parked mode has no timer by default. An optional inactivity timer releases Tailpark’s locks after application traffic stops; this is not a battery cutoff.

See [installation instructions](INSTALL.md), [test results](TESTING.md), and the [roadmap](ROADMAP.md).

## Inactivity-based parked access

Settings offers no timeout (the default), 30 minutes, 1, 2, 4 or 8 hours without remote activity. The window begins at confirmed ACC off; ACC on ends that session. **Keep awake for this session** covers quiet interactive work, long pauses and very slow transfers and clears when driving resumes. Expiry releases app/helper wake, Wi-Fi and display ownership and stops the helper/watchdog. It preserves requested VPN connection and login; it does not power off the car. Android/firmware decides whether and when the head unit sleeps.

The embedded backend chains the accepted inbound packet hook before netstack and accepted outbound payload hook. It measures application data in both directions, not encrypted VPN byte counts, socket existence or peer presence. At least 1 KiB within a ten-second window renews activity: TCP payload excluding one-byte keepalive probes, advancing ACKs on a previously observed application stream (covering outbound userspace/Taildrop data), or UDP payload in packets of at least 128 bytes. DNS (ports 53/5353), ICMP, TSMP, empty ACKs, SYN scans and control-plane/WireGuard keepalives do not count. All accepted tailnet peers and application services count; there is no peer allowlist. A bounded 256-entry flow table lives only in memory, with no payloads or addresses exported/persisted. Oldest entries are evicted only for new flows, preserving active streams during ordinary churn. If a burst evicts recent stream evidence (more than 256 flows within ten seconds), the reporter becomes unavailable and keeps parked access awake until tracking is disabled/re-enabled or the app restarts. Stream metadata remains bounded and observed while counting is disabled, so enabling a timeout during an existing netstack transfer preserves its ACK progress. Continuous ordinary telemetry above the threshold can prevent sleep; sparse/quiet sessions below it can be missed. Use Keep awake for work with long quiet periods.

The helper receives a fresh monotonic idle age with the existing ten-second controller exchange. An unknown/stale reporter, unhealthy VPN or unknown ACC defers expiry, even if that keeps the head unit awake longer. Two fresh idle reports at least ten seconds apart are required. Immediately before expiry, a DUMP-protected authenticated broadcast rechecks the current backend idle age, current timer generation, override, requested connection and fresh OEM ACC state, then commits terminal expiry to private app preferences. A second fresh activity/ACC read after the commit rolls that decision back if traffic arrived or vehicle state changed during persistence; synchronized controller reads cannot stop on that transient state. Only then is expiry acknowledged. New application data before that final check defers sleep. Work started after the terminal expiry decision is not guaranteed to remain reachable. Failed/lost confirmation never establishes inactivity.

Shell session checkpoints are bounded to once per minute and tagged with the kernel boot ID. They survive helper restarts without treating clock resets as elapsed inactivity. A restarted application reporter starts a full conservative idle window. Terminal `idle_expired` preferences and the shell `idle-expired` marker prevent watchdog, process, APK-update or reboot recovery from reviving an expired parked session. Re-enabling a timer or Keep awake explicitly starts a fresh window. Otherwise the next controller/app/boot startup must observe ACC on to clear expiry; a reboot while still parked does not clear it. If firmware does not launch the app when driving resumes, opening Tailpark with ACC on also clears expiry. Once the head unit sleeps, remote wake or extension of an expired session is not guaranteed.

Helper status adds `idleState`, `idleRemainingMs` and `idleExpired` for diagnostics and update gating. States distinguish active traffic, tracking, unknown activity, Keep awake, pending final confirmation and terminal expiry. The native release build runs the Go packet-tracker regression suite before rebuilding the AAR. JVM and watchdog tests simulate multi-hour transfers, routine traffic, expiry-boundary renewal, lost reporters, monotonic resets and terminal watchdog behavior. Actual multi-hour vehicle transfers, ACC transitions, crashes and sleep behavior still require head-unit validation.
