# Android app implementation plan

Status: implementation in progress. Prepared on 2026-10-03 against
`fergusean/consolidated`, commit `26cca8e56374da73f0437f9223e935fad30cba61`.
The baseline app and host version was `2.3.0`. This document plans the work;
its acceptance criteria are not claims that Android features already exist.

Build a native Android client equivalent to the shipped iOS app while keeping
the fork straightforward to update from upstream. Put the new client under
`apps/android/`, consume the existing protocols, and limit changes to existing
services to small Android compatibility additions. Upstream remains the owner
of the host, account system, encrypted relay, agent execution, and API design.

The first useful build should pair with a computer, show its bots, exchange
messages, answer permissions, and recover after disconnection. Full parity also
includes accounts, attachments, groups, threads, automation, integrations, voice,
remote desktop, usage, widgets, and background notifications.

## Decisions and assumptions

| Topic | Proposed decision |
| --- | --- |
| Framework | Native Kotlin and Jetpack Compose, with coroutines and immutable UI state |
| Initial OS range | Android 10 and later; confirm after dependency and device checks |
| Devices | Phones first, with usable tablet and foldable layouts; no separate tablet feature set |
| Distribution | Signed APKs for development and private testing, then a signed AAB for Google Play |
| Push support | Google Play services and FCM for the first release; recognize unavailable services explicitly |
| Backend | Existing Codync host, `cloud/`, and `relay/`, with bounded compatibility patches |
| Feature baseline | Implemented iOS behavior at the recorded baseline, updated when upstream changes are integrated |
| Product scope | Preserve the existing account/computer structure and selectors; do not expand multi-computer functionality |
| Fork deployment | Sean's configured services and host, as recorded in [deployment memory](../reference/sean-deployment.md) |
| Initial modularization | Three Android Gradle modules: `app`, `core`, and `design` |

These are implementation defaults. Final application ID, display name,
minimum OS, Firebase projects, signing ownership, and distribution access must
be recorded before registering production integrations. Their absence does
not block local protocol and UI work.

Google Play services availability is a distribution assumption, not a
requirement for pairing or foreground chat. Provide a clear notification state
when FCM is unavailable. Alternative push providers would be a separate scope.

## Constraints for maintaining the fork

The Android implementation should absorb differences between platforms through
its own code. It should not reshape existing clients or services to make the
Android code resemble the Swift implementation.

- Keep new client code, dependencies, Gradle configuration, resources, and
  Android-specific tools under `apps/android/`.
- Preserve existing file locations, public methods, wire formats, internal
  ownership boundaries, and release conventions.
- Use the existing crypto and protocol fixtures directly. Add Android fixtures
  inside the Android tree rather than moving or regenerating upstream fixtures.
- Add only necessary branches or small helpers to existing files. Avoid broad
  renames, formatting churn, unrelated cleanup, and dependency upgrades.
- Keep compatibility patches in separate commits from Android feature work.
- Keep product behavior changes separate from the client port. A discovered
  upstream bug is not automatic authorization to redesign its subsystem.
- Prefer Android-side adaptations over new backend methods or schema changes.
  Record any genuinely missing capability and the smallest proposed extension
  before implementing that extension.

Do not extract a shared Rust client, introduce JNI for the host core, migrate
Swift code to Kotlin Multiplatform, add a universal UI framework, or introduce
a generated API system across the repository as part of this project. Those
would create ongoing changes in upstream-owned architecture.

New Android features that reproduce existing behavior leave the other clients
unaffected because they already implement that behavior. For each milestone,
inspect the corresponding Swift, iOS, macOS, GTK, and TUI implementation and
record that conclusion. If a shared action, field, term, or state changes,
update every applicable client together under the repository's parity rules.
An Android-only system adaptation should identify the unaffected clients and
the concrete platform reason in its change summary.

## Existing implementation and sources

Source code is the authority when a document and implementation disagree.
Use the following entry points to establish behavior, then capture representative
payloads as Android tests. Links identify source files, not stable line numbers.

| Responsibility | Existing reference |
| --- | --- |
| Root navigation and State surfaces | [RootView](../../apps/ios/Views/RootView.swift) and [CodyncApp](../../apps/ios/App/CodyncApp.swift) |
| Pairing, computer management, account controls | [PairingView](../../apps/ios/Views/PairingView.swift), [SettingsView](../../apps/ios/Views/SettingsView.swift), [AccountSession](../../apps/shared/AccountSession.swift) |
| Connection and cloud account protocol | [HostConnector](../../kit/Sources/CodyncKit/Client/HostConnector.swift), [ChannelTransport](../../kit/Sources/CodyncKit/Client/ChannelTransport.swift), [CloudClient](../../kit/Sources/CodyncKit/Client/CloudClient.swift) |
| Cryptography and device identity | [RelayCrypto](../../kit/Sources/CodyncKit/Client/RelayCrypto.swift), [DeviceIdentity](../../kit/Sources/CodyncKit/Client/DeviceIdentity.swift), [shared vectors](../reference/fixtures/remote-relay-vectors.json) |
| Account context and host mirror | [AccountStore](../../kit/Sources/CodyncUI/Store/AccountStore.swift) and [BotStore](../../kit/Sources/CodyncUI/Store/BotStore.swift) |
| Host methods and caller restrictions | [host dispatch](../../host/src/api/mod.rs), [caller permissions](../../host/src/api/devices.rs), [HostClient](../../kit/Sources/CodyncKit/Client/HostClient.swift) |
| Conversation UI and rendering | [ThreadView](../../kit/Sources/CodyncUI/Thread/ThreadView.swift), [ChatRows](../../kit/Sources/CodyncUI/Thread/ChatRows.swift), [ReadingConversation](../../kit/Sources/CodyncUI/Thread/ReadingConversation.swift) |
| Bot configuration and templates | [BotEditorView](../../kit/Sources/CodyncUI/Bots/BotEditorView.swift), [BotTemplateView](../../kit/Sources/CodyncUI/Bots/BotTemplateView.swift) |
| Automation and integrations | [routines](../features/routines.md), [marketplace](../features/marketplace.md), [connector credentials](../features/connector-credentials.md) |
| Mobile integrations | [voice](../features/voice-call.md), [screen](../features/remote-screen.md), [attachments](../features/file-attachments.md), [widgets](../design/mobile-widgets.md), [push](../design/push-and-live-activity.md) |
| Push and activity lifecycle | [iOS registration and tracking](../../apps/ios/App/Push.swift), [host push](../../host/src/remote/push.rs), [push Worker](../../relay/src/index.ts) |
| Upload retry semantics | [Swift delivery](../../kit/Sources/CodyncUI/Store/BotStore.swift) and [host chunk storage](../../host/src/chat/uploads.rs) |
| Existing desktop and terminal parity | [Linux client](../../apps/linux/src/client.rs), [Linux UI](../../apps/linux/src/ui.rs), [TUI client](../../host/src/tui/net.rs), [TUI UI](../../host/src/tui/view.rs) |

## Feature parity and acceptance

Maintain this matrix as work progresses. A working happy path is insufficient:
each area includes its applicable loading, empty, error, offline, and permission
states. Sample data belongs only in labeled previews and test fixtures.

| Area | Required Android behavior | Acceptance example |
| --- | --- | --- |
| Welcome and setup | First-launch welcome, computer installation guidance, pairing, explicit skip, persistent setup completion | Skipping setup leaves a usable empty app; removing the last computer does not trap the user in onboarding |
| Pairing | Camera scan, pasted `codync://pair` URL, short-lived code, host-key checks, direct or relay pairing | An expired code and a changed host identity produce distinct actionable errors |
| Accounts | Google and Apple sign-in, session restoration, supported account switching, account-free local context, confirmed sign-out cleanup | A delayed response from account A cannot populate account B's roster, notification, or widget; a failed sign-out retains the active session and reports the error |
| Start over | Explicit local reset of account/local contexts, pairing credentials, caches, preferences, widgets, and tracked activities | Reset completes local erasure after best-effort account sign-out without deleting the computer's bots or chats |
| Computer access | Account computer list, access request and comparison code, pending/denied/expired/revoked states, existing route controls | Account login alone never bypasses host authorization |
| Bot roster | Activity/status, previews, unread state, pinned/hidden ordering, computer filtering and existing reorder behavior | A hidden bot can be restored; saved filtering never hides all available computers |
| Bot configuration | Create/edit/delete, avatars, backend/model selection, instructions, working folder, capability and notification settings | Saving uses the selected host's model/options and reports the host's validation errors |
| Groups | Create/edit roster, group messages, member attribution and existing mention behavior | The host schedules member responses; Android sends text without implementing group routing |
| Conversations | User messages, final replies, notices, permission cards, Markdown/code, selection/copy, history, reactions, reading state | Intermediate narration stays in Full conversation rather than appearing as final chat replies |
| Reply threads | Open/reply, counts, loading, unread state, navigation back to main chat | Thread replies remain in the correct lane after reconnect and history loading |
| Permissions and task control | Existing option labels, pending response state, stop, new session, error recovery | A second tap cannot submit a second permission answer while the first is pending |
| Offline sends | Optimistic messages, deduplication, relay mailbox, cancel/retry/discard, delivered/expired/failed state | A queued text message is reconciled after process death; cancellation does not falsely claim delivery was prevented |
| Attachments | Photos/files/paste where supported, removable chips, files-only messages, progress, previews, download/share | Up to 100 MiB per file; group attachments and offline mailbox uploads retain current restrictions |
| Trace and memory | Full conversation, tool/plan details, memory listing, forget one/all, confirmations | Forgetting a fact updates the list and surfaces host errors |
| Templates | Create and copy the current portable `BotDraft` JSON settings snapshot | Supported settings are preserved; bot identity, pin/hidden state, conversation history, and runtime state are excluded |
| Routines | List, chat-assisted draft, create/edit, pause/resume, delete, test run, active/last-run status, chat results, webhook credentials | Editing an unsupported trigger preserves it until the user explicitly replaces it |
| Marketplace | Agents, connectors, connected apps, skills, search, explicit pagination, install/remove/configure | Late search results cannot overwrite the latest search; scrolling alone does not fetch another page |
| Agent setup | Install/sign-in terminal, model discovery, authentication status, required environment fields | A real host setup session accepts input, resizes, reports exit, and releases resources on close |
| Connector sign-in | Host-owned OAuth, system browser callback, credential status, required-field updates | Canceling sign-in retains the installed connector and reports its actual authorization state |
| Usage | Host-reported provider windows, resets, timestamps, refresh, unknown providers | A missing or stale report is not displayed as zero consumption |
| Voice | Text recognition, spoken final replies, mute/interrupt/end, settings, approval announcements, background continuation, call notice | Background speech reaches the bot and its reply is audible; ending restores ordinary notification behavior |
| Remote screen | Viewing, pointer/keyboard/scroll, clipboard, display selection, fit/zoom/rotation, takeover | Actual TURN media and input work on separate networks, and revocation stops both |
| Alerts | Encrypted needs-you/done/failed content, generic fallback for an authorized context, grouping, routing, toggles, token rotation | A background alert opens the exact context and bot or explains why that destination is unavailable; erased-context pushes are discarded |
| Widgets and task state | Bot and usage widgets, provider selection, setup status, labeled previews, ongoing/stale/finished task state | Account switching refreshes all installed widgets without showing the previous account's data |

Apple-specific surfaces need explicit Android substitutions. Home Screen widgets
map to Android app widgets; lock-screen information uses the system's supported
notification/widget surfaces. Live Activity task tracking maps to ongoing task
notifications, with optional Live Update promotion only where the task qualifies.
The State tab should explain the Android surfaces and their actual availability.
It should not show Dynamic Island setup controls.

Google's [Live Update guidance](https://developer.android.com/develop/ui/compose/notifications/live-update)
requires ongoing, user-initiated, time-sensitive activities and identifies chat
messages and alerts as inappropriate uses. Treat promotion for an explicitly
tracked coding task as a feasibility decision, not a promised release dependency.

The baseline includes the implemented device speech/TTS call. The future
bring-your-own-key realtime engines described in the voice document remain a
separate feature because they are not part of the shipped iOS implementation.
The current template screen copies settings JSON; it does not implement template
import or a separate host template API. An importer would be a separate feature.

## Android structure and dependencies

The following is a proposed layout. Add a package when its feature is implemented;
do not create empty layers or a Gradle module for every screen.

```text
apps/android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/
├── gradlew
├── gradlew.bat
├── README.md
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/               # App, navigation, features, services, resources
│       ├── test/               # ViewModel and presentation behavior
│       └── androidTest/        # Navigation, device services, database checks
├── core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/               # Models, crypto, protocol, transport, mirror
│       └── test/               # Protocol vectors and state regression tests
└── design/
    ├── build.gradle.kts
    └── src/main/               # Tokens, controls, avatars, activity graphics
```

The `core` module may use Android storage APIs while keeping crypto, frame
handling, model decoding, and mirror transitions independently testable on the
JVM. Platform microphone, WebRTC rendering, widget, browser, and notification
integration belongs in `app`. Widgets should depend on shared presentation data;
Glance has its own UI primitives and cannot reuse ordinary Compose components
directly. [Android architecture](https://developer.android.com/topic/architecture/recommendations),
[Glance](https://developer.android.com/develop/ui/compose/glance).

| Concern | Initial choice and selection criteria |
| --- | --- |
| Build | Gradle Kotlin DSL, checked-in wrapper, pinned version catalog; select mutually compatible stable Kotlin/AGP/Compose versions |
| UI and state | Compose, AndroidX ViewModel and lifecycle collection, coroutines/Flow; Android-owned constructor injection |
| Network | OkHttp for HTTP and WebSockets, behind a small injectable socket interface |
| JSON | Kotlin serialization, unknown-field tolerance, typed RPC results and explicit compatibility defaults |
| Local data | Room for mirrors and pending-send state; DataStore for preferences and selected context |
| Identity | Vetted Ed25519/X25519/ChaCha20 implementation; Keystore-backed wrapping of raw private keys where necessary |
| Account SDK | Clerk Android API with Codync-owned sign-in controls and browser/provider adapters |
| Photos and files | Android Photo Picker and Storage Access Framework; stream content rather than loading maximum-size files into memory |
| Images and Markdown | Android-local image loader and renderer chosen for selectable text, code blocks, tables, streaming performance, and license suitability |
| QR | Native camera/scanner integration with a working pasted-link fallback; validate operation without a network download |
| Terminal | Android-local terminal emulator with ANSI/UTF-8, keyboard, resize, host byte-stream support, and an acceptable redistribution license |
| Voice | Engine adapter for a proven device recognizer plus Android TTS; select the recognizer after the background-call spike |
| Screen | Native WebRTC Android bindings with a maintained/buildable artifact, hardware H.264, reproducible version, and license notices |
| Widgets and work | Glance and WorkManager; bounded background refresh, not a permanently connected widget service |

Before adding a dependency, record the exact version, minimum API, license,
maintenance source, required permissions, and any native binary provenance in
the Android README. Keep its upgrades separate from upstream compatibility
patches. Do not freeze current dependency version guesses in this plan.

## Protocol and local state

### Connections and identity

Phones use the existing encrypted channel, either directly or through `cloud/`.
Loopback bearer HTTP/SSE remains for local desktop clients and helpers. Android
should expose an injectable transport for tests and the real encrypted transport
for device operation, without exposing the local bearer token to the phone.

- Parse pairing URL version 3, validate key/code lengths and computer ID, pin
  the host signing and mailbox keys from authenticated sources, and redeem the
  one-time code inside the encrypted channel.
- Use channel protocol version 1, Ed25519 signatures, ephemeral X25519,
  HKDF-SHA256, and ChaCha20-Poly1305 exactly as specified. Reject all-zero shared
  secrets, bad signatures/tags, counter errors, and changed host identities.
- Keep one serialized writer per channel so frame counters and chunks cannot
  interleave. Implement current message-size limits, reassembly, cancellation,
  RPC IDs, subscription IDs, close codes, heartbeat, and backoff semantics.
- Preserve existing automatic/direct/relay preferences and direct-first timing.
  Distinguish an unreachable computer from relay-reported computer offline,
  rejected access, expired authorization, and upgrade required.
- Use the existing signed cloud HTTP/WebSocket requests, signing the exact body
  bytes and canonical authority/path/query rather than a reserialized body.
- Learn host URLs and mailbox keys through authenticated inner `hello` results;
  cloud-displayed mailbox keys alone are insufficient to authorize encryption.

Android network security configuration must accommodate the existing direct
channel, which can use `ws://` while its contents are encrypted by Codync.
Constrain the Android network adapter to validated channel endpoints and keep
cloud/account traffic on HTTPS. Verify the configuration with real direct URLs
rather than changing the host to require a new TLS architecture.

For apps targeting Android 17/API 37 or later, direct LAN access also requires
the appropriate local-network permission. Denial must produce a clear state
and allow relay use when available. Earlier targets should follow their
applicable permission behavior. [Network security configuration](https://developer.android.com/privacy-and-security/security-config),
[local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

### Account boundaries and key storage

Create distinct signing and push identities per installation and account
context, including the account-free context. Keep all database keys, cache paths,
queued messages, widget snapshots, and deep-link destinations scoped by account
and computer. A bare bot ID is not a complete destination.

Do not assume every supported device can hold Ed25519 or X25519 directly in
hardware. Use a vetted implementation and protect persisted raw key material
with a Keystore wrapping key. Exclude identities and sensitive local state from
Android backup/restore; a restored install must not silently reuse another
installation's device authorization. Verify locked-device access after first
unlock and the generic notification fallback before credentials are available.
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).

On account switching, cancel the old account's network/jobs and retire its
stores before activating the new context. Capture context identity in async
work and reject results for a retired context. Match the current ordinary
sign-out behavior: erase the context after confirmed SDK sign-out and only when
no remaining session owns that account. A sign-out failure keeps the session
and reports the error. Do not imply automatic host-grant revocation or remote
push unregistration; the existing sign-out path does not provide those guarantees.

Preserve the separate explicit Start over action: attempt account-wide sign-out,
then erase all local/account contexts, credentials, caches, pending sends,
preferences, and widget snapshots, and end tracked activities. Computer-side
bots and chats remain. Discard pushes for erased or unauthorized contexts rather
than posting a generic alert; the generic fallback applies to a still-authorized
context whose decryption key is temporarily unavailable. A retained inactive
account is distinct from an erased account: its alert remains scoped to its own
identity, and opening it uses the account switch/access checks before navigation.

### Conversation mirror and revisions

Use a local mirror containing account/computer scope, bots, transcript entries,
thread relationships, read/presentation state, pending sends, and the applied
revision. Index transcript entries by their existing IDs and host sequence.
Preserve opaque fields needed for trace rendering and future optional data.

Apply events serially. Upsert by ID and revision, process deletion tombstones,
and commit the affected rows with their applied revision in one transaction.
The initial event `hello` advertises the host's current revision; it is not a
safe checkpoint before the accompanying catch-up events have been applied.
Handle host instance changes, revision rollback, and cache version changes
according to the existing Swift store behavior.

Advance the stream checkpoint only from applied stream events. RPC mutation
replies and history/thread pages may upsert rows but must not advance the stream
cursor to the highest revision they contain; doing so could skip unrelated
changes that have not arrived on the stream. Catch-up returns at most 200 recent
entries per bot and has no separate catch-up-end marker. Fetch older history
through the existing paging methods. Treat the mirror as a bounded recent cache,
not a complete archival copy of every conversation.

Decode added optional fields leniently. A malformed known mutation must not be
silently skipped while advancing the cursor: use the existing resync/rewind
behavior and stop with an actionable compatibility error if recovery repeatedly
fails. Match and document the current handling of unknown event kinds rather
than assuming every unknown event can advance the mirror.

Match current reconnect UX: short drops are held before displaying offline,
unauthorized state is immediate, and eligible user actions wait for the link
within the existing patience window. Retry only operations that current client
behavior considers safe; preserve send IDs and avoid blindly replaying setup,
installation, routine execution, or other mutations with uncertain outcomes.

### Offline sends and files

Keep a stable `clientNonce` for every retry and mailbox attempt. Persist the
bounded pending-send record needed for recovery, reconcile it with the relay's
queued list, and clear it after terminal state or discard. Use existing mailbox
TTL, acknowledgements, cancellation races, and host deduplication. This is an
adapter to the existing text mailbox, not a new general-purpose offline RPC queue.

Attachments use existing `upload`/`readUpload` calls and 384 KiB chunks, with
the existing 100 MiB per-file limit (`100 * 1024 * 1024` bytes). Serialize chunks.
Retry only the most recent chunk with the same upload ID, offset, and identical
bytes; the host's retry check is positional and does not compare content bytes.
A restarted whole-file attempt needs a fresh upload UUID, as the Swift client
does, rather than replaying offset zero against a partially filled upload.
Keep the message's `clientNonce` stable across those attempts so an uncertain
final send is deduplicated. Do not invent an upload resume/query endpoint.

Obey offsets, sanitize names, and distinguish upload completion from message
delivery. Use bounded streams/cache files for large content and supported image
conversion. Do not put attachment messages into the text-only mailbox. Preserve
the current group restriction and failed-send file retention behavior.

## Changes to existing services

This table is the anticipated surface of changes outside Android-owned files.
Tests and documentation directly covering each patch belong in the same change.
Inspect actual upstream code again when a patch is implemented.

| Patch | Existing files expected to change | Required behavior and merge boundary |
| --- | --- | --- |
| Android QR pairing | `host/src/remote/channel.rs` | Accept `android` alongside existing platforms; preserve pairing, authorization, and error behavior |
| Android cloud device | `cloud/src/api.ts` | Extend device-platform validation; preserve current D1 schema, access flow, and grant semantics |
| Android foreground recognition | `host/src/api/mod.rs`; comments/tests where necessary | Recognize Android event subscriptions using the existing client-counting approach; preserve current suppression semantics |
| Android activity cancellation | `host/src/api/mod.rs`, `host/src/store.rs` | Add idempotent `unregisterActivity` for the caller's exact bot/ticket registration; reuse the existing table without a migration or lifecycle refactor |
| FCM delivery | `relay/src/index.ts`, minimal relay environment/type declarations | Add provider dispatch and backward-compatible ticket handling; leave APNs code in its current location |
| Relay validation commands | `relay/package.json` if needed | Append the focused FCM test runner without replacing existing commands or changing unrelated dependencies |
| Android automation | New `.github/workflows/android.yml` and Android release workflow | Add isolated path-filtered checks and distribution jobs; preserve existing Apple/host workflows |
| Releases and documentation | Existing version files when required; targeted docs links | Follow repository version rules; make separate version commits and generate Apple project changes through XcodeGen |

The pairing and cloud platform strings are currently ordinary stored strings;
there is no identified need for a device-table migration. The host already treats
push tickets as opaque and exposes both `registerDevice` and `registerActivity`.
This permits FCM to reuse existing host storage and registration methods.
Activity cancellation needs the narrow addition described below because an FCM
installation token does not expire when one tracked notification is dismissed.

Keep existing internal names if renaming them would spread a compatibility patch
through the repository. For example, Android recognition need not rename the
entire `IosClientGuard`/`ios_connected` path during this port. Explain the small
extension in its comment and maintenance record.

Current alert suppression is global when an iOS subscription is connected.
The first Android patch should preserve that behavior while adding Android
recognition. Record the resulting mixed-device limitation in acceptance results.
Per-device suppression would be a separate behavior change with its own design,
tests, and merge assessment; it is not hidden inside Android support.

### FCM module and ticket compatibility

Add `relay/src/fcm.ts` and focused tests. That module should own FCM credentials,
OAuth access-token caching, HTTP v1 calls, Android payload conversion, and provider
error mapping. It should use Worker-compatible APIs and keep credentials in the
deployed Worker's secrets.

Extend encrypted ticket payloads additively with a provider discriminator and
the minimum FCM routing information. Existing tickets without a discriminator
retain their existing APNs meaning. Reuse the existing AES-GCM ticket helpers
with a small type extension; do not move the APNs implementation into a new
provider abstraction or replace the ticket system.

Add narrow FCM branches to registration, push dispatch, and batch destination
comparison. APNs requests, environment/topic selection, existing ticket decoding,
batch result indices, and failure responses must retain their current behavior.
Missing FCM configuration should fail Android requests without breaking APNs.

FCM tokens are opaque and case-sensitive. They must bypass APNs hex validation
and lowercasing. Destination comparison includes provider, destination/project,
token kind, and exact FCM token. Preserve the existing newest-registration rule
for ordinary alerts to a physical destination and return the same superseded/dead-ticket
outcomes that the host already processes. For task status, also compare the
logical context/computer/bot destination: two different tracked bots must never
supersede one another merely because they share an installation token. A new
subscription replaces only its previous logical registration. Restrict ticket
routing to the Worker's configured Firebase projects; registration cannot choose
arbitrary credentials or endpoints.

Normal alerts reuse the current sealed notification payload. Deliver FCM data
messages, decode/decrypt locally, and immediately post an Android notification
or the generic fallback. Flatten/encode nested values as required by FCM, check
the complete provider payload size, and map confirmed invalid registrations to
the host's existing dead-ticket cleanup. `UNREGISTERED` maps to the existing
410 outcome. Inspect `INVALID_ARGUMENT` details: malformed payloads do not prove
that a token is invalid. Authentication, project mismatch, rate limits, and
transient provider errors must not delete otherwise valid registrations.
Do not expose private title/body to Firebase or the push Worker.
[FCM message types](https://firebase.google.com/docs/cloud-messaging/customize-messages/set-message-type),
[FCM error codes](https://firebase.google.com/docs/cloud-messaging/error-codes).

Use high priority for timely visible needs-you/done/failed alerts. Avoid using
high priority for silent widget or task polling. FCM can delay normal priority
in Doze and deprioritize high-priority traffic that does not result in visible
notifications. [FCM priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority).

For ongoing task state, reuse `registerActivity` and the host's status-only
`liveActivity` payload. The FCM adapter translates it into Android task status
data; no host task table is required. Preserve terminal cleanup and freshness
deadlines, convert the existing Swift date epoch where applicable, and verify
registration-after-completion races. The host pushes status transitions rather
than every free-text activity change. Display delayed or stale state explicitly.

Unlike an APNs activity token, an FCM token identifies the app installation,
not an individual activity. The Android activity ticket must therefore carry
the originating context, computer, bot, and a small subscription ID supplied
during Android registration. The adapter includes that routing metadata in
status delivery; the existing host need only retain and submit the opaque ticket.
Keep bounded active/dismissed subscription state locally so an old update cannot
resurrect a dismissed card or appear in a newly selected account. A fresh tracking
subscription gets a new ID; terminal/expired records are cleaned up. This does
not create a new host task identity or an event-history table.

Add a focused `unregisterActivity {botId, ticket}` host method. Remove a row only
when its device key matches the authenticated caller and its bot and opaque
ticket both match. Make deletion idempotent and usable even after a bot is
deleted. The exact-ticket condition prevents a delayed cancellation from
removing a newer registration. `unregisterDevice` currently removes ordinary
push tickets, so it cannot substitute for this operation.

Dismiss locally immediately, ignore subsequent updates for that subscription,
and attempt cancellation over the existing encrypted channel. Serialize its
registration and cancellation; if registration completes after dismissal,
cancel that exact ticket again rather than restoring the local card. Any bounded
offline retry retains its original context and ticket and must never run under
a newly selected account; discard the retry when its credentials are erased.
Use a finite expiry for FCM activity tickets so an abandoned registration can
eventually receive the existing 410 cleanup outcome.
Choose and disclose a maximum tracking lifetime; refresh an active registration
when the app reconnects, and show expired/stale state without declaring completion.
Expiry applies to that ticket, not to the physical FCM token or other tracked
bots. Do not rotate the installation token or add a Worker cancellation database
to end one activity.

Initially use non-collapsible data messages with bounded freshness TTLs. FCM
does not guarantee ordering and supports only four simultaneous collapse keys
per installation; a per-bot key for an arbitrary roster or one shared key for
unrelated bot updates can discard another task's terminal update. Coalesce
rendering locally, using subscription identity and timestamps, without claiming
perfect ordering for equal timestamps. If FCM reports deleted pending messages,
mark cached task state unconfirmed and resync on the next permitted connection;
do not infer completion or open a permanent background socket.
[FCM collapse behavior](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types).

Existing status pushes use second-resolution timestamps and provide best-effort
delivery. They do not provide a unique task-run ID or guaranteed ordering. Android
should not infer completion from silence or advertise stronger delivery guarantees.
If overlapping runs expose a correctness problem, document it and propose the
smallest necessary contract extension separately.

Sean's host uses one configured push-relay URL for all devices. Keep Android
registration and both providers on that same deployment. A separate Android
push endpoint would not work without changing routing or adding a gateway, and
neither is part of this plan. Preserve the existing `TICKET_KEY` and APNs topic.

## UI and platform integration

### Navigation and design

Use a single app activity with Android-owned navigation state. Retain Bots and
State as the primary destinations. Put account/computer management, marketplace,
usage, bot details, and task actions at equivalent points in the flow, adapting
presentation to Android back navigation, system browser, keyboard, and pickers.

Recreate the shared palette, card surfaces, spacing, fonts, icon semantics,
avatar shapes, and four used activity orb geometries in Android. Reuse source
assets and license notices where suitable. Keep filled surfaces free of border
lines. Prefer labeled-accessible icon actions and explicit text for approval
choices. Animate user-triggered visibility changes and pause graphic animation
offscreen, in the background, or with reduced animation enabled.

Use custom Codync controls for product surfaces and native Android containers
for system authentication, notification/widget UI, file picking, and platform
navigation. Document the Android mapping in a new Android design note rather
than broadly changing the Apple custom-control rules. Match terminology,
displayed information, and states without imitating Liquid Glass.

Preserve drafts, scroll positions, selected bot/thread, and tab stacks through
rotation and reasonable process restoration. A notification, widget, or external
link carries account/computer scope and opens Bots before its conversation.
If scope is missing or stale, show an explicit destination error rather than
opening a similarly named bot in another account.

Port the existing `codync://` routes for `pair`, `bot`, `plugins`, `usage`,
`computers`, and `screen`, with account and connector callbacks handled separately.
Verify both cold-start and already-running delivery, invalid destinations,
and cancellation of callbacks tied to a retired context.

### Authentication and OAuth callbacks

Use Clerk's Android API for session restoration, token retrieval, Google login,
Apple web OAuth, and supported multiple-account behavior. Prove Apple login
returns the same existing Codync account; verify the Services ID association
and provider configuration rather than assuming email equality links accounts.
Production requires a registered Android package and callback.
[Clerk Android setup](https://clerk.com/docs/android/getting-started/quickstart),
[hosted authentication](https://clerk.com/docs/android/guides/account-portal/hosted-auth).

Keep account authentication callbacks distinct from connector callbacks. For
connectors, use the existing host-managed PKCE/state flow and `codync://oauth`
redirect, then send `connectorSignInFinish` to the originating host/context.
Validate the pending state and ignore an unsolicited, canceled, or retired
context callback. Connector access/refresh tokens remain on the host.

Custom URI schemes can collide if multiple Codync variants are installed. Test
callback selection in the intended private/public installation setup. A move
to a different connector callback contract requires a separately scoped patch;
do not change cloud OAuth routing preemptively.

### Voice

Implement a `VoiceSession` adapter with listening, working, speaking, muted,
interrupted, ending, and failed states. Send recognized utterances through the
ordinary text-send path. Speak new final replies for the active bot, strip
Markdown/code as current `SpokenText` does, and suppress replayed, other-bot,
and thread replies. Announce permission requests and leave approval to the chat.
Keep listening while the bot works; working is bot state rather than an automatic
microphone pause. Pause recognition during TTS and resume it afterward according
to the existing mute and interruption behavior.

Start the microphone/media foreground service from the visible call UI after
permission is granted. The service owns audio focus, recognizer/TTS lifecycle,
Bluetooth/headset changes, transport retention, and notification actions for
mute/end. Handle incoming calls, audio interruptions, permission revocation,
sign-out, computer removal, and task cancellation. End cleanly, release the
transport, and record duration through existing `logCall` behavior.

The stock recognizer is not intended for continuous recognition and may send
audio to its recognition service. Prefer supported on-device recognition, expose
the actual engine/fallback, and prove repeated utterance/background behavior
early. If it cannot meet the baseline, select a suitable Android-local engine
before promising voice parity. An approved device engine is a release gate;
foreground-only voice is not full parity.
[SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer),
[foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

### Remote screen

Reuse `screenPrepare`, `screenOffer`, `screenClose`, and `screenTakeover`. Build
the same non-trickle SDP exchange and use the server-provided ICE configuration.
Receive H.264 video and send the existing reliable `input` and fast `input-fast`
messages with correct viewport coordinates. Implement touch, pointer mode,
keyboard/modifier input, scroll, clipboard, display changes, fit/zoom, and rotation.

Refresh prepared sessions and TURN credentials through the existing lifecycle;
release codecs, renderers, peer connections, and host sessions on close. Respect
screen scope, disabled screen access, computer permission requirements, expiry,
and revocation. The phone cannot enable the host's loopback-only screen setting.
No capture helper changes are expected for this viewer.

Verify both direct-only operation without internet and actual TURN candidates
across unrelated networks, including TCP/TLS fallback. A relay chat connection
only proves signaling reachability. Select Android WebRTC bindings during the
feasibility phase using the [native Android implementation](https://webrtc.googlesource.com/src/+/main/docs/native-code/android/)
as the interoperability reference.

### Widgets and task notifications

Implement bot summaries, cross-provider usage, and provider-specific usage in
Glance, with compact and expanded responsive layouts. Widget configuration
uses actual paired-computer/provider data. Track real installed widgets, offer
system pin/configuration flows, and label gallery data as samples.

Read account-scoped snapshots, refresh after relevant foreground events/account
changes, and use bounded scheduled work for usage refresh. Display the last
update and missing/stale/unpaired states. Avoid claims of real-time refresh:
the system controls background scheduling. Widget periodic updates have a
30-minute minimum; WorkManager periodic refresh has a 15-minute minimum and
may still be deferred. Glance and app previews should share presentation logic
even though their rendering components differ.
[Widget updates](https://developer.android.com/develop/ui/compose/glance/glance-app-widget).

Start task tracking when the user initiates or explicitly tracks a task, reuse
the existing activity subscription, and stop/downgrade it on terminal state or
user dismissal, including the focused host cancellation method. Needs-you should
open the permission conversation. Completion and failure must retain distinct
symbols, and stale updates must not show old
activity text as current. Keep status-only notification updates free of private
task text unless it arrives through an authenticated encrypted path.

## Implementation milestones

Each milestone produces a working increment and an acceptance record. Build
Android capabilities first, apply the small required service patches, and test
the resulting integration before adding the next dependency-heavy area.

Feasibility probes that require authorized Android access may bring forward
the focused platform-validation patch from Milestone 1. Keep that patch separate
and complete its regression coverage in the same increment; do not register
the Android prototype as an iOS device.

### Milestone 0 Baseline and feasibility

- [x] Record the upstream commit used as baseline and confirm the upstream remote
  separately from the fork's `origin`; avoid mixing fork patch history with upstream.
- [ ] Reserve the Android namespace/application ID, dev suffix, signing ownership,
  and initial private distribution route.
- [x] Bootstrap the Gradle wrapper, version catalog, three modules, theme, and
  a minimal app. The implementation reads real host data; tests use the shared
  fixtures. Apple changes are limited to the required product version bump.
- [ ] Prove all necessary crypto primitives against shared vectors on JVM and
  the proposed minimum Android version; verify key wrapping and locked behavior.
- [ ] Prove Clerk restoration, Google login, Apple web login, and account identity.
- [ ] Prove H.264 rendering and both input data channels against an existing helper.
- [ ] Prove repeated/background voice utterances and TTS on Pixel and Samsung;
  evaluate an alternative engine if the stock recognizer fails.
- [ ] Evaluate terminal emulator, Markdown, image, and QR dependencies and licenses.
- [ ] Record unsupported devices/services, pending external configuration, and
  any genuinely required extra upstream-file change.

Exit: dependency versions and feasibility decisions are recorded with actual
device evidence. Crypto, voice, authentication, and WebRTC risks are resolved
or have a concrete implementation path and explicit acceptance work remaining.

### Milestone 1 Encrypted transport and pairing

- [x] Implement pairing URL validation, identity, handshake, encrypted frames,
  serialized writing/chunking, RPC/subscription handling, and signed requests.
- [ ] Implement direct/relay route selection, close-code handling, presence,
  heartbeat, reconnect, network changes, and permission denial.
- [x] Add the focused host `android` pairing patch and its regression coverage.
- [ ] Build camera/pasted pairing, cached computer storage, hello, and bot listing.
- [ ] Verify account-free QR pairing via LAN and forced relay, host-key rejection,
  expired/reused code, interruption, and clean shutdown.

Exit: a physical Android phone pairs using real identity and reads the existing
host through both routes. No host authentication or transport redesign is required.

### Milestone 2 Core chat and local recovery

- [x] Implement transactional mirrors, event catch-up, tombstones, history paging,
  compatibility defaults, resync, and host/cache version handling.
- [x] Build bot roster, create/edit, basic conversation, Markdown/code, permissions,
  stop/new session, reading state, and scoped navigation.
- [x] Implement stable send nonces, optimistic state, retry/discard, relay mailbox,
  queue reconciliation, cancellation, expiry, and process restoration.
- [x] Match connection grace/patience behavior and prevent duplicate action taps.
- [x] Add focused mirror/state tests and core navigation/permission instrumentation.

Exit: first functional chat alpha. A phone creates a bot, sends a task, answers
an approval, backgrounds/resumes, and recovers pending text without duplicate
messages or a revision checkpoint that skips data.

### Milestone 3 Accounts and background alerts

- [x] Add cloud `android` registration validation and cover existing platforms.
- [ ] Implement account restoration/switching, host-approved access, pending codes,
  denial/expiry/revocation, computer management, context retirement, ordinary
  sign-out failure handling, and explicit Start over cleanup.
- [x] Add FCM module, ticket compatibility, provider dispatch, dead-token mapping,
  and APNs regression checks.
- [x] Add minimal Android subscription recognition in the existing host guard.
- [ ] Implement notification permissions/channels, encrypted local rendering,
  generic fallback, grouping, deep links, toggles, and token rotation.
- [ ] Verify actual background FCM delivery, Doze, locked phone, killed process,
  wrong/stale context, mixed iOS/Android behavior, and unchanged APNs delivery.
- [x] Deploy the validated service patches to Sean's configured infrastructure
  and verify fresh binaries/configuration at the existing endpoints.

Exit: core chat beta with real accounts and encrypted background alerts. An app
process killed by the system can receive supported FCM alerts; a user force-stop
is recorded separately from ordinary process death and is not promised delivery.

### Milestone 4 Conversation and management parity

- [ ] Complete groups, attribution, reply threads, reactions, trace, memory,
  copyable template snapshots, pin/hide/delete, and all current bot configuration
  fields.
- [ ] Implement photos/files/paste, streaming upload/download, preview/share,
  files-only messages, failure retention, and current offline/group restrictions.
- [ ] Match unread behavior, filtered roster ordering, draft restoration,
  conversation selection/copy, and accessible empty/error states.
- [ ] Add behavior tests for group/thread lanes, latest-chunk retries versus
  fresh whole-upload attempts, memory actions, excluded/preserved template
  fields, and race cases that are meaningful for recovery.

Exit: conversation and bot-management actions match the baseline iOS matrix,
including existing limits and all relevant failure states.

### Milestone 5 Automation and integrations

- [ ] Implement routines list/editor, host-backed schedule preview, saved trigger
  preservation, enable/delete/test, existing run summaries/chat results, and
  chat-assisted drafts.
- [ ] Implement webhook URL/key copy/reveal/rotation and actual connected state.
- [ ] Implement agents, connectors, connected apps, skills, explicit catalog
  pagination/search, custom/imported connector configuration, and removal.
- [ ] Complete connector browser OAuth, credential fields/status, agent models,
  authentication/environment settings, and host setup terminal.
- [ ] Complete provider usage, reset/update timestamps, refresh, and missing data.
- [ ] Test real host execution/authentication, not just catalog listing or form saves.

Exit: an Android user can configure the same host capabilities as an iOS user.
Routines continue to run on the host; Android does not parse cron or schedule work
locally. Provider-event/compound triggers round trip without silent replacement.

### Milestone 6 Voice and remote desktop

- [ ] Complete the selected voice engine, call UI, pause/speed settings, TTS
  filtering, foreground service actions, audio focus, and transport retention.
- [ ] Test background capture/playback, headset changes, incoming call interruption,
  permission revocation, account retirement, and call duration notices.
- [ ] Complete screen UI, coordinates, input, clipboard, display changes, rotation,
  takeover, session renewal, TURN, and lifecycle cleanup.
- [ ] Test real direct and TURN paths, TCP/TLS fallback, Wi-Fi/cellular change,
  one-hour credential lifecycle, host disable, and device revocation.

Exit: audio continues during an active background call and desktop input/media
stop when authorization ends. Evidence distinguishes direct, relay signaling,
and TURN media routes. Existing capture helpers remain compatible.

### Milestone 7 State surfaces and release

- [ ] Complete bot/usage/provider widgets, configuration, previews, pin/setup
  guidance, installed-state checks, and account-scoped scheduled refresh.
- [ ] Complete task notification lifecycle, freshness, dismissal, and optional
  Live Update promotion after eligibility testing.
- [ ] Add and validate exact-ticket activity cancellation, including late updates,
  offline dismissal, re-registration races, and caller isolation.
- [ ] Complete the Android State tab and usage entry points with accurate system
  capability/permission states.
- [ ] Run large-font/TalkBack/dark-mode/reduced-animation checks and test keyboard,
  system back, tablet/foldable layout, process restoration, and long transcripts.
- [ ] Complete licenses, private/public service configuration, signing, AAB/APK
  distribution, release notes, Android CI, and the documented device test matrix.
- [ ] Perform an upstream merge rehearsal and rerun affected integration checks.
- [ ] Close every parity row with passing evidence or a documented platform
  substitution; no missing major feature is silently called full parity.

Exit: a signed release candidate passes the complete Android checklist and
service regression gates on Sean's infrastructure.

## Validation and evidence

Tests should cover observable behavior and failure recovery. Avoid tests that
only restate component implementation. Use deterministic sockets/host fixtures
for logic and real hardware for OS/background behavior.

| Layer | Required checks |
| --- | --- |
| Protocol | Shared vectors; bad signatures/tags; counter order; chunk limits; signed request bytes; close codes; mailbox envelope and queue races |
| Mirror and account state | Duplicate/out-of-order updates, deletion, partial catch-up crash, capped catch-up/history, RPC replies ahead of the stream, resync, host restart, stable send nonce, account retirement, scoped cache cleanup |
| UI | Cold/warm deep links, critical navigation, permission response, account switch, sign-out failure/reset, failed sends, attachment state, routine preservation, stale search response |
| Host patches | Existing host formatting/Clippy/tests plus focused Android pairing/subscription and exact-ticket activity cancellation coverage |
| Cloud patch | Existing cloud tests/typecheck and applicable local account/relay integration with Android platform |
| Push patch | Existing APNs tests, FCM ticket/token/payload tests, invalid payload versus dead-token errors, logical task destination comparison, mixed batches/bots, late status after dismissal/end, pending-message loss, preserved ticket decoding, live APNs/FCM delivery |
| Device services | Physical voice/background audio, notifications/Doze, widget installation/refresh, URI callbacks, camera/files, and real WebRTC routes |
| Existing clients | Relevant Swift/Apple, Linux, and TUI builds/checks when their code or shared behavior changes; document concrete platform-only non-applicability otherwise |

Proposed Android CI commands, once the build exists:

```bash
cd apps/android
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:bundleRelease
```

The connected-device task belongs in an emulator/device job; release signing
belongs in protected distribution jobs. Configure path filters for Android
files and directly consumed fixtures. Shared-service patches still run their
existing workflows. Add an explicit private-branch/manual path for Android
validation on `fergusean/consolidated` without rewriting upstream workflows.

| Device or condition | Verification scope |
| --- | --- |
| Minimum supported OS | Install, crypto/provider compatibility, pairing, core UI and foreground services |
| Recent Pixel | Notification permissions, local network permission where applicable, background voice, widgets, system back |
| Recent Samsung | Background restrictions, recognizer/TTS, FCM, widget layouts, screen codecs |
| Tablet/foldable emulator or device | Resize, rotation, keyboard, split-window layout and state restoration |
| Wi-Fi to cellular and back | Channel recovery, pending sends, audio retention, screen renegotiation |
| Different networks without Tailscale | Forced cloud chat and observed TURN media candidate |
| Doze and locked phone | Alert arrival/rendering, generic fallback, permitted background work and stale state |
| Process death and user force-stop | Restore cached state after process death; document OS limits after explicit force-stop |
| Host restart/offline/revocation | Catch-up, mailbox, explicit offline state, removal of screen/call access |
| Mixed iOS and Android | Existing global suppression semantics, per-destination token cleanup, both providers still deliver |

Each acceptance record should include app/host commit and version, environment,
device/OS, foreground/background state, direct/relay/TURN route, expected result,
actual result, and supporting logs/screenshots where useful. Never include keys,
tickets, full notification plaintext, or unrelated conversations in logs.

## Deployment and release integration

Implementation includes deployment of completed host/cloud/push updates under
the standing repository instruction. Use
[Sean's deployment memory](../reference/sean-deployment.md) for targets and
[the host deployment procedure](../guides/development.md#deploying-the-backend-to-rdev)
for the controlled rdev update. This planning task itself changes documentation.

Preserve the rdev identity/database and service environment, Sean's Cloudflare
account/resources, Clerk instance, push ticket key, APNs topic, and private build
configuration. Refresh source in the private deployment copies before deploying;
do not replace them with upstream defaults. Set Android public configuration to
the intended fork environment and keep its registration relay consistent with
the host's pinned `CODYNC_RELAY_URL`.

Deploy platform-acceptance support before testing clients that register as
Android. Deploy FCM support and configuration before enabling Android push
registration. Check live APNs after the FCM change, and preserve both providers
when rolling back the Android app. Rollbacks must account for outstanding opaque
tickets; avoid reverting the Worker to one that cannot interpret active FCM tickets.

Follow repository versioning: shipped source changes require a release bump
before reaching `main`; use the existing ahead-of-tag check and stay on 2.x.
The new client/protocol additions normally warrant a minor bump. Keep the Android
release version aligned with the chosen product version and maintain an
independent monotonic Android `versionCode`. Update the existing Apple/host
version sources and regenerate through XcodeGen only when the required bump is
made, in its separate version commit. Preserve App Store build-number rules.

## Upstream merge maintenance

Track the upstream repository separately from the fork's `origin`, using the
confirmed upstream URL and its actual release branch. Keep integration on the
existing workspace/branch convention; do not create child Herdr workspaces.
Use a temporary integration branch for a merge rehearsal when needed, protecting
unrelated uncommitted work before branch operations.

Maintain a short patch register inside the Android README or a focused maintenance
document. For each patch record its purpose, original upstream file/function,
regression check, commit, deployment effect, and whether upstream now provides
equivalent behavior. It should explain deliberate retained names and adaptations
without copying upstream implementation text.

For each upstream update:

1. Review changes to protocol/models, mobile UI behavior, platform validation,
   account handling, push tickets, and activity lifecycle since the recorded base.
2. Merge upstream normally and resolve each small compatibility patch on its own
   merits. Remove a patch when upstream supplies its behavior; avoid duplicate hooks.
3. Run Android contract/mirror checks against the updated fixtures and host, then
   service checks covering whichever compatibility patches were affected.
4. Update the Android parity matrix for added/changed iOS features and record
   unsupported additions explicitly until implemented.
5. Run applicable live checks for auth, relay, APNs/FCM, voice, and screen; update
   the baseline and patch register after the integrated build is verified.

The same-major compatibility rule does not guarantee that older upstream hosts
accept the new `android` platform string. Report the required Android-supporting
host/service build clearly. Do not impersonate an iOS device to avoid a validation
patch or silently weaken identity/protocol checks.

## Risks and decisions to resolve

| Risk or open decision | Planned resolution |
| --- | --- |
| Crypto library works on JVM but differs on Android | Run common vectors on the minimum OS and hardware before feature development |
| Device speech cannot sustain background conversation | Resolve during Milestone 0; choose a proven Android engine and document provider/audio behavior |
| Android WebRTC artifact is unmaintained or lacks H.264 | Select a reproducible maintained/native build and test real helper interoperability early |
| Terminal dependency license or input limitations | Review distribution license and test real install/sign-in flows before committing to the dependency |
| Apple login creates a different identity | Verify Clerk production Services ID association against an existing iOS account |
| Callback collision between installed app variants | Test the private/public setup and document the intended handler; scope any contract change separately |
| Widgets/status notifications receive delayed updates | Use freshness/last-updated states and bounded refresh; do not promise continuous background sockets |
| Activity ordering lacks a unique run identity | Preserve current best-effort contract and test races; propose a focused extension only for a demonstrated failure |
| Existing global foreground suppression surprises mixed-device users | Record current behavior and evaluate a separately scoped improvement rather than hiding it in this port |
| Fork changes spread through upstream files | Review every existing-file diff at each milestone and retain an explicit compatibility patch register |
| Private infrastructure diverges from upstream defaults | Use deployment memory, refresh private source copies, preserve configuration, and verify actual endpoints |

## Effort and completion criteria

Use a rough initial planning range of approximately 4 to 6
engineer-weeks for core chat with accounts/alerts and 12 to 18 engineer-weeks for
complete parity, assuming an experienced Android developer and a stable backend.
It is not a delivery commitment. Milestone 0 must revise the range after voice,
WebRTC, authentication, terminal, and crypto dependencies are proven. Additional
recognition-engine work, provider configuration delays, and upstream feature
growth can extend it.

Most feature work should land under `apps/android/`. The recurring upstream
merge points should stay concentrated in platform validation, foreground
recognition, the small exact-ticket activity cancellation addition, and the push
worker's few dispatch/type changes. Work can overlap in Android after the
transport/mirror contracts stabilize, but service patches should remain
independently reviewable.

Full parity is complete when every feature row has passing evidence or an
explicit supported Android substitution, a signed build works with Sean's live
services, existing client/service behavior passes applicable regressions, and
an upstream integration rehearsal succeeds. Passing unit tests or a working
chat demo alone does not close the project.

The immediate implementation increment is Milestones 0 and 1: establish the
isolated Android project, resolve high-risk dependencies, and deliver authenticated
pairing and bot listing over both direct and relay routes with only the focused
host platform patch.

## Implementation progress

Implementation began on 2026-10-03. The Android project, pinned build, JVM crypto,
request signing, direct/relay channels, pasted-link pairing, Keystore storage,
onboarding/reset, and read-only live roster are implemented. The focused host
QR-platform patch accepts Android. Build and route acceptance results belong in
the [Android README](../../apps/android/README.md).

JVM acceptance has paired, verified `hello`, and read bot sync against a real
Rust host over both direct and forced relay routes. The validated 2.4.0 host
patch is deployed to rdev with its identity and configuration preserved. API 36
emulator instrumentation has verified key wrapping, the shared encrypted push
vector on Android ART, and skipped onboarding. This does not establish physical
device, minimum-version, account, voice, screen or push feasibility.

The protocol `core` module is currently Kotlin/JVM; Android storage lives in
`app`. Its actual unit-test command is `:core:test`, replacing the proposed
`:core:testDebugUnitTest`. No upstream module was reorganized for this choice.
Milestone 0 and Milestone 1 remain open: physical optical pairing,
network/lease/upgrade edge cases, and the independent account,
voice, WebRTC and terminal feasibility probes still need their stated evidence.

The next increment implements transactional chat mirrors, stable send nonces,
manual recovery, encrypted mailbox actions, history, permissions, threads,
reactions, basic configuration/templates, Markdown, and streaming file delivery.
API 29 and API 36 ARM64 emulators passed the local instrumentation suite and
real-host text/permission/final-reply and files-only upload/download acceptance.
Minimum-OS acceptance caught an unavailable Java file API; the final implementation
uses bounded reads supported on Android 10. These emulator gates establish the
core-chat implementation, while physical-device and live mailbox expiry/process
recovery checks remain pending.

Sean selected Firebase project `zc-codync` and a Samsung physical device. Separate
development and release Android apps are registered there. Their public SDK
configuration is under the Android variant source sets; server credentials remain
outside the repository. Native account contexts, signed cloud management, SAS
access, FCM alerts, task status, context retirement and reset are implemented.
Cloud/relay/host compatibility changes passed their full regression checks and
are deployed to Sean’s preserved infrastructure. Both emulators received and
decrypted real-host completion alerts; API 36 also passed killed-process
delivery, notification routing, and background delegated-task updates.
The Samsung completed sign-in and host-approved access; its current signed-in
account restored and populated the local mirror with all four rdev bots.
Encrypted FCM delivery also passed on the Samsung after OS process termination
and during forced deep idle; the device test overrides were restored afterward.
Visible device UI, notification taps, reboot/pre-unlock, token rotation and
mixed-provider physical delivery remain open gates.

The active management increment adds group creation/editing and host-owned
mention routing, memory inspection/forgetting, roster search, and pin/hide/read/delete
actions. It is being
validated against the existing fake-agent Rust host; see the Android README
for completed evidence. Existing clients already implement these actions and
need no UI changes for this Android port.

Large first-time history exposed Android receive-queue overflow and expensive
per-event mirror writes. Android now propagates bounded transport backpressure,
loads the current roster through the existing sync RPC without advancing the
event cursor, and commits catch-up in bounded transactions. Request deadlines
produce recoverable errors while actual owner cancellation still retires work.
These changes stay inside Android; no upstream service architecture or wire
contract changed. The 600-event slow-consumer regression and the Samsung's
four-bot account sync passed. Memory forgetting/clearing preserved the exact
conversation on both emulator OS versions.

Samsung catch-up committed through host revision 31,073 with 5,021 entries in
about 13 seconds. Group create/mention/edit and roster pin/hide/restore/delete
acceptance passed against the isolated Rust host on Android 10 and Android 16.
Persistent composer drafts passed restoration after OS process death on both
emulator OS versions, including main text, a reply and a private file. Drafts
are scoped by account/computer/chat/thread, with nonce reconciliation against
durable sends. Text, multi-chunk file and group acceptance passed again.
The routines increment passed live acceptance on both emulator OS versions:
host-backed schedule validation, creation, pause/edit/resume, preserving multiple
triggers and filters, webhook-key replacement, a completed fake-agent test run,
and deletion. Usage now displays host-reported providers, windows, source and
timestamps without substituting zero for missing consumption. Its missing-data,
unknown-provider, old-timestamp, offline, refresh and expand/collapse checks passed
on both emulator OS versions. The latest ordinary instrumentation runs contained
25 passing checks and eight opt-in skips per OS; 42 core checks passed and one
opt-in host check skipped. Debug, instrumentation, R8 release and lint passed.

Cold bot links also passed on both emulator OS versions after process death,
including an initially uncached bot, stale account/computer/missing-bot errors,
leaving an open Usage screen, and retaining current navigation on Activity
recreation. Android now waits for the intended active account runtime and asks
the existing host for a current roster when the bot is not cached.
Camera pairing uses CameraX lifecycle ownership and bundled ZXing decoding.
Explicit permission/settings and unavailable-camera UI passed on API 29 and 36,
as did opening a real emulator camera, releasing it in background, reopening
and releasing it when leaving the scanner. QR decoding passed rotation,
reflection/inversion, obsolete/malformed link and camera plane-layout checks.
The Samsung's physical optical scan remains open. Core now has 47 passing checks
and one opt-in skip from this increment.

Two-step computer installation/pairing guidance passed navigation and scanner
return on both emulator OS versions. It preserves the chosen Mac/Linux steps
and allows setup to be skipped. The fork has no published releases, so the page
describes the provided ZC build rather than advertising an unavailable download.
Existing iOS pairing screens were inspected; desktop clients and the TUI already
provide the pairing code and do not need an Android camera adaptation.

Bot settings now query the selected host's model catalog and directory list.
Unknown saved models survive discovery, the agent's default appears once, and
late cancelled responses cannot replace another agent's catalog. Offline model
entry remains possible. Folder browsing/filtering and recoverable errors passed
on both emulator OS versions; a real host saved the selected directory and a
default-notifications-off change while preserving custom command, connectors
and skills. New bots retain the host's default connector selection, and saving
an installed agent clears a stale custom command. Account changes close old
editors before another context can save them. Shared SwiftUI, iOS/macOS, GTK and
TUI settings were inspected and retain their existing behavior.
This increment passed 49 core checks with one opt-in skip and 34 ordinary
instrumentation checks with 11 opt-in skips per OS, plus debug, instrumentation,
R8 release and lint. Samsung's upgraded build passed the read-only account/mirror
check again with sign-in and pairing preserved; visible checks still await unlock.

The avatar increment reproduces the existing dotted character palette/shapes,
group clusters and approval/unread badges using Android drawing. Activity animates
only while foreground and system animation is enabled. SwiftUI, GTK and TUI avatar
implementations were inspected; they already provide these representations.
Chat, file, group, settings and actual routine-results navigation passed on both
emulator OS versions, including scrolling to offscreen roster controls.

The connector/skill marketplace increment passed explicit pagination, duplicate
suppression, stale-search cancellation and per-bot plugin-selection checks on both
OS versions. Inert custom connectors and instruction skills installed and were
removed through the real host; cancel-removal retained the plugin, and fixture
bot settings were restored afterward. OAuth callback handling retains one
account/computer-bound state hash for 15 minutes, sends codes to the existing host
and keeps the connector installed after cancellation. Callback rejection and
fresh-owner storage checks passed; real provider/browser completion remains open.
The connector increment passed 53 core checks with one opt-in skip, 38 ordinary
instrumentation checks with 12 opt-in skips per OS, and debug/instrumentation/R8
release builds and lint. This is not a claim of full marketplace parity.

Connected apps, credential management and agent setup are implemented with
passing Android 10/16 local and isolated-host gates.
The Android-owned terminal uses integrity-pinned MIT xterm assets in a restricted
WebView, the original encrypted `term` subscription, and the existing
`agentSetup`, input, resize, close and auth/environment RPCs. Its session tests
cover ordered input, queue saturation, uncertain delivery, reconnect replay and
a setup reply arriving after Back. The real isolated Rust-host PTY passed Back
cleanup, rotation without starting another PTY, IME input, exit and updated
sign-in status on both OS versions after the final input-bridge adjustment.
Ten terminal/key-form checks now pass on both versions, including IME composition,
deletion, repeated Enter without duplicate input, hardware-key order, resizing
and renderer termination. Screen-reader mode follows touch exploration;
TalkBack and the Samsung keyboard remain physical acceptance gates. Host key
save/preserve/remove and the ordinary native suite passed on both versions;
debug, instrumentation, lint and R8 release passed. No real provider sign-in is
claimed.

Connected apps retain explicit paging and current connection IDs across Activity
recreation. Cold resume polls the original ID without starting another mutation;
unknown states remain visible. Composio, connector and sign-in fields remain in
memory and use existing host APIs. Live fixture acceptance passed saved sign-in
save/removal, MCP import/verification, credential replacement, chat request
save/cancellation, idempotent completion and password exclusion from public
metadata/conversations on both OS versions. Card submission now belongs to the
account runtime and survives row disposal. Existing native clients already
implement these features; no shared backend contract or architecture changed.
This increment passed 66 core checks with one opt-in skip, 59 ordinary native
checks with 16 opt-in skips per OS, and the build gates above. Real external
Composio/OAuth and 1Password acceptance remain open. Voice, remote screen,
multiple-computer operation, widgets and release acceptance remain unfinished.

### Layout correction and English voice prototype, 2026-10-03

The Android roster was visibly too sparse compared with the bundled iOS roster
and conversation screenshots and their current SwiftUI sources. Its permanent
account/action rows and tall cards are replaced by the existing iOS hierarchy:
a compact account/computer/New header, flat two-line bot rows, unread capsules,
and floating Bots/State tabs. Chat now has a compact header, aligned bubbles,
contextual message actions and a capsule composer. Accounts, Usage, Marketplace
and Routines share the compact header. Search, hidden bots, bot/group creation,
profile editing, trace, routines and reset remain reachable through labeled
menus or long press. The State tab exposes real bot attention and notification
preferences; widget/provider/setup parity is still open.

This correction stays inside Android-owned files. Shared SwiftUI/iOS already
provide these layouts and actions. Mac uses its existing sidebar/context menu;
Linux has compact transparent roster rows/context actions; the TUI uses its
existing roster/chat pages and keyboard actions. No behavior or contract change
requires modifying those clients.

Both Android 10 and Android 16 passed live text/files, group create/mention/edit/
pin/hide/restore/delete, routines and settings after the navigation correction.
The five-row density and large-text header checks are exercised by `LayoutTest`.
The Samsung's real roster shows Dex, Sophie, Miles and Theo together in dark
mode, and its actual Dex conversation was visually checked. The final build
preserves sign-in and pairing; the temporary USB screen-awake setting was restored.
Short conversations align near the composer; gaps over an hour add time separators.
Normal chats omit redundant author labels, while groups retain them. The ordinary
82-check suite contained 63 passing checks and 19 opt-in skips on each OS.
Final layout, actual text/files and rotation/background/notification voice-cleanup
checks passed on both. Debug, instrumentation, lint, production R8 release and
minified native speech acceptance passed.
Android source was retained across the upstream refresh and built on the
updated consolidated branch at version 2.6.0. Existing client architecture was
preserved.

An English voice prototype now implements the call/session adapter, bundled
checksum-pinned Vosk recognition, offline Android TTS, microphone foreground
service, account/computer ownership, text-only durable sends, mute/interrupt/
resume/end, settings, announcements and call notices. Native model decoding,
AudioRecord open/close and offline TTS completion passed on both emulator OS
versions. Rotation, background channel retention and notification End passed;
cleanup waits for the recorder before releasing audio focus/routes. Eight core
voice tests contribute to 74 passing core checks and one opt-in host skip.
A separate minified acceptance variant decodes the public WAV through native
Vosk/JNA on both OS versions. It preserves the production signing/build variant;
full licenses and source/hash provenance are bundled with the speech assets.

The repeated background microphone-to-host test remains unproven: injected
emulator audio reaches a zero capture level and times out. Physical Samsung
microphone/background speech and TTS, headsets, incoming calls, revocation and
languages beyond English remain open. No Samsung speech result or full voice
parity is claimed. The earlier Samsung bot-list issue was visually resolved:
Dex appears under the connected rdev-sean computer, and its approved account's
mirror contains all four bots.

### State and native widget increment, 2026-10-03

State now has Widgets and Task status pages. The gallery checks actual pairing
and installed widgets, provides wrapping selection controls with accessibility
selection semantics, labels previews as sample data, and opens the launcher's
pin flow. Bot summaries, usage limits and provider usage have native Glance
widgets with responsive sizes. Provider configuration uses the computer's
reported providers plus Claude/Codex, preserving an unknown saved selection.

Private snapshots retain bot identity/status/unread and usage only. They follow
the active account and reject retired-account writes and superseded background
responses. A reconnect cannot freshen cached bot activity before ordered event
catch-up reaches the host's reported revision. Account retirement joins widget
readers before storage erasure; removing a computer hides its saved data. Widget
links carry the original account/computer scope, including Usage navigation.
Foreground updates coalesce; installed widgets schedule bounded encrypted host
reads about every 30 minutes. Missing and stale states retain honest timestamps,
and old activity never appears current. Android/launcher deferral remains expected.

The real Android widget-host checks exercise rendering, six bot rows, responsive
size changes, Claude-to-Codex configuration, account clearing, scoped Usage
routing and rotation. The production refresh adapter reads the isolated host
without changing its bots. Tests retire their private owner before erasure,
then restore the original owner; they never run on a user phone. Production R8
preserves Glance widget class identities so Bots/Provider/Usage registries cannot
be merged by optimization. The acceptance helper runs inside the optimized app,
using a framework instrumentation bridge; it is excluded from production.

| Check | Result |
| --- | --- |
| Core | 80 passing checks and one opt-in host skip |
| Ordinary instrumentation | 65 passing checks and 21 opt-in skips on each of Android 10 and Android 16 |
| Final focused gates | Eight checks passed per OS: normal/large-text gallery, account/freshness guards, real widget host/refresh/routing, chat/files and voice cleanup |
| Terminal timing | WebView's IME acknowledgement is awaited before the synthetic composition test injects a hardware delete; its focused checks and final ordinary runs pass on both OS versions |
| Packaging | Debug, instrumentation, lint and production/acceptance R8 builds passed; lint has zero errors and 54 warnings |
| Minified native runtime | Native speech and real Glance widget rendering/registry checks passed on both OS versions (two framework checks per OS) |
| Visual/device checks | Native widget renders and the gallery at normal/1.5× text were inspected; the new Samsung build preserves account, pairing and original screen-awake settings |

The corresponding iOS WidgetKit widgets/gallery, Mac usage display, GTK usage
view and TUI usage page were inspected. Android Home Screen widgets and launcher
configuration require Android-owned adapters; those clients retain their existing
features and navigation. No existing client architecture, host schema or shared
protocol changes in this increment.

Samsung pin/reconfiguration/deferred refresh, generated launcher picker previews,
remaining widget presentation options, release signing/distribution and the other
physical-device gates remain open. Remote screen and full voice acceptance are
still unfinished; these results do not establish full iOS parity.

A post-install Android 16 emulator launch reported one background-job ANR
(`No response to onStartJob`) after alternating debug and minified APKs. Its
trace captured paging waits and timed-out stack collection. The same APK then
passed three cold-launch/State-gallery checks with no additional ANR, and the
gallery was inspected in a normal app launch. Android 10 also passed two normal
cold launches of the minified acceptance APK through the State gallery. The cause is not established by
those traces; deferred job/startup behavior remains a release acceptance gate.
The emulator's temporary diagnostic root access was restored to its original
shell UID, and Samsung settings were unchanged by this investigation.

### Message actions follow-up — 2026-10-03

Android message actions now open in a floating popup at the held part of the
bubble. The popup stays inside the visible conversation, placing itself above
the touch when there is insufficient room below. Opening it never increases
the message height or shifts the conversation or composer. Its six reactions
show the user's selected state and toggle through the existing host command.
Copy keeps the full text, Reply in thread retains its existing routing, and bot
replies also offer Show what it did. Back and outside taps dismiss the popup;
small viewports and large text can scroll its contents. Account/session or
thread/trace changes dispose of the old message's menu.

Message text and code now use the message menu for a hold instead of a competing
selection toolbar. Tapping a link still opens it; holding a link opens the same
message menu without visiting the link. Permission details and other selectable
content retain their existing behavior. Accessibility actions anchor to the
visible portion of a long message when there is no touch coordinate.

The corresponding shared SwiftUI/iOS/macOS, GTK and TUI implementations were
inspected. iOS already uses a native context menu; macOS and GTK provide pointer
menus and desktop controls, and the TUI provides keyboard message actions.
Windows retains its desktop action controls. This Android touch/menu correction
does not require changing those clients, host behavior or shared contracts.

| Check | Result |
| --- | --- |
| Placement unit checks | Three passed: long-message touch anchoring, placement above the composer, and a narrow keyboard-sized viewport |
| Android 10 and Android 16 | Eight focused checks passed per OS: three chat layout checks, four popup/gesture/accessibility/large-text checks, and one real-host text/permission/file round trip |
| Visual checks | Floating menus over the middle of a long conversation were inspected on both emulators |
| Packaging | Debug, instrumentation and production R8 release builds passed; lint has zero errors and 54 warnings |
| Samsung | Updated debug build installed with sign-in and pairing preserved; real-phone visual confirmation awaits unlocking |

These checks complete this Android message-menu correction. The remaining
full-parity and physical-device gates above remain open.

### Thread presentation follow-up — 2026-10-03

Android Reply in thread now presents an animated overlay above the retained main
conversation. The grabber, Thread title, bot subtitle and close button follow
the iOS thread sheet. The original message appears first, followed by No replies
yet, the reply count or Loading replies during an empty fetch. A Reply… composer
uses the existing thread-scoped draft and send route. Main-chat and thread drafts
survive opening, closing and reopening independently.

The title and composer remain visible when long messages scroll and the keyboard
opens. Close, Back, outside taps and a downward grabber drag dismiss the overlay.
The original message cannot recursively open another reply thread. Show what it
did presents a second overlay and returns to the same thread when closed.
Existing reply-count chips open the same overlay. Account, computer, bot or
session changes dispose of the previous thread presentation.

Keyboard resizing is explicit in both the main activity and overlay window for
Android 10. Message holds retain their window coordinates during keyboard
relayout, preserving the floating actions without changing ordinary link taps.

Shared SwiftUI/iOS/macOS RepliesView, Linux's thread pane and the TUI's thread
view were inspected. They already include the original message, empty reply
state and thread-specific composer. iOS already presents the mobile sheet;
macOS and Linux use desktop panes or compact overlays, and the TUI uses keyboard
navigation. Windows retains its desktop thread controls. This correction is
Android-owned and requires no upstream client architecture or host/shared
contract changes.

| Check | Result |
| --- | --- |
| Placement unit checks | Three passed with the current floating-menu implementation |
| Android 10 and Android 16 | 23 focused checks passed per OS: three chat layout, four message-menu, five reply overlay, eight terminal, two roster and one live-host thread check |
| Live thread routing | Original message, empty state, independent drafts, reply send/permission/agent response, nested trace close, native Back and reply-count reopening passed against the disposable host on both OS versions |
| Visual checks | Empty and populated thread overlays were inspected on both emulators, including the retained main chat behind the dimmed overlay |
| Packaging | Debug, instrumentation and production R8 release builds passed; lint has zero errors and 54 warnings |
| Samsung | Updated debug build installed without clearing app data; final thread visual confirmation requires unlocking |

During the overlapping release build, Android 16's callback-idle check and
PixelCopy screenshot capture timed out. Both passed when repeated after the
build, without source changes. The first combined regression run also found a
message-hold callback retaining the initial empty viewport; replacing the local
function reference with an updated callback fixed it, and the menu regressions
passed on both OS versions.

These checks complete the Android thread presentation correction. The remaining
full-parity and physical-device gates above remain open.

### Account and bot settings presentation follow-up — 2026-10-03

Android Account now follows iOS AccountSwitcherView: a scan card, centered profile
avatar/email, saved-account faces and Add account, then Computers and Sign out.
Computer settings remain inside the account sheet instead of filling the profile
overview. Existing sign-in providers, hosted fallback, account switching, SAS
access requests, routes, grants, devices, notification preferences and reset are
preserved. Destructive confirmations mask the underlying controls and require an
explicit action. Pairing and scanning present nested sheets and return to the
same account page when closed.

Android Bot Settings/New bot follows the shared SwiftUI BotSettingsForm: centered
avatar, visual shape/color choices, Name for existing bots, Standing instructions,
and a rounded option card with Computer, Agent, Model, Workspace, Permissions and
notification/computer-tool switches. Save/Create and Close remain in the title
bar. Connector, skill and memory controls remain available, with Android's
additional pin/visibility, manual folder, template and delete actions under More
settings. Folder browsing presents a nested sheet above the retained draft;
closing it returns to the same settings and scroll position. Saving retains the
existing BotDraft and host API. Closing settings discards unsaved edits.

The roster or conversation stays composed below both screens. Native Back routes
to the visible page or nested sheet before closing its presenter. This extends
the existing Android thread sheet without changing thread call sites, account
storage, wire contracts or upstream architecture.

Shared SwiftUI BotEditorView/BotSettingsForm, iOS AccountSwitcherView/SettingsView,
macOS's shared editor and sidebar account/computer controls, Linux's editor and
account menu, and the TUI's bot editor/account actions were inspected. iOS already
has the mobile hierarchy being restored. macOS and Linux retain desktop forms and
account menus; the TUI retains keyboard editing and local host/agent sign-in,
without a mobile OAuth avatar switcher or camera pairing sheet. Windows retains
its desktop presentation. These Android presentation corrections require no
changes to the other clients, backend or shared contracts. Android's hosted
sign-in fallback and system notification settings remain platform-specific.

The initial native run found a Compose accessibility merge error when the account
confirmation's clickable scrim contained its pane-title card. Making the scrim
and card siblings preserves independent accessible controls and fixes that error.

| Check | Result |
| --- | --- |
| Android 10 and Android 16 | 38 distinct focused native checks passed per OS: seven account, eight bot form, four sheet presentation/theme, five replies, three onboarding, two roster, three chat layout, four message-menu, one live settings and one live thread check |
| Live bot settings | A disposable bot retained unsaved edits when returning from folders; closing settings left its host name, folder and notifications unchanged; saving updated folder/notifications and preserved agent, command, connectors and skills |
| Live thread regression | Original message, separate drafts, reply routing/permissions/response, native Back, nested trace and reopening passed on both OS versions |
| Visual checks | Account and bot sheets were inspected in light/dark mode on both emulators, plus 1.5× text; SDK images lock system night mode, so dark previews override only read-only fixture configuration |
| Builds | Debug, instrumentation and production R8 release builds passed; three placement unit checks passed; lint has zero errors and 54 warnings |
| Samsung | Device disconnected before installation; the previous thread build remains installed, and USB reconnection is needed for the new build and final real-account visual check |
| Documentation | Markdown fences, local links and whitespace checks passed |

The remaining feature and physical-device gates above remain open, including
multiple simultaneous computers and remote-screen viewing/control.
