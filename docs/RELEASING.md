# Automated releases

The **Tailpark stable releases** GitHub Actions workflow checks the latest published
Tailscale Android release every six hours, at minute 17. It can also be started
with **Run workflow** in GitHub Actions.

`tailpark.properties` records the upstream version, exact tag, and BYD revision.
Local builds and CI use this file for the APK filename, version suffix and build
manifest. Increase `BYD_VERSION` when releasing Tailpark-specific changes without
an upstream version change.

When a newer stable Android release appears, the workflow merges its full tag
into the current `main` in its temporary checkout. Drafts, prereleases, odd-minor
development versions and downgrades are rejected. Merge conflicts stop the run
before any changes are pushed.

The workflow builds the Go library and signed release APK, runs JVM and watchdog
tests, checks Kotlin formatting, performs critical release lint, and verifies the
APK signature and package contents. Only after these checks pass does it push
the tested commit, create a `vVERSION-byd.REVISION` tag and publish a GitHub
release with the APK, SHA-256, build manifest, package metadata and certificate
verification report. An existing release is skipped. A failed run is visible
in GitHub Actions and can be retried after fixing its cause.

Watchdog tests, native compilation, Android build and APK verification have
separate visible steps and timeouts. Go and Gradle dependency/build caches are
reused between successful builds; the first uncached build takes longer.

The repository secrets `TAILPARK_KEYSTORE_B64` and `TAILPARK_KEYSTORE_PASSWORD`
contain the existing Tailpark signer. Keep the original `.signing/` directory
backed up. CI fails if signing material is missing; it never creates a new key.
Signing files are removed at the end of each run. Forks do not run this release
job without changing its repository guard and configuring their own signer.

Standard GitHub-hosted runners are free for this public repository. Docker Cloud
Sandboxes credits are a separate compute product and are not required by this
workflow.

Car installation and real head-unit testing remain separate from publication.
Automated checks cannot establish reboot recovery, long-term parked connectivity
or battery consumption. Release notes state this limitation.

## Troubleshooting

- **Merge conflict:** merge the new upstream stable tag locally, resolve it,
  update `tailpark.properties`, build and test, then push and rerun the workflow.
- **Missing signer:** restore the two secrets from the original signing key.
- **Push rejected:** inspect concurrent changes or branch rules, reconcile the
  tested commit, then rerun. The workflow never force-pushes.
- **Tag exists without a release:** the workflow will reuse it only if it points
  to the exact tested commit; it refuses to move a release tag.
- **Schedule inactive:** GitHub can disable scheduled workflows on inactive
  public repositories. Enable it again in Actions or use the manual trigger.

Before changing the automation, run:

```sh
python3 scripts/test-release-automation.py
python3 scripts/update-upstream.py --check
actionlint .github/workflows/tailpark-release.yml
```
