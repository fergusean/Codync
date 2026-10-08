# Bot file download implementation plan

Status: implemented, validated, integrated into local consolidated, and deployed to Sean's rdev, Mac and iPhone on 2026-10-08. Implementation baseline: upstream [`leepokai/Codync` main at `c7790a45c264268a72f7d09dc6d785fdb68cbe98`](https://github.com/leepokai/Codync/tree/c7790a45c264268a72f7d09dc6d785fdb68cbe98), fetched and inspected for this revision. Feature branch: `fergusean/file-downloads`; upstream [PR #79](https://github.com/leepokai/Codync/pull/79) targets upstream main. The implementation has no dependency on the consolidated fork.

Let a bot explicitly share a local file into its own chat or a reply thread, and let the reader save the original bytes from iPhone, desktop, or terminal. The supplied Grok reference defines the card arrangement: file-type icon, filename, size, and download action between the bot's messages. The feature supports every regular-file format, including documents, spreadsheets, archives, images, audio, video, arbitrary binary data, and files with unknown or absent extensions.

## Scope and boundaries

| Area | Scope |
|---|---|
| Bot action | One new `send_file` tool in the existing built-in chat MCP server. One call publishes one file card. |
| Conversations | The bot's own chat and its reply threads, matching upstream's existing `send_message` boundary. |
| Clients | iPhone, the shared macOS/Linux/Windows desktop app, and terminal UI. |
| Contents | Byte-for-byte downloads of any regular file, from zero bytes through the existing 100 MiB limit. No extension or MIME allowlist and no transcoding. |
| Storage | A persistent host snapshot independent of the original file and the bot's working directory. |
| Transport | One new authenticated chunk-read method over existing local, SSH, and encrypted direct/relay connections. |
| UI | File card, download progress, Cancel, Retry, and the platform's save/export action. |

Android and Watch are excluded from this spec and will be backfilled separately. Group conversations, routine outputs, and bot-to-bot delivery are also excluded: upstream handles their completion differently, and this feature does not change those contracts. `send_file` rejects those turns using the same eligibility rules as `send_message`.

General file viewers, new image previews, automatic opening, folder packaging, workspace browsing, inferred attachments from Markdown/ACP links, background downloads, persistent transfer resume, and a new file-management settings screen are outside this change. The size limit applies to all formats; directories must already be packaged as a file if the bot wants to share them.

## Upstream code to reuse

Upstream already has a built-in [chat MCP tool](../../host/src/chat/outbox.rs), [chunked uploads and reads](../../host/src/chat/uploads.rs), serializable attachment metadata, file icons and size formatting, and a terminal action that saves selected-message attachments to Downloads. The iPhone and desktop currently render attachments only on user rows, and their existing readers collect the full file in memory.

Reuse those presentation helpers, API transports, caller permissions, and message ordering. Keep the existing inbound attachment flow intact. Add focused output-file storage and streaming download code rather than turning this feature into a rewrite of uploads, caches, context management, or turn completion.

## Bot publication contract

Add `send_file` alongside `send_message`:

```json
{
  "name": "send_file",
  "arguments": {
    "path": "/absolute/path/report.pdf",
    "name": "report.pdf"
  }
}
```

`path` is required. `name` is optional and defaults to the source basename. Relative paths resolve against the publishing bot's configured working directory; explicit absolute paths are allowed because upstream treats the working directory as an execution default, not a sandbox. The actor supplies the current lane, author, and turn. The tool cannot select another conversation.

The publication sequence is:

1. Require an eligible running turn and capture its identity and lane.
2. Resolve and open a readable regular file. A symlink may resolve to a regular file; directories, devices, FIFOs, and sockets are rejected without blocking on special-file reads. Check the opened source, not just an earlier path lookup.
3. Copy into host storage with bounded buffers, enforce the 100 MiB cap during copying, and compute SHA-256 over the stored bytes. Reject detected source changes or read failures before publishing.
4. Flush the completed snapshot to disk and finish it atomically, recheck the original turn, and persist the final agent entry through the existing hub revision/sequence mechanism. Never publish a reference to a partial file.
5. Return the entry ID and file metadata after publication succeeds. Clean up the snapshot if entry insertion fails. A timeout, Stop, or ended turn must prevent an abandoned copy from publishing into a later turn.

Keep copying off the actor's event loop so Stop and other events remain responsive. Use a file-appropriate deadline rather than the current ten-second text-delivery timeout, and propagate copy, storage, and publication errors to the bot.

Store snapshots at `<CODYNC_HOME>/bots/<bot-id>/files/<file-id>`, using host-issued UUIDs as path components. The display filename is metadata, not a storage path. Validate it as a plain filename with no separators or control characters; allow hidden filenames and extensionless files. At export, adapt the suggested name to the destination platform's filename rules without changing the contents.

Published snapshots are never overwritten by later shares. Retain them with the transcript, across host restarts, source edits/deletion, and changes to the working directory. Follow upstream's existing deletion policy: deleted bots become inaccessible while their stored files remain on disk. Remove failed staging files; do not add automatic expiry, storage quotas, or a general garbage-collection subsystem in this feature. Storage exhaustion produces a visible error.

Repeated deliberate tool calls are separate shares. Download retries only repeat read requests; they never invoke `send_file` again. If publication's acknowledgement is lost, do not automatically republish or claim the first call failed definitively.

## Transcript and compatibility

Use the existing `agent` entry with `final: true`. Add one optional `files` collection containing output-file metadata:

```json
{
  "text": "Shared file: report.pdf (1.2 MB)",
  "final": true,
  "files": [
    {
      "id": "2f2e84ea-96c7-451c-9876-22a9ab5e57ac",
      "name": "report.pdf",
      "size": 1200000,
      "sha256": "64 lowercase hexadecimal characters"
    }
  ]
}
```

The digest in the example describes its format; publication fills in the actual SHA-256. The existing entry envelope and actor supply the bot/thread, turn, sequence, and author.

`files` marks a file-card entry; `text` is its filename/size fallback for older clients, roster previews, notifications, and plain transcript readers. Updated clients render the card instead of that fallback bubble. Keep `attachments` and `readUpload` for existing user uploads. This avoids making older attachment readers mistake a generated file for an upload, and requires no new entry kind, source discriminator, or rendering flag.

Swift and TypeScript receive the same optional field and file metadata shape; the terminal reads that field too. Old clients ignore it and show the fallback text. New clients on old hosts keep their existing behavior because those hosts never produce the field. The change is additive under [upstream compatibility policy](../reference/compatibility.md); keep release numbers and compatibility floors unchanged in the feature PR.

Track file publication separately from upstream's `sent` text list. A shared file must not suppress an intended final text reply or create a duplicate fallback bubble. A files-only turn still finishes successfully, with its filename used for the completion summary. Reuse existing text delivery, reactions, thread creation, and chronological sync.

Describe the tool and its boundaries in the chat MCP instructions and description, and update newly rendered bot instructions. Existing frozen context snapshots stay frozen. If resumed sessions need a reminder, append a short file-specific availability sentence to eligible turn prompts; do not add a general instruction revision/migration mechanism. Verify tool discovery for new and resumed ACP sessions without resetting their conversations.

## Download API and storage access

Add one method, `readFile`:

```json
{
  "entryId": "entry-uuid",
  "fileId": "2f2e84ea-96c7-451c-9876-22a9ab5e57ac",
  "offset": 0
}
```

Resolve the owning bot from the stored entry. Apply existing local/device authentication and Control scope checks before reading. Require an undeleted bot, a final agent entry, and a matching file ID in that entry's `files` metadata. Derive the snapshot path from validated stored identifiers; the client never supplies a filesystem path. Refuse missing, mismatched, unpublished, or symlink-replaced snapshots.

Return base64 `data` and total `size`, matching the existing file chunk response shape. Reuse the existing 384 KiB chunk size and extract a shared constant/reader only where needed. Accept integer offsets from zero through the stored file size; reject negative or out-of-range values. At end-of-file, return an empty chunk with the correct size. Validate the on-disk file's size against the publication metadata.

Publication remains behind the local-only `chatCall` method. Remote downloads use existing channel authorization and revocation behavior. File bytes pass through the encrypted direct/relay channel; no new cloud storage, public URLs, standalone HTTP file route, push payload, or cryptographic protocol is required. Leave the existing `upload`, `send`, and `readUpload` contracts unchanged.

## Client download and card behavior

Every output format uses the same card. Reuse existing extension-based icons with a generic document fallback; icon selection never controls download eligibility. Render the card on the bot's side between its messages, using Codync's filled surfaces without borders. Truncate long filenames visually and keep the full accessible name. Use existing motion and Reduce Motion conventions.

| Client | Save action |
|---|---|
| iPhone | Tap Download, stream into a private temporary file, verify it, then present the system share/export handoff to save to Files or another app. Keep only the current verified export file and remove it when the handoff finishes. |
| Desktop | Choose a destination in the system Save As dialog, then stream into a temporary file alongside it. Commit the destination after verification; respect explicit overwrite choices and clean up partial files. |
| Terminal | Show `▤ filename size` for final agent file entries. Extend the existing message selection and `f` action to download to Downloads, choosing a non-conflicting name. Show progress, cancellation, and failures through existing terminal feedback. |

| State | Behavior |
|---|---|
| Ready | Filename, size, and Download action. |
| Downloading | Byte progress and Cancel; duplicate taps share the active transfer. |
| Complete | Confirm the save/export. Another save/export starts a fresh verified transfer. |
| Offline | Show a connection error and allow retry after reconnecting. |
| Failed | Keep the card, show the error, and offer Retry for recoverable failures. Missing snapshots show `File no longer available on the computer`. |

Transfer services own networking, filesystem I/O, progress, and cancellation; views render state and send intents. Stream with memory bounded by chunk size rather than assembling an entire file in Swift `Data` or a renderer array. Validate chunk encoding/length, expected total size, and forward progress, and verify SHA-256 before exposing the completed file. A zero-byte file is valid; premature empty chunks for a nonempty file fail.

Use one active download per account/computer/file. Retry restarts a failed transfer from zero; persistent resume is excluded. Cancel outstanding reads and remove partial files on cancellation or context retirement. Scope temporary files and caches to the account/computer, and prevent completions from a retired context from becoming visible in the new one. Keep this logic specific to downloads instead of refactoring existing inbound caches.

Desktop saving goes through a narrow main-process save-session bridge. The main process owns the chosen destination, writes bounded chunks for that session, and verifies the result before committing it. The renderer never gets a general write-by-path API. The iPhone and terminal use their corresponding file/transfer services.

## Implementation phases

### Phase 1 Host sharing

- [x] Add focused snapshot storage and `readFile` handling, reusing existing chunk constants and caller permissions.
- [x] Add `send_file` and actor routing for own-chat/thread turns, including responsive copying, cancellation, and atomic publication.
- [x] Add optional `files` metadata and file-specific tool guidance; preserve upstream text completion and reject unsupported turn types.

### Phase 2 Three client implementations

- [x] Add typed chunk reads and file-specific streaming download services for iPhone, desktop, and terminal.
- [x] Add native save/export integration, verification, progress, cancellation, retry, and context-safe temporary storage.
- [x] Render bot-side cards with shared file icons/size formatting and extend terminal selection/`f` handling.

### Phase 3 Focused validation and documentation

- [x] Add regression coverage for publication, authorized reads, file integrity, failed/cancelled transfers, and unchanged text completion.
- [x] Validate all three clients and perform product UI checks using generic sample data. Screenshots stay outside Git and are attached to the PR manually.
- [x] Complete deployment acceptance over SSH and encrypted direct/relay transfers. Host restart and interruption regression checks passed.
- [x] Update upstream file-attachment/chat documentation and required iOS PR release-note bullets; record additive wire fixtures where affected.

## Implementation entry points

| Area | Upstream files |
|---|---|
| Sharing/storage/API | `host/src/chat/outbox.rs`, `uploads.rs`, `host/src/api/`; add focused `chat/files.rs` and `api/files.rs` modules |
| Actor/tool integration | `host/src/agent/bot/mod.rs`, `updates.rs`, `turn.rs`, `session.rs`, `host/src/chat/context.rs`; keep file-specific orchestration in a sibling actor module |
| iPhone wire/client | `apps/ios/Kit/Sources/CodyncKit/Models/Models.swift`, `Client/HostClient.swift`; add a focused streaming file client |
| iPhone card/state | `apps/ios/Kit/Sources/CodyncUI/Thread/Attachments.swift`, `ChatRows.swift`, `Store/`; add focused download state and export handling |
| Desktop wire/client | `apps/desktop/src/shared/models.ts`, `src/renderer/client/host-client.ts`, `src/renderer/store/` |
| Desktop card/save | `apps/desktop/src/renderer/views/thread/Attachments.tsx`, `ChatRows.tsx`, `src/main/files.ts`, `src/shared/ipc.ts`, `src/preload/index.ts`; keep saving in a focused main-process module |
| Terminal | `host/src/tui/net.rs`, `manage/`, `view/transcript.rs`, existing message-selection actions |

Follow upstream's [clean-code rules](../guides/clean-code.md): split the affected responsibility out before adding behavior to an oversized file, and keep I/O outside view components. Changes are limited to file sharing, downloads, their UI, and directly affected tests/docs. The feature branch stays free of private deployment configuration and release version changes.

## Acceptance checks

The core scenario is: the bot creates a file, calls `send_file`, and sends another text message. Each client shows the card in sequence and saves bytes identical to the published snapshot. Repeat after the original is edited/deleted, the host restarts, the working directory changes, and a second client reconnects. Test both main chat and reply threads, including a files-only turn and a file followed by a normal final text reply.

Use representative text, PDF, spreadsheet, ZIP, image, audio/video, arbitrary binary, unknown-extension, extensionless, hidden, and empty files. Verify that output files never pass through the incoming photo-conversion path. Include multi-chunk files, exactly 100 MiB, and a file just over the cap.

Cover unsafe names, source replacement/change, special files, invalid IDs/offsets, wrong-entry reads, unpublished/missing snapshots, unauthorized or revoked connections, disk-full/write errors, Stop during copying, failed entry insertion, lost acknowledgements, truncated/corrupt chunks, duplicate taps, cancellation, retry, context retirement, and destination collisions. Confirm group/routine/delegated tool calls fail before copying, and their existing completion behavior remains unchanged.

Run host format/Clippy/tests, desktop typecheck/tests/build, and iOS package tests/app build. Verify cards and save/export states, terminal keyboard interaction, accessibility, light/dark themes, long filenames, and Reduce Motion. Apple build output remains solely in `build/dd`. Check supported old/new client-host combinations for readable fallback and unchanged core flows. Documentation validation uses non-visual local-link/Markdown checks and `git diff --check`.

## Implementation outcome

The deployed host published generic binary, hidden, empty and PDF fixtures through real ACP and chat MCP. Direct and private relay reads verified all four snapshots (600,018 bytes) against SHA-256. The installed Mac app canceled its native Save dialog cleanly, then saved a 600,000-byte binary over SSH after its source was removed; the saved digest matched. The temporary test bot and transport device were removed/revoked.

The signed Mac and private iPhone builds retain version 2.10.0, account configuration, saved computers, and the existing private identities and keychain groups. The iPhone launched with its original active account and saved bot snapshots. Physical iPhone export interaction was not exercised; its streaming, cancellation and export state were checked through package tests and simulator UI checks.

Consolidated validation passed: host formatting and Clippy, 237 unit tests (one ignored), all integration suites after one startup-timeout retry, 49 desktop tests with type checking and production build, and 196 Swift package tests. Upstream CI passed host checks on all three operating systems and desktop checks plus Linux/Windows packaging. Fork PR Mac packaging lacks signing secrets; Vercel requires upstream authorization.
