# App and host updates

A client and a host on versions that can't work together say which one to update:
[Client and host compatibility](../reference/compatibility.md).

## macOS

Release builds use Sparkle 2.10.0. Open **Settings → Updates** in the menu bar
to check for a release, enable scheduled checks, or opt into automatic downloads
and installation. The chat window's account menu has the same **Check for
updates** action. Debug builds disable the production updater.

The app checks daily. Automatic installation waits until the app is inactive,
there has been no keyboard/mouse input for ten minutes, and the local host reports
no working bots, pending input, or running setup terminals. A staged update can
also install when quitting. Manual installation can interrupt work.

Before replacement, Codync unregisters its screen helper and stops the host,
waiting for its data lock to be released. If cleanup fails, installation pauses
with a retry action. A persistent restart marker makes the next app launch
reinstall the host service from the new bundle; remote screen registration is
restored when the host reports that it is enabled. Ordinary Quit keeps the host
running when no update is staged.

Sparkle verifies Ed25519 signatures before extraction. Its standard installer
handles app replacement and relaunch. Appcast URLs identify an immutable release
archive; the feed itself is served from the latest GitHub release. macOS build
versions follow the marketing version; the iOS build counter is independent.

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

The Linux app exposes these controls under **Computers & devices → Host updates**
(also reached from the account menu's **Check for updates**); the TUI's action
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

Bundled hosts are updated with the Mac app. Homebrew hosts use
`brew upgrade leepokai/codync/codync-host`, followed by `codync-host install`.
Development builds must be rebuilt. The independent updater refuses to overwrite
those installations. The Linux desktop executable remains package-managed or
manually installed; its update controls update the host.

## Release configuration

Both workflows run for a `vMAJOR.MINOR.PATCH` tag. Keep the host Cargo version and
the app marketing version aligned before tagging. The host workflow refuses a
tag/version mismatch. Existing clients without this updater need one manual
upgrade to adopt it.

Public keys are committed in `packaging/updates/`. Keep their corresponding
private keys backed up securely; replacing a public key breaks updates for
already distributed clients. Repository Actions secrets:

| Secret | Contents |
| --- | --- |
| `SPARKLE_PRIVATE_KEY` | Sparkle's base64 Ed25519 private key export |
| `HOST_UPDATE_SIGNING_KEY` | Separate base64 32-byte Ed25519 seed for host manifests |

The Mac workflow signs/notarizes the app, builds its DMG, generates `appcast.xml`,
then verifies the actual DMG signature and metadata before upload. The host
workflow emits `codync-host-<platform>.update.json` and `.update.json.sig` alongside
each archive. Signing fails if a private key does not match the committed public
key. Never commit private keys or put them in command-line arguments.

A published release must include these assets before clients can update. A
missing feed or manifest is reported as a check failure; no unsigned fallback is
used. Building locally or configuring secrets does not publish a release.

## References

- [Sparkle publishing documentation](https://sparkle-project.org/documentation/publishing/)
- [Sparkle gentle reminders](https://sparkle-project.org/documentation/gentle-reminders/)
- [Grok Bot's safe relaunch gate](https://github.com/b-nnett/grok-bot-0.18-reconstructed/blob/main/source/electron-main/update/safe-relaunch-gate.ts)

Grok Bot supplied the reference for opt-in, staged updates and idle-gated restart.
Codync uses app inactivity and input idle time; it does not require a locked screen.
