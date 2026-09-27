# FORJA APK startup correction

The previously delivered APK was internally corrupt. Its ZIP CRCs passed, but
`classes.dex` contained 33,205,087 bytes while its DEX header declared 34,280,932.
The DEX SHA-1 and Adler-32 checks also failed. Checking only the APK archive and
the delivery hash did not detect this invalid executable payload.

- Broken delivery SHA-256:
  `35b9af4372d1c9589f2b43b02702baabf722e0bb5cd00b4a7182b0d1d8030e24`.
- Rebuilt, emulator-tested APK SHA-256:
  `2aad947e2fce655411c9d5fc828b99651155070277169088307509b98f8635bf`.
- All 20 DEX files in the rebuilt APK have correct lengths and integrity checks.
- Only `classes.dex` differs in extracted content between the two APKs. Android
  manifest, other DEX files and packaged resources match.

GitHub Actions run **34705977933**, source commit
`3fde48c20b0163794175e6a28943a184c1fc6139`, rebuilt the app and installed it on an
accelerated Android 15 / API 35 emulator. MainActivity launched successfully,
remained alive and in the foreground for 15 seconds, and rendered the normal
login form. The crash buffer was empty. The screenshot and UI hierarchy were
inspected. The same built APK is the corrected deliverable. Its signing certificate
matches the previous copy, so the correction can be installed as an update.

This fixes the executable artifact; no fitness or server behavior was changed.
Package and version remain `com.forja.app.research`, `3.7-online.4` (code 34).
The CI workflow now installs and launches builds before publishing its APK
artifact. `scripts/verify_apk.py` also checks the internal DEX sizes, signatures
and checksums before installation, to reject this specific corruption even if
the ZIP itself is valid.

The device-specific behavior reported by the user has not been directly
observed. The defective DEX is confirmed; its origin is not established. The
runtime check covers cold startup to login, not authentication or every feature.
