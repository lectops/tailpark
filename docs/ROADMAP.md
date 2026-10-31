# Roadmap

## Next

- **Vehicle tests still open:** reboot recovery, Wi-Fi/cellular handover, disconnect and sign-out on the car, same-key updates, and measured 12 V drain. See [TESTING.md](TESTING.md).
- **CI builds:** build and test on GitHub Actions with pinned JDK, Android SDK/NDK and Go, and sign releases from a protected secret. The inherited upstream workflows are not a signed release pipeline.
- **Lint:** fix or explicitly baseline the inherited full-lint findings so full lint can gate releases.
- **Versioning:** keep the upstream version, Tailpark revision and Android version code in one place instead of being repeated across Gradle and packaging scripts.

## Tracking upstream

Follow stable Tailscale Android releases. Each update gets its own PR with the upstream changes, any conflicts with the parked-connectivity code, and test results. Nothing merges or publishes automatically.

## Releases

Publish each release with its APK SHA-256, signing certificate fingerprint and release notes. Promote the exact APK that passed vehicle testing rather than rebuilding it. Use separate beta and stable channels once there are other testers.

## Later

- **In-app updates:** implemented with signed-APK validation, manual confirmation and opt-in parked shell installation; same-key updates and firmware behavior still need vehicle validation. See [UPDATES.md](UPDATES.md).
- **Recovery from a bad update:** the helper loads its code from the main APK, so a broken update could take out both. Evaluate a small independent recovery component before relying on unattended updates.
- **Health screen:** local connection and power observations, recovery events and redacted timeline export are implemented. A fuller live status screen and last healthy tunnel time remain future work.
- **Parking limits:** inactivity-based parked access and Keep awake are implemented; active transfers renew the optional timer. Vehicle sleep/transfer validation remains open, and a battery cutoff needs validated 12 V readings.
- **Wider support:** check platform capabilities before enabling parked control on untested firmware, and test other BYD models.
