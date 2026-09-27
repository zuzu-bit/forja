# First-launch setup and automatic collection

The research copy now uses one Romanian **Permisiuni și sincronizare** screen at first launch and in Profile. All optional categories default to off. Saving selected categories is the explicit online collection choice; Android grants alone do not start collection. No manual review/send checkbox remains. First-launch setup can be completed with every category off, leading to the normal login.

After sign-in, the foreground collector uses the same Firebase account and existing FORJA Insights endpoint. Location, usage events, selected photo/file contents and live microphone clips are sent automatically for the enabled categories. The initial page states background collection, destination, retention and how to stop. A visible notification lists active categories, explicitly says when the microphone is recording, and provides Stop. There is no boot receiver, remote start, hidden recorder, blanket file access, or removal of Android permission dialogs. Files/photos are explicitly selected with system pickers and persistable URI access. Changing a selection is staged until Save.

Android grants are checked during collection and before each upload. Account changes, logout and settings revisions invalidate captured work; disable cancels queued/in-flight HTTP calls and releases sensors. An already received request cannot be recalled by canceling it. Turning off stops new data; existing sessions can be deleted from the account portal. On Android termination/force-stop/reboot, collection can resume only when the user reopens the app with saved choices. Android foreground-service limits apply, including data-sync timeouts when files/photos are enabled.

## Bounds and server behavior

- Latest 300 location samples per automatic session, sampled no more frequently than every 10 seconds. Dwell estimates do not bridge missing samples or inaccurate fixes.
- App usage starts at activation or current session start, whichever is later; never before the preceding 24 hours. Approximate one-minute metric refresh.
- At most five selected files/photos total, at most 5 MiB each. Contents are checked for changes at approximately one-minute intervals; unchanged bytes are not retransmitted during the current collector run.
- Microphone sends 5-second PCM WAV clips. Server keeps the latest 24 clips in the session; replaced item IDs become inaccessible. Network/microphone interruptions can create gaps.
- Automatic sessions permit bounded snapshot/item replacement; manually uploaded sessions remain immutable. Replacing content invalidates AI observations about the previous bytes. Session storage accounting subtracts replaced bytes.
- Sessions expire after 24 hours; the collector rotates at 23 hours. Existing 20-session/account and 32 MiB/session limits remain. Errors are displayed and retried at 30-second intervals. Internet and Android scheduling can delay delivery.
- Existing fitness journals and account-scoped AI recommendations remain available. AI processing is separately user-initiated in the portal; microphone audio is not sent to the recommendation model.

## Verification

34 Node tests pass locally, including automatic snapshot replacement, rolling audio, consent immutability, account isolation and stale AI-observation invalidation. JVM policy tests cover Android grants without consent, revocation, account change/logout, revision cancellation, and usage-history boundaries.

CI additionally validates DEX integrity, startup, default-off setup, continuing to login without collection and no repeated setup. An instrumented Android 15 emulator test uses a disposable Firebase account (no profile or fitness journal), verifies real automatic uploads to the online server, background continuation, disable, no restart from Android grants alone and logout. Synthetic sessions and the temporary authentication account are removed in test cleanup. The deliverable is the exact APK from a successful emulator-tested run; its saved/downloaded bytes must match the tested SHA-256.

Android references: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [photo picker and persistent URI grants](https://developer.android.com/training/data-storage/shared/photo-picker).
