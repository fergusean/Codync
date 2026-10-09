# Codync

Bot-based remote for coding agents: persistent named bots on your computer, messaged from the iPhone (UI patterns from Grok Bot).

## Language & Syntax

- **Swift 6** strict concurrency mode — use latest Swift 6 syntax throughout
- Prefer SwiftUI lifecycle and modern APIs (`@Observable`, `@State`, `@Environment`)
- Use structured concurrency (`async/await`, `TaskGroup`) over Combine
- Use `sending`, `nonisolated`, `@MainActor` correctly per Swift 6 rules
- Avoid `@unchecked Sendable` — prefer proper `Sendable` conformance
- Desktop app (`apps/desktop/`) is Electron + React + strict TypeScript: state in `Observable` models (`src/renderer/store/`), host calls through the `window.codync` bridge (`src/shared/ipc.ts`), main-process code in `src/main/`; CI runs `npm run typecheck` and `npm test`
- Host is Rust 2024 edition and follows the `rust-skills` rules (`~/.agents/skills/rust-skills`). Lints live in `host/Cargo.toml` (`[lints]`: default groups + pedantic, `unwrap_used`); CI runs `cargo fmt --check` and `cargo clippy --all-targets -- -D warnings`
- Host conventions: no `unwrap()` outside tests (`expect("why this can't fail")` for true invariants); lock std mutexes with `LockExt::locked()` (poison-tolerant); enums, not strings, for states and modes (`BotStatus`, `Permission`, `EntryKind`, `AlertKind`); `tracing` with structured fields (`error = format!("{e:#}")` keeps the context chain); blocking fs/process work goes through `spawn_blocking`; registry JSON is untrusted (paths are validated)

## Clean code

- Keep code modular and clean on every change: reuse before writing, one responsibility per file/type, no new code appended to files past ~500 lines (split first), views/components hold no I/O or logic, short single-purpose functions, enums over flags, no dead code, no swallowed errors. Full rules: [docs/guides/clean-code.md](docs/guides/clean-code.md).

## Architecture

- Clients: iOS app (SwiftUI), desktop app for macOS, Linux and Windows (`apps/desktop/`, Electron: menu bar/tray + chat window; why and how: [docs/architecture/desktop-app.md](docs/architecture/desktop-app.md)), terminal UI (`codync-host tui`, `host/src/tui/`, ratatui; layout and state vocabulary modeled on herdr). All talk to the host API.
- iPhone Swift package (`apps/ios/Kit/`; `CodyncUI` iOS only, `CodyncKit` also builds for watchOS): `CodyncKit` (models, client, theme, avatars; also used by the widgets and the notification extension) + `CodyncUI` (`BotStore` + screens).
- UI principle: buttons an icon can express are icon-only (with tooltip / accessibility label); text only where an icon would be ambiguous (approval choices).
- UI controls default to the shared custom components. Explicit exception: iOS BotListView and ThreadView use native navigation/toolbar items and automatic back navigation for system Liquid Glass, as specified in `docs/design/ui-conventions.md`. **iOS menus are always the system ones**: tap menus through `DropdownMenu`/`ChoicePicker` (native `Menu`), long-press through `.contextActions` (native `contextMenu`). Never hand-build a dropdown on iOS: a custom overlay lands in the wrong place (sheets, scroll views, the composer's + menu). The desktop app's menu bar/tray menu is the system menu. Keep system authentication and widget containers native. On iOS `.codyncSheet` presents the system sheet (grabber, swipe down). Outside these exceptions, avoid: no `Menu`/`Picker`, `.switch` toggles, `Form`/`List` styling, `confirmationDialog`/`alert`, `ProgressView`, `.sheet`/`.popover`/`.fullScreenCover`, `.toolbar`/navigation bars, `TabView`, `ContentUnavailableView`. Use `apps/ios/Kit/Sources/CodyncUI/Controls.swift` + `Chrome.swift` (`.codyncSheet`, `ModalHeader`, `ScreenHeader`, `TabBar`, `.codyncDialog`, `ToggleStyle.codync`); on the desktop, `apps/desktop/src/renderer/components/` (`Sheet`, `Dialog`, `AnchoredMenu`, `ModalHeader`, `Controls.tsx`, `Icon` for SF Symbols). Every tap that shows/hides something animates (`Motion`). Anything with a background fill gets no border line.
- Host, sync, remote transport, screen and account implementation context: [agent architecture reference](docs/reference/agent-architecture.md).

## Cross-platform UI changes

- Any UI change in any client must include the corresponding updates to the other clients in the same change: iOS (`apps/ios/`, `apps/ios/Kit/Sources/CodyncUI/`), desktop (`apps/desktop/src/renderer/`, macOS, Linux and Windows), and terminal UI (`host/src/tui/`). This applies in every direction.
- Keep shared features, actions, terminology, displayed information, and loading, empty, error, and permission states consistent. Adapt layout, controls, and input to each platform, including terminal keyboard interaction, while preserving the same user-facing behavior.
- Inspect every client's corresponding implementation before finishing a UI task. Implement applicable changes together; do not silently defer another client. For a platform-only change or an unsupported capability, document which clients are unaffected and the concrete reason in the change summary.
- Validate each affected client with its relevant build/tests and UI checks. Report any checks that could not run and why.
- The desktop app is one codebase for macOS, Linux and Windows: platform differences go through `window.codync.platform` checks or the main process, never a fork of a view.

## Codync 1.x does not exist for us

- Ignore everything from Codync 1.x (the Claude Code hooks + CloudKit session monitor): no migration, no compatibility shims, no cleanup of its files or hooks, no keeping old workers or App Store copy alive for it. Don't mention 1.x in code, docs or release notes.
- Build only the current design; don't reintroduce hooks or CloudKit.
- Don't carry legacy along. Old names, settings, schemes, files or code paths left from earlier designs get renamed or deleted outright when you meet them, not kept "for compatibility". Put full effort into the new design.

## Installing a new build: kill the old one first

Always stop the old desktop app, host and iPhone process before running a new build (old host = old protocol, old app = old UI); commands in [docs/guides/development.md](docs/guides/development.md#desktop-app).

Keep only the latest build: in this checkout, Apple builds go to `build/dd` only (no other `-derivedDataPath`, no copies in scratchpads, `/tmp` or Xcode's DerivedData); delete any older Codync build right away, so macOS never launches a stale copy. A git worktree may keep its own single build inside that worktree.

## Project generation

- `apps/project.yml` + `xcodegen generate --spec apps/project.yml` produce `apps/Codync.xcodeproj`. Edit `project.yml`, not the pbxproj.

## App Store Upload

- Xcode Cloud archives the `iOS` scheme on every `v*` tag and uploads it to App Store Connect with its own build number, then the `Submit iOS` workflow submits it to App Review for the App Store; don't upload from the local machine or edit `CURRENT_PROJECT_VERSION` for it. Details: [development guide](docs/guides/development.md#ios-releases-xcode-cloud).

## Versioning

Release and deployment policy follows [AGENTS.md](AGENTS.md#versioning--releases);
upstream feature PRs keep their release versions unchanged.

## Layout & naming

Build targets, folder layout, file naming and shared terms: [docs/architecture/file-structure.md](docs/architecture/file-structure.md). Follow it when adding or moving files.

## Several agents share `dev`

- Several agents often work in this checkout on `dev` at once. When you start a task, name your session after its area (`/rename kit-markdown`, `host-voice`, …; ask the user if you can't rename yourself) so others can find you in `ListAgents`.
- Before touching files with someone else's uncommitted changes, or anything tree-wide (renames, `xcodegen`, version bump, `git stash`/`reset`/`checkout`), `SendMessage` the agents involved with what you'll change and wait for or answer their replies. Never discard, revert or reformat hunks that aren't yours.
- External contributors open PRs against `dev`, never `main`. Retarget a contributor PR aimed at `main` to `dev` before reviewing or merging it.
- An external PR is merged only after its author has verified it end to end locally (real host + the clients it touches, not just builds/tests) and says so in the PR's *Validation*; ask for it in the review if missing.

## Commit messages

- Conventional Commits: `type(scope): subject`. Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `build`, `ci`, `chore`. Scope is the area touched: `ios`, `desktop`, `host`, `cloud`, `relay`, `web`, `docs`.
- Subject: imperative mood ("add", not "added"), lowercase after the colon, no trailing period, at most 72 characters (aim for 50). Say what changes for the user, not which files moved.
- Body (after a blank line, wrapped at 72 columns) when the change isn't obvious from the subject: what and why, not how. Bullets are fine.
- One logical change per commit. Don't mix unrelated work, and stage only your own hunks when others have uncommitted changes in the tree.
- Breaking changes: `!` after the type/scope, or a `BREAKING CHANGE:` footer.
- English only.
- PRs: fill the template's *What's New* bullets (zh-Hant + en-US) for user-visible iOS changes; they become the App Store notes when `apps/ios/WhatsNew.md` has no section for the release.

## Keeping this file short

- CLAUDE.md and AGENTS.md hold only rules an agent needs on every task. Reference material (file structure, naming tables, API details, audits, how-tos) goes in `docs/` as its own file, with a one-line pointer here.
- Whenever you edit either file, check its length: past ~100 lines, or a section past a few lines of reference detail, refactor that detail into `docs/` and leave the pointer, without being asked.
- Keep `docs/` current: update the doc in the same change that makes it stale.
