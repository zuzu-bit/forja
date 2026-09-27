# FORJA v22 — couples and contact discovery

The Android build is 3.7-online.22 / 52; the Worker health version was 10, with
`partners:1` and `contacts:1`. FORJA 4.x raises the Worker to version 17 with
`contacts:2` (declared numbers + mutual-agenda friendships, below). Deploy the
Worker before installing/testing.
The existing social Durable Object and migration are reused. No new paid service
or Firebase configuration has been enabled from this environment.

## Couple flow and location lifecycle

An accepted friend can request a couple association. Both parties must accept
the friendship, then the recipient accepts the couple request. Only one pending
or accepted partner is allowed. Pair acceptance never starts either microphone,
location service or upload. Each person separately starts their own continuous
location session on their phone with a selected partner and explicit consent.

`POST /v2/social/partner`: `{id, action: invite|accept|decline|disconnect}`.
`POST /v2/social/session`: `{mode:partner, minutes:0, continuous:true,
audience:acceptedPartnerUid, consent:true}`. The resulting session has `until:0`.
Normal walk/cycle/out sessions retain their existing duration limits.

Every location write verifies owner, current session, reciprocal unblocked
friendship and reciprocal accepted pair/token. Continuous coordinates are visible
only to the selected partner, not other accepted friends. No other user's GPS can
be started from the web. Stop/ghost is allowed from the owner's app, notification
or website; disconnect/remove/block revokes both continuous grants. An old session
ID cannot restart sharing or stop a newer session. Accepting another association
requires a new manual start. Only the latest coordinate is retained, with a
120-second freshness window and expiry alarm. Continuous mode does not create
exploration cells, distance records or activity history. Location includes speed,
accuracy and battery, as in the existing map. Transport is HTTPS; not E2E encrypted.

The native location foreground service has a visible notification and stop action.
Continuous sessions request GPS/network updates around every 30 seconds with zero
minimum distance, so stationary positions can refresh. Network/provider failures
are retried, subject to Android. Owner-bound local session intent survives process
recreation (START_STICKY), but no new grant is minted on restart. Before resuming
updates the phone checks that the same server session is still active. An account
change or revoked permission stops collection. Stop clears local intent first and
queues an owner/session-bound server revocation for connectivity recovery.

The private SocialBootReceiver handles BOOT_COMPLETED and MY_PACKAGE_REPLACED.
Boot resumption additionally requires ACCESS_BACKGROUND_LOCATION on Android 10+;
otherwise reopening the foreground app can resume the previously chosen session.
Force-stop requires reopening. Notification permission is required on Android 13+.
OS process limits, notification settings, battery management, GPS and connectivity
can interrupt service. A continuous choice is not a guarantee of uninterrupted
tracking. The app does not bypass OS restrictions or hide the location notification.

## Agenda and optional matching (v17: declared or verified number, reciprocity)

Two trust levels exist for the account's OWN number. **Verified**: the Firebase
token carries `phone_number` (Phone Auth enabled in the console); the gateway sets
`x-forja-phone` from the token only. **Declared** (new): the app sends the number it
typed as `x-forja-phone-declared`; the gateway forwards it ONLY under that name
(validated as E.164, never as `x-forja-phone`). The Durable Object uses
`phone = verified || declared` and stores `phone:{hmac} = {uid, until, verified}`.
Rules: a verified registration replaces a declared one of another account; a
declared one can replace neither a verified listing nor another account's still
active declared listing (409 „Numărul e folosit deja de alt cont. Verifică-l prin
SMS ca să-l revendici.”). Match results carry `verified` per match. Accepted risk,
explained to the owner: a declared number is not proven; an impostor would need
both numbers and to register before the real person; enabling Phone Auth in the
console closes the gap (the verified path is implemented).

Reciprocity: for each account the server keeps ONLY the HMAC fingerprints of the
numbers in that account's agenda (`contacts-of:{uid}`, truncated to 64 bits, at
most 5000 entries, 30-day expiry refreshed on every match; deleted by
`DELETE contacts/discovery`). Never numbers or names. When my fingerprint is in
his index and his in mine, `/contacts/match` returns `mutual:true`, adds both to
each other's `friends` on the site graph (unless blocked or at the 100-friend
limit) and the app creates `friendships/{a_b}` in Firestore (idempotent). A
non-mutual match stays a listing with an invite proof; the app shows it under
„Din agendă” with „Trimite-i codul tău” — no friendship (= map visibility) is
created without the other person's consent.

READ_CONTACTS is the only new manifest permission. The local phone-number query
reads all accessible contact names and phone numbers, deduplicates normalized
numbers and supports search/paging. It does not modify contacts or silently send
SMS. The SMS invite action only opens the user's SMS composer.

Matching is separate and opt-in: declare the account's own phone (or verify it
with Firebase when Phone Auth is enabled), enable finding this account by that
phone, then enable daily contact matching (the app does all three from the sixth
„Echipare” row, and exposes the switch, SMS verification and „Sincronizează acum”
in the profile).
The Firebase credential is linked to the already signed-in UID (or updates that
UID's phone). Credential collisions do not auto-sign-in, merge accounts or move
existing data. Changing a previously linked phone first removes its old discovery
mapping and disables local contact sync. Failed number changes can require
reenabling discovery for the unchanged number.

The Worker validates Firebase RS256 JWT issuer/audience/signature/expiry and
**overwrites**, never trusts, incoming owner, phone and token-issued headers.
Discovery registration requires a fresh token (within five minutes) and either a
verified `phone_number` claim or a declared number header. Reading local contacts
does not prove number ownership.

`POST contacts/discovery {consent:true}` registers the verified or declared
number (response `{ok, until, verified}`); DELETE removes it together with the
account's agenda fingerprint index. The server stores a keyed HMAC-SHA256 index
with UID, expiry and the `verified` flag, not the raw number. An inactive listing expires after 30 days; already-enabled
matching renews a still-active listing. Opt-out is never automatically reversed.

`POST contacts/match {numbers:[E164],consent:true}` compares batches of up to 200
numbers over HTTPS. Names remain on the phone. Supplied numbers are processed in
memory and not persisted in the social store, responses or application logs.
Only opted-in, unblocked accounts are returned with the input index, public FORJA
name, UID, relationship state (`friend`, `pending`, `mutual`, `verified`) and a
short-lived invite proof. There
is no unauthenticated directory endpoint. A 10,000-number daily quota limits
matching, distinct from reading the full local agenda. An installation can read
more locally but does not match more than this server quota per day.

`POST contacts/invite {id,proof}` requires proof bound to both accounts, target's
current listing and expiry. This sends a friend request; it does not accept the
friendship or share location. Both sides must still accept the friendship.

Local periodic WorkManager matching runs approximately daily with connectivity
and adequate battery. Its owner and generation are checked between batches;
disabling or changing the account invalidates in-flight results. Cached matches
are private on-device. The phone settings can stop sync separately from removing
its own discoverability. Removing discoverability on the website also causes
subsequent matching to fail; the server does not resume it automatically.

## Required Firebase configuration (not performed here)

Project: `forja-65093`; Android package: `com.forja.app.research`.
1. Firebase Console → Authentication → Sign-in method → Phone → enable.
2. Configure allowed SMS regions for intended users, including Romania as needed.
3. Add this existing APK signing certificate to the Android app settings:
   SHA-1: F0:66:F9:5C:64:2C:7D:D1:56:EB:36:8E:07:82:EB:32:F8:CA:8C:5A
   SHA-256: 6F:C5:6C:1F:F1:64:D4:6B:49:94:FD:BF:A3:E3:8C:78:6E:80:4C:19:18:97:CC:AD:58:1D:27:8A:A8:FE:ED:74
4. Validate Play Integrity and the reCAPTCHA fallback for sideloaded APKs. The
   API-key restrictions must support the Firebase auth-domain fallback described
   in the official instructions; don't remove unrelated key restrictions.
5. Real verification SMS requires the applicable Firebase Blaze plan and SMS
   quota/billing configuration. Configure this as the project owner; this package
   does not change billing. Firebase fictional test numbers may be used for QA.

Without this configuration, local agenda access and invite codes remain usable,
but verified contact discovery cannot be claimed to work. Partner sharing uses
existing FORJA accounts and accepted invite-code friendships; SMS is not required
for that flow. Each partner uses a distinct account.

Official documentation checked 2026-09-17:
- https://firebase.google.com/docs/auth/android/phone-auth
- https://firebase.google.com/docs/auth/android/account-linking
- https://firebase.google.com/docs/auth/limits
- https://firebase.google.com/pricing
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/identity/providers/contacts-provider/retrieve-names

## Validation and remaining device checks

Automated checks cover pair acceptance, chosen-partner isolation, independent
consent, >24-hour sessions, stale-coordinate erasure, stop/block/remove, verified
phone claims, contact opt-out, number reassignment, bounded batches/quotas and
proof scope/expiry; `social-contacts.test.mjs` adds declared vs verified priority
and 409, mutual → friend on both profiles, blocks winning over reciprocity, the
fingerprint-only agenda index (5000 cap, 30-day expiry), opt-out cleanup and the
gateway header/version 17 contract. A separate bundled-Worker test uses synthetic RS256 keys to
check issuer/audience/expiry and replacement of attacker-supplied identity headers.
Existing server, organization and Android unit tests remain part of the build.
APK checks cover manifest changes, preserved assets, signature continuity,
DEX uniqueness and actual bundled Firebase method availability. Web DOM tests
exercise the couple panel, stop, opt-out, safe text and logout cleanup.

No emulator/device, actual Firebase SMS, live Cloudflare deployment, rendered
Android UI or two-phone GPS/background/reboot test was available. Those practical
checks are still required before public rollout. The Windows publisher validates
and dry-runs before deployment, preserves existing data and confirms Worker v10.
