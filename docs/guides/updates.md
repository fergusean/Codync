# App and host updates

A client and a host on versions that can't work together say which one to update:
[Client and host compatibility](../reference/compatibility.md).

## Desktop app

Release builds update through electron-updater from GitHub releases
(`apps/desktop/src/main/updates.ts`). Open **Settings → Updates** in the menu bar
to check for a release, enable scheduled checks, or opt into automatic downloads
and installation. The chat window's Settings has the same **Check for updates**
action on its Updates page. Development builds (`npm run dev`) don't update.

The app checks daily (hourly while a release waits for the iPhone app, below).
Automatic installation waits until no Codync window is focused, there has been no
keyboard/mouse input for ten minutes, and the local host reports that it is idle.
A staged update also installs when quitting. Manual installation can interrupt work.
A release whose `latest-mac.yml` names a `minApp` newer than the App Store's iPhone
app waits while an iPhone is paired with this host.

Before replacement, Codync unregisters its screen helper and stops the host
(`codync-host stop`). If that fails, installation pauses with a retry action. A
persistent restart marker makes the next app launch reinstall the host service from
the new bundle; remote screen registration is restored when the host reports that
it is enabled. Ordinary Quit keeps the host running when no update is staged.

The release workflow publishes `latest-mac.yml` and `latest-linux*.yml` with the
app archives. macOS build versions follow the marketing version; the iOS build
counter is independent. Installs of the SwiftUI Mac app that preceded the desktop
app still read the Sparkle appcast; the release workflow generates one for the
desktop zip, so those installs move to the desktop app.

## Standalone hosts on Linux and macOS

```sh
codync-host update --check
codync-host update
codync-host update --status --json
codync-host update --auto on
codync-host update --auto off
```

Use `--port` for a host on a nondefault port. `--force` explicitly allows a manual
update to interrupt bots. Stop a manually launched host before replacing it.
Automatic updates require an installed background service and are off by default.
They check daily while idle; failures are reported in update status.

The TUI's action
list (`^k`) has **Check for updates**, which reports the result in the status line.
The API operations (`hostUpdateStatus`, `checkHostUpdate`, `installHostUpdate`,
`setHostAutomaticUpdates`) require a local connection, including an SSH tunnel.

Installed services schedule the updater as an independent launchd job or systemd
user unit, so stopping the daemon does not kill its updater. The CLI returns after
scheduling; inspect progress with `--status`. A lock prevents concurrent updates.
The updater verifies the manifest's Ed25519 signature, platform, stable version,
URL, archive size and SHA-256 before extracting only the host executable.

It stages beside the installed executable, keeps a rollback copy, stops the old
service, atomically replaces the binary and starts the service with its existing
configuration. Health must report the expected version, binary fingerprint and
computer identity within 30 seconds. Startup failure restores and restarts the
previous binary. Data is preserved; this is binary rollback, not database rollback
or recovery from power loss during installation.

The host bundled in the Mac desktop app is updated with the app. Homebrew hosts use
`brew upgrade leepokai/codync/codync-host`, followed by `codync-host install`.
Development builds must be rebuilt. The independent updater refuses to overwrite
those installations. The Linux desktop app uses the installed host, which updates
with the commands above, independently of the app.

## Release configuration

Both workflows (`host.yml`, `release-desktop.yml`) run for a `vMAJOR.MINOR.PATCH` tag. Keep the host Cargo version,
`apps/desktop/package.json` and the marketing version aligned before tagging; the
desktop workflow fails when the app and host versions differ. The host workflow refuses a
tag/version mismatch. Existing clients without this updater need one manual
upgrade to adopt it.

Public keys are committed in `packaging/updates/`. Keep their corresponding
private keys backed up securely; replacing a public key breaks updates for
already distributed clients. Repository Actions secrets:

| Secret | Contents |
| --- | --- |
| `SPARKLE_PRIVATE_KEY` | Sparkle's base64 Ed25519 private key export (the migration appcast) |
| `HOST_UPDATE_SIGNING_KEY` | Separate base64 32-byte Ed25519 seed for host manifests |

The desktop workflow signs and notarizes the Mac app, builds its DMG and zip, adds
`minApp` to `latest-mac.yml` and generates `appcast.xml` for the zip; it builds the
Linux AppImage, deb and tar.gz on x86_64 and arm64. The host
workflow emits `codync-host-<platform>.update.json` and `.update.json.sig` alongside
each archive. Signing fails if a private key does not match the committed public
key. Never commit private keys or put them in command-line arguments.

A published release must include these assets before clients can update. A
missing feed or manifest is reported as a check failure; no unsigned fallback is
used. Building locally or configuring secrets does not publish a release.

## References

- [electron-updater](https://www.electron.build/auto-update)
- [Sparkle publishing documentation](https://sparkle-project.org/documentation/publishing/)
- [Grok Bot's safe relaunch gate](https://github.com/b-nnett/grok-bot-0.18-reconstructed/blob/main/source/electron-main/update/safe-relaunch-gate.ts)

Grok Bot supplied the reference for opt-in, staged updates and idle-gated restart.
Codync uses window focus and input idle time; it does not require a locked screen.
