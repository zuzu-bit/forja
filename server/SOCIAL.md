# FORJA social map — implementation and delivery boundaries

For the v22 continuous partner session, reboot recovery, contacts permission and
verified-phone discovery additions, see PARTNERS_CONTACTS.md. Timed sessions below
retain their v21 behavior.

Native Android map uses the already bundled osmdroid 6.1.20 SDK and OSM standard
raster tiles. Own artwork consists of circular initial markers and FORJA colors;
Compose sheets/chips use animated size/visibility, camera uses native animation,
and updated nearby markers interpolate over 750 ms when system animations are
allowed. Web uses vendored Leaflet 1.9.4 (official SHA-256 verified, BSD license
included), CSS transitions and camera animation. No proprietary Bump/Plimb
assets, branding or implementation were copied. This is not complete parity.

## Model and access

The JWT-verifying existing Worker routes `/v2/social/*` to a new `SocialGraph`
Durable Object in binding `SOCIAL`, migration `social-v1`. The internal UID header
is always overwritten from the verified token. The object is not public. It
serializes operations and holds the graph in one namespace (an early deployment,
not a horizontally sharded large-network design). There is no public user lookup
or uploaded contact book. Invite codes are random UUIDs; requests must be accepted
by their recipient. Both sides of every friendship and block are checked on reads.

Friendship does not activate location. The owner starts an expiring session with
explicit sharing consent. Location POSTs require the correct current session ID;
ghost mode removes that session and last position. GET state never returns stale
positions (120-second freshness) or expired sessions. Client markers also expire
without a successful refresh. The Android service starts in the visible app with
location permission and notification, stops at expiry, on account change or
revocation, and is START_NOT_STICKY. It does not run at boot. Polls are normally
15 seconds; OS, battery and network availability can interrupt/delay them.
An old service's cleanup DELETE includes its session ID and cannot stop a newer
session. Stop from web takes effect at server immediately and on the phone on its
next request. Approximate location is accepted and accuracy displayed.

Only latest coordinates, explicit check-in, battery and speed are shared. No
friend trail, inferred home/work, overnight companions or group location override
exists. Own saved places, last 500 exploration cells and last 30 activity summaries
are private. Distance uses accuracy, elapsed time and speed filters; it is an
estimate, not a verified sports record or basis for financial rewards.

Groups are private invitations to at most 20 people, created from accepted friends.
They carry an explicit meeting point, activity kind, start and attendance response.
Blocking prevents viewing/responding in a group containing the blocker. Groups
expire seven days after their event. Expiry alarms remove member indexes too.

Chat is authenticated friend-only text/emoji, maximum 100 messages per pair and
seven days, with expiry alarms. It is not end-to-end encrypted. Message text is
rendered as text nodes, never HTML. Removing/blocking clears the pair's messages.
Limits: 100 friends, 50 incoming/outgoing requests, 200 blocks, 100 saved places,
30 active group memberships, 90 writes/minute/user. v22 adds READ_CONTACTS for the separate opt-in contact workflow.

The old independent `PresenceRepository.start/publishState` publishers are retired
in the APK so new ghost mode does not leave a second publisher active. Existing
old Firestore documents are not migrated/deleted by this patch. Old APKs must not
be used for testing the new social consent flow. Users exchange new invitation
codes and accept the new friendships explicitly.

## Organization modes

Cleanup selection supports `mode: manual | local`, optional for old clients.
Manual is an inventory-only branch: no OCR, hash/dedup scan or classification.
Native manual starts with no selected mutations, existing paths, editable targets;
only reviewed changes reach the retained MediaStore/SAF operation engine.
Web remains manual destination editing, with local or online AI optional. Upload
is orthogonal to local/manual mode and retains the existing explicit grant.
Online AI does not apply plans by itself. Copies and receipts keep their 24-hour
retention, existing quotas and original-write checks.

Protocol 3 advertises manual support. Protocol 2 still handles local requests;
manual requests fail clearly for old clients rather than silently running AI.
The v21 phone must reactivate the organizer grant to publish the capability.
Existing scheduled analysis remains local. Online AI needs an explicit separate
request/consent after upload; at most five items per AI request.

## Not included and validation boundary

No Spotify OAuth/playback library integration, camera/gallery chat attachments,
custom stickers, proximity BUMP, widgets, all-day sharing to all friends,
Health Connect addition, purchased territories, virtual-currency economy,
anti-cheat leaderboards or pixel-identical Bump animations are claimed.
These need separate implementation/integration and product decisions.

Tests cover mutual friendships, no silent sharing, cross-account/session denial,
block/revoke/expiry, position validation, private places, text/idempotency, groups,
message expiration, old-session stop races, organizer mode capability and web DOM
flows. DOM tests use mocked map/network geometry, not a real rendered browser.
Compilation, DEX ABI verification, manifest/signature and Worker dry-run are local.
No device/emulator, live Firebase login, Cloudflare deployment, real two-phone
background/GPS test or visual comparison with Bump was performed in this environment.

Public references checked 2026-09-17:
- https://help.bumpmaps.com/en/articles/11420926-how-bump-works
- https://www.plimb.app/
- https://developer.android.com/develop/sensors-and-location/location/permissions
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://operations.osmfoundation.org/policies/tiles/
- https://leafletjs.com/download.html
