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

- **In-app updates:** check for a new release, show notes, download and install with confirmation. Verify checksum, package, signing certificate, Android version and ABI before installing. A failed update must leave the existing install running. Automatic install would be opt-in and only after manual updates are proven.
- **Recovery from a bad update:** the helper loads its code from the main APK, so a broken update could take out both. Evaluate a small independent recovery component before relying on unattended updates.
- **Health screen:** show version, connection intent, ACC and display state, network, last healthy tunnel time, and recent recovery events, with a redacted diagnostic export.
- **Parking limits:** an optional parking time limit, and a battery cutoff once 12 V readings have been validated on real cars.
- **Wider support:** check platform capabilities before enabling parked control on untested firmware, and test other BYD models.
