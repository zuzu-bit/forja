# FORJA v20 — latest selections and organization from laptop

The deliverable is a constrained feature update on the exact v19 APK. The full
modern Gradle application project was unavailable. Do not build the older app
checkout as a replacement; it lacks previously delivered features.

Read audio-diagnosis/forja-v17/server/ORGANIZER.md for the behavior and constraints.
The historical module names are retained for continuity, not version identity.

## Build

Required: JDK 17 (with javac), Gradle 8.10.2, Android SDK 35/build-tools 35.0.0,
R8 8.7.18, smali/baksmali 2.5.2, existing debug signer, and exact v19 base:
SHA-256 48da9bf3459901972194e21678b8d9171b4280a32786e0b0171252ab29463196.
No signing private key or account credentials are included in these sources.

Use a fresh local build directory outside synchronized artifact directories.
Copy cleanup-update build.gradle.kts/settings.gradle.kts/gradle.properties and
feature/build.gradle.kts + feature/src into that directory. Then:

    gradle :feature:assembleDebug :feature:exportCompileClasspath :feature:testDebugUnitTest
    python organizer-update/build.py --base V19.apk --tools TOOLS --compiled CLEAN_BUILD --output /tmp/FORJA_v20.apk
    python organizer-update/verify.py /tmp/FORJA_v20.apk --work-dir WORK_DIR_PRINTED_BY_BUILD --android-jar TOOLS/sdk/android-35/android.jar

The tools layout matches audio-update/build.py: SDK android-35/android.jar,
SDK android-15 build-tools directory, smali/*.jar, r8-8.7.18.jar, debug.keystore.
`gradle_build.py` is this environment's convenience runner; adjust its tool paths
or invoke Gradle normally. It uses the existing configured network proxy.

Build.py replaces cleanup DEX22 and audio/setup DEX23. DEX4 adds the visible-app
organizer polling hook and version metadata; DEX16 only updates exported version.
The other 19 DEX, all original assets, and Android permissions are preserved.
ABI-only declarations are excluded from the feature DEX. Verify.py validates
unique classes, DEX checksums and all cleanup/audio references across the APK,
including retained legacy callers. Signer and preservation checks run in build.

## Server and UI tests

    cd audio-diagnosis/forja-v17/server
    npm ci
    npm test
    node node_modules/wrangler/bin/wrangler.js deploy --dry-run --config wrangler.insights.toml
    cd ..
    node --test publish.test.mjs

`organizer-update/ui-test.cjs` uses jsdom 26.1.0 (set FORJA_JSDOM to its module).
It loads the full production script, checks request fields, preserves edited
forms during polling, reviews item IDs, rejects mixed-source move selection,
uses AI drafts only on selected IDs, approves destinations, and clears on logout.

Validation is local/automated. No attached Android device or connected Cloudflare
account was available. The live feature becomes available after deploying the
included Windows package; authorization alone is not deployment. Actual provider
moves, background scheduling and gallery refresh need a physical-device check.
