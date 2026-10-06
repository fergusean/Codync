<div align="center">

<img src="apps/desktop/resources/icon.svg" width="128" alt="Codync icon">

# Codync

**The open-source, 1:1 alternative to Grok Bot and Muse.**<br>
Your coding agents, as teammates you can message.

[![Download for macOS](https://img.shields.io/badge/Download-macOS-000000?style=for-the-badge&logo=apple&logoColor=white)](https://github.com/leepokai/Codync/releases/latest/download/codync-macos.dmg)
[![Download for Linux x86_64](https://img.shields.io/badge/Download-Linux_x86__64-FCC624?style=for-the-badge&logo=linux&logoColor=black)](https://github.com/leepokai/Codync/releases/latest/download/codync-linux-x86_64.AppImage)
[![Download for Linux ARM64](https://img.shields.io/badge/Download-Linux_ARM64-FCC624?style=for-the-badge&logo=linux&logoColor=black)](https://github.com/leepokai/Codync/releases/latest/download/codync-linux-arm64.AppImage)

macOS: Apple Silicon + Intel (DMG) · Linux: AppImage · [All downloads](https://github.com/leepokai/Codync/releases/latest)

[![App Store](https://img.shields.io/badge/App_Store-iOS-0D96F6?logo=apple&logoColor=white)](https://apps.apple.com/app/codync/id6760984418)
[![Homebrew](https://img.shields.io/badge/Homebrew-codync-FBB040?logo=homebrew&logoColor=white)](https://github.com/leepokai/homebrew-codync)
[![Release](https://img.shields.io/github/v/release/leepokai/Codync?color=black)](https://github.com/leepokai/Codync/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)
<br>
![iOS](https://img.shields.io/badge/iOS-18+-black?logo=apple)
![macOS](https://img.shields.io/badge/macOS-14+-black?logo=apple)
![Linux](https://img.shields.io/badge/Linux-x86__64%20%7C%20arm64-black?logo=linux&logoColor=white)
![Rust](https://img.shields.io/badge/host-Rust-B7410E?logo=rust)
![Swift](https://img.shields.io/badge/iPhone-SwiftUI-F05138?logo=swift&logoColor=white)
![Electron](https://img.shields.io/badge/desktop-Electron-47848F?logo=electron&logoColor=white)

<a href="https://youtu.be/awhZJPjJaPc"><img src="docs/screenshots/launch-film.jpg" width="760" alt="Watch the Codync launch film on YouTube (1:26)"></a>

<a href="https://apps.apple.com/app/codync/id6760984418"><img src="https://developer.apple.com/assets/elements/badges/download-on-the-app-store.svg" height="54" alt="Download on the App Store"></a>

</div>

Codync turns the coding agents on your computer — Claude Code, Codex, Cursor, Pi, OpenCode, Grok Build, Gemini, Copilot and ~40 more — into persistent *bots* you delegate to from your iPhone, Mac, Linux desktop or any terminal, the way you'd message a colleague. Pick who, say what, put the phone away. You get a notification when a bot finishes or needs your approval.

> Why bots? On a phone, "find the right working session, then pick an environment" is too slow. With bots you already know who to hand the intent to: open the chat, type, done.

## Why Codync

- **Free and open source.** No subscription, no paid tier, MIT licensed. It runs on your computer with the agents and accounts you already have.
- **Bring any coding agent.** Claude Code, Codex, Cursor, Gemini, Copilot, OpenCode, Pi, Grok Build and ~40 more — everything in the [ACP registry](https://agentclientprotocol.com/registry). Installed agents are found automatically, the rest are fetched on first use, and every bot picks its own. Mix them freely: a Claude bot can ask a Codex bot for a review.
- **Many bots, one team.** Run as many bots as you like side by side, each with its own agent, project folder and approvals, all working at once. Put several in a **group chat** and they answer in turn, like a team channel; `@name` picks who replies. Start a **thread** on any message to branch off without cluttering the main chat.
- **Built in Rust.** `codync-host` is one small, fast Rust binary for macOS and Linux (static on Linux, runs on any distro) that drives every agent, keeps the transcripts and serves every client.
- **One app per kind of device.** Native SwiftUI on the iPhone, one desktop app for Mac and Linux (Electron), and a terminal UI for SSH.
- **A 1:1 Grok Bot / Muse alternative.** Persistent named bots, group chats, reply threads, bots asking each other for help, approval cards, per-bot memory, remote screen, voice calls and "needs you / done" notifications — the same features, without being tied to one model or one subscription.
- **Reach your computer from anywhere, free.** The hosted Cloudflare relay is included at no cost: no Tailscale, no VPN, no port forwarding. Traffic is end-to-end encrypted between your phone and your computer, so the relay only forwards ciphertext. Same Wi-Fi or Tailscale? The phone connects directly instead.
- **Every computer, one app.** Sign in and your iPhone lists every computer on your account (Mac, Linux desktop, server or cloud VM); tap **Connect**, confirm a 6-digit code on that computer, and its bots show up next to the others. A new bot can live on any of them, and the Mac app reaches your SSH machines too. Each computer approves each device itself, so an account alone never unlocks a computer.
- **Remote screen.** See and control your computer from the iPhone over WebRTC (hardware H.264), and let bots use the screen themselves through the built-in `computer` tool.
- **Voice calls.** Talk to a bot hands-free from the iPhone and hear its replies read aloud.
- **Private push.** Notification text is sealed to your phone's key, so the push relay never sees what your bots said.

## Platforms

| | Platform | App | Highlights |
|---|---|---|---|
| 📱 | **iPhone** | Native SwiftUI | Chat with bots, approvals, push notifications, Live Activity, widgets, remote screen, voice calls |
| 💻 | **macOS** | Desktop app (Electron), menu bar + window | Runs the host, chat window, usage in the menu bar, iPhone pairing |
| 🐧 | **Linux** | Desktop app (Electron), tray + window | Chat window, iPhone pairing; the host runs on desktops and headless servers |
| ⌨️ | **Terminal** | `codync-host tui` | Message your bots from any terminal, over SSH too |

The host (`codync-host`, Rust) runs on macOS and Linux; every client talks to it, so all your bots and chats are the same everywhere.

**Looking for the former native Mac app (SwiftUI + AppKit)?** Version 2.7.0 moved Mac and
Linux to one Electron desktop app. The native implementation is preserved in Git history;
see the [changelog and source lookup commands](#changelog) and the
[archived AppKit chat implementation notes](docs/archive/macos-chat-swiftui.md).

## How it works

```text
Remote Apple client ⇄ encrypted channel (direct or Cloudflare cloud/) ⇄ codync-host ⇄ ACP agent
Local / SSH client  ⇄ loopback HTTP + SSE                             ⇄ codync-host
Host notifications → APNs worker (relay/) → iPhone
```

- **Bots** have a name, a character avatar, standing instructions, an agent backend, a project folder and a permission policy. Each bot has **a persistent main chat and optional reply threads**; the agent sessions underneath are an implementation detail (resumed with `session/load`, restarted with *New session*).
- **Group chats** hold several bots and you. Each message starts a short room turn: the bots you @-mention (or everyone) reply one at a time, each in its own session, and can react to each other, up to 3 rounds. See [group chats and threads](docs/features/groups-and-threads.md).
- **Bots can ask each other for help.** Tell one “ask Reviewer to check these changes.” The built-in `team` MCP server lets it discover your other visible bots and wait for a reply. Requests appear in both chats; each recipient keeps its own agent, folder and approvals. Native subagents stay under the coding agent's control. See [bot collaboration](docs/features/bot-collaboration.md).
- **The chat only shows what matters**: your messages, each turn's final reply, approval cards and notices. Every tool call, diff, plan and thought is one tap away in *Full conversation*. While a bot works, its row shows what it's doing right now.
- **Agents are detected automatically**: the host hydrates PATH from your login shell (plus Homebrew, ~/.local/bin, nvm, bun, volta, asdf, mise, pnpm…), finds every harness you have installed, and prefers its native ACP mode. Anything else in the official [ACP registry](https://agentclientprotocol.com/registry) can be picked too — Codync fetches it on first use (npx, uvx or a checksummed binary).
- **codync-host** (Rust, macOS + Linux) runs on your computer. It speaks the [Agent Client Protocol](https://agentclientprotocol.com) to each agent, keeps transcripts in SQLite, and serves the phone. Every change carries a global `rev`, so the phone reconnects with `since: rev` and never misses anything.
- **Usage limits** come from your local installs, with no extra login: Claude via `claude -p /usage` (a local command, no model call) plus Claude Code's status line, Codex via `~/.codex/sessions`. The phone, widgets and menu bar only see percentages.
- **Push** goes through a tiny relay that holds the APNs key. The phone trades its device token for an encrypted ticket; the host only ever holds tickets.

UI patterns (roster, character avatars, approval cards, trace sheet, "needs you / done" notifications) follow Grok Bot.

## Install

**One line** (macOS or Linux):

```bash
curl -fsSL https://raw.githubusercontent.com/leepokai/Codync/main/packaging/install.sh | sh
```

On a Mac it installs the app (the host ships inside it); on Linux the host, plus the desktop app when a display is present. Add `sh -s -- --host-only` for servers and headless Macs. Re-run it to upgrade.

**Mac** — [download codync-macos.dmg](https://github.com/leepokai/Codync/releases/latest/download/codync-macos.dmg) and drag it to Applications, or:

```bash
brew install --cask leepokai/codync/codync
```

All three install the same signed, notarized app.

Open Codync: it sets up the host on first launch. **Open Codync** opens the full window; **Pair iPhone…** shows the QR code.

**iPhone** — [get Codync on the App Store](https://apps.apple.com/app/codync/id6760984418) (iOS 18+, free), then scan the QR code from **Pair iPhone…** on the Mac, the Linux app or `codync-host pair`.

> **Why the iPhone app can be a version behind:** every iPhone release goes through Apple's App Review first, which usually takes a day or more, so the App Store version can trail the Mac, Linux and host releases. Your computer accounts for that: an update that needs a newer iPhone app waits until that version is in the App Store, then installs, and the iPhone app shows a reminder when a newer version is available.

**Linux** — the host, plus the desktop app:

```bash
curl -fsSL https://raw.githubusercontent.com/leepokai/Codync/main/packaging/install.sh | sh
# or: brew install leepokai/codync/codync-host
codync-host install   # systemd --user service
codync-host pair      # QR code in the terminal, or Settings in the app
```

The script also installs the desktop app (`codync`, an AppImage, with its `.desktop` file) when you're in a graphical session. By hand: `codync-linux-<arch>.AppImage`, `.deb` or `.tar.gz` from [Releases](https://github.com/leepokai/Codync/releases/latest).

Building the desktop app yourself: `cd apps/desktop && npm ci && npm run dev`.

**Linux server / cloud VM** — the host runs headless on any distro (static binary, x86_64 + arm64). Setup, remote access and limitations: [docs/guides/linux-servers.md](docs/guides/linux-servers.md).

**Remote access** works out of the box through the free Cloudflare relay, end-to-end encrypted; the phone switches to a direct connection on the same network or over Tailscale. Details: [remote relay](docs/reference/remote-relay.md).

**Agents** — install and sign in to whichever you use; Codync finds them. Claude Code, Codex and Pi run through their ACP adapters (fetched by `npx`, so Node.js is needed for those).

`codync-host install` also routes Claude Code's status line through `codync-host statusline` so live limits reach the host; an existing status line keeps working (it's wrapped, and restored on `uninstall`).

## `codync-host`

| Command | |
|---|---|
| `codync-host install` / `uninstall` | background service (launchd on macOS, systemd `--user` on Linux) |
| `codync-host tui [--url … --token …]` | message your bots from a terminal (this computer by default; SSH-friendly) |
| `codync-host pair [--json]` | pairing QR code / link |
| `codync-host status` | installed? running? |
| `codync-host serve [--port 19222]` | run in the foreground |
| `codync-host statusline [-- <your command>]` | Claude Code status line command (wraps yours) |
| `codync-host reset-token` | rotate the local loopback bearer token |
| `codync-host cloud` | cloud enablement, URL and status |
| `codync-host devices` | list/revoke authorized remote devices |
| `codync-host access` | review device access requests |

Data lives in `~/.codync`. The local bearer token authorizes loopback helpers and SSH-forwarded callers. Remote devices use individual keys and grants over the encrypted channel; revoke a lost device through device management rather than rotating the loopback token. Host methods and caller permissions live in `host/src/api/`.

## Repository

| Path | |
|---|---|
| `host/` | `codync-host` — Rust daemon: ACP client, SQLite transcript, HTTP/SSE API, push, usage |
| `apps/ios/` | iOS app: pairing, roster, push, Live Activity glue |
| `apps/ios/Kit/` | The iPhone app's Swift package: `CodyncKit` (wire models, host client, theme, avatars; shared with the widgets) and `CodyncUI` (store + screens) |
| `apps/ios/Widgets/` | Bots, usage and per-provider usage widgets + bot Live Activity |
| `apps/desktop/` | Desktop app for macOS and Linux (Electron, React, TypeScript): menu bar/tray + chat window; installs/monitors the host |
| `cloud/` | Cloudflare accounts, D1, encrypted channel relay and offline mailbox |
| `apps/shared/Config/` | Environment configuration (Clerk key, cloud URL) for the iPhone and desktop apps |
| `apps/screen-macos/`, `apps/screen-linux/` | Platform screen capture/input helpers |
| `docs/` | [Documentation index](docs/README.md) and [file structure](docs/architecture/file-structure.md) |
| `relay/` | Cloudflare Worker APNs relay with encrypted per-device tickets |
| `web/` | Website (Next.js static export, deployed on Vercel) |
| `packaging/` | Homebrew cask and formula templates (published to `leepokai/homebrew-codync` on release) and `install.sh` (the curl installer) |

Build, install, restart and full checks: [development guide](docs/guides/development.md).

The Xcode project is generated: `xcodegen generate --spec apps/project.yml`.

```bash
cd host && cargo test            # host
cd apps/desktop && npm test      # desktop app (also: npm run typecheck)
# iPhone package: xcodebuild test -scheme CodyncKit-Package (apps/ios/Kit, iOS Simulator)
cd cloud && npm test            # cloud API / channel relay
cd relay && npm test             # relay tickets
```

## Changelog

### 2.7.0 — one desktop app for Mac and Linux

The SwiftUI Mac app and the GTK 4 Linux app are replaced by a single desktop app in
`apps/desktop/` (Electron). It looks and works like the Mac app it replaces: the same roster,
chat, inspector, marketplace, settings, menu bar, pairing, SSH computers, voice calls and
remote screen helper. Why: the native chat had become too slow to scroll and stream long
transcripts, and keeping two desktop apps in step took more than one person can maintain.
The iPhone app stays native SwiftUI; its shared package moved into `apps/ios/Kit/`.
Details: [desktop app](docs/architecture/desktop-app.md).

**Native Mac source (SwiftUI + AppKit).** The last native revision is `1055636`, the parent
of migration commit `41135ac`. It includes `apps/macos/` and the Mac code paths in `kit/`.
The chat used `MacChatList`, an AppKit `NSScrollView` hosting SwiftUI message rows;
see the [archived implementation notes](docs/archive/macos-chat-swiftui.md).

```bash
git show 1055636:apps/macos/Views/ChatWindow.swift
git worktree add ../codync-swiftui 1055636  # a checkout of the whole native-era repo
```

For release-by-release changes, see [GitHub Releases](https://github.com/leepokai/Codync/releases).

## Versioning

The major version is the phone ↔ host protocol: apps work with hosts of the same major version.

## License

MIT
