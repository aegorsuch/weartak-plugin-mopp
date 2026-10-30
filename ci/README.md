# MOPP CI/CD and releases

Same model as WearTAK WearOS (`core/weartak-core/wearos-tak-civ`) and the WearTAK plugin
template. TAK.gov (`git.tak.gov/weartak-plugins/weartak-plugin-mopp`) is canonical; GitHub
(`aegorsuch/weartak-plugin-mopp`) is a mirror.

**Release is currently blocked** in `preReleaseBuild`: the shared typed PPE SDK
adapter is implemented but matching host integration is not yet cleared. The pipeline below
is scaffolded, not evidence of a production-signed or published build. Release
and publisher jobs explicitly use `when: never` for source-only publication, so
merging this scaffold does not access signing material or distribute an APK.

## Pipeline

| Trigger | Jobs |
| --- | --- |
| Merge request | `release_publisher_tests`, `release_checklist_tests`, `unit_tests` (debug build, no production signing) |
| Push to protected `main` | tests and debug build only |
| Tag | tests and debug build only |

After SDK, host, hardware and signing acceptance, restore these dormant rules:
`main_release` on default-branch push, `official_release` on `vX.Y.Z[.B]` tags,
and `publish_github_main` only on protected `main` pushes from this exact
canonical project. Keep the independent Gradle release gate until integration
has been verified. The remaining release documentation describes that future
pipeline, not currently enabled behavior.

Release builds are signed with the **WearTAK production key** and the job fails unless the
APK signer SHA-256 is `1579b600…348d`. Release WearTAK only binds plugins with the same
signature (signature-level `BIND_PLUGIN`), so debug builds only work with WearTAK `.dev`.

SDK binaries are not in this repository. `scripts/fetch-sdk.sh` builds exact
canonical `weartak-plugins/weartak-plugin-sdk` source from `sdk-source.properties`,
using `SDK_READ_TOKEN` if set, otherwise `CI_JOB_TOKEN`. Source is checked out
detached at that immutable SHA; existing SDK source changes or a different origin
fail explicitly. Local derived AAR hashes are provenance, not signed publication.
Transient source authentication also covers partial-clone lazy fetches during
checkout; it is not persisted into the cloned repository's configuration.
CI must be allowed to read SDK source; it does not fetch template binaries.
For dependency routing, both plugin and SDK prefer the existing TAK Artifactory
consumer when a complete pair exists: first nonblank user from
`ARTIFACTORY_CONSUME_USER`, `ARTIFACTORY_USER`, `TAK_USERNAME`, independently
paired with first nonblank password from `ARTIFACTORY_CONSUME_PASSWORD`,
`ARTIFACTORY_PASSWORD`, `TAK_PASSWORD`. Public repository fallbacks remain.
Only availability booleans are logged; never print credential values.
Gradle jobs obtain a short-lived per-job token through
`devsecops/gitlab-ci-common`'s `artifactory_token_service.yml`, using the
`_no_depends_bash` template for the non-root launcher image. Token setup is
explicitly referenced first when a job has its own `before_script`. Non-Gradle
jobs retain an empty global setup. No static credentials or bundled Gradle init
script are required; the latter conflicts with `FAIL_ON_PROJECT_REPOS`.
Robolectric's separate test-JVM resolver receives the same Artifactory routing.
First-time token exchange may require the triggering user to sign in at
`https://artifacts.tak.gov`; successful CI logs must show `Token exchange successful`
and `takArtifactory for plugins: enabled` without disclosing tokens.
This Maven routing does not grant cross-project SDK source access.

## Versioning

Same scheme as WearTAK (WEARTAK-239), in `app/build.gradle.kts`:

- `baseVersionCode` / `baseVersionName` (currently `1` / `0.1.0`), independently
  versioned from WearTAK. Bump them for each official `vX.Y.Z[.B]` tag.
- CI builds: versionCode = `base * 10000 + CI_PIPELINE_IID` (build fails at IID ≥ 10000),
  versionName = `<base>-p<IID>+<short sha>`, e.g. `0.1.0-p12+1a2b3c4d`. Every pipeline is
  therefore an in-place upgrade (MDMs such as Knox accept it).
- Local builds: versionCode = base, versionName = base.

## Rolling GitHub release

After **all** earlier jobs pass, `publish_github_main` publishes the verified `main_release`
APK to the GitHub mirror as **TAK.gov Forge CI/CD Pipeline Release (<version>)** on the
fixed tag `TAK.gov-Forge-CI-CD-Pipeline-Release`, asset `MOPP-<version>.apk`
(`<version>` is the versionName without `+sha`). It is a stable release (`prerelease=false`)
that does not take GitHub's "Latest" flag. The body records versionName/versionCode,
pipeline ID/IID and URL, source SHA and APK SHA-256.

Safety rules (identical to WearOS, see `ci/publish_github_release.py`):

- Runs only for protected `main` push pipelines of this exact project; the script
  re-checks the gates.
- Uploads only the signed APK, verified against `verified-apk.sha256` and Gradle metadata;
  it downloads the upload again and checks size + SHA-256 before publishing.
- Never pushes commits or creates/moves tags; the anchor tag must already exist.
- Older pipelines never replace newer ones; only older publisher-owned assets are deleted,
  after verification. Resource group `mopp-github-main` serializes writers.

### Administrator setup

1. Signing variables: `WEARTAK_RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`,
   `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (protected, masked). Projects in the
   `weartak-plugins` group inherit them; elsewhere they need separate approval.
2. Protect `main` on git.tak.gov.
3. Create tag `TAK.gov-Forge-CI-CD-Pipeline-Release` on a commit already in the GitHub mirror.
4. GitHub token able to write releases on `aegorsuch/weartak-plugin-mopp`: a classic
   token with only the `public_repo` scope (the mirror is public), or a fine-grained token
   scoped to this repository with **Contents: read and write**. Add it as GitLab variable
   `MOPP_GITHUB_TOKEN` (masked, protected, hidden, no expansion).
5. If `CI_JOB_TOKEN` cannot read SDK source, an administrator must approve access
   or add `SDK_READ_TOKEN` (read_repository); do not change access automatically.

## Release testing

After a verified publish, the job sends `repository_dispatch` `mopp-release-published`.
`.github/workflows/release-testing.yml` (on the GitHub default branch) re-reads the release
and opens one issue per pipeline, `Release testing: <release title>`, with the APK link and
`ci/release-testing/checklist.md`, assigned to the repository variable `RELEASE_TESTER`
(needs Write access). Existing issues, including closed ones, are never recreated.
Edit the checklist when features change.

## Local validation

```text
python -m unittest discover -s ci/tests -v
node --test ci/release-testing/create_release_test_issue.test.cjs
```