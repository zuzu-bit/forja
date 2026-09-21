# FORJA v23 — lost-phone recovery, signature pending

Latest source is `audio-diagnosis/forja-v17/server` and the adjacent
`cleanup-update/feature/src`. These historic folder names do not indicate version.
Read `server/LOST_PHONE.md` for Romanian setup, privacy, Android limits and API.

The original full app checkout is unavailable. This is a compiled feature update
against the checksum-pinned v22 binary. The v22 signing key is also unavailable in
this session. The compiled v23 APK is UNSIGNED and cannot be installed as delivered.
Do not present it as a signed replacement or overwrite the user's v22 APK artifact.
No new signing identity was generated and no installed phone data was changed.

Build toolchain: JDK17, Gradle8.10.2, SDK35/build-tools35, Kotlin2.1.0,
AGP8.7.3, R8 8.7.18, smali2.5.2. Base FORJA_v22_Cuplu_Contacte.apk SHA256:
244e0e9bf98be9203df119819765c0d5d1775917fb5c647df521c543722ea6e3.
Copy cleanup-update into a fresh build directory excluding build/.gradle/.kotlin,
set the Android SDK path, then run:

    gradle :feature:assembleDebug :feature:testDebugUnitTest :feature:exportCompileClasspath
    python lost-phone-update/build.py --base V22.apk --tools TOOLS --compiled CLEAN_DIR --output V23_unsigned.apk
    python lost-phone-update/verify.py V23_unsigned.apk --work-dir BUILD_DIR --android-jar TOOLS/sdk/android-35/android.jar
    python lost-phone-update/check_manifest.py V22.apk V23_unsigned.apk

With the original keystore, supply `--keystore PATH`. Set the password in the
FORJA_KEYSTORE_PASSWORD environment variable, or use the named environment-variable
options supported by build.py. The final signature must match v22 SHA256:
6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74.
The separate signing kit contains sign_update.py, the verified unsigned APK and
apksigner.jar; it prompts privately for passwords and refuses a different signer.

Packaging replaces DEX22 plus version metadata in DEX4/16 and adds one private
location foreground service declaration. Version: 3.7-online.23 / 53. Twenty DEX,
all original assets and the v22 signing identity requirement are preserved.
The v22 couple/contacts and old audio/organizer behavior remain present.

Server: npm ci, npm test, node --test ../publish.test.mjs; then Wrangler deploy
--dry-run --config wrangler.insights.toml --outdir /tmp/forja-worker23.
From the root, run `node lost-phone-update/ui-test.cjs`,
`node partner-update/ui-test.cjs` and
`node lost-phone-update/worker-auth-test.mjs /tmp/forja-worker23/insights-worker.js`.
DOM tests need jsdom26.1.0; set FORJA_JSDOM to its package path.

The Windows publisher prepares Worker11 and confirms lost_phone:1 in /health.
This is ready for owner review; no live deployment, GPS/reboot test or Firebase
account change was made. The app signing blocker must be resolved before the
feature can be activated on an existing v22 installation.
