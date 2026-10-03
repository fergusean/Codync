# Repository Guidelines

## Project Structure & Module Organization

- `host/`: Rust daemon, ACP integration, SQLite storage, HTTP/SSE API, and terminal UI; unit tests live alongside modules in `src/`.
- `kit/`: shared Swift package. `CodyncKit` contains models, clients, and design primitives; `CodyncUI` contains shared screens and stores. Tests and fixtures live in `kit/Tests/CodyncKitTests/`.
- `apps/`: iOS, macOS, and Linux clients, plus `screen-macos` and `screen-linux` helpers. Apple assets live in each target’s `Resources/`; widgets live in `apps/ios/Widgets/`.
- `cloud/`: Cloudflare accounts, encrypted relay, Durable Objects and D1; tests live in `cloud/test/`.
- `relay/`: Cloudflare push worker and `test/`; `web/`: website (Next.js); `packaging/`: distribution templates; `docs/`: architecture and naming guidance.

## Build, Test, and Development Commands

Run from the repository root unless a command changes directories:

- `xcodegen generate --spec apps/project.yml`: regenerate `apps/Codync.xcodeproj` after editing `apps/project.yml`; never edit `project.pbxproj` directly.
- `xcodebuild build -project apps/Codync.xcodeproj -scheme macOS -configuration Debug`: build the Mac app. Use Xcode’s `iOS` scheme to run on a simulator or device.
- `cd host && cargo build`: build the host; `cargo run -- serve` starts it in the foreground.
- `cd host && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test`: run host CI checks.
- `cd kit && swift test`: run shared Swift tests.
- `cd apps/linux && cargo test`: test Linux code; requires GTK 4, libadwaita and VTE GTK 4 development packages.
- `cd cloud && npm ci && npm test && npm run typecheck`: validate the cloud service; see `docs/guides/cloudflare-testing.md` for integration checks.
- `cd relay && npm ci && npm test && npm run typecheck`: install dependencies and validate the relay.

## Installing a new build: kill the old one first

Always stop the old Mac app, host and iPhone process before running a new build (old host = old protocol, old app = old UI); commands in [docs/guides/development.md](docs/guides/development.md#apple-apps).

## Versioning & releases

- **Every source change ships as a release.** A version bump on `main` is the only release trigger (Auto Tag → DMG, host, Linux app, Homebrew, in-app update), so any change to shipped code (`apps/`, `kit/`, `host/`, `packaging/`) bumps the version before it reaches `main`, without being asked. Docs, `web/`, `cloud/`, `relay/` and CI-only changes don't bump.
- Phone ↔ host compatibility: each side names the oldest version of the other it works with (`minApp` in the host's `hello`, `minHost` in each client). Additive changes need nothing; a rename/removal raises `minApp` in the same release, and hosts hold that release until the App Store has the iPhone app: [docs/reference/compatibility.md](docs/reference/compatibility.md)
- Pick the bump yourself: patch for fixes and small tweaks, minor for new features or protocol additions. Never bump major (stay on 2.x). One bump per merge into `main`: if `MARKETING_VERSION` is already ahead of the latest `v*` tag, leave it.
- How: set `MARKETING_VERSION` in `apps/project.yml` and `version` in `host/Cargo.toml` and `apps/linux/Cargo.toml` (+ their `Cargo.lock` entries) to the same value, run `xcodegen generate --spec apps/project.yml`, commit as `build: bump version to X.Y.Z`. Never touch `CURRENT_PROJECT_VERSION` (Xcode Cloud sets the iOS build number). When the iOS app changes, also write that version's section in `apps/ios/WhatsNew.md` (zh-Hant + en-US, what iPhone users notice); it becomes the App Store "What's New".

## Coding Style & Naming Conventions

Use four-space indentation for Swift and Rust. Follow Swift 6 strict concurrency, SwiftUI, and structured `async/await`; avoid unchecked sendability. Rust uses edition 2024, rustfmt, and Clippy; avoid `unwrap()` outside tests.

Name Swift files after their main `UpperCamelCase` type; use `snake_case.rs` and `kebab-case.ts`. Follow role suffixes such as `View`, `Row`, and `Store`. See `docs/architecture/file-structure.md` and `CLAUDE.md` for architectural conventions.

- UI controls default to the shared custom components. Explicit exception: iOS BotListView and ThreadView use native navigation/toolbar items and automatic back navigation for system Liquid Glass, as specified in `docs/design/ui-conventions.md`. The macOS menu bar also uses native `MenuBarExtra(.menu)`, menus, pickers and toggles. Keep system authentication and widget containers native. Outside these exceptions, avoid: no `Menu`/`Picker`, `.switch` toggles, `Form`/`List` styling, `confirmationDialog`/`alert`, `ProgressView`, `.sheet`/`.popover`/`.fullScreenCover`, `.toolbar`/navigation bars, `TabView`, `ContentUnavailableView`. Use `kit/Sources/CodyncUI/Controls.swift` + `Chrome.swift` (`.codyncSheet`, `ModalHeader`, `ScreenHeader`, `TabBar`, `.codyncMenu`, `.codyncDialog`, `ToggleStyle.codync`). Every tap that shows/hides something animates (`Motion`). Anything with a background fill gets no border line.

## Cross-platform UI changes

- Any UI change in any client must include the corresponding updates to all other native clients and the TUI in the same change: shared SwiftUI (`kit/Sources/CodyncUI/`), iOS (`apps/ios/`), macOS (`apps/macos/`), Linux GTK (`apps/linux/src/`), and terminal UI (`host/src/tui/`). This applies in every direction; Linux and TUI changes must also be reflected in SwiftUI.
- Keep shared features, actions, terminology, displayed information, and loading, empty, error, and permission states consistent. Adapt layout, controls, and input to each platform, including terminal keyboard interaction, while preserving the same user-facing behavior.
- Inspect every client's corresponding implementation before finishing a UI task. Implement applicable changes together; do not silently defer another client. For a platform-only change or an unsupported capability, document which clients are unaffected and the concrete reason in the change summary.
- Validate each affected client with its relevant build/tests and UI checks. Report any checks that could not run and why.

## Testing Guidelines

Use Swift Testing (`@Test`, `#expect`), Rust unit tests, and the relay’s Node assertion tests. Name tests after observable behavior. Add regression coverage for changed logic; no numeric coverage threshold is configured.

## Commit & Pull Request Guidelines

- Conventional Commits: `type(scope): subject`. Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `build`, `ci`, `chore`. Scope is the area touched: `ios`, `macos`, `kit`, `host`, `linux`, `cloud`, `relay`, `web`, `docs`.
- Subject: imperative mood ("add", not "added"), lowercase after the colon, no trailing period, at most 72 characters (aim for 50). Say what changes for the user, not which files moved.
- Body (after a blank line, wrapped at 72 columns) when the change isn't obvious from the subject: what and why, not how. Bullets are fine.
- One logical change per commit. Don't mix unrelated work, and stage only your own hunks when others have uncommitted changes in the tree.
- Breaking changes: `!` after the type/scope, or a `BREAKING CHANGE:` footer.
- English only; no `Co-Authored-By` or other AI attribution trailers (the commit-msg hook rejects them).

PRs should explain behavior changes, link relevant issues, list validation performed, and include screenshots for UI changes. Fill the template's *What's New* bullets (zh-Hant + en-US) for iOS changes users can see; they become the App Store notes when `apps/ios/WhatsNew.md` has no section for the release. Update affected documentation in the same change.

## Keeping this file short

- CLAUDE.md and AGENTS.md hold only rules an agent needs on every task. Reference material (file structure, naming tables, API details, audits, how-tos) goes in `docs/` as its own file, with a one-line pointer here.
- Whenever you edit either file, check its length: past ~100 lines, or a section past a few lines of reference detail, refactor that detail into `docs/` and leave the pointer, without being asked.
- Keep `docs/` current: update the doc in the same change that makes it stale.
