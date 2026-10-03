# macOS activation diagnostics

The local build can record app activation, key-window changes, composer focus,
chat selection changes and menu transitions in the macOS unified log. This is
for investigating intermittent AppKit accessibility crashes, including the
October 2, 2026 `EXC_GUARD` in `_XMIGPostNotification`. It is diagnostic
instrumentation, not a confirmed crash fix.

Enable it before restarting Codync Local:

```sh
defaults write com.pokai.Codync activationDiagnostics -bool true
```

Alternatively, launch the executable with `CODYNC_ACTIVATION_DIAGNOSTICS=1`.
Stop existing Codync copies before launching another build. Logging is off by
default and records event labels, active state, window number and responder
class. It does not record message text, names, account/bot identifiers or credentials.

Read the breadcrumbs alongside the crash report:

```sh
/usr/bin/log show --last 15m --style compact \
  --predicate 'subsystem == "com.pokai.Codync" AND category == "Activation"'
```

Try switching away and back with Command-Tab while the composer has focus.
Compare an empty draft, a multiline draft and an input method with marked text;
also compare with and without an accessibility client attached. Check menus
and minimize/restore separately. Preserve VoiceOver and input-method behavior
in any subsequent workaround. The breadcrumbs do not query accessibility
elements or post accessibility notifications.

Disable logging for the next launch:

```sh
defaults delete com.pokai.Codync activationDiagnostics
```

This instrumentation is macOS-only because the observed failure is in
AppKit/HIServices. iOS, GTK and the terminal client do not use that code path.
