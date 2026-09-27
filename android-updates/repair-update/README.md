# v27 corrective delivery

Build from the exact signed v26 APK (SHA-256 `917d24c066a7811e81ef5e85525303da46c8ed0e79920bea384ae5c77490a103`) with the original signing key. This restores the primary local sleep/alarm/history screen, removes the final onboarding synchronization summary page, and repairs map initialization/fallback. It does not rebuild the older root app.

Compile `../cleanup-update` with JDK 17, Gradle 8.10.2, SDK/build-tools 35:

```
:feature:assembleDebug :feature:testDebugUnitTest :feature:exportCompileClasspath
```

Then provide the keystore password through `FORJA_KEYSTORE_PASSWORD` and run:

```
python build.py --base /path/FORJA_v26_Continuitate.apk --tools /path/android-tools --compiled ../cleanup-update --sleep-patch ../sleep-update/restore_legacy.py --keystore ../../app/debug.keystore --output /path/FORJA_v27_Corectat.apk
```

Version is `3.7-online.27`, code `57`. The allowlisted changes are DEX 4/16 version constants, DEX 22 feature code, a single observer call in DEX 6 connecting the original Start/Stop sleep card to the actual recorder, and manifest version fields. All other manifest fields, permissions/components, assets/native libraries and DEX files remain unchanged. The builder verifies archive preservation, original signer, retained hooks, reflection ABI and new static calls.

## Isolated rendering probe

The `qa` module is included only with `-PforjaQaProbe=true`; production packaging never includes it. Build `:qaProbe:assembleDebug` alongside the feature, then use `qa/build_probe.py` with an already-built production APK, tools, output and keystore arguments. It produces application ID `com.forja.app.renderqa`, redirects that separate manifest's launcher to `TestActivity`, and appends one test-only DEX. Every production DEX/asset is preserved. Production authentication is neither changed nor bypassed.

Launch the isolated activity with `--es screen map` or `--es screen sleep`. Its fresh signed-out state can validate public map rendering and local alarm/history UI; it cannot validate authenticated sharing, live account synchronization, or real recording sessions. Capture screenshots/UI trees and crash logs separately. The probe APK is an internal test artifact, not a release.
