# FORJA v21

Latest implementation: `audio-diagnosis/forja-v17/server` and
`audio-diagnosis/forja-v17/cleanup-update/feature/src`. Those historic directory
names are not the version of their content. Read `server/SOCIAL.md` for contracts,
behavior, access model and limits; `CITESTE-MA.txt` gives the Romanian setup flow.

The full current Android app sources were unavailable. This is a compiled feature
update against the exact v20 binary, preserving unrelated DEX and every original
asset. Do not use an older incomplete app checkout as a replacement.

## Build

Use JDK17, Gradle8.10.2, SDK35/build tools35.0.0, R8 8.7.18, smali2.5.2,
the original signing keystore (not included), and this exact base:
FORJA_v20_Organizare.apk SHA256
6e9d2af8424ac193f2d248414ef62405a69d28379e22835159afc655978561d1.
Copy cleanup-update into a fresh temporary build directory excluding build,
.gradle and local.properties; set SDK location, then:

    gradle :feature:assembleDebug :feature:testDebugUnitTest :feature:exportCompileClasspath
    python social-update/build.py --base V20.apk --tools TOOLS --compiled CLEAN_DIR --output V21.apk
    python social-update/verify.py V21.apk --work-dir BUILD_DIR --android-jar TOOLS/sdk/android-35/android.jar

Tool layout matches organizer-update/build.py. Build asserts exact source APK,
excludes ABI-only stubs, replaces DEX22, forwards MapScreen to SocialMapScreen,
retires the independent legacy presence publisher, updates version51/.21, adds
one private location service, verifies the existing signer and byte-preserved
original assets plus 18 untouched DEX. No new permission is declared.

    cd audio-diagnosis/forja-v17/server
    npm ci && npm test
    node node_modules/wrangler/bin/wrangler.js deploy --dry-run --config wrangler.insights.toml
    node --test ../publish.test.mjs

DOM checks use jsdom26.1.0; set FORJA_JSDOM to its path for both scripts.
Run `node organizer-update/ui-test.cjs` and `node social-update/ui-test.cjs`.
The code and compiled APK are development builds; see verification.json for exact
checksums and test boundaries. The Windows package deploys Worker v9 after the
owner's Wrangler authentication. It has not been published from this environment.
