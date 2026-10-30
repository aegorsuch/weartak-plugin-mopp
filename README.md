# WearTAK MOPP plugin

Separately installable native Wear OS plugin, application ID
`com.weartak.plugin.mopp`. TAK.gov project **6933** is canonical:
`https://git.tak.gov/weartak-plugins/weartak-plugin-mopp`.
The public GitHub mirror is `https://github.com/aegorsuch/weartak-plugin-mopp`.

MOPP is a separate Android APK, accessible through WearTAK's normal Plugins menu
like LACE, not a launcher-only tool. Discovery uses the signature-protected
`com.weartak.plugin.action.PLUGIN_SERVICE` service and advertises
`externalMainActivity=true` so WearTAK can open the editor. The watch launcher is
an additional entry point. This repository does not target Garmin or iOS.

**Migration in progress; not yet cleared for release.** The editor, typed SDK
adapter, plugin entry point, packaging and tests are implemented. Matching WearTAK
host persistence, lifecycle and reporting gates must pass coordinated acceptance.
There is deliberately no private Binder encoding, reflective adapter, raw CoT,
chat, marker, settings-mutation fallback, or local saved-PPE store.

Source publication does not imply host/device acceptance. Protected-main CI builds
production-signed APKs with verified signer and SHA-256; GitHub publication then
runs automatically, matching other plugins. Activation must wait for coordinated
host acceptance. Actual MOPP UI belongs to this
plugin; the host retains storage and status projection.
Do not install against a host that lacks the matching typed capability.

## Source provenance

Initial source was authored in the standalone MOPP development session and imported
from its sanitized October 30, 2026 snapshot. Packaging and CI were adapted from
canonical TAK.gov LACE revision `c1e0116925b199a4a081ddc5682f8434888fd006`.
SDK binaries, local configuration, signing material and build outputs are not
included. Source mirrors automatically; signed APK publication is a separate
verified step after all release gates below are satisfied.

## State ownership and behavior

WearTAK must remain the sole durable `savedPPE` authority and SELF_STATUS writer.
MOPP keeps only an in-memory pending selection and the last host read. None is distinct from
MOPP 0 through 4, and a never-saved selection is distinct from all six choices.

- Tapping a posture calls the editor's host port immediately; no separate Save.
  Selection changes visually only after a positive persistence acknowledgement.
  Closing never sends an additional save.
- Read authoritative state on open/resume and after a revision conflict.
- Preserve the host's timestamp verbatim, including legacy unknown timestamps.
- A same-value tap is still a commit; never synthesize a timestamp.
- Use host revisions and mutation IDs. A lost reply blocks changes until a read;
  it never automatically replays the operation.
- Disable/unload/uninstall must revoke the live host contribution while retaining
  the host's saved selection and saved time.
- New bindings start without cached selections or drafts. Stale in-flight replies
  cannot restore an old binding's state.
- If the plugin is absent or incompatible, the host must stop MOPP reporting while
  retaining saved status and timestamp. Reconnection reads that
  state without creating a default or a new saved timestamp.
- Always-on publishing requires a matching WearTAK host companion change: status
  reads report `enabled=true`, saves accept legacy false or unset enable state, and
  `setEnabled(false)` remains revision-checked/idempotent but normalizes to true
  without changing saved selection or timestamp. Until that host change lands, this
  behavior is not shipped. The plugin never enables publishing on read.
- A matching host change also derives the Plugins-menu subtitle from authoritative
  status: null selection displays `None` without saving it; explicit None also
  displays `None`; saved levels display as compact `MOPP0` through `MOPP4`. This host
  rendering is not shipped yet.
- Reporting is gated only by a live compatible plugin and a saved selection; unset
  selection never contributes.

`MoppEditor` and `MoppHost` are editor-domain types, not SDK IPC declarations.
Their tests use an in-memory host double; they are not proof of host enforcement.
The production host must independently check package, Binder UID, signing trust,
current binding lease, revision, mutation identity and durable commit success on
every operation.

## Compatibility gate

`PluginInitParcel.hostExtras` advertises `MoppPpeContract.EXTRA_CAPABILITY`
(`moppPpeV1`) and `EXTRA_CONTROL` (`moppPpeControlV1`). The control implements the
shared generated `IMoppPpeControlV1` interface: `read`, `save` and `setEnabled`,
with typed `MoppPpeResultParcel` / `MoppPpeStateParcel` results.

Missing capability or a mismatched descriptor shows **Update WearTAK**. The plugin
links Binder death, validates typed results and reads authoritative state before
allowing selection changes. Remote failures require reconciliation; no automatic replay.
The adapter never manually encodes Binder transactions or invokes raw CoT APIs.
Host-side builtin routing removal and all reporting gates must ship together with
this plugin, without duplicate writers.

## Build

Use JDK 17 and Android SDK 35. Packaging follows LACE's pinned canonical revision
`c1e0116925b199a4a081ddc5682f8434888fd006`: Gradle 8.14.5, AGP 8.13.2,
Kotlin 2.2.0, minSdk 30 and targetSdk 34. MOPP's base version **5.8.4**
matches WearTAK and the other WearTAK plugins (base versionCode **584**).

Build SDK inputs from the canonical repository and immutable commit recorded in
[`sdk-source.properties`](sdk-source.properties):

```powershell
.\scripts\fetch-sdk.ps1
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

PowerShell script execution must be permitted by the user's existing system
policy; do not weaken or bypass that policy. On a host with Bash, the equivalent
repository-supported command is `scripts/fetch-sdk.sh`.
Set `ANDROID_HOME` to the installed Android SDK if needed.
SDK source needs Android SDK 34; plugin compilation needs SDK 35. Fetch scripts
verify the canonical origin and exact source commit, refuse local SDK source
changes, then build debug/release AARs under ignored `.gradle\mopp-sdk-source`.
Derived inputs are copied to ignored `app\libs`; **never commit them**. Scripts
print source revision and local SHA-256 values for provenance, not binary trust.
No unsigned SDK release download or signature-policy bypass is involved.

## Release gates

See [CI/CD and releases](ci/README.md) and the
[manual acceptance checklist](ci/release-testing/checklist.md).
Do not distribute an operational release until all gates pass.

1. Pin the green canonical shared SDK source and matching typed host implementation.
2. Verify host persistence, CAS/idempotency, identity and lifecycle enforcement.
3. Complete coordinator-owned hardware acceptance with no duplicate builtin writer.
4. Verify production signing with inherited group variables; never retrieve/log
   secret values. Expected signer SHA-256:
   `1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d`.
5. Provision `MOPP_GITHUB_TOKEN` through explicit user-approved administration,
   configure protected `main`, and create the fixed rolling-release anchor tag
   after a commit exists in the mirror. Protected `main` and the enabled source
   mirror were verified during source publication; no token or tag was provisioned.
6. Only after these gates pass, merge release workflow activation. Signing
   builds alone never authorize installation or operational status transmission.

No device pass, production-signature pass, canonical merge, mirror publication,
or release is implied by a successful debug build or mocked publisher tests.
