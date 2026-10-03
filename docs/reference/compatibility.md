# Client and host compatibility

Clients (iPhone, Mac window, Linux app, terminal client) and the host update on different
schedules: the iPhone app waits for App Review, Mac and standalone hosts update when their
owner (or the opt-in automatic updater) installs a release, and the Linux desktop app is
package-managed. Any client can meet an older or a newer host.

## Rule

Each side names the **oldest version of the other side** it still works with. Versions are
the ordinary release versions (`MARKETING_VERSION`, `host/Cargo.toml`, `apps/linux/Cargo.toml`,
always equal). There is no separate protocol number.

| Floor | Lives in | Meaning |
|---|---|---|
| `minApp` | `host/src/compat.rs` `MIN_APP`, sent in `hello` | The oldest client this host serves correctly. |
| `minHost` | `kit/.../Models/Compatibility.swift` `VersionMismatch.minHost`, `apps/linux/src/compat.rs` `MIN_HOST`, `host/src/compat.rs` `MIN_HOST` (terminal client) | The oldest host this client works with. |

On every (re)connect the client reads `hello` and decides, in this order:

1. its own version is below `minApp` → **Update this app**;
2. the host's `version` is below its `minHost` → **Update Codync on <computer>**;
3. otherwise it syncs normally.

Versions compare as `major.minor.patch` (a leading `v` and any `-pre`/`+build` suffix are
ignored). An unreadable version, or a `hello` without one, never blocks: an unknown is not a
reason to lock someone out. Hosts from before 2.4.0 send no `minApp`; only the client's
floor applies to them.

## What each client does

| | Apple apps (`BotStore.mismatch`) | Linux app | Terminal client |
|---|---|---|---|
| Notice | `UpdateNeededCard` above the computer's bots and in place of the composer; status reads *Needs update* | Banner over the roster | *needs update* in the status line, with the reason |
| Update this app | iPhone: App Store page. Mac: Sparkle | Opens the GitHub release page (the desktop app is package-managed) | Text only |
| Update the host | Mac: its own host is reinstalled from the app bundle. iPhone: instructions for that computer | `installHostUpdate` | `^k` → Check for updates |
| Sync while mismatched | Stopped; `hello` is asked again every 30 s and on every reconnect | Continues (JSON is read leniently); sending is blocked | Continues; sending is blocked |

Drafts are kept while sending is blocked. A host update restarts the host, so the reconnect
reads the new `hello` and the notice goes away on its own.

Apple apps also treat **undecodable data from a newer host** as *Update this app*: an event
that stays undecodable after the one rewind, or a `hello` whose other fields can't be decoded
(its `version` and `minApp` are still read). From an older or equal host it's only logged.

## Changing what crosses the wire

- **Additive changes need no floor change**: new fields (clients decode leniently; new
  Swift fields are optional), new methods, new enum values (kept as strings).
- **Renaming, removing or changing the meaning** of anything an older client reads or sends
  needs `minApp` raised, in two releases:
  1. The host sends/accepts **both** the old and the new form; clients switch to the new one.
     The iPhone part of this release must actually ship (it changes `kit/`, so `Submit iOS`
     includes it).
  2. After that iPhone version is **live on the App Store**, a later host release drops the
     old form and sets `MIN_APP` to the version from step 1.

  Raising `MIN_APP` before the App Store has that version locks every iPhone out with no
  update to install.
- **Raise `minHost`** only when the client's core flows need something older hosts lack. An
  optional feature that needs a newer host hides its control instead.
- Floors never exceed the release that ships them (unit tests in both crates check this).
- The encrypted channel's handshake version (`v` in its `hello`, rejected with
  `unsupportedVersion`, close code 4400) is separate: the host must keep accepting every `v`
  a supported client sends.

## Floor history

Update this table in the release that changes a floor, with the reason.

| Release | Host `minApp` | Client `minHost` | Why |
|---|---|---|---|
| 2.4.0 | 2.3.0 | 2.3.0 | First release with the check. Since 2.3.0 the wire only gained `minApp` and lost the unused `protocol`; 2.3.0 is the oldest pairing verified to work, and a higher floor would lock out hosts that never auto-update. |

## Trying it on a simulator

Run a throwaway Debug host (it registers with the dev cloud) on its own data and port, never
on `~/.codync` or 19222:

1. Build a copy with a patched floor or version into a separate target, then restore the
   source: e.g. `MIN_APP = "9.0.0"` in `host/src/compat.rs` (app too old) or `"version": "2.2.0"`
   in `hello` in `host/src/api/mod.rs` (host too old), built with
   `CARGO_TARGET_DIR=<scratch>/hosttarget cargo build`.
2. `CODYNC_HOME=<scratch>/home <scratch>/hosttarget/debug/codync-host serve --port 19333`.
3. Pair the simulator: `POST /api/pairing` with `<scratch>/home/token`, then launch with
   `SIMCTL_CHILD_CODYNC_PAIR_URL=<pairingUrl>`.
4. Swap hosts on the same `CODYNC_HOME` (patched ↔ normal) to see the notice appear in the
   list and in place of an open chat's composer, and go away after the reconnect.

The simulator has no App Store app, so *Update Codync* ends in a Safari error there; on a
device it opens the App Store.
