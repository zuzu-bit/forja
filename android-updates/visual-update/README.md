# FORJA visual update — v24

This update is built on the signed, delivered v23 app. It does not rebuild the
older root `app/` project. The cleanup module, first-launch permission UI and
web-pairing UI can change while the delivered services and other features remain.

## Build

Required: JDK 17, Gradle 8.10.2, Android platform 35, build-tools 35.0.0,
R8 8.7.18 and smali/baksmali 2.5.2. The historical packaging tool layout is:

```
TOOLS/sdk/android-35/android.jar
TOOLS/sdk/android-15/zipalign
TOOLS/sdk/android-15/lib/apksigner.jar
TOOLS/r8-8.7.18.jar
TOOLS/smali/*.jar
```

The SDK should also expose standard `platforms/android-35` and
`build-tools/35.0.0` paths to Gradle. The packaging Java tools are fetched using
`cleanup-update/scripts/fetch_packaging_tools.py` from official repositories.

From `android-updates/cleanup-update`:

```
gradle :feature:assembleDebug :feature:testDebugUnitTest :feature:exportCompileClasspath
```

From the repository root:

```
python android-updates/visual-update/build.py \
  --base /path/FORJA_v23_Telefon_Pierdut.apk \
  --tools /path/TOOLS \
  --compiled android-updates/cleanup-update \
  --permissions-patch android-updates/visual-update/permissions_patch.py \
  --keystore /path/original.keystore \
  --output /path/FORJA_v24_Visual.apk
```

Set the keystore password through `FORJA_KEYSTORE_PASSWORD`, or use the named
environment-variable options. Do not put signing passwords in source files or
command-line arguments. Omitting the keystore produces an unsigned review APK.

The input SHA-256 is
`cf0bf97cbc13d3b2aa2ef341b1f95f0b74e8ab026aab8b747b37f5d7b455bbf1`.
The required signer SHA-256 is
`6fc56c1ff164d46b4994fdbfa3e38c786e804c191897ccad581d278aa8feed74`.

## Packaging checks

The script only replaces DEX 4 and 16 (version and permission entry), DEX 22
(cleanup UI), DEX 23 (`WebPairActivity` only), and manifest version values.
All other archive entries, including every asset, remain byte-for-byte equal.
The manifest keeps the existing permissions and component declarations. The
recording implementation and its consent/session gates are preserved. Existing
DEX/ABI checks run before a deliverable is copied to its output path.

The permission patch replaces only `ResearchExportActivity.Screen`; existing
permission launchers, state and behavior remain in the pinned base. New UI code
calls those preserved handlers. New OS permissions or shared-data grants are not
added by packaging.

The generated `.verification.json` beside the APK records its hash and actual
verification status. Building, signing and ABI checks do not constitute an
Android device test. Install/startup, runtime permission denial/revocation,
background services, map gestures and authenticated server use still require a
device or emulator; report those tests separately when performed.
