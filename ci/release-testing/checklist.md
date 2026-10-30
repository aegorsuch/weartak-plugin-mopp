## Test setup

Record MOPP version, WearTAK version, pipeline, APK SHA-256, watch model, Wear OS
version, tester and date. Use release WearTAK with production-signed release MOPP.
Hardware acceptance is manual; automated tests do not imply a device pass.

## Core acceptance checklist

- [ ] MOPP installs separately and appears in WearTAK as MOPP / Protective posture.
- [ ] Open from WearTAK and the watch launcher; Back returns normally.
- [ ] An unsupported host shows **Update WearTAK** and cannot save or publish.
- [ ] Saved selection initially matches WearTAK, including never saved, None, and MOPP 0.
- [ ] Header says MOPP, with no gas-mask icon or header helper text.
- [ ] Legacy unknown saved time remains unknown after open and reconnect.
- [ ] Every choice from None through MOPP 4 saves immediately on tap; no Save control.
- [ ] Checked selection changes only after WearTAK acknowledges durable persistence.
- [ ] Back, close, and unload never send an additional save.
- [ ] Selection records the host's commit timestamp.
- [ ] Same-value tap is a new committed revision, not a receipt-time rewrite.
- [ ] Concurrent host edits produce a conflict; current saved selection remains visible.
- [ ] Lost replies are reconciled by reading host revision/mutation ID, not replayed.
- [ ] Disable stops live contribution and retains saved selection and saved time.
- [ ] Unload, disconnect, uninstall, and package replacement revoke the live contribution.
- [ ] Missing/incompatible plugin stops every MOPP projection but retains status/time/enable preference.
- [ ] Re-enable/rebind reads committed host state; it never replays a plugin cache.
- [ ] Host SELF_STATUS contains the expected PPE, with no duplicate external/builtin writer.
- [ ] No MOPP chat, raw CoT, marker, or unrelated physiology changes are emitted.
- [ ] Nonmatching package/UID/signature and old binding leases are rejected.
- [ ] Update installs in place and preserves the host's saved PPE.

## Results

**Overall:** PASS / FAIL / BLOCKED

**Exact builds and APK SHA-256:**

**N/A items and reasons:**

**Failures:** steps, expected/actual behavior, timestamp, sanitized logs and screenshots.

**Release blockers:** list all unchecked requirements; do not infer hardware acceptance.
