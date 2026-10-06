# Apple Watch app

A watchOS companion (`apps/watch/`, target `Watch`) that lists the bots on the iPhone's current computer, shows a bot's recent chat, sends dictated text and answers approvals. It talks only to the iPhone app over WatchConnectivity (WC); the iPhone's `BotStore` does all host work. The watch has no device identity and never opens a transport (watchOS forbids WebSocket outside an audio session, and every Codync transport is a WebSocket). Nothing changes in the host, `cloud/` or `relay/`.

The watch app is embedded in the iOS app (`Codync.app/Watch/`); installing the iPhone app offers it to the paired watch. Design and phases: [plan](../plans/watch-app.md).

## Behavior

- **Roster.** The phone's order, one row per bot: avatar at 32 pt with the unread and needs-you dots (a group shows its members, drawn from the snapshot's `faces`), name, `RelativeTime.short`, and the iPhone row's preview: the activity (or *Needs your approval* / *Working…*) with an orb while the bot works or needs you, the last message in the danger color (or *Something went wrong*) after an error, else the last message (two lines). The header names the computer and shows the iPhone's connection label; when the phone's link isn't caught up it adds *Updated <time>*.
- **Chat.** Bottom-anchored. User messages, final replies (inline markdown), approval cards and notices (including *Voice chat · mm:ss*), as the iPhone shows them. While the bot works in this chat, a line shows the orb, its activity (or *Working…*) and a timer. A new final reply in the open chat plays a haptic.
- **Sending.** The mic button in the toolbar opens the system text input (`TextFieldLink`: dictation, Scribble or keyboard). The text shows at once as a local `local-<nonce>` entry; the host's entry with that `clientNonce` replaces it. *Failed to send* offers Resend with the same nonce, so a retry never creates a second message. Resend works for a failure on either side: if the phone had already confirmed the message and its own entry failed later, the watch asks the phone to retry that nonce and shows *Sending…* until the phone's next chat.
- **Approvals.** The card shows the headline, the title (3 lines) and text choices in the iPhone's order. After a tap the chosen option shows the orb and the others dim; a second tap does nothing. Once the card settles it shows the outcome (*Allowed once*, *Denied*, …). The orb clears after 25 s if the card never settles, or at once if the phone didn't take the answer on (the card can then be answered again).
- **Computer.** The watch follows the phone: its current account and computer. A different account or computer replaces the roster and removes cached chats, pending sends and the cache file.

## Protocol

All messages are `WatchEnvelope`s (`CodyncKit/Models/Watch.swift`) carrying the sender's version and the oldest peer version it works with.

| Message | Direction | WC API |
|---|---|---|
| `snapshot` | phone to watch | `updateApplicationContext(["e": Data])`; kept as `receivedApplicationContext` for a cold launch |
| `request` / `response` | watch to phone | `sendMessageData(_:replyHandler:errorHandler:)` (15 s timeout) |
| `chat` | phone to watch | `sendMessageData` while reachable, else `transferUserInfo(["e": Data])` |

Requests: `hello`, `renew(open:)`, `chat`, `send`, `respond`, `close`; each but `hello` carries the `WatchScope` (account context and computer) it was made against. A phone that moved on answers `staleScope`; the watch reloads with `hello`. An empty reply means the phone app is running but has nothing to serve yet. `WatchMirror` (CodyncKit, pure state) orders snapshots and chats by the phone's `builtAt`; only a snapshot changes the scope.

## Holds and release

While the watch app is in front it sends `hello` and then `renew` every 10 s; each grants the phone a short lease that keeps its link to the computer up (and the open chat marked read) even with the phone locked. Leaving the front sends `close`, but only if a lease was granted within 20 s; a lease granted by a request still in flight when the app left is closed as soon as it arrives. After a send or an approval the phone keeps the link for the reply and then releases it; the host's own pushes keep arriving throughout (a watch hold doesn't count as a phone, unlike a voice call).

## Notifications

The phone posts no alerts of its own and a watch lease doesn't mute the host's pushes: the host's push (*Task complete*, *Response needed*, *Task failed*) is mirrored to the watch by iOS while the phone is locked. The watch registers the iPhone's categories (`done`, `needsInput`, `failed`). Tapping one opens that bot when its `ctx` and `computerId` match the roster on screen, even for a bot past the snapshot's 40 (its chat is requested); a tap that launches the app waits for the first snapshot. A banner is silenced only while the app is active and that chat (same computer and bot) is open; otherwise it shows.

## States

| Situation | Watch shows |
|---|---|
| No snapshot yet | Avatar + *Open Codync on your iPhone* |
| iPhone needs unlock after reboot | *Unlock your iPhone* |
| Phone not reachable | Cached roster or chat + *iPhone not reachable · <time>*; sends fail |
| Computer state | The iPhone's label (*Connecting…*, *Connected*, *Offline*, *No access*, *Not paired*, *Needs update*); *Updated <time>* when not fresh |
| No computer / no bots / empty chat | *Not paired* + *Pair a computer in Codync on your iPhone* / *No bots yet* + *Create one in Codync on your iPhone* / *No messages yet* |
| Loading a chat | Cached entries, else orb + *Loading…*; with the phone out of reach and nothing cached, *iPhone not reachable* (or *Unlock your iPhone*) instead. A bot that no longer exists closes its chat |
| Sending | *Sending…*, *Queued*, *Waiting for the computer to come online*, *Failed to send* + Resend |
| Version mismatch | *Needs update*, naming the older side and its minimum version (a phone that can't decode a request counts as the older side) |
| Newer phone | *Update Codync on Apple Watch* |

Chats are cached as one JSON file in Caches (`watch-chats.json`, the 10 newest), shown at the next launch while the phone is out of reach. A new scope (another account or computer) deletes the file and cancels any save still waiting; a save re-checks the scope right before writing. A haptic plays only for a reply the phone pushes while the chat is open and both the held and new chat are fresh, never for a catch-up.

## Device checks

The local Mac has the watchOS 27 simulator runtime installed; pair an iPhone and a watch simulator with `xcrun simctl pair` and check `xcrun simctl list pairs`. Background wake, reachability, the lease and notification mirroring need a physical iPhone and Apple Watch. Check: roster within about 5 s with the phone locked; a dictated send then reply with haptic; a wrist-down reply as a notification that opens the chat; an approval answered once; Bluetooth off shows the cached roster, *iPhone not reachable* and a failed send whose Resend delivers once.
