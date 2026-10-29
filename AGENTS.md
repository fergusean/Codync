# Repository Guidelines

## Structure and ownership

- `host/` owns bot/session/process logic, validation, routing, routines and memory. Clients render host state and send intents; device-only work stays on the device.
- iOS lives in `apps/ios/`, with shared Swift packages in `apps/ios/Kit/`; Android lives in `apps/android/`; the Electron desktop app lives in `apps/desktop/`; the terminal UI lives in `host/src/tui/`.
- Screen helpers live in `apps/screen-macos/` and `apps/screen-linux/`. Cloud and push Workers live in `cloud/` and `relay/`; `private-relay/` is the self-hosted relay. The website is `web/`.
- Detailed commands, naming and contribution conventions: [repository conventions](docs/reference/repository-conventions.md), [file structure](docs/architecture/file-structure.md), [development](docs/guides/development.md).

## Worktrees and shared edits

- Use a separate existing task worktree and branch for features and fixes; never implement in the shared checkout. Show `pwd` and the current branch after selecting the worktree.
- Several agents share this checkout. Before touching another agent's hunks or performing tree-wide changes, coordinate with them; if unavailable, ask the user. Preserve unrelated changes and never discard another agent's work.
- Before starting, sync with `git pull --rebase --autostash origin dev`, then push finished local commits to `origin/dev`. Preserve both sides if autostash conflicts.
- Stage only owned hunks. Inspect every worktree and archive refs, index, diffs and original file/deletion states before refresh rewrites or retirement.

## Builds, validation and deployment

- Stop the old app, host and iPhone process for the variant being rebuilt first. Follow [development](docs/guides/development.md) and [dev app isolation](docs/guides/dev-app.md).
- Apple build output belongs only in this checkout's `build/dd`; a worktree may have its own single `build/dd`. Remove obsolete Codync builds promptly.
- Edit `apps/project.yml`, then regenerate with `xcodegen generate --spec apps/project.yml`. Never edit `project.pbxproj` directly.
- Run the affected component checks from [repository conventions](docs/reference/repository-conventions.md#build-test-and-development-commands).
- After a code change, use [after-change](.agents/skills/after-change/SKILL.md): tests, three read-only reviews, verification, one editor, cleanup and real-app E2E. Reviews do not run tests unless they wrote code needing validation first.
- Drive desktop E2E through Computer Use; iPhone E2E uses an iOS Simulator through Device Hub. Android uses its emulator. Builds and tests do not replace actual-app checks.
- Full E2E applies to platform, packaging and desktop main-process changes: macOS, installed Lima `codync-linux`, and installed UTM Windows. Report unavailable VMs instead of creating them.
- After validated host/cloud/push work reaches `fergusean/consolidated`, deploy to Sean's infrastructure using [local upgrades](docs/guides/local-upgrades.md) and preserving [deployment memory](docs/reference/sean-deployment.md), except planning, investigation or review tasks.
- Preserve private configuration, account state, device keys, history and disabled automatic updates. Rehearse data migrations before cutover; binary rollback cannot undo them.

## Code and UI

- Use four spaces for Swift/Rust and two for TypeScript. Follow Swift 6 concurrency and structured async/await; avoid unchecked sendability.
- Assign an awaited result to a named local before further operations; do not chain operations from awaited expressions.
- Reuse existing code, then proven components. Keep one responsibility per file/type, short functions, enums over flags, no dead code or swallowed errors, and no new code appended past roughly 500 lines: [clean code](docs/guides/clean-code.md).
- Views/components perform no I/O or business logic. Follow [host ownership](docs/architecture/overview.md#host-owns-the-logic).
- Default to shared custom controls and animate every show/hide interaction. Filled backgrounds have no border. Native navigation, system tabs/authentication, widgets and tray menus are the documented exceptions: [UI conventions](docs/design/ui-conventions.md).
- Desktop platform differences use `window.codync.platform` or the main process; never fork a renderer view by OS.

## Client parity

- Shared UI behavior, actions, terminology and loading/empty/error/permission states change together in iOS, Android, desktop and terminal UI.
- Every iPhone or Android change also lands and is checked in the other app, including behavior, tests and docs; Apple-only/Android-only capabilities are the exception.
- Inspect every corresponding implementation. Adapt controls and keyboard input while preserving behavior; document concrete reasons for unaffected clients.
- Validate affected clients with builds/tests and UI checks, reporting checks that could not run and why.

## Versioning & releases

- Feature PRs and refresh integrations inherit upstream versions. Do not add version-only changes; separate release work on `main` owns bumps.
- Follow [compatibility](docs/reference/compatibility.md): additive changes need no floor change; removals/renames raise `minApp` and wait for the phone release.

## Commit & Pull Request Guidelines

- Use Conventional Commits with an imperative lowercase subject, no trailing period and at most 72 characters. Always add `Co-authored-by: Codex <codex@openai.com>`.
- One logical change per commit. PRs explain behavior, link issues and list validation; include UI screenshots and the template's zh-Hant/en-US What's New bullets for visible phone changes.

## Documentation

- Update affected documentation and its [user manual](docs/guides/user-docs.md) in the same change. Validate docs nonvisually; do not use mdxserve for validation or launch a browser for docs unless explicitly asked.
- Keep `AGENTS.md` and `CLAUDE.md` near 100 lines. Put reference detail in `docs/` with a pointer here; refactor excessive detail when editing either instruction file.
