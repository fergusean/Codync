# File attachments

Photos and files sent with a message, from the iPhone and the desktop app.

- Composer: the **+** button left of the message box. iPhone: a menu with Photos (sent as JPEG) and
  Files (plus Paste when the clipboard has an image); desktop: an open panel. Files and images can
  also be dropped on the box or pasted: ⌘V / Ctrl+V on the desktop takes a copied image or file
  (`apps/desktop/src/renderer/views/thread/Composer.tsx`), and on the
  iPhone a "Paste image" chip appears above the box while typing after an image was copied.
  HEIC/TIFF/BMP become JPEG (`OutgoingFile.prepared`, `Thread/Attachments.swift`). Picked files show as chips above
  the text and can be removed; a message can be files only. Not in group chats (a group has no
  folder of its own). Up to 100 MB per file.
- Upload: `upload {botId, uploadId, name, offset, data (base64), done}`, 384 KiB per call so each
  stays under the channel's 1 MiB message limit. A repeated chunk is accepted (retry); anything out
  of order is refused. Then `send {…, attachments: [uploadId]}`; the text may be empty.
- Storage (`host/src/chat/uploads.rs`): `<workspace>/uploads/<uploadId>/<name>` for a personal
  workspace (so reading needs no extra approval), otherwise
  `~/.codync/bots/<bot>/uploads/<uploadId>/<name>`. Only UUID ids and plain file names are accepted.
- The agent gets the message text plus an "Attached files" list of absolute paths and reads them with
  its own tools (Claude and Codex read images too). The entry keeps `attachments: [{id, name, size}]`
  for display.
- Files don't go through the relay mailbox: while the computer is offline, a message with files
  waits in the composer's send button (disabled) instead. A failed send keeps its files on the device
  for Resend until the app quits.
- Chat: images show as pictures (tap for full size), other files as cards. Other devices fetch a
  sent file with `readUpload {botId, uploadId, offset}` (384 KiB chunks) and cache it; the sender
  caches its own copy at upload.
- Terminal UI: dragging a file onto the terminal pastes its path, which becomes an attachment (⌫ on
  an empty draft removes the last one); sent files show as `▤ name size` lines. It uploads with the
  same `upload` chunks.
- A files-only message previews in the roster as its file names.
