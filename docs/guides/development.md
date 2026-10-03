# Development and validation

For release updates, automatic installation and host rollback, see [updates](updates.md).

Run commands from the repository root unless a block changes directory. See [file structure](../architecture/file-structure.md) for ownership and [environment configuration](environments-and-deployment.md) before testing cloud access.

## Apple apps

Install Xcode and XcodeGen, then generate the project from its source:

```sh
xcodegen generate --spec apps/project.yml
xcodebuild build -project apps/Codync.xcodeproj -scheme macOS -configuration Debug -derivedDataPath build/dd
```

Use the `iOS` scheme with a connected device or simulator in Xcode. CLI builds accept `-destination 'platform=iOS,id=<device-id>'`. Do not edit `project.pbxproj` directly. Keep normal simulator signing: Clerk uses Keychain, and unsigned simulator builds can fail initialization with OSStatus -34018.

Before launching a newly built Mac app, quit running copies, including DerivedData copies:

```sh
osascript -e 'tell application id "com.pokai.Codync" to quit'
pkill -x Codync
open build/dd/Build/Products/Debug/Codync.app
```

`pkill` can return nonzero when no process exists.

The Mac app compares the running host's executable path and SHA-256 fingerprint
with its bundled host, so reopening a rebuild replaces the service even when the
version number has not changed. **Restart host** reinstalls the service from this
app and waits for the old host's data lock to be released. If a manually started
host still owns that data directory, installation fails instead of reporting a
successful restart; stop that host in its terminal and retry. Hosts attached using
`CODYNC_PORT` are managed manually.

On SIGTERM the host stops its bots without waiting for open SSE or WebSocket
connections. ACP adapters run in separate process groups; stopping an adapter
also kills tools and MCP servers that remain in its group. Processes that detach
into their own sessions and unrelated hosts using other data directories are
outside this cleanup.

After rebuilding the host, restart the launch agent (it runs
`build/dd/.../Codync.app/Contents/MacOS/codync-host`) and kill any `codync-host`
still running from a different path. Test hosts you start yourself must be
stopped when done:

```sh
launchctl kickstart -k gui/$(id -u)/com.pokai.codync.host
pgrep -fl codync-host
```

Install the device build and replace the old iPhone process (substitute its device ID):

```sh
xcrun devicectl device install app --device <device-id> build/dd/Build/Products/Debug-iphoneos/Codync.app
xcrun devicectl device process launch --terminate-existing --device <device-id> com.pokai.Codync.ios
```

Unlock the phone when required. Build, install, launch, and visual inspection are separate checks; report which actually completed.

## Component checks

| Component | Commands | Requirements |
| --- | --- | --- |
| Host | `cd host && cargo build` | rustup; `rust-toolchain.toml` pins the version CI uses (a Homebrew `rust` ahead of rustup on PATH ignores it) |
| Host checks | `cd host && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test` | Local agent credentials are unnecessary for unit tests |
| Shared Swift | `swift test --package-path kit` | Swift 6 / Xcode |
| Cloud | `cd cloud && npm ci && npm test && npm run typecheck` | Node and npm; CI uses Node 24 |
| Cloud integration | `cd cloud && env -u CODYNC_CLOUD npm run e2e` | Built host; see [Cloudflare testing](cloudflare-testing.md) |
| APNs relay | `cd relay && npm ci && npm test && npm run typecheck` | Separate package from `cloud/` |
| Linux desktop | `cd apps/linux && cargo test` | GTK 4 and libadwaita development packages |

### Screen helpers

Enable the repository's staged-file checks once per checkout:

```sh
git config core.hooksPath .githooks
```

When a screen helper changes, the pre-commit hook checks Linux Rust formatting
and parses the macOS Swift sources (on macOS). The platform builds remain in
GitHub Actions: Linux requires GStreamer development packages, and macOS builds
the `Screen` Xcode target against WebRTC.

Host development: `cargo run --manifest-path host/Cargo.toml -- serve`. Avoid competing with an installed host on port 19222; isolated tests should use a temporary `CODYNC_HOME` and another port. Stop test hosts when finished.

## Deploying the backend to rdev

After every validated backend update is integrated into `fergusean/consolidated`, deploy its host to `dex@100.66.59.58` (`rdev`). The remote user service runs `~/.local/bin/codync-host` on port 19222. Preserve its service configuration and `~/.codync` data.

From a clean consolidated checkout, cross-compile a static x86_64 Linux release locally with Rust, Zig and `cargo-zigbuild`, then stage it on the server:

```sh
rustup target add x86_64-unknown-linux-musl
cargo zigbuild --manifest-path host/Cargo.toml --release --locked --target x86_64-unknown-linux-musl
shasum -a 256 host/target/x86_64-unknown-linux-musl/release/codync-host
scp host/target/x86_64-unknown-linux-musl/release/codync-host dex@100.66.59.58:.local/bin/codync-host.new
ssh dex@100.66.59.58 'sha256sum ~/.local/bin/codync-host.new'
ssh dex@100.66.59.58 '~/.local/bin/codync-host.new --version'
```

Confirm the uploaded hash matches the local artifact, record the current `/health` computer identity, and check that bots and setup terminals are idle before restarting. Retain one previous binary at `~/.local/bin/codync-host.previous`. Stop `systemctl --user stop codync-host.service`, atomically replace the executable with the staged file, and start `systemctl --user start codync-host.service`. Do not reinstall the service or change its captured PATH.

Verify the service is active and `http://127.0.0.1:19222/health` reports `ok`, the expected `binaryHash` and `binaryPath`, and the same `computerId`. Check that the team MCP tool list includes `message_bot`. If startup or verification fails, stop the service, restore the previous binary, restart, and verify recovery. Report deployment separately from builds and tests.

## Visual checks

Follow [UI conventions](../design/ui-conventions.md) for native toolbar behavior and [widget design](../design/mobile-widgets.md) for previews. Widget images can be rendered with `python3 tools/render-widgets.py`; output is under `build/widget-previews/`.

Versions are defined in `apps/project.yml`; keep the host package version aligned with `MARKETING_VERSION`, then regenerate the Xcode project.

## iOS releases (Xcode Cloud)

The Xcode Cloud workflow "Release" on the product "iOS" has no automatic start condition (its own tag trigger never fired); it only runs when started through the API, and it archives the `iOS` scheme in Release with deployment preparation **TestFlight and App Store** (the "Internal Testing Only" option produces builds App Review can't take) and uploads it to App Store Connect. Xcode Cloud overrides the build number with its own counter (`CI_BUILD_NUMBER`, configured under Xcode Cloud settings → Build Number in App Store Connect), so `CURRENT_PROJECT_VERSION` in `project.yml` is only the local default. The checked-in `apps/Codync.xcodeproj` is what the cloud builds: regenerate it whenever `project.yml` changes, and commit `apps/Codync.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` whenever a package version changes (Xcode Cloud never resolves packages itself; copy `kit/Package.resolved` there if Xcode wrote it to `kit/`). Xcode Cloud reads public package repositories without any connection; its setup wizard still blocked on Sparkle (asking for a GitHub app install only its owners can grant), so the product was created with Sparkle temporarily removed from `project.yml`. The `Submit iOS` GitHub workflow (`.github/workflows/ios-submit.yml` → `tools/asc-submit.py`, secrets `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY`) runs on every `v*` tag (the tags Auto Tag pushes after a version bump on `main`). It compares the tag with the last version sent to the App Store: when nothing the iPhone app is built from changed (`apps/ios/`, `apps/shared/`, `kit/`, the package pins, or `apps/project.yml` beyond its version lines), iOS skips that release, so host- or Mac-only releases neither build nor touch a version in review. Otherwise it starts the Xcode Cloud archive on the tag, waits for that build to finish processing and submits it for App Review, released automatically after approval. The latest version wins: a version still *Waiting for Review* is pulled back, renamed and resubmitted with the new build; one already *In Review* or approved is left alone (rerun the workflow with the version once it is out). *What's New* comes from the version's `## <version>` section in `apps/ios/WhatsNew.md` (one `### <locale>` per App Store language, written in the version bump); without one, the `### zh-Hant` / `### en-US` bullets under `## What's New` in the PR template are collected from every PR merged since the last App Store version (changes pushed to `main` without a PR add nothing); with neither, empty fields get a generic "Bug fixes and improvements" line. Running `Submit iOS` by hand with a version ships it even without iPhone changes. `python3 tools/asc-submit-test.py` checks the PR parsing and the change detection. `DRY_RUN=1 tools/asc-submit.py <version>` prints the writes without making them.
