# Codync Android

The Android development client provides account-free camera/pasted-link pairing,
encrypted direct/relay channels, account sign-in/switching and host-approved
access, live bots, conversations, permissions, threads, reactions, bot
editing/templates with host-discovered models and folder browsing, file delivery,
encrypted FCM alerts, delegated task status,
groups, memory inspection/forgetting, routines and webhook management, usage
reports, character/group avatars, connector/skill marketplace with custom plugins,
and computer installation guidance with local setup/reset. Physical camera acceptance,
multiple computers, physical widget acceptance, full voice acceptance, screen, integrations, and
remaining management parity are tracked in the
[implementation plan](../../docs/plans/android-app.md).

## Build and run

Use JDK 21, Android SDK 36, and SDK Build Tools 36.0.0. Set `JAVA_HOME` to JDK 21
and `ANDROID_HOME` to the SDK, or set `sdk.dir` in ignored `local.properties`.
The checked-in Gradle wrapper verifies its distribution checksum.

```sh
cd apps/android
./gradlew :core:test :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On a jenv-managed Mac, prefix Gradle commands with `JAVA_HOME="$(jenv prefix)"`
if `/usr/libexec/java_home` selects another JDK. A release APK is unsigned until
production signing is configured; debug signing is for local/private testing.

Run `codync-host pair` on the computer, paste the resulting `codync://pair`
link into Android, and select Pair. The computer must run an Android-supporting
host build (the first compatibility patch is in 2.4.0). The UI displays real
host data; it contains no sample bots. Tap a bot to open its conversation.
The client does not persist or log pairing links or their one-time codes.
This increment supports one paired computer. Pairing another computer requires
forgetting the current computer in Accounts; multiple-computer roster support
belongs to a later increment. Account contexts have separate identities, mirrors,
attachments, caches and composer drafts. Drafts retain unsent text and private
files separately for each computer, bot and reply thread. A saved draft's message
ID carries through the durable send queue; recovery removes drafts already
represented by pending or confirmed messages. Storage is bounded to 64 drafts,
64 KiB of text and 20 files per draft, with the existing attachment storage caps.
Switching retires writers and notification work
before activating the next context.

The development application ID is `com.sgnl24.codync.android.dev`, with the
release ID `com.sgnl24.codync.android` and display name ZC Codync.
Both Android applications are registered in Sean's Firebase project `zc-codync`.
Release signing and distribution remain pending. Product `versionName` reads the existing
`MARKETING_VERSION` in `apps/project.yml`; Android `versionCode` is independent.

## Ownership and compatibility

- `app`: Compose screens, lifecycle, Keystore-backed identity wrapping, and
  account-scoped atomic storage under `noBackupFilesDir`.
- `core`: pure Kotlin/JVM models, crypto, pairing, request signatures, WebSocket
  transport, RPC and event subscriptions. This keeps protocol tests independent
  of Android and the Swift/Rust build systems.
- `design`: Android theme using the existing Swift palette values.

Raw Ed25519/X25519 seeds are wrapped using an Android Keystore AES-GCM key;
no cryptography provider is globally registered or replaced. Credential-protected
storage prevents access before the first unlock after reboot, while the wrapping
key does not require each operation to happen with the screen unlocked.
Backup and device transfer are excluded through the manifest and explicit
[Android extraction rules](https://developer.android.com/identity/data/autobackup).
A reset replaces the local identity
and forgets pairing without deleting computer-side bots or chats.

Direct candidates may use HTTP/WebSockets because the existing protocol encrypts
the channel. The network adapter validates endpoint types; relay URLs must use
HTTPS. The initial target is API 36; local-network permission work is required
when targeting API 37. Notification permission is requested after pairing on API 33 and later.

The foreground owner reconnects with bounded backoff and closes channels when
the activity stops, unless its own active voice call retains the channel. An account-scoped SQLite mirror commits entity updates,
send reconciliation and the event cursor in one transaction. RPC replies and
the event stream's initial head revision cannot skip unseen events. Bot
tombstones prevent late replies from reviving deleted bots; a changed host
database clears the mirror and a changed version rewinds event catch-up.
Initial roster loading uses the existing sync RPC without moving the event
cursor. Catch-up applies bounded batches in one SQLite transaction; bounded
transport queues suspend their producers rather than dropping updates. Request
deadlines report recoverable errors without cancelling the sync owner or
replaying uncertain mutations.

Every send persists its nonce before delivery. Interrupted or uncertain sends
require explicit Resend with that same nonce. Text can wait in the existing
encrypted relay mailbox, with list reconciliation and honest cancellation states.
File sends require a connected computer and are unavailable in groups. Photos,
documents and pasted content are copied into private storage, uploaded in
384 KiB chunks, and retained for retry across process death. Each whole-file
attempt uses a fresh upload ID. Downloads verify sizes before publishing the
cache file; sharing grants access only to a separate temporary copy. File size
is capped at 100 MiB and attachment storage is bounded.

The tests consume [upstream vectors](../../docs/reference/fixtures/remote-relay-vectors.json)
in place. Instrumentation packages those fixtures only in its test APK.
Existing source paths, public contracts and fixtures remain in their owners.

## Dependencies

Gradle dependency versions live in [libs.versions.toml](gradle/libs.versions.toml).
Bundled terminal packages and file hashes live in
[vendor-manifest.json](app/src/main/assets/terminal/vendor-manifest.json).
The APK includes AndroidX's standard `libandroidx.graphics.path.so`, CameraX's
`libimage_processing_util_jni.so` / `libsurface_util_jni.so`, and its transitive
DataStore `libdatastore_shared_counter.so` from Google Maven for the four Android
ABIs. These are Apache-2.0 AndroidX artifacts; no custom native camera binary is
introduced. The English voice prototype bundles Vosk and JNA, with notices and provenance in
[voice-manifest.json](app/src/main/assets/licenses/voice-manifest.json). WebRTC
native artifact selection remains open. Android minimum API is 29;
device evidence, rather than the declared minimum alone, determines support.

| Dependency | Version | Source/license and role |
| --- | --- | --- |
| Gradle | 9.6.0 | [Gradle](https://gradle.org/releases/), Apache-2.0; checksum-pinned wrapper |
| Android Gradle plugin | 9.4.1 | [Compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes), Apache-2.0; SDK 36 build |
| Kotlin and Compose compiler | 2.4.20 | [Kotlin](https://kotlinlang.org/docs/releases.html), Apache-2.0 |
| Compose BOM | 2025.12.01 | [AndroidX](https://developer.android.com/develop/ui/compose/bom/bom-mapping), Apache-2.0; UI components |
| AndroidX Activity / Lifecycle | 1.12.2 / 2.10.0 | [AndroidX](https://developer.android.com/jetpack/androidx/releases), Apache-2.0; platform lifecycle |
| Coroutines / serialization | 1.10.2 / 1.9.0 | [Kotlin libraries](https://github.com/Kotlin), Apache-2.0; concurrency and JSON |
| OkHttp | 4.12.0 | [Square](https://square.github.io/okhttp/), Apache-2.0; WebSockets and test server |
| Bouncy Castle | 1.86 | [BC Java](https://www.bouncycastle.org/download/bouncy-castle-java/), [BC license](https://www.bouncycastle.org/licence.html); lightweight Ed25519/X25519/ChaCha20-Poly1305 |
| JUnit / AndroidX Test | 4.13.2 / runner 1.7.0 / ext 1.3.0 | [JUnit](https://junit.org/junit4/), EPL-1.0; [AndroidX Test](https://developer.android.com/training/testing), Apache-2.0 |
| CommonMark and GFM extensions | 0.30.0 | [CommonMark Java](https://github.com/commonmark/commonmark-java), BSD-2-Clause; selectable Markdown, tables and strikethrough |
| Coil | 3.4.0 | [Coil](https://github.com/coil-kt/coil/releases/tag/3.4.0), Apache-2.0; bounded image loading and caching |
| Java API desugaring | 2.1.5 | [Android desugaring](https://developer.android.com/studio/write/java11-default-support-table), GPL-2.0 with Classpath Exception; CommonMark runtime support |
| Clerk Android | 1.1.11 | [Clerk SDK](https://github.com/clerk/clerk-android), MIT; native account sessions and browser/provider authentication |
| Firebase BOM / Google Services plugin | 34.19.0 / 4.5.0 | [Firebase Android](https://firebase.google.com/support/release-notes/android), Apache-2.0; FID registration and data-only FCM delivery |
| WorkManager | 2.12.0 | [AndroidX Work](https://developer.android.com/jetpack/androidx/releases/work), Apache-2.0; bounded background registration retries |
| Glance | 1.2.0 | [AndroidX Glance](https://developer.android.com/jetpack/androidx/releases/glance), Apache-2.0; responsive native app widgets |
| CameraX | 1.6.2 | [AndroidX Camera](https://developer.android.com/jetpack/androidx/releases/camera), Apache-2.0; lifecycle-bound preview/analysis, minimum API 23, optional camera permission |
| ZXing Core | 3.5.4 | [ZXing releases](https://github.com/zxing/zxing/releases/tag/zxing-3.5.4), Apache-2.0; bundled Java QR decoding without a network/model download |
| xterm / fit / web-links | 6.0.0 / 0.11.0 / 0.12.0 | [xterm.js](https://github.com/xtermjs/xterm.js/tree/6.0.0), MIT; bundled terminal rendering and explicit HTTP links, with each license included in the assets |
| Vosk Android / English model | 0.3.75 / small-en-us-0.15 | [Vosk](https://github.com/alphacep/vosk-api), Apache-2.0; bundled offline recognition and checksum-pinned model |
| JNA | 5.18.1 | [Java Native Access](https://github.com/java-native-access/jna/tree/5.18.1), Apache-2.0 option with notice; native speech binding |
| esbuild (asset regeneration only) | 0.28.2 | [esbuild](https://esbuild.github.io/api/#target), MIT; transforms newer syntax for older WebViews, never included as an APK binary |

Production packaging must include dependency notices before public distribution.
Background speech acceptance and WebRTC feasibility remain open. Setup-terminal emulator and
isolated-host gates have passed; physical provider and keyboard checks remain.
CameraX and bundled
QR decoding have passed emulator permission/lifecycle and decoding gates;
physical optical pairing remains an acceptance gate.

The terminal loads only bundled assets through an allowlisted virtual HTTPS
origin. File/content access, network loads, storage, frames and page navigation
are disabled; terminal bytes never become HTML or script. Explicit HTTP links
open in the device browser, and only an explicit copy action writes the
clipboard. Renderer termination releases the dead view and shows a recoverable
error. No terminal output, keystrokes or agent key values are written to the app's
storage. Agent keys use the existing host vault API; the host returns saved
field names only.

Screen-reader mode follows Android touch exploration and updates when that
setting changes. The Android input bridge uses xterm's public hooks for committed
key-229 text and deletion, avoiding a delayed textarea comparison that could
emit a false Backspace after Enter. Actual compositions retain xterm's own
handling. Ten terminal/key-form checks passed on both emulator OS versions,
including composed Unicode, repeated commands, exact hardware-key order,
resize, output escaping, explicit clipboard/link actions and renderer failure.
TalkBack and the Samsung keyboard still need physical acceptance.

Regenerate the pinned assets with `python3 tools/vendor-terminal.py`. It checks
the npm archive integrity, selects the JS/CSS/license files, transforms syntax
with pinned esbuild, and records both original and generated file hashes. The
syntax target is Chrome 83; it does not establish support for that WebView
version. Acceptance currently uses WebView 91 on Android 10 and WebView 133 on
Android 16. Older WebViews remain unverified and can require an update.

`LiveTerminalTest` requires the inert
[setup-agent.py](tools/fixtures/setup-agent.py) copied into the isolated Android
acceptance host's cached registry. It checks the fixture computer ID before
starting a PTY and never starts a real provider login. Reset its sign-in marker
and refresh `agentAuth` between device runs. Hardware-key tests require the
emulator's `hw.keyboard=yes`; soft-keyboard tests call the input connection on
its requested handler thread. Do not run the fixture suites on a user phone.

## Fork patch register

Baseline: fork branch `fergusean/consolidated`, commit
`26cca8e56374da73f0437f9223e935fad30cba61`. Confirmed upstream:
`https://github.com/leepokai/Codync.git`, branch `main`; `origin` is Sean's fork.
Upstream HEAD and latest tag at implementation start were
`9d838b72073cf7e592c28a1059173603f2bc3d7c` and `v2.3.0`.

| Existing-file patch | Purpose | Regression and deployment |
| --- | --- | --- |
| `host/src/remote/channel.rs`, pairing validation | Accept `android` alongside `ios` and `macos`; preserve one-time codes, storage and scopes | Android pairing/reconnect/permissions plus existing host tests; deploy to rdev following [deployment memory](../../docs/reference/sean-deployment.md) |
| Apple/host version sources and generated Xcode project | Prepare the required 2.4.0 feature release | XcodeGen generation, host build/version; no Apple behavior change |
| `cloud/src/api.ts`, new `0003_android_devices.sql` | Accept Android without changing account grants, identity or access flow | All 100 cloud tests and type checking; populated Windows/Android migration coverage; deployed with preserved D1 bindings and history |
| `host/src/api/mod.rs` | Count Android event subscriptions through the existing phone suppression guard; add exact-caller `unregisterActivity` | Focused stream and cancellation coverage, full host checks; deployed to rdev |
| `host/src/store.rs` | Delete only the caller’s selected bot activity ticket | Caller/bot isolation and idempotence coverage; existing table, no host migration |
| `relay/src/index.ts`, new `fcm.ts` | Add FCM tickets and delivery alongside legacy APNs | Existing APNs/ticket tests, FCM OAuth/payload/error tests and live Worker delivery; preserved APNs topic and ticket key |

Android is registered as `android`. APNs tickets and dispatch retain their
existing provider behavior. FCM destinations and service-account credentials
remain private to the push relay; hosts receive opaque tickets.

The Swift, iOS, macOS, GTK and TUI roster, setup, conversation, permission,
thread, configuration and attachment implementations were inspected.
Their source UI is unaffected: Android ports existing behavior to a new
Android client and extends the stored platform allowlist without changing shared
actions, terminology, or presentation fields. Version generation changes only
the existing product version.

## Acceptance evidence

Validated on 2026-10-03:

| Check | Result |
| --- | --- |
| Android core | 18 unit/integration tests passed; the opt-in live test skips in the ordinary suite |
| Packaging | Debug APK, R8-minified unsigned release APK and instrumentation APK built with JDK 21 |
| Lint | No errors; remaining warnings report newer available dependency versions |
| API 36 ARM64 emulator | Three instrumentation tests passed: identity wrapping/restoration/reset and account isolation, shared encrypted push vector on Android ART, and usable skipped onboarding |
| Live app on API 36 | Cold/warm deep-link pairing, real roster, live bot creation/deletion, background/resume, process recreation and consumed-code rejection passed against the isolated host; the paired screen was visually inspected |
| Real Rust host, direct | Kotlin paired as Android, verified `hello` identity and read the bot snapshot against an isolated 2.4.0 host |
| Real Rust host, forced relay | Kotlin paired and read `hello`/bot sync through Sean's existing Cloudflare relay to rdev; the temporary device was revoked afterward |
| Host regression checks | Rust formatting and strict Clippy passed; 213 tests passed and the existing OS-keychain test remained ignored |
| rdev deployment | Verified active 2.4.0 static binary, unchanged computer identity, preserved service/data/configuration and `message_bot` in the team MCP tool list; previous binary retained |

The rdev artifact SHA-256 is
`37af245294b41fafbdf879833d086ae26153e9f9ea74e06fde141a34716592fe`.

The chat and recovery phase was subsequently validated on the same date:

| Check | Result |
| --- | --- |
| Android core | 26 tests passed, covering conversation decoding, template fields, mailbox seals and responses, streaming transfers, and existing protocol behavior; the separate live test remains opt-in |
| SQLite recovery | Seven instrumentation cases passed: cursor safety, atomic nonce reconciliation, tombstones, version/database replacement, interruption, late failure, and schema migration |
| Android 10 / Android 16 | All 14 local instrumentation tests passed on API 29 and API 36 ARM64 emulators |
| Live app, both OS versions | Each completed real host text send → permission answer → final reply, then pasted a multi-chunk file, sent it without text, answered permission, and verified the downloaded bytes |
| Host file protocol | The opt-in JVM acceptance verified a multi-chunk upload/download and repeated-send deduplication against the real Rust host using the existing fake ACP agent |
| Packaging | Debug, instrumentation and R8-minified unsigned release APKs built; lint passed after the minimum-OS file API fix |

`LiveChatTest` runs only with the instrumentation argument `liveHost=true`.
It requires an isolated host on port 29322 with `host/tests/fake_agent.py`,
a bot named `Android chat acceptance`, and an already paired emulator. It
does not run real agent work. `HostInteropTest` accepts a private pairing file,
cleanup-key file and optional `CODYNC_ANDROID_FILE_TEST_BOT`; use `--rerun-tasks`
for each fresh live acceptance input.

Physical Samsung notification taps, screen-lock/reboot behavior,
optical camera pairing, speech and TURN media remain unverified. The offline
mailbox has protocol and recovery tests; live process-death/expiry acceptance
through Sean's relay remains open. These records do not establish full iOS parity.

The account and push phase was subsequently validated on the same date:

| Check | Result |
| --- | --- |
| Account contracts and crypto | Signed Android registration, canonical request bytes, cancellation, SAS vectors and unknown-state handling passed; 31 core tests passed before management work |
| Packaging | Gradle 9.6.0 / AGP 9.4.1 / Kotlin 2.4.20 built debug, instrumentation and R8-minified release; lint has no errors |
| Android 10 / Android 16 | 18-test instrumentation runs completed on both emulators; the opt-in live test skipped |
| Real chat, both OS versions | Real text/permission/final reply and multi-chunk file send/download passed again with the upgraded toolchain |
| Background FCM | Deployed Worker accepted both SDK-registered FIDs; sealed notification title/body decrypted locally on API 29 in the background and API 36 after OS process termination |
| Real host alerts | Both emulators received and decrypted the fake-agent host’s completion alert through Sean’s deployed push relay |
| Notification routing | A real notification tap recreated the API 36 process and opened the matching bot/context/computer |
| Delegated task status | API 36 received needs-input and completion by FCM in the background; both updates matched the same local delegation generation |
| Service regression | Cloud: all 100 tests and type checking; relay: APNs/ticket and FCM tests/type checking; host: formatting, strict Clippy and 216 passing tests with the existing keychain test ignored |
| Deployment | Android D1 migration and cloud/push Workers deployed with preserved resources; rdev updated with unchanged identity/data/configuration and verified team MCP tools |

The latest Android-supporting rdev binary SHA-256 is
`3ec44d360ff40836182d7ef70a2972b342eb25b1e1a3eb784e578d562996677c`.
The Samsung completed account sign-in and host-approved access. A read-only
transport probe returned the current roster; the app's account restoration and
sync populated its existing local mirror with all four rdev bots and 5,021
conversation entries, committing through host revision 31,073 in about 13 seconds. This check
preserved its identity, session and host data. Visible UI and notification-tap
checks continue after the phone is unlocked. Encrypted FCM alerts also arrived
after OS process termination and during forced deep idle on the Samsung, with
the test's idle/battery overrides restored afterward. Reboot
pre-unlock behavior, Samsung background restrictions, FID rotation, mixed-provider
physical delivery and account access/revocation remain acceptance gates. Ordinary
OS process death is covered above; a user force-stop is a separate state.

The app defaults to Sean’s public ZC cloud, Clerk and Firebase configuration.
`codyncCloudUrl`, `codyncClerkPublishableKey`, and `codyncPushRelayUrl` Gradle
properties provide Android-only overrides. Both Firebase variant configurations
are public SDK config; service-account private keys are never bundled in the APK.

The management and catch-up increment was subsequently checked on the same date:

| Check | Result |
| --- | --- |
| Android core | 37 tests passed; the separate opt-in host test skipped. Includes a 600-event slow-consumer burst, cold direct handshake, recoverable request deadlines and owner cancellation |
| Mirror recovery | Initial roster replies cannot checkpoint past earlier history; bounded batches retain atomic cursor/nonce reconciliation |
| Groups and roster, both emulator OS versions | Create, mention one member, edit, pin, hide, restore and delete passed through the real host; member bots survived deletion |
| Memory, both emulator OS versions | A disposable fake-agent bot loaded profile/history facts, forgot one, cleared the remainder and retained exactly the same conversation |
| Drafts, both emulator OS versions | Main-chat text, a reply and a private file survived OS process death; sending used the persisted nonce and removed the composer draft after durable queue commit |
| Draft and chat regression | Four draft-storage checks passed on both OS versions; real text, multi-chunk file and group-management acceptance passed again with persistent composer state |
| Routines, both emulator OS versions | Host validation rejected invalid cron; creating, pause/edit/resume, preserving multiple triggers/filters, webhook-key replacement, a completed fake-agent test run and deletion passed |
| Usage | Missing consumption remained unavailable, unknown providers retained their source, old timestamps stayed visible, offline refresh was disabled and expand/collapse worked on both OS versions |
| Latest Android checks | 42 core tests passed with one opt-in host test skipped; 33-test instrumentation runs on each OS included 25 passing checks and eight opt-in skips; debug/instrumentation/R8 release builds and lint passed |
| Cold bot links, both emulator OS versions | A bot absent from the local cache opened after process death; other accounts/computers, missing bots and duplicate scopes produced errors; a link left an open Usage screen and activity recreation preserved the current conversation |
| Camera, both emulator OS versions | Explicit permission/settings and unavailable-camera fallback passed; real CameraX camera opened, released in background, reopened and released when leaving the scanner |
| Bundled QR decoding | Three checks passed for rotated/mirrored/inverted QR images, obsolete/malformed links and padded/strided luminance planes; no network or model download |
| Setup guidance, both emulator OS versions | Installation and pairing steps retained the selected computer OS after navigation/scanner return; Skip kept the empty roster usable. The fork has no published release download, so setup describes the provided ZC build |
| Bot settings, both emulator OS versions | Host model choices retain unknown saved overrides and fold the agent default; late cancelled discovery cannot replace another agent's catalog. Folder filtering/error/retry passed, and a real host save retained the selected folder, custom agent command, connectors and skills while turning default notifications off |
| Settings increment checks | 49 core checks passed with one opt-in skip; each 45-test ordinary instrumentation run contained 34 passing checks and 11 opt-in skips; debug/instrumentation/R8 release builds and lint passed |
| Avatars and conversation regression | Character/group avatars match the existing palette and silhouettes. Chat, files, group actions, settings and actual routine-result navigation passed on both OS versions; Android 10 roster rendering was inspected |
| Connector/skill marketplace, both emulator OS versions | Catalog pages deduplicate, reveal 12 rows per explicit request and never fetch on scroll; late cancelled searches are ignored. Inert custom connectors and instruction skills installed and were removed through the real host, with cancel-removal and original fixture bot choices restored |
| OAuth boundaries | Two core checks reject wrong-computer, expired, repeated-field, mixed-result and unrelated callbacks; a fresh storage owner restores only the pending state hash. No code or credential is persisted. Real provider/browser sign-in remains unverified |
| Marketplace increment checks | 53 core checks passed with one opt-in skip; each 50-test ordinary run contained 38 passing checks and 12 opt-in skips; debug/instrumentation/R8 release builds and lint passed |
| Samsung account | Existing signed-in account restored; approved host returned four bots; the mirror caught up through revision 31,073 with 5,021 entries |
| Samsung FCM | Real Firebase delivery through Sean's Worker decrypted locally after OS process termination and in forced deep idle; notifications were already permitted and device test overrides were restored |

The forced-idle procedure follows [Android's Doze testing guide](https://developer.android.com/training/monitoring-device-state/doze-standby).
It establishes delivery during controlled Doze; Samsung's longer-term app sleeping
policies, reboot/pre-unlock behavior and actual notification taps remain separate gates.

`LiveManagementTest`, `LiveDraftTest`, `LiveRoutinesTest` and `LiveMemoryTest` are
disposable-host checks. Draft acceptance uses `draftPhase=prepare` followed by
OS process termination and `draftPhase=restore` on the same paired emulator.
`LiveRoutingTest` similarly uses `routePhase=prepare` / `routePhase=restore`;
it creates and deletes one temporary bot to check an initially uncached destination.
The memory
test needs a seeded temporary bot passed as `memoryBot`; never supply a user's bot.
`LiveRosterTest` is opt-in: `inspectHost=true` reads a paired host without changing
its data, and `inspectMirror=true` exercises the app's account restoration and
local sync without resetting identity, sign-in or pairing. These probes must be
selected by class/method when run on a physical phone; the ordinary fixture suite
includes reset tests and belongs on emulators.

`CameraScannerTest` runs its native camera lifecycle gate only with
`inspectCamera=true` on an emulator. Its ordinary permission UI checks do not
request camera access. The native gate grants the permission on that emulator;
never run it on a physical phone. Core QR tests use generated fixture images.
This does not establish a real optical scan or pairing on the Samsung.

`LivePluginsTest` installs inert fixture plugins on the isolated acceptance host,
removes them and restores its bots' original connector/skill choices. Never point
it at a user's computer. Android's connector OAuth callback accepts a current
account/computer-bound state hash and sends the code to the existing host. Keys
remain on the host; the phone saves one 15-minute state hash only. Canceling keeps
the connector installed. Real provider sign-in remains an acceptance gate.

### Connected apps, credentials and setup acceptance

Connected apps use the existing Composio APIs, with explicit catalog paging,
declared credential fields, browser sign-in and bounded foreground polling.
Activity recreation keeps its current attempt. Cold restoration resumes a
known connection ID through reads only; an interrupted attempt without an ID
requires checking installed apps before starting again. Unknown connection and
request states remain visible without reporting success.

Saved sign-ins, 1Password configuration, MCP-config import, connector credential
replacement and verification use existing host APIs. Credential fields stay in
memory, clear after confirmation and are not restored after process death.
Blank connector replacement fields preserve saved values. Chat setup cards
submit through account-owned actions so scrolling a row away does not cancel
its request. Cancellation never submits the typed credential.

| Gate | Evidence |
| --- | --- |
| Core | 66 passing checks and one opt-in skip, including connection polling, cancellation, uncertain submission, cold resume and credential whitespace |
| Ordinary native suite | 59 passing checks and 16 opt-in skips on each of Android 10 and Android 16 |
| Live credentials, both OS versions | Saved sign-in save/keep/remove; MCP import and handshake verification; saved-field replacement; request save/cancel and repeated completion; public metadata and conversation contain no fixture passwords |
| Live agent setup, both OS versions | Real isolated-host PTY closes on Back, retains its ID/output across rotation, accepts IME input, exits and refreshes auth; host key save/preserve/remove returns names only |
| Builds | Debug, instrumentation, lint and R8 release passed after the card lifecycle fix |

`LiveCredentialsTest` requires the disposable paired fixture computer, no signed-in
account, `liveCredentials=true` and the local `credentialMcp` fixture path. Its
chat test additionally receives a disposable `credentialBot`, `loginRequest`
and `cancelRequest` seeded through the host's loopback-only bearer API. The
controller removes that bot and its saved sign-in afterward. Remote Android
devices cannot call the privileged request-generation or credential-runtime APIs.
No real provider login, shared-vault access or external Composio connection is
claimed by these fixture checks. Existing SwiftUI, Linux and TUI setup and
credential implementations already provide these behaviors and are unaffected.

### Android layout alignment

The roster follows the existing iOS hierarchy: one compact account/computer/New
header, flat two-line rows with 46 dp avatars and unread badges, and the floating
Bots/State tab bar. Search, hidden bots, marketplace and usage live in the computer
menu; new bot/group actions live under New. Long press exposes roster actions.
Start over remains an explicit confirmation in Accounts.

Chat uses a compact header, user bubbles aligned to the right, bot replies aligned
to the left, and a capsule composer with attachment and send/voice controls.
Conversation actions live in its menu. Tapping or holding a message opens a
floating menu beside its visible text, with six reactions, Copy, Reply in thread,
and Show what it did for bot replies. The menu stays within the visible chat
area, including when the keyboard reduces it; it never expands the bubble or
changes the scroll position. Back or tapping outside dismisses it. Chosen
reactions can be removed from the reaction strip, and existing reply counts stay
visible. Holding message text, code or links opens the same menu; tapping links
still opens them. Copy preserves the entire message. Accessibility actions use
the visible part of the bubble as their anchor. Accounts, Usage, Marketplace and
Routines use the same header spacing.

Reply in thread opens an animated overlay above the retained chat, matching the
iOS thread presentation. Its title bar shows Thread, the bot name and a close
button. The original message appears above No replies yet or the reply count;
an active fetch shows Loading replies instead. Replies use a separate Reply…
composer and draft. Closing and reopening the overlay preserves both drafts.
The title and composer stay visible while long messages scroll and the keyboard
is open. Back, the close button, an outside tap or dragging the grabber dismisses
the overlay. Show what it did opens a second overlay and returns to the same
thread when closed.

`RepliesPopoverTest` checks original-message ordering, empty/loading states,
reply counts, long-message scrolling, keyboard sizing and overlay dismissal.
`LiveThreadTest`, enabled with `liveHost=true` on the disposable emulator host,
checks separate drafts, reply delivery, nested trace dismissal and reply-count
reopening. The thread correction passed 23 focused native checks per OS on
Android 10 and Android 16, plus three placement unit checks; debug,
instrumentation and production R8 release builds passed.

Account and bot settings now use the same animated mobile sheet, keeping the
presenting roster or conversation alive. Account has the iOS scan card, centered
avatar/email, saved-account faces, Add account, and Computers/Sign out card.
Computer routes, access codes, authorized devices, notification preferences and
reset remain one level inside. Pairing and scanning open nested sheets; closing
them returns to the same account page. Account confirmations require an explicit
destructive action, and Back cancels the confirmation before leaving the page.

Bot Settings/New bot has the iOS title bar, Save/Create checkmark, close button,
centered avatar, visual shape/color choices, labeled fields and compact Agent,
Model, Workspace and Permissions options. Notifications, computer tools,
connectors, skills and memory keep their existing actions. Additional roster and
template actions remain under More settings. Folder browsing opens a nested
sheet over the retained draft. Closing settings discards unsaved changes;
saving uses the existing host contract. Android's current single-computer limit
and unfinished remote-screen viewing remain tracked in the plan.

`AccountScreenTest`, `BotEditorTest` and `ScreenPresentationTest` exercise account
switching, authentication states, destructive confirmations, computer management,
model discovery/cancellation, folder browsing, draft cancellation, native Back,
sheet nesting and large text. `LiveSettingsTest` uses only the isolated emulator
host and a disposable bot to verify saving and cancellation.

The account/settings follow-up passed 38 distinct focused native checks per OS
on Android 10 and Android 16, including the existing chat, message-menu, thread,
roster and onboarding regressions. Three placement unit checks, debug and
instrumentation builds, and the production R8 release build passed. Lint has zero
errors and 54 warnings. Light/dark sheets and 1.5× text were visually inspected;
SDK images lock system night mode, so the dark previews override only their
read-only fixture configuration. Samsung installation and final visual checks
await USB reconnection; the previous thread build remains installed.

State includes a widget gallery, actual setup/installation checks, and a Task
status page with bot attention and notification preferences. This layout
correction is Android-owned: iOS already uses this hierarchy;
Mac/SwiftUI, Linux and TUI retain their existing compact roster, context actions
and platform-specific navigation. Their source and shared contracts do not change.

Five-row visibility and header bounds at 1.5× font scale are checked in
`LayoutTest`. Live chat/files, group create/edit/pin/hide/restore/delete, routines
and bot settings passed on Android 10 and Android 16 after moving actions into
menus. Short conversations stay near the composer, and gaps over an hour display
time separators. The Samsung's actual four-bot roster and Dex conversation were
visually checked in dark mode, with sign-in and pairing retained. Its temporary
USB screen-awake setting was restored. The 82-check ordinary suite contained 63
passing checks and 19 opt-in skips on each OS; final chat/context/voice cleanup
checks and minified speech decoding passed on both.

### Android widgets

Bots, cross-provider usage limits, and provider usage are native, resizable Glance
widgets. State offers small/medium/large previews explicitly labeled Sample data,
system pin requests, launcher setup instructions and an actual installed-widget
count. The launcher chooses the installed size; resizing and provider selection
use its own controls. Provider widgets default to Claude and can be reconfigured
to Codex or another provider reported by the computer. Gallery choices affect the
preview; edit the installed widget to change its provider.

Installed widgets follow the active account. Private snapshots contain bot
identity/status/unread summaries and usage reports, with no chat text, keys or
credentials. Account activation and a bounded revision marker reject old-account
and superseded background responses. Account retirement clears the displayed
snapshot and joins readers before erasure. Removing a computer hides its cached
widget data. Bot and usage links retain their original account/computer scope.

Foreground updates coalesce snapshot writes. With installed widgets, WorkManager
schedules short encrypted host queries about every 30 minutes, with bounded
retries; Android and the launcher may delay them. Widgets display the report time
and explicit missing/stale states. A stale bot never displays its old activity
as current. Reconnection cannot freshen cached bot activity until the event
mirror has caught up to the host's reported revision. API 29 progress bars use a small bounded raster to preserve Codync's
colors; bot icons reuse the app's character mask and palette.

`WidgetStoreTest` checks account and same-account response races and removal.
`WidgetSystemTest` is emulator-only and opt-in with `widgetHost=true`. It uses the
real Android widget host, temporarily adopts widget-binding permission, deletes
its own widgets/storage, and restores the original active context. It also reads
the isolated acceptance host through the production refresh adapter. Add
`widgetSnapshots=true` for private native render captures. Never run this fixture
suite on a user phone. Samsung pinning, launcher reconfiguration and deferred
background refresh remain physical acceptance gates.

Glance discovers widget kinds by class identity. Production R8 rules retain
the three widget class names and constructors so optimization cannot merge
their registries. The minified acceptance variant checks real Bots and Provider
RemoteViews, distinct widget IDs and provider selection, alongside native speech
decoding. Its framework runner loads the smoke check from the optimized app;
production does not include the acceptance helper.

The State gallery uses compact wrapping choices with native selection semantics
and automatic setup checks. Normal and 1.5× text layouts were inspected; account
removal, late responses, widget resizing, provider changes, scoped Usage routing,
rotation and the production refresh adapter passed on Android 10 and Android 16.
The final focused eight-check run also exercised real chat/files and voice
rotation/background cleanup. Core has 80 passing checks and one opt-in skip;
debug/instrumentation builds and lint passed with zero errors and 54 warnings.
The final ordinary suite has 65 passing checks and 21 opt-in skips on each OS.
The final production and acceptance R8 builds passed; both emulator OS versions
passed the two minified native checks (speech decoding and real widget rendering).
One post-install Android 16 background-job startup ANR was observed after APK
variant switching. The same APK passed three subsequent cold-launch/gallery
checks; its cause remains unconfirmed and is recorded in the implementation plan.
Two normal minified Android 10 cold launches also reached the State gallery.

iOS already supplies WidgetKit widgets and the corresponding gallery. macOS,
Linux and the TUI retain their existing usage/activity displays; they do not host
Android app widgets. Their source, behavior and shared contracts are unaffected.

### English voice prototype

The non-group main conversation can start an explicit microphone foreground
service. Recognition uses a bundled offline English Vosk model; replies use an
installed offline English Android TTS voice. Missing TTS data produces a visible
error. Audio is never saved or sent to the computer; recognized text uses the
original durable send queue. An active call retains its original account,
computer, bot and channel while backgrounded. Mute, interrupt, resume, pause/speed
settings, approval announcements and an End notification action are implemented.

Core ownership/cancellation tests passed. Actual native model decoding, microphone
open/close and offline TTS completion passed on both emulator OS versions. Call
rotation/background/notification cleanup also passed. The model loads and decodes
through JNA in a separate minified acceptance APK on both OS versions; this uses
the public fixture WAV, not a microphone recording. It is built only with
`-PcodyncReleaseTests=true`, is debug signed with the development application ID,
and does not change unsigned production release signing.

Repeated injected microphone speech has not passed end-to-end: the emulator
capture level stays at zero. Physical Samsung speech, background utterances,
headsets/audio interruptions, permission revocation and languages beyond English
remain acceptance gates. The phone's microphone must be granted through its
visible permission prompt; emulator permission grants do not establish a device
result. This prototype does not establish full voice parity.
