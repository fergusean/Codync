# Apple Watch app implementation plan

Status: phases 1–6 implemented and embedded in the iOS app; device validation pending. This feature is for the `fergusean` fork only (not upstream), so the App Store steps below don't apply to it. Prepared on 2026-10-05 against `fergusean/consolidated`, commit `cf4e5f67464743e44143110f3cb1d7eb301ad0c2` (app and host `2.7.1`); landed on `fergusean/watch-app` from `main`.

A watchOS companion that works only through the iPhone app over WatchConnectivity (WC). It lists the bots on the phone's current computer, shows a bot's recent chat, sends dictated text and answers approvals. Nothing changes in the host, `cloud/` or `relay/`. The watch has no device identity and never opens a transport: watchOS forbids WebSocket outside an active audio session (`URLSessionWebSocketTask` is denied even then), and every Codync transport is a WebSocket.

## Decisions

| Topic | Decision |
|---|---|
| Transport | WC only; the iPhone's `BotStore` does all host work |
| v1 scope | Roster, main-chat reading, dictated sends, approvals. No threads, attachments, trace, bot editing, calls, read-aloud or `logCall` |
| Computer | Follow the phone: `storage.lastComputerId`, else the first computer (today's `AppStore.currentStore`, `apps/ios/App/CodyncApp.swift:123`) |
| Input | System text input through `TextFieldLink` (dictation, Scribble or keyboard, as watchOS offers), sent with `BotStore.send` |
| Shared code | The watch links `CodyncKit` only; `CodyncUI` needs WebRTC and SwiftTerm |
| Source / target | `apps/watch/`; XcodeGen target `Watch`, product `CodyncWatch.app`, bundle id `com.pokai.Codync.ios.watchkitapp`, embedded at `Codync.app/Watch/` |
| Minimum OS | watchOS 11.0, the same generation as the iOS 18.0 floor. The APIs used need at most 10 (`@Observable`, `defaultScrollAnchor`, `containerBackground`), `TextFieldLink` needs 9 and WC needs 6. This excludes Series 4/5 (watchOS 10 max) |
| Versioning | Release versions, not a protocol number, as in [compatibility](../reference/compatibility.md): each envelope carries the sender's `version` and `minPeer` |
| Out of scope for v1 | Complication / Smart Stack widget; hands-free audio recorded on the watch and transcribed on the phone; bot-avatar (communication) local notifications |

## CodyncKit on watchOS

Type-checking every `CodyncKit` source against the watchOS 27 simulator SDK (`arm64-apple-watchos11.0`) finds three blockers. `Client/` compiles unchanged: CryptoKit, `Network`/`NWPathMonitor`, `Security`, `URLSessionWebSocketTask` and `getifaddrs`. So does the rest of `Design/`: `Canvas`, `TimelineView` and the asset catalog.

| File | Blocker | Fix |
|---|---|---|
| `Models/BotActivity.swift:1` | `import ActivityKit` (not in the watchOS SDK) | Wrap the file in `#if canImport(ActivityKit)`; only `apps/ios/App/Push.swift` and `Widgets` use it |
| `Design/Theme.swift:24` | `UIColor(dynamicProvider:)` and `userInterfaceStyle` are unavailable | `#if os(watchOS)`: `self.init(hex: dark)` (watchOS is always dark); iOS keeps the UIKit path |
| `Design/Motion.swift:33` | `UIAccessibility.isReduceMotionEnabled` is unavailable | `#if os(watchOS)`: `WKAccessibilityIsReduceMotionEnabled()` (`import WatchKit` under the same guard) |

Compile the whole module for watchOS instead of splitting it:

- SwiftPM can't exclude files per platform.
- A models-only module would churn every import.
- Dead stripping drops the unused client code.

Enforce the boundary at compile time instead. Mark `HostConnector` and `CloudClient` with `@available(watchOS, unavailable, message: "The watch reaches the host through the iPhone")`. `ChannelTransport.init` is internal, so `HostConnector.connect` is the only way to get one.

In the package, set `platforms: [.iOS(.v17), .watchOS(.v11)]` (`apps/ios/Kit/Package.swift:6`). Only the `CodyncKit` product is built for watchOS; `CodyncUI` and the `CodyncKit-Package` scheme stay iOS-only.

## WatchConnectivity protocol

New `CodyncKit/Models/Watch.swift`, shared by both sides; all types are `Codable & Sendable`:

- **`WatchEnvelope { version, minPeer, message }`.** `WatchCompatibility.minPhone` and `.minWatch` are the version floors. A mismatch shows *Needs update* naming the older side, as `VersionMismatch` does.
- **`WatchScope { context, computer }`.** `context` is `SharedStore.Context.id`: `"local"` or the SHA-256 digest, never the raw Clerk id.
- **`WatchMessage`:** `.request(id: UUID, WatchRequest)`, `.response(id: UUID, WatchResponse)`, `.snapshot(WatchSnapshot)`, `.chat(WatchChat)`.
- **`WatchRequest`:** `hello`, then (each with `scope`) `renew(open: String?)`, `chat(botId)`, `send(botId, text, nonce)` (also used for Resend), `respond(botId, entryId, optionId)`, `close`.
- **`WatchResponse`:** `.snapshot`, `.chat`, `.accepted`, or `.failed(WatchFailure)`; failures are `staleScope`, `noComputer`, `notFound`, `needsUpdate`, `unavailable` (the phone is in the background without time to hold the link).
- **`WatchSnapshot { scope?, computerName?, state, fresh, bots: [Bot], faces: [Bot], builtAt }`.** `faces` holds members of listed groups that aren't in `bots`, for group avatars only.
  - `WatchComputerState` is `connecting | online | offline | noAccess | notPaired | needsUpdate`, using the iPhone labels (*Connecting…*, *Offline*, *No access*, *Not paired*, *Needs update*).
  - `fresh` means the phone's link is live and caught up.
  - An unknown state or failure from a newer phone decodes as `unknown` ("Update Codync on Apple Watch"), never as *Needs update*, so additive changes need no floor bump.
- **`WatchChat { scope, botId, bot, entries: [Entry], answering: [String: String], recentNonces: [String], fresh, builtAt }`.** `recentNonces` lists the nonces of the newest 50 user messages, so a send older than `entries` is still confirmed.

Reuse `Bot` and `Entry` rather than lean copies. Their lenient decoding (`Models.swift:489`) and helpers then work on the watch unchanged: `isChat`, `isWorking`, `needsInput`, `BotExchange`, `AvatarWithStatus`, `GroupAvatar`. Builders scrub before encoding:

- **`WatchSnapshot.build`:**
  - Bots in `store.roster` order, at most 40.
  - Every member of a listed group that isn't listed goes in `faces` (`GroupAvatar` draws up to four plus "+N").
  - Clears `description`, `cwd`, `command`, `model`, `connectors` and `skills`.
  - Truncates `lastMessage` to 140 characters (the host's newest chat line, `host/src/hub.rs:168`) and `activity` to 80.
- **`WatchChat.build`:**
  - Takes `store.chat(botId)` filtered by `Entry.isChat`: user messages, final replies, permission cards and notices. No streaming text. Newest 25 only.
  - `EntryData` keeps only these fields:
    - `text` (≤ 1,500), `final`, `status`, `clientNonce`
    - `title` (≤ 300), `toolKind`, `options`, `selected`
    - `style`, `author`, `callSeconds`
    - for a bot-exchange notice (`botMessage` set): `botMessage`, `sourceBotId`, `targetBotId`, `delegationId` and `status` only; `heading` is "" and `text` is dropped, so the row still parses without the request or reply
    - attachment names
  - It drops `diffs`, `output`, `detail`, `locations`, plans and `connectionRequest`.
- **Size budget:** both builders drop items until the encoded envelope is ≤ 48 KiB, below WC's payload limit (`WCError.payloadTooLarge`): faces first, then the roster's tail; a chat's oldest entries.

| Message | Direction | WC API | Reason |
|---|---|---|---|
| `snapshot` | phone → watch | `updateApplicationContext(["e": Data])` | Latest wins and is delivered in the background; kept as `receivedApplicationContext` for cold launch |
| `request` / `response` | watch → phone | `sendMessageData(_:replyHandler:errorHandler:)` | Wakes the iPhone app in the background. The phone always answers at once, from cache or with `accepted` |
| `chat` | phone → watch | `sendMessageData(_:replyHandler: nil)` while reachable, else `transferUserInfo(["e": Data])` (cancel that bot's older outstanding transfer first) | Live while the watch app is up; otherwise queued for its next launch |

**Ordering and idempotency.**

- `snapshot` and `chat` carry full state, and every `builtAt` comes from the phone's clock. A snapshot older than the held one is dropped, whatever its scope; a newer one with another scope wipes the mirror. Only snapshots change the scope: a chat for any other scope is dropped, so one queued before a sign-out can't bring an old account back.
- A send carries the watch's `clientNonce` end to end. WC retries, phone relaunches and the host (which skips a nonce it has) therefore never double-send.

## iPhone side

**Placement.** `CodyncUI/Store/WatchBridge.swift` holds `@MainActor final class WatchBridge` and two protocols:

- `WatchLink`: context, message, transfer, reachability, installed state.
- `WatchSystem`: background assertion and `backgroundTimeRemaining`.

The bridge lives in `CodyncUI` for two reasons:

- It needs internal `BotStore` API that should not become public: holds, reading scopes and sends with a nonce.
- `CodyncUITests` already has `FakeRemote` (`Tests/CodyncUITests/StoreTests.swift:8`).

It imports neither WatchConnectivity nor UIKit. The WC delegate and system effects live in `apps/ios/App/WatchCompanion.swift`, since device hooks belong in `apps/` (file-structure dependency rules).

**Holds, generalized from voice-call retention.**

- Replace `VoiceCall`, `beginVoiceCall` and `endVoiceCall` (`BotStore.swift:103-111,276-289`) with `beginHold(_ id: UUID, botId: String?, mutesPushes:, reply:, needsInput:, settled:, end:)` and `endHold(_:) async`.
- Any hold keeps the transport across `setActive(false)`, exactly as calls do today.
- Only a hold with `mutesPushes` (a voice call) subscribes as `client=ios`, so the host holds its pushes back; other holds (the watch) keep the link without that and the host keeps pushing.
- The callbacks:
  - `reply` fires on a new final main-chat entry (today's `call.speak` condition, `:540`).
  - `needsInput` fires on entering that state (`:523`); the spoken sentence moves into `CallView`.
  - `settled` fires when a held bot stops working or needing input, but not for a transition inside the first catch-up after a transport (re)start (the cached state may be old news). An events-only restart's catch-up counts: its state came from the same transport.
  - `end` fires when the store is retired.
- `endHold` awaits the `remote.shutdown()` it triggers (fire-and-forget today, `:300`), so the close frame leaves before the app can suspend.
- Other changes in the same step:
  - Make the nonce `send` (`:685`) internal.
  - Add internal `isLive`.
  - Let `markRead` run while held: `isActive || !holds.isEmpty` (`:949`).
  - Move `currentStore` into `AccountStore`.

**Background launch.** A WC message may launch the app without connecting a scene. Then `scenePhase` never fires (`CodyncApp.swift:54`), but `AccountStore` starts active (`AccountStore.swift:54,326`). Every computer's `client=ios` stream would stay open and mute pushes until iOS kills the socket. The fix:

- Never read `UIApplication.shared.applicationState` in `AppStore.init`: the App's `@State` builds the store in `CodyncApp.init`, before UIKit has created the application, and the state would read `.active`. Instead `AppStore.init` builds the first `AccountStore` with `active: false` and marks the cloud work (device registration, account refresh) pending. The scene-phase handler's first `.active` activates the stores and runs the deferred cloud work. `switchAccount` and `startOver` only run in the foreground: they build active stores and run the cloud work at once.
- Activate `WCSession` in `AppStore.init` and re-activate in `sessionDidDeactivate`.
- Do no bridge work unless `isSupported && isPaired && isWatchAppInstalled`, checked after activation and in `sessionWatchStateDidChange`.

**Observation.** While the watch app is installed, use `withObservationTracking` over the properties the snapshot reads:

- `accounts.computers` and `accounts.selection`
- the current store's `bots`, `connection` and `hostVersion`
- `entries` and `answering` for the bot the watch has open

`onChange` hops to the main actor, waits 300 ms to coalesce changes, re-arms and rebuilds. It sends only when the payload changed, as `BotsWidgetFeed` does (`CodyncApp.swift:330`): compare the value with `builtAt` cleared, not encoded bytes (`answering` is a dictionary, and `builtAt` always differs).

**Requests.**

- The adapter decodes on the WC queue and answers with `DispatchQueue.main.sync { MainActor.assumeIsolated { bridge.answer(data) } }`, the pattern at `NotificationService.swift:74`.
- Replies never wait on the network, so the non-`Sendable` reply handler never crosses actors.
- `answer` checks versions, then checks `scope` against `accounts.storage.id` and the current computer. On a mismatch it returns `staleScope` and republishes the snapshot.

Each request:

- **`hello`:** start a link-only lease hold; reply with the snapshot.
- **`renew(open:)`:** extend the lease, and register or unregister a reading scope (`setReading`) for the open bot. A chat open on the watch is then marked read like any visible conversation.
- **`chat`:** lease plus reading scope; reply with the cached chat (`fresh: false` until caught up).
- **`send`:**
  - If the store already has `local-<nonce>`, retry it when it failed; otherwise it is a no-op.
  - If the host entry carrying the nonce exists, also a no-op.
  - Otherwise send with that nonce, start an exchange `{botId, nonce, hold}`, and reply `accepted`.
- **`respond`:**
  - Known entry: `store.respond` (already single-flight through `answering`), then start an exchange.
  - Unknown entry before catch-up: reply `accepted` and retry once caught up (≤ 20 s).
  - Otherwise `notFound`.
- **`close`:** end the lease and reading scope; exchanges continue.

**Exchanges and release.**

A final reply is forwarded only if it follows the host entry carrying the nonce (`seq` greater) and is not from an earlier turn still running (`turn` at least the message's, since a message sent to a busy bot gets the next turn number), so an earlier turn's reply or a catch-up replay is never shown as the answer. A respond exchange forwards replies with `turn` at least the card's. The exchange settles on idle only once the message's status is `sent` (the host goes idle after every turn); a respond, once the card was answered.

Forwarding:

- Send a `chat` message when the watch is reachable; if the send fails (reachability lags), fall back to the transfer.
- Otherwise send by `transferUserInfo`.
- No local notification: a watch hold's stream is not counted as a phone (`mutesPushes` false), so the host keeps pushing with its own wording and settings, and iOS mirrors the push to the watch while the phone is locked. The open watch app silences the banner for the chat on screen only while the phone is really keeping it current: app active, that chat open, phone reachable, the snapshot and the chat both fresh, and the last lease answer within 12 s. The phone answers `renew` and `chat` with `unavailable` when it could not hold the link, so the watch then keeps its cached state and the banner shows. Only voice calls mute pushes.

An exchange ends when:

- the bot settles: a send once its message started a turn (`status == "sent"`) and the bot then goes idle; an answered card on the first idle after it. A reply to a send needs `seq` and turn at or after the message; a reply to a card, its turn or later.
- the store goes unauthorized, offline or computer-offline.
- its deadline passes.

**Release invariant: the app never suspends while holding a link.**

- When the app is not active, holds start a `beginBackgroundTask`.
- Deadline = min(20 s lease or 25 s exchange, `backgroundTimeRemaining` − 5 s).
- The expiration handler releases synchronously.
- The assertion ends only after the last `endHold` returns.
- Pushes are never suppressed by a watch hold: while inactive, the events stream is subscribed without `client=ios` (the host counts only `ios` and `android`, `host/src/api/mod.rs:1022`), and the stream is resubscribed when that value changes.

**Accounts.** `AppStore.switchAccount`, `signOut` and `startOver` call `watch.bind(accounts)`. It:

- drops exchanges and leases (the retired stores' `end` callbacks fire, and nothing is posted after retirement);
- cancels outstanding transfers;
- publishes the new scope's snapshot (`scope: nil`, `notPaired` when empty).

On any scope change the watch wipes its chats, pending sends and cache.

## Watch app (`apps/watch/`)

- **`App/CodyncWatchApp.swift`** (`@main`). Entering `.active` sends `hello`, then `renew` every 10 s. Leaving sends `close`, only if a lease was granted in the last 20 s.
- **`App/WatchSessionLink.swift`.** `nonisolated` WC callbacks decode `Data` and hop with `Task { @MainActor in }`. Requests go through `withCheckedThrowingContinuation` with a 15 s timeout.
- **`App/NotificationRouting.swift`.** `didReceive` with a matching `ctx`/`computerId` opens the bot. It registers the iPhone's notification categories.
- **`Store/WatchStore.swift`.** An `@MainActor @Observable` shell over `WatchMirror`, a pure `CodyncKit` value type that is tested on iOS. `WatchMirror` handles:
  - scope reset and `builtAt` ordering;
  - optimistic `local-<nonce>` entries, replaced when the host's entry with that `clientNonce` arrives;
  - `answering`, cleared once the card stops pending or after 25 s.

  The last snapshot comes from `receivedApplicationContext`. Chats are cached in one JSON file in Caches.
- **Views:**
  - `RosterView`: a `List` of rows with `AvatarWithStatus` at 32 pt, name, two lines of `lastMessage`, and `RelativeTime.short`. The header shows the computer name and state.
  - `ChatView`: a bottom-anchored `ScrollView`; user and reply bubbles in `Palette` colors; inline `AttributedString(markdown:)`; notices including *Voice chat · mm:ss*. A working line shows `ThinkingOrb` + `activity` (or *Working…*) + a timer. A haptic plays only for a new final reply the phone pushes in the chat on screen when both the held and new chat are fresh, never for a catch-up.
  - `ApprovalCard`: headline, title (3 lines), text choices; an orb on the chosen option with the others dimmed, then an outcome line.
  - `DictationButton`: `TextFieldLink` with `mic.fill`, labelled "Dictate a message".
  - `StateView` for the states below.
- **Shared wording.** Move these into `CodyncKit/Models/ChatPresentation.swift`:
  - `PermissionCard`'s `headline`, option order, labels and `outcome` (`CodyncUI/Thread/PermissionCard.swift:17,78,83,119`);
  - `UserBubble`'s status strings (`ChatRows.swift:184`).

  iOS output stays identical, and the watch shows the same words.

| Situation | Watch shows |
|---|---|
| No snapshot yet | Avatar + "Open Codync on your iPhone" |
| `iOSDeviceNeedsUnlockAfterRebootForReachability` | "Unlock your iPhone" |
| Phone not reachable | Cached roster/chat + "iPhone not reachable · <RelativeTime>"; sends fail |
| Computer state | The iPhone labels; when `fresh` is false, "Updated <time>" is added |
| No computer / no bots / empty chat | *Not paired* + "Pair a computer in Codync on your iPhone" / *No bots yet* + "Create one in Codync on your iPhone" / "No messages yet" |
| Loading a chat | Cached entries, else `ThinkingOrb(.connecting)` + "Loading…" |
| Send states | *Sending…*, *Queued*, *Waiting for the computer to come online*, *Failed to send* + Resend (same nonce) |
| Version mismatch | *Needs update*, naming which side |

**UI conventions on watchOS.**

- Navigation, lists, toolbars, crown scrolling and text input are system-owned, and the custom chrome lives in `CodyncUI`, which doesn't build here. The watch therefore uses native `NavigationStack`, `List`, toolbar and `TextFieldLink`.
- Record this in `docs/design/ui-conventions.md` as an explicit exception, like the iOS toolbar one.
- Every other rule still applies:
  - icon-only buttons with accessibility labels;
  - text only for approval choices;
  - `Palette`, and `Motion.reduced` on visibility changes;
  - no border on filled shapes;
  - `CharacterAvatar` and `ThinkingOrb` for identity and working. They pause outside `.active`, so the always-on display shows them static.

## Project, signing and release

```yaml
options:
  deploymentTarget: { iOS: "18.0", macOS: "14.0", watchOS: "11.0" }
targets:
  iOS:
    dependencies:
      # …existing…
      - target: Watch        # XcodeGen 2.46: "Embed Watch Content" at $(CONTENTS_FOLDER_PATH)/Watch
  Watch:
    type: application
    platform: watchOS
    sources: [watch]
    dependencies:
      - package: CodyncKit
        product: CodyncKit
    settings:
      base:
        PRODUCT_NAME: CodyncWatch
        PRODUCT_BUNDLE_IDENTIFIER: com.pokai.Codync.ios.watchkitapp
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_CFBundleDisplayName: Codync
        INFOPLIST_KEY_WKCompanionAppBundleIdentifier: com.pokai.Codync.ios
        INFOPLIST_KEY_WKRunsIndependentlyOfCompanionApp: NO
        INFOPLIST_KEY_UISupportedInterfaceOrientations: "UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown"
        ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon
        ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME: AccentColor
```

- **Entitlements and background modes:** WC needs no entitlement on either side and no new `UIBackgroundModes`.
- **Versions:** `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` come from the project base settings, so the watch app's versions match the iPhone app's, as App Store validation requires.
- **Watch resources:**
  - `Resources/Assets.xcassets`: a single-size 1024 `AppIcon` (`"platform": "watchos"`, opaque, made from the iOS `image.png`) and `AccentColor`.
  - `Resources/PrivacyInfo.xcprivacy`: UserDefaults with reason CA92.1 (the linked `CodyncKit` references it).
- **Xcode Cloud:** the `iOS` archive includes the watch app. Register the App ID first, or let a local automatically-signed archive create it.
- **Submission tooling:** add `apps/watch` to `IOS_PATHS` (`tools/asc-submit.py:45`), with a case in `tools/asc-submit-test.py`.
- **App Store Connect:** needs Apple Watch screenshots (manual; not in `metadata/`) and review notes before the first version that includes the watch app. The PR's *What's New* bullets should mention it.

## Tests and validation

**`CodyncKitTests/WatchProtocolTests.swift`:**

- Envelope round trip for every case; unknown fields ignored; version floors.
- Snapshot: order, cap and scrubbing (no `cwd`, `command`, `description`).
- Chat: the filter (no thoughts, tools, plans or non-final text) and truncation.
- The 48 KiB budget with 200 long bots and 25 long entries.
- `WatchMirror`: scope reset; stale `builtAt` dropped; optimistic entry replaced by nonce; Resend keeps the nonce; `answering` cleared.
- `ChatPresentation` labels.

**`CodyncUITests/WatchBridgeTests.swift`**, with `FakeRemote`, `FakeWatchLink`, `FakeWatchSystem` and injectable durations:

- A background send holds the link and sends once, even when the request is replayed.
- A reply after the sent message (`seq` greater, `turn` not earlier) is forwarded; an older or replayed final reply is not.
- Reachable: the reply goes by message (a failed push falls back to a transfer). Unreachable: transfer. No local notification; a reply in a resync catch-up is still forwarded.
- Inactive + watch hold: the events subscription has no client; active, or a call hold: `ios`; transitions resubscribe once.
- Settling and the deadline release the link (`shutdownCount == 1`) and end the assertion after shutdown.
- Expiration releases synchronously.
- The lease expires without renewal.
- A stale scope is rejected.
- `bind` posts nothing for the old store and publishes the new scope.
- A voice call and a watch hold coexist.
- Port the voice tests (`StoreTests.swift:147,186,207,596`) to the hold API.

**Builds** (repo root, `build/dd` only):

```sh
xcodegen generate --spec apps/project.yml
xcodebuild build -project apps/Codync.xcodeproj -scheme Watch -configuration Debug -derivedDataPath build/dd -destination 'generic/platform=watchOS Simulator'
xcodebuild build -project apps/Codync.xcodeproj -scheme iOS -configuration Debug -derivedDataPath build/dd -destination 'generic/platform=iOS Simulator'
ls build/dd/Build/Products/Debug-iphonesimulator/Codync.app/Watch/CodyncWatch.app
(cd apps/ios/Kit && xcodebuild test -scheme CodyncKit-Package -destination 'platform=iOS Simulator,id=<sim-id>' -derivedDataPath ../../../build/dd)
```

**CI:**

- `kit.yml` adds `xcodebuild build -scheme CodyncKit -destination 'generic/platform=watchOS Simulator' -quiet`.
- A new path-filtered `watch.yml` (`apps/watch/**`, `apps/project.yml`, Kit) runs xcodegen and builds `Watch` with `CODE_SIGNING_ALLOWED=NO`, like `screen-macos.yml`.

**Devices:**

- The local Mac has the watchOS 27 simulator runtime installed (elsewhere: `xcodebuild -downloadPlatform watchOS`). Pair an iPhone and a watch simulator with `xcrun simctl pair`; `xcrun simctl list pairs` shows the pairs and their state.
- Background wake, reachability and notification mirroring need a physical iPhone and Apple Watch.

## Other clients and docs

**Unaffected clients:**

- Desktop and TUI reach the host directly over loopback. No host method, event, term or shared state changes; the watch is an iPhone accessory.
- Android is unaffected for the same reason.
- iOS shows no visible change: the wording extraction keeps output identical, and holds are internal.

**Docs**, each updated in the phase that changes the behavior:

- New `docs/features/watch-app.md`: behavior, protocol, holds and release, notifications, states, device checks. Link it from `docs/README.md`.
- `docs/architecture/file-structure.md`:
  - the tree, the target list and a where-to-change row;
  - a dependency rule: CodyncKit builds for watchOS, and the watch never constructs a transport.
- `docs/architecture/overview.md`: Watch ↔ iPhone in the runtime diagram, plus a client-state bullet.
- `CLAUDE.md` clients line and `AGENTS.md` structure line, one clause each. The cross-platform rule names the watch for the surfaces it has.
- `docs/design/ui-conventions.md`: a watchOS section.
- `docs/features/voice-call.md`: holds.
- `docs/design/push-and-live-activity.md`: watch holds don't suppress pushes; voice calls still do.
- `docs/reference/compatibility.md`: phone ↔ watch floors.
- `docs/guides/development.md`: target list, the build commands above, and an update to the "iOS-only package" note.

## Phases

Each phase builds, passes its tests and updates its docs before the next starts.

1. **CodyncKit for watchOS + empty target.**
   - Files: `Package.swift`, `BotActivity.swift`, `Theme.swift`, `Motion.swift`, the `@available` marks; `project.yml` (YAML above); `apps/watch/App/CodyncWatchApp.swift` (shows a `CharacterAvatar` to prove linking) and `apps/watch/Resources/`; the `kit.yml` step; the `asc-submit.py` path.
   - Validate: the Watch, iOS and Kit builds above, Kit tests pass.
2. **Protocol and wording.**
   - Files: `CodyncKit/Models/Watch.swift`, `WatchMirror.swift`, `ChatPresentation.swift`; `PermissionCard` and `UserBubble` adopt the wording.
   - Validate: `WatchProtocolTests`, Kit tests, iOS build.
3. **Holds.**
   - Files: the `BotStore` hold API, nonce send, `isLive`, `markRead`, awaited shutdown; `AccountStore.currentStore` and `active:`; `CallView`; `StoreTests`; `voice-call.md`.
   - Validate: Kit tests, including the voice regressions; iOS build.
4. **WatchBridge.**
   - Files: `CodyncUI/Store/WatchBridge.swift` and `WatchBridgeTests`.
   - Validate: Kit tests.
5. **iPhone wiring.**
   - Files: `apps/ios/App/WatchCompanion.swift` (`PhoneWatchLink`, `PhoneWatchSystem`); `CodyncApp.swift` (WC activation, `active:`, `bind` on switch, sign-out and Start over); `push-and-live-activity.md`.
   - Validate: iOS build; foreground launch and account switch behave as before.
6. **Watch app.**
   - Files: store, link, routing, views, states; `watch-app.md`, `ui-conventions.md`.
   - Validate: Watch build, paired simulators, then device acceptance (below).
7. **Embedding and fork build.** Done in the repo: the iOS target embeds `Watch`. The private ZC build overrides the watch bundle id to `com.sgnl24.codync.ios.watchkitapp` and `WKCompanionAppBundleIdentifier` to `com.sgnl24.codync.ios` (team `VUDU4KEQ99`). Not done: `watch.yml` CI. App Store screenshots, review notes and *What's New* apply only if this ever goes upstream.

## Acceptance criteria

- With the iPhone locked in a pocket and Codync not running, opening the watch app shows the current computer's roster within about 5 s. It is fresh, with the phone's order, unread dots and needs-you dots.
- A dictated message shows *Sending…*, then the host's entry. A reply that arrives within the hold appears in the open watch app with a haptic, and the phone shows the chat as read.
- With the wrist down during the hold, the host's own push arrives on the watch (mirrored by iOS); tapping it opens that chat.
- Approval: the card appears on the watch; *Allow once* shows the orb; a second tap does nothing; the card becomes *Allowed once*.
- A retried WC delivery or a repeated `send` never creates two user messages.
- Switching account or signing out on the phone replaces the watch roster and removes the old chats. A request from a stale screen fails and reloads.
- With Bluetooth off, the watch shows the cached roster and *iPhone not reachable*, and a send shows *Failed to send* + Resend. Resend after reconnecting delivers exactly once.
- Voice calls in the background still send and read replies (regression check for the hold refactor).
- Without the watch app installed, no snapshots are encoded (log check).

## Risks and open questions

| Risk or question | Plan |
|---|---|
| Background time per WC wake is undocumented; repeated `renew` wakes may not extend it | Deadlines come from `backgroundTimeRemaining`; log it on a device in phase 6. Correctness never depends on it: release falls back to the ordinary push |
| A reply in flight at release is never processed by the phone | Close before suspension (5 s margin, expiration handler, awaited shutdown). The host keeps pushing during a watch hold, so no alert is lost |
| The watch silences the banner for the open chat while the phone isn't updating it | Silenced only if the app is active, the chat is open, the phone is reachable, the snapshot and chat are fresh, and the last lease answer is under 12 s old. The phone answers `renew`/`chat` with `unavailable` when it couldn't hold the link, so none of that holds and the banner shows |
| Suppression is host-wide (the host's phone-stream count) | Only voice calls mute; a watch hold's stream isn't counted |
| A watch out of range during sign-out keeps the old roster until `applicationContext` arrives | Every request is scope-checked, so nothing reaches a retired account; the watch wipes its data on the next snapshot |
| iOS forwards notifications to the watch only while the phone is locked or asleep | Expected: an unlocked phone shows them itself |
| The `applicationContext` size limit is undocumented | 48 KiB builder budget with a test; measure on a device |
| The first submission that includes the watch app needs watch screenshots | Manual step in phase 7 |
| The 11.0 floor excludes Series 4/5 (watchOS 10) | Lower to 10.0 only if asked; no API prevents it |
