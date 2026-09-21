# FORJA v22 — couple sharing and contact discovery

Authoritative code is in `audio-diagnosis/forja-v17/server` and
`audio-diagnosis/forja-v17/cleanup-update/feature/src`; the directory names are
historical. User setup: `audio-diagnosis/forja-v17/CITESTE-MA.txt`.
Contracts, privacy, Android limits and required Firebase setup:
`audio-diagnosis/forja-v17/server/PARTNERS_CONTACTS.md`.

This adds owner-enabled continuous location for one mutually accepted partner,
private notification/stop/reboot recovery, local contact browsing, Firebase phone
linking and optional verified-contact discovery with daily synchronization.
The web panel supports pairing, location viewing and revocation. It does not
remotely mint a new location-sharing grant. READ_CONTACTS is the only new permission.

## Rebuild

The full original app source is unavailable. This is a compiled feature update
against the exact v21 APK, not a complete clean-room Android app checkout.
Required base: FORJA_v21_Social.apk, SHA256
8b2b1b240ffee0695fe441015418e4c848d3b354c236b7998277e76ab4dd5609.

Use JDK17, Gradle8.10.2, SDK/build-tools35, R8 8.7.18, smali2.5.2 and the original
signing keystore (not included). See social-update/README.md for tool layout.
Copy cleanup-update to a fresh build directory, exclude build/.gradle/.kotlin and
local.properties, set the Android SDK path, then:

    gradle :feature:assembleDebug :feature:testDebugUnitTest :feature:exportCompileClasspath
    python partner-update/build.py --base V21.apk --tools TOOLS --compiled CLEAN_DIR --output V22.apk
    python partner-update/verify.py V22.apk --work-dir BUILD_DIR --android-jar TOOLS/sdk/android-35/android.jar

Build pins the base, removes ABI-only stubs, replaces compiled DEX22, updates
version metadata in DEX4/16 and manifest to .22/52, adds READ_CONTACTS and a private
boot receiver. Twenty original DEX and all original assets are byte-preserved.
DEX23 recording code and its explicit arming/permission/upload gates are retained.
All referenced FORJA/Firebase/osmdroid methods are checked against actual APK code.
The v21 map route and disabled legacy presence publisher remain unchanged.

Server validation (Node22+):

    cd audio-diagnosis/forja-v17/server
    npm ci
    npm test
    node --test ../publish.test.mjs
    node_modules/.bin/wrangler deploy --config wrangler.insights.toml --dry-run --outdir /tmp/forja-worker22 --experimental-provision=false

From the source root:

    node partner-update/ui-test.cjs
    node partner-update/worker-auth-test.mjs /tmp/forja-worker22/insights-worker.js

DOM tests use jsdom26.1.0; set FORJA_JSDOM to its installed package path.
The Worker authentication integration test executes the dry-run bundle, replacing
only static Text imports for Node, with synthetic RS256 keys and a mocked Google
JWK endpoint. Production JWT-verification logic is unchanged.

`verification.json` records the exact final APK and evidence. Tests are local;
real GPS, reboot, Firebase SMS and live Cloudflare deployment were not performed.
The Windows ZIP publishes Worker10 after the owner's Wrangler authorization.
Firebase Phone Authentication, regions, signing hashes and SMS billing/quota must
be configured in the owner's existing Firebase project; see setup instructions.
