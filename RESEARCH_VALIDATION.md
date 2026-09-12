# Research copy validation

Base source: `e22f5c89913248aa9bf0719cf62c40cd5de2bbc5`.
Original downloaded release APK SHA-256:
`6485e34bdceba5dfde40131d63282401ebfb90a9a84b0ad48bddbd19004ef0d7`.

## Receiver integration tests

`node --test research-server/server.test.mjs`: **9 passed, 0 failed**.
The tests run a real local HTTP server and temporary filesystem, using synthetic
records and random lab tokens. No original FORJA service is contacted.

- Exact upload bytes, SHA-256 and counts match the receipt and subsequent GET.
- Stored files are owner-readable/writable only; explicit deletion removes them.
- Missing or wrong authentication blocks uploads, reads and deletion.
- Unselected data, missing selected categories and empty selection are rejected.
- A single selected category is accepted.
- Extra contacts, transcript, route, photo-path and permission fields are rejected.
- Invalid numeric bounds, types, row limits, intervals, package and run ID are rejected.
- Malformed, oversized and non-JSON bodies are rejected without storage.
- Expired exports are unavailable and removed; the health route exposes no records.

## Android build isolation checks

Inspected Gradle-generated BuildConfig, merged manifest and Firebase resources:

| Property | Observed value |
| --- | --- |
| Application ID | `com.forja.app.research` |
| Version | `3.7-research` |
| Research mode | `true` |
| Original Worker API URL | Empty |
| Firebase project | `demo-forja-research` |
| Launcher | `com.forja.app.feature.research.ResearchExportActivity` only |
| Boot receiver | Disabled |

## Build result

`assembleResearch`: **BUILD SUCCESSFUL** with Gradle 8.10.2, JDK 17,
Android platform 35 and the repository's existing dependency versions.
Kotlin and Java compilation, DEX assembly and APK packaging completed.
`apksigner verify` passed. Packaged APK metadata confirms the research package,
version, minimum API 26 and target API 35.

APK SHA-256:
`de7e9f926168bff59497be8d22cebfd6c6f237144d432dcfd77e552a4d18c52d`.

The environment initially lacked a JDK and required proxy configuration for
Gradle downloads. The first compilation also encountered duplicated generated
Room Java files under `app/build/generated/ksp/research/java/byRounds`.
Removing that temporary generated directory and rerunning `assembleResearch`
completed the build. No compiler task was excluded and no application source
was changed to suppress the error. A similar incremental-generation problem
is tracked in [KSP issue 1678](https://github.com/google/ksp/issues/1678).

## Device limitation

An Android 35 emulator was started without hardware acceleration. ADB became
reachable, but Android's package-manager service was still unavailable during
the validation attempt. APK installation and the on-device preview, consent,
upload and deletion flow have therefore **not been verified**. No physical
phone was connected. Local-database export and Firebase emulator account/sync
flows also remain untested on Android.

These checks establish a compiled research copy and a tested receiver, not
observed permission abuse on a phone or a prevalence result for other apps.
