# FORJA Insights validation

**Startup correction:** the earlier delivered APK had an invalid primary DEX.
It has been replaced by the exact APK built and launched on Android 15 in
GitHub run 34705977933. See `STARTUP_FIX.md` for the defect and verification.


## Completed before deployment

- `node --test server/insights.test.mjs research-server/server.test.mjs`:
  30 passed, zero failures (14 online-store/evidence tests; 16 receiver tests).
- Online-store tests: exact metrics/binary receipts, unknown fields and bounds,
  category enforcement, cross-account isolation, duplicate concurrent uploads,
  deletion of known and orphan items, expiry and audio-window/WAV validation.
- AI/evidence tests: no suggestions supported by an empty account; coordinates
  excluded from evidence; missing activity labeled as incomplete coverage;
  invalid evidence citations and output confidence rejected; model URL fields
  dropped; failed Firestore queries do not hide other journals. Object and JSON
  model responses are accepted; truncated output and fabricated citations fail.
  Model-written reasons are discarded in favor of actual received evidence, and
  sparse evidence cannot acquire medium confidence.
- `wrangler deploy --dry-run -c wrangler.insights.toml`: passed with the intended
  AI, private R2 and SQLite Durable Object bindings. Wrangler pinned to 4.131.1.
- Android `clean assembleResearch testResearchUnitTest`: passed; 9 JVM tests.
  GitHub Research build run 34702135485 also succeeded for the same Android code.
- Packaged identity/launcher checked; the original archive check did not detect
  internal DEX corruption. The replacement APK passes DEX integrity checks and
  Android installation/startup. Package
  `com.forja.app.research`, version `3.7-online.4`, label FORJA, MainActivity.
- APK SHA-256:
  `2aad947e2fce655411c9d5fc828b99651155070277169088307509b98f8635bf`.

## Public checks

- Portal is deployed at https://forja-insights.forja-22e7ea2d.workers.dev/insights.
- GitHub Actions deployment run 34703058257 succeeded at source commit
  `a8a8baa3300198c7e9b390574033785512a2d08f`. Cloudflare version
  `88a3dc03-2b57-4652-b2ff-875e8b19adb6`.
  The preceding full integration run used deployment 34702733571.
- Deployment created the dedicated private `forja-insights-data` bucket and bound
  it to the new receiver; the original fitness Worker is unchanged.
- Public login page rendered in the browser with no visible overlap.
- The first live run verified authentication, private Firestore reads, exact
  binary/metric uploads, account isolation, text-model availability and deletion,
  but recommendations returned HTTP 502. The subsequent revision enables JSON
  schema mode, handles object responses and raises the bounded output budget.
- The repeat public run passed all 39 checks against real Firebase, Durable
  Object, R2 and Workers AI services: registration/login, anonymous/forged-token
  rejection, exact metrics and binary receipts/downloads, cross-account read and
  deletion denial even with a spoofed owner header, all three private journals,
  selected-text analysis, six evidence-linked recommendations, and complete
  session/journal/account cleanup.
- A further eight checks passed for a generated checkerboard image: upload, real
  vision-model interpretation and cleanup. This verifies the model transport;
  the description contained repetitive wording, so it is not evidence that all
  image interpretations are accurate.
- Review of the generated cards found unsupported assumptions in model-written
  explanations, despite valid source IDs. The final revision assembles each card
  explanation from its actual evidence on the server, caps sparse-data confidence
  at low, and makes the prompt require optional activities rather than claims
  about unobserved behavior. All 15 focused public checks passed on the final
  deployment: real model output, exact server-built explanations, low confidence
  for sparse data, and deletion of both journals and the temporary account.

## Limits

No physical phone is connected. Real Android sensor/picker collection and
phone-to-server delivery have not been observed in this environment. Previous
software-emulator attempts stalled in Android System UI. Authenticated browser layout and physical-phone interaction have not been
visually verified. Local store tests use an in-memory adapter; public API tests
exercise real Durable Object/R2 bindings. The recommendation model can be wrong despite validated
output structure and source IDs; the panel does not verify shopping availability
or treat inferred preferences as facts.
