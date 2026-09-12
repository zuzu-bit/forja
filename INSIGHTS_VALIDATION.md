# FORJA Insights validation

## Completed before deployment

- `node --test server/insights.test.mjs research-server/server.test.mjs`:
  29 passed, zero failures (13 new online-store/evidence tests; 16 receiver tests).
- Online-store tests: exact metrics/binary receipts, unknown fields and bounds,
  category enforcement, cross-account isolation, duplicate concurrent uploads,
  deletion of known and orphan items, expiry and audio-window/WAV validation.
- AI/evidence tests: no suggestions supported by an empty account; coordinates
  excluded from evidence; missing activity labeled as incomplete coverage;
  invalid evidence citations and output confidence rejected; model URL fields
  dropped; failed Firestore queries do not hide other journals.
- `wrangler deploy --dry-run -c wrangler.insights.toml`: passed with the intended
  AI, private R2 and SQLite Durable Object bindings. Wrangler pinned to 4.131.1.
- Android `clean assembleResearch testResearchUnitTest`: passed; 9 JVM tests.
- APK signature and packaged identity/launcher: passed. Package
  `com.forja.app.research`, version `3.7-online.4`, label FORJA, MainActivity.
- APK SHA-256:
  `35b9af4372d1c9589f2b43b02702baabf722e0bb5cd00b4a7182b0d1d8030e24`.

## Public checks

Pending deployment and live synthetic-data verification. Local tests are not
proof of successful Cloudflare deployment or of real model availability.

## Limits

No physical phone is connected. Real Android sensor/picker collection and
phone-to-server delivery have not been observed in this environment. Previous
software-emulator attempts stalled in Android System UI. The data-store tests
use an in-memory adapter; live tests are needed to exercise actual Durable
Object/R2 bindings. The recommendation model can be wrong despite validated
output structure and source IDs; the panel does not verify shopping availability
or treat inferred preferences as facts.
