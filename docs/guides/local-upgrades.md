# Local upgrades for Sean's Codync

Use this guide when upgrading source builds on Sean's Mac and private infrastructure. The [deployment memory](../reference/sean-deployment.md) owns the current targets, identities and configuration. For published release installers, see [app and host updates](updates.md).

**Upgrade the components a feature depends on together.** A new Mac app can build and launch successfully while rdev or a Worker still serves the old protocol. The 2026-10-03 webhook failure came from an app expecting `localUrl` and `connected` while the old host returned only `url` and `key`.

## Prepare one upgrade

Run the Mac commands from the repository root, in the same terminal session. Use the intended, validated revision of `fergusean/consolidated` for backend deployments. Inspect existing edits before changing branches or regenerating the Xcode project; retain unrelated work.

```sh
git status --short
git branch --show-current
git rev-parse HEAD
export CODYNC_UPGRADE_DIR="$HOME/.config/codync-cloudflare/upgrades/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$CODYNC_UPGRADE_DIR"
chmod 700 "$CODYNC_UPGRADE_DIR"
git rev-parse HEAD > "$CODYNC_UPGRADE_DIR/source-commit.txt"
git status --short > "$CODYNC_UPGRADE_DIR/source-status.txt"
```

Keep backups, deployment records and any credential-bearing files in this private directory. Never print tokens, webhook keys, APNs credentials or private configuration secrets.

Choose the affected components and run their [development checks](development.md#component-checks) before deployment. Use `xcodebuild test -scheme CodyncKit-Package` from `apps/ios/Kit/` for Swift checks on this Mac: the unqualified `swift` command can resolve to an unrelated OpenStack CLI. Rust commands should use rustup's Cargo, available as `$HOME/.cargo/bin/cargo`, so the repository's pinned toolchain applies.

Branch refreshes inherit upstream's current app and host versions. Keep feature PRs free of version-only changes; version bumps belong to separate release work on `main`, following [repository versioning](../../AGENTS.md#versioning--releases). Documentation-only changes do not bump versions.

Build and stage artifacts before interrupting work. Capture identity and webhook-key hashes before cutover when verifying their preservation. For a feature spanning components, the usual deployment order is:

1. Apply required cloud schema/binding additions and deploy the changed Workers.
2. Upgrade the idle rdev host and verify its identity, executable and cloud connection.
3. Install and relaunch the Mac app; update the private iPhone build if affected.
4. Verify the feature through its actual host and transport.

Review migrations and protocol changes when choosing the order. Worker rollback and binary rollback do not undo data migrations.

## Refresh feature branches and consolidated

Fetch both remotes and inspect each feature's live PR, local head and fork head. A clean remote branch can contain newer reviewed work than a dirty local worktree; a local branch can also contain newer committed fixes than its remote. Archive refs in a private Git bundle and save every dirty worktree's diffs, index, file bytes and hashes before rewriting branches. Preserve deleted files as deletions when checking the snapshot.

Rebase each active feature onto current upstream `dev`. Replay only that feature's own commits when its old base includes unrelated consolidated work. Reconstruct consolidated from the refreshed heads, preserving private integration behavior and fork documentation. Resolve conflicts against all affected clients and the current shared models. Generate private Apple projects in `build/dd/private-source` so existing generated-project edits remain intact.

Validate the integrated source, then publish each feature and consolidated to the fork with an explicit `--force-with-lease=refs/heads/<branch>:<fetched-sha>`. Verify that the remote heads match. Delete merged source branches only after checking for newer local work; a squash merge can require comparing the feature's files rather than ancestry. Retire empty aliases and temporary integration branches only after retaining recovery refs. Preserve dirty worktrees.

Build and deploy the coordinated components above. Confirm the installed client's host version and actual feature data after cutover. A rebuilt client connected to an older host can still show an older feature set. Check both new exchanges and saved history when a notice format changes; a passing build and a running app process do not establish either. Any required private history conversion needs a frozen plan, database backup, guarded updates and verification that original transcript fields and unrelated tables remain intact.

## Build and stage the Mac app

Since 2.7.0 the Mac app is Electron under `apps/desktop/`; the native `macOS` Xcode scheme no longer exists. iOS and the Screen helper remain Xcode targets, and the iPhone package lives at `apps/ios/Kit/`. Use [desktop development](../architecture/desktop-app.md#development) for packaging.

The installed source build is `~/Applications/Codync Local.app`, signed by Signal24 LLC. Preserve its display name, bundle identity and signing team. Translate its existing `AccountConfig.plist` into the desktop build's `resources/account-config.json`; preserve the cloud URL and Clerk public key. Do not use upstream's default developer signing identity. Keep credential-bearing backup and build records in the private upgrade directory.

Build with `npm ci`, `npm run typecheck`, `npm test` and `npm run build` in `apps/desktop/`. Packaging also needs its speech helper, Screen helper and bundled host; use the packaging scripts with an explicit environment and Signal24 signing override. Preserve the host's runtime `CODYNC_CLOUD_URL` and `CODYNC_RELAY_URL` overrides. The current source embeds `CODYNC_ENV` during compilation but reads those URL overrides only when running.

The Electron renderer currently reaches remote computers through SSH. Before replacing the installed native app, migrate and verify its saved SSH profiles, connect to rdev, and confirm chat, pairing and account behavior with the preserved configuration. Encrypted account-computer transport from the old Mac app was not ported upstream. Do not assume installing the new bundle migrates native preferences or account sessions.

Stage a signed Electron bundle in `build/dd/electron` and verify its signature and embedded Screen helper before cutover. Stop old app/host processes according to [desktop restart](development.md#desktop-app), retain a compressed private rollback copy, and launch only the verified replacement. Source integration and a successful renderer build do not establish installed-app acceptance.

## Upgrade changed Cloudflare Workers

Deploy from the private copies in `~/.config/codync-cloudflare/`. The repository's root Wrangler configurations target the publisher's infrastructure. Preserve Sean's account, Worker names, routes, D1 database, Durable Object namespaces, Clerk variables, rate-limit namespaces and existing secrets.

Back up the private sources/configuration, excluding installed dependencies and local Wrangler state:

```sh
mkdir -p "$CODYNC_UPGRADE_DIR/cloud" "$CODYNC_UPGRADE_DIR/relay"
rsync -a --exclude=node_modules --exclude=.wrangler \
    "$HOME/.config/codync-cloudflare/cloud/" "$CODYNC_UPGRADE_DIR/cloud/"
rsync -a --exclude=node_modules --exclude=.wrangler \
    "$HOME/.config/codync-cloudflare/relay/" "$CODYNC_UPGRADE_DIR/relay/"
diff -ru cloud/src "$HOME/.config/codync-cloudflare/cloud/src"
```

`diff` exits with status 1 when sources differ. Review those differences and apply the current validated source changes to the private copy, including new files and removals. Update its package manifest/lockfile, TypeScript configuration, tests and migrations when those change. Retain private source customizations; copy code deliberately rather than replacing the entire deployment directory.

Compare new upstream Wrangler requirements with the private configuration and add applicable bindings without replacing existing values. Public webhooks require `HOOK_LIMITER`; Sean's namespace is `21021`, with limit 60 per 60 seconds. Remote screen needs the existing `TURN_LIMITER` and `TURN_KEY_ID`/`TURN_KEY_API_TOKEN` secrets. Keep D1 and Durable Object migration history intact. List pending D1 migrations and apply required additive changes to Sean's existing database before code that needs them:

```sh
(
    set -e
    cd "$HOME/.config/codync-cloudflare/cloud" || exit
    export CLOUDFLARE_ACCOUNT_ID=cde4c425f3771be1762658ea8c990b09
    npm ci
    npm test
    npm run typecheck
    ./node_modules/.bin/wrangler deployments status
    ./node_modules/.bin/wrangler d1 migrations list codync --remote
    # Run only when the reviewed upgrade requires pending migrations:
    # ./node_modules/.bin/wrangler d1 migrations apply codync --remote
    ./node_modules/.bin/wrangler deploy --dry-run
)
```

Record the previous Worker version ID from deployment status. Confirm the dry-run bindings point to Sean's resources, then deploy:

```sh
(
    set -e
    cd "$HOME/.config/codync-cloudflare/cloud" || exit
    export CLOUDFLARE_ACCOUNT_ID=cde4c425f3771be1762658ea8c990b09
    ./node_modules/.bin/wrangler deploy --keep-vars
)
curl -fsS -A 'Codync/local-upgrade' \
    https://codync-cloud.signal24.workers.dev/v1/health
```

Record the new Worker version ID. The Worker health version is maintained separately from the app/host marketing version. Cloudflare can reject a default scripting User-Agent; use the explicit Codync User-Agent above for health probes.

For push changes, repeat the source comparison, dependency install, deployment-status, dry-run and `deploy --keep-vars` steps from the private `relay` directory. Validate with `relay/` tests and type checking first. Preserve its APNs topic `com.sgnl24.codync.ios`, Apple team and sandbox/production selection. Preserve `TICKET_KEY`; changing it invalidates existing phone registrations. An ordinary upgrade does not rerun secret creation from the relay's initial setup instructions.

## Upgrade the rdev host

Use the [rdev deployment procedure](development.md#deploying-the-backend-to-rdev) for the full build and verification requirements. Build the Linux artifact from the same validated source as the app:

```sh
"$HOME/.cargo/bin/rustup" target add x86_64-unknown-linux-musl
"$HOME/.cargo/bin/cargo" zigbuild --manifest-path host/Cargo.toml \
    --release --locked --target x86_64-unknown-linux-musl
shasum -a 256 host/target/x86_64-unknown-linux-musl/release/codync-host
scp host/target/x86_64-unknown-linux-musl/release/codync-host \
    dex@100.66.59.58:.local/bin/codync-host.new
ssh dex@100.66.59.58 'sha256sum ~/.local/bin/codync-host.new'
ssh dex@100.66.59.58 '~/.local/bin/codync-host.new --version'
```

Confirm the uploaded hash matches the local artifact. Record the existing `/health` response and the intended new hash/version. Check `busy` immediately before stopping the service; it covers working bots, pending input and running setup terminals. Wait for idle and avoid starting new work during cutover.

```sh
ssh dex@100.66.59.58 'bash -se' <<'REMOTE'
curl -fsS http://127.0.0.1:19222/health \
    > "$HOME/.local/bin/codync-host.health-before.json"
python3 - <<'PY'
import json
from pathlib import Path

health = json.loads((Path.home() / '.local/bin/codync-host.health-before.json').read_text())
assert health['ok'] and health.get('busy') is False, 'Host must be healthy and idle'
print('Previous version:', health['version'])
print('Computer identity:', health['computerId'])
PY
cp -p "$HOME/.local/bin/codync-host" "$HOME/.local/bin/codync-host.previous"
systemctl --user stop codync-host.service
mv -f "$HOME/.local/bin/codync-host.new" "$HOME/.local/bin/codync-host"
systemctl --user start codync-host.service
REMOTE
ssh dex@100.66.59.58 'systemctl --user is-active codync-host.service'
ssh dex@100.66.59.58 'curl -fsS http://127.0.0.1:19222/health'
ssh dex@100.66.59.58 '~/.local/bin/codync-host cloud'
```

Allow up to 30 seconds for health to respond. Require `ok: true`, the expected version/hash, `binaryPath: /home/dex/.local/bin/codync-host`, and the original `computerId` and `hostId`. Cloud status must use Sean's Worker and return registered/connected with no error. Verify the team MCP tool list still includes `message_bot`.

Preserve `~/.codync`, its identity/database, the service unit, cloud/relay override and captured PATH. Do not reinstall the service to perform a binary upgrade. If startup or verification fails, use the host rollback below immediately.

If `apps/screen-linux/` changed, upgrade the helper too, following [rdev remote-screen deployment](../reference/sean-deployment.md#remote-screen-on-rdev). Disable screen sharing, replace `~/.local/libexec/codync-screen` atomically, retain its previous executable and preserve `~/.local/bin/codync-screen` (the launcher), portal approval and private plugins. Re-enable sharing and check capture/input. A helper-only update does not need a host restart.

## Install and relaunch the Mac app

First check whether this Mac has a local host: inspect `/health` on port 19222 and `pgrep -fl codync-host`. If it does, require idle, stop its launch agent before replacing the bundle, and stop any manually started host using the same data directory. **Quit normally leaves the local host running.** Hosts attached through `CODYNC_PORT` are managed manually. See [Apple app restart details](development.md#desktop-app).

```sh
osascript -e 'tell application id "com.pokai.Codync" to quit'
pkill -x Codync || true
pgrep -fl codync-host
# If this Mac has the installed local host, stop its job before replacement:
# launchctl bootout "gui/$(id -u)/com.pokai.codync.host"
```

Confirm all old app copies have exited, including DerivedData copies, and no old local host owns the data directory. The same-volume staged rename below preserves an immediate fallback. Run it only after staging/signature verification passed and after confirming the `.displaced.app` path is unused:

```sh
(
    set -e
    test ! -e "$HOME/Applications/Codync Local.displaced.app"
    mv "$CODYNC_MAC_APP" "$HOME/Applications/Codync Local.displaced.app"
    if ! mv "$CODYNC_MAC_STAGE" "$CODYNC_MAC_APP"; then
        mv "$HOME/Applications/Codync Local.displaced.app" "$CODYNC_MAC_APP"
        exit 1
    fi
    open -n "$CODYNC_MAC_APP"
)
pgrep -x Codync
ps -p "$(pgrep -x Codync)" -o args=
codesign --verify --deep --strict "$CODYNC_MAC_APP"
```

Require one app process from `~/Applications/Codync Local.app/Contents/MacOS/Codync`. Opening the app reinstalls a bundled local host when its path/hash differs; **Restart host** retries that installation. Verify the local host's health against the installed bundle whenever this Mac runs one. Leave no test hosts or obsolete DerivedData hosts running.

For an affected private iPhone build, preserve the ZC Codync identities, app group, private account/relay configuration and any private fixes recorded in the deployment memory. Build from the refreshed private source, install with `devicectl`, and launch `com.sgnl24.codync.ios` using `--terminate-existing`. The repository's public bundle ID is different. Keep Debug sandbox APNs separate from production/TestFlight.

## Verify the feature and record completion

Opening the app and a healthy Worker endpoint are separate checks from functional acceptance. Verify the affected feature against rdev, and record builds/tests, each deployment version/hash, app path/version, preserved identity and the actual functional checks in the private upgrade directory.

For webhook upgrades:

- Query each saved webhook/event routine using `routineWebhook` with `rotate: false`. Require a string `localUrl`, a boolean `connected`, the existing nonempty key and a public URL on Sean's Worker. Require `connected: true` after the host reconnects. Handle the bearer token and returned keys privately; record only key hashes if comparing before/after.
- Check that existing routine keys are unchanged. Do not rotate keys or run enabled routines merely to check an upgrade.
- A POST to a registered public webhook with an invalid key should return 401. When an existing paused webhook is available, its valid key should return 409, proving registration without executing work. Neither check establishes a provider subscription or proves a real active delivery ran.
- Reopen the webhook editor or relaunch the app to clear a cached error. If it still says the data is missing, check the serving host's live response/path/hash before rebuilding again.

For push changes, verify a real notification on the private phone build. For remote-screen changes, use the [remote-screen acceptance list](../features/remote-screen.md#verification). Report which checks actually completed.

## Roll back a failed upgrade

**rdev host:** stop the failed service, copy the retained executable beside the installed path, atomically replace it and start the existing service. Recheck health, the previous binary hash and the original identity:

```sh
ssh dex@100.66.59.58 'bash -se' <<'REMOTE'
systemctl --user stop codync-host.service
cp -p "$HOME/.local/bin/codync-host.previous" "$HOME/.local/bin/codync-host.rollback"
mv -f "$HOME/.local/bin/codync-host.rollback" "$HOME/.local/bin/codync-host"
systemctl --user start codync-host.service
REMOTE
ssh dex@100.66.59.58 'curl -fsS http://127.0.0.1:19222/health'
```

**Mac app:** quit all app copies and stop its local host as above. Move the failed bundle aside and restore `Codync Local.displaced.app` to the original installed path, then reopen and verify. The full backup in the private upgrade directory is a second recovery copy. Preserve the user's data and account state.

**Worker:** from the affected private deployment directory, set `CLOUDFLARE_ACCOUNT_ID` to Sean's account and run `./node_modules/.bin/wrangler rollback <previous-version-id>`. Use the version recorded before deployment; check health and host connectivity afterward. Restore the matching private source/configuration backup before the next deploy. This does not reverse D1 or Durable Object schema/data changes; assess those separately.

Retain the previous working artifacts until the whole upgrade passes. Do not overwrite a known good rollback binary with a failed build on a retry.
