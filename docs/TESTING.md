# Testing

This page records what has been verified on a real car and what hasn't. A successful check proves the car was reachable at that moment; it doesn't prove uninterrupted connectivity in between.

## Test vehicle

BYD Sealion 6, DiLink 3.0, Android 10.

## Host tests

Every build runs the JVM unit tests, the watchdog process tests (with fake Android commands, never touching a car), formatting checks and critical release lint. The full lint report still has inherited findings from upstream.

## Vehicle results

These were run on 1.104.1-byd.4, the last build before the rename. byd.5 changes only the app name, package ID, icon and signing key.

| Check | Result |
| --- | --- |
| Recovery after ACC off | BYD kills the client at ACC off. The helper's recovery activity brought it back automatically, with about 35 seconds without connectivity. |
| Remote access after leaving the car | Relay connectivity and authenticated ADB worked with ACC off and the screen dark. |
| 24-hour monitored run | Sampled every 60 seconds. For the first 9.5 hours the car was reachable in all but one sample, including nearly 8 hours of healthy parked operation. The car was then driven and parked underground with no signal, so it was unreachable for the rest of the window. That gap was a coverage issue, not a software failure. |
| Daily use | One week of normal driving and parking with remote access working wherever the car had signal. |

## Not yet verified

| Test | What's needed |
| --- | --- |
| Full head-unit reboot | VPN and helper return, saved login and debugging authorization survive |
| Wi-Fi/cellular handover while parked | Traffic survives, recovery time after each switch |
| Explicit disconnect, sign-out and VPN revocation on the car | Stays stopped, no unwanted recovery, reconnect works |
| App update signed with the same key | Settings and login retained, helper adopts the new APK |
| Parked power use | Measured 12 V drain over time |
| Other BYD models and DiLink versions | Any report helps |
| Connection timeline on DiLink | Network loss with app killed, helper/watchdog restart observations, boot retention, explicit export and offline clear; verify that gaps stay unknown and exports contain no secrets or network identifiers |

## Monitoring script

[`scripts/monitor-byd.py`](../scripts/monitor-byd.py) runs a read-only reachability test from another machine on your tailnet. Each sample sends a Tailscale ping and reads the helper's status snapshot, Android uptime and helper heartbeat over authenticated ADB. It doesn't restart apps, change settings or install anything.

```sh
python3 scripts/monitor-byd.py \
  --target CAR_TAILSCALE_IP \
  --output /path/to/results \
  --deadline 2026-01-01T00:00:00+00:00 \
  --interval 60 --adb-server-port 5039
```

The machine running it needs the `tailscale` and `adb` commands, a login to the same tailnet, and ADB already authorized on the head unit. A sample counts as healthy parked only if the snapshot and heartbeat are under 15 seconds old, ACC is off, the screen is off, the helper is in its parked phase, the watchdog is alive, and there's no helper error. Results go to `samples.ndjson`, `latest.json` and `summary.json` in the output folder.

Run `python3 scripts/test-monitor-byd.py` to test the monitor's logic without contacting a car.
