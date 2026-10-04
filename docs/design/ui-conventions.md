# UI conventions

## iPhone navigation

The bot list and conversation use the system `NavigationStack` toolbar. `ToolbarItem` owns the account button, centered computer status/filter menu, and new-chat button. Computer management is inside the centered menu. The conversation copies Grok Bot: the title pill is a native `Menu` (details and the bot's actions), the only trailing button is the computer (remote screen; it pulses while this bot operates the screen), and the call button sits in the composer as a filled waveform capsule while the box is empty. Menus opened from toolbar buttons (New, the computer filter, the title) are native `Menu`s, like every iOS menu; the conversation uses the system back button. iOS supplies Liquid Glass, control sizing, grouping and interaction feedback.

The conversation keeps the native glass buttons over the chat with a soft top scroll-edge effect, so messages blur out under the header instead of running sharp behind it. Root error dialogs use an in-place, full-screen scrim so presenting them does not change the underlying glass controls' appearance.

Do not wrap those toolbar controls in `IconButtonStyle`, hand-sized rounded backgrounds or another glass effect. The account avatar is toolbar content, with the system supplying the enclosing surface. This is the explicit exception to the custom-chrome rule below.

Connecting and reconnecting are background work. `BotStore` gives initial offline reports 1 second to recover and holds drops from online (including relay "computer offline" reports) for 5 seconds. "No access" shows immediately. Automatic read acknowledgements never open an error dialog and visible conversations are acknowledged again after reconnecting. An action taken during a reconnect (back in the foreground, a new network, a drop still inside its grace) waits for the link, up to 20 seconds, instead of failing; while one waits, headers read `Connecting…` and the conversation's connection label shows a spinner. Calls that are safe to repeat (send, stop, approvals, pin, hide, mark as read, delete) are tried again when the link drops under them. Tapping while the header says Offline because the computer can't be reached starts a fresh connection attempt and waits for it, instead of failing until the backoff gets there; a computer the relay reports as off fails at once, since the relay says when it is back. A permission card shows a spinner on the chosen option, dims the others and takes no second answer until the computer has it. The desktop app's `BotStore` (`apps/desktop/src/renderer/store/bot-store.ts`) applies the same graces and 20-second wait. The TUI follows the same rule over loopback: a command the host can't be reached for is retried for up to 20 seconds behind its "Reconnecting…" indicator (the `hello` probe that explains an unreachable host still answers at once), and its permission cards spin on the chosen option. The "Something went wrong" dialog is left for a computer that is off, a refused device, the computer's own answers, and reconnects that outlast the wait. Background work (cloud refresh, device registration, automatic access requests) logs connection, busy-cloud and stale-token failures instead of showing them. The iOS app disconnects only in `.background`, not `.inactive`. Connection status lives only in headers and their menus. The roster header shows a small summary (`1 connected`, `1/2 connected`, `2 offline`, or `Connecting…`). Its computer menu supports multiple selections, Show only, All computers, reconnect, and management. At least one available computer stays selected; if saved exclusions would hide every computer, the list falls back to all. Filters persist on the device. Bots sort pinned first, then by recency. On iPhone, when more than one computer is shown, each computer gets a light heading (badge, name, connection, and "No bots yet" when empty) above its own bots; with one computer the list stays flat. Tapping a heading folds that computer's bots away (chevron; saved on the device). Long-press a heading to lift the whole section and drag it up or down; the other sections slide out of its way and the order is saved on the device on release (`AccountStore.move`; VoiceOver: Move up / Move down). The desktop app shows a flat roster. There are no connection banners. The compact desktop rail uses a computer icon for the same menu. Conversation headers show a small connection label, with the computer name available in the header menu or help text. Pull-to-refresh remains available on iPhone. Computer setup and access requests live under Manage computers.

Sources: `apps/ios/Views/BotListView.swift`, `AccountSwitcherView.swift`, `apps/ios/Kit/Sources/CodyncUI/Thread/ThreadView+iOS.swift`, `Bots/ComputerFilterHeader.swift`, `Store/ComputerSelection.swift`, `Bots/BotRow.swift`; desktop: `apps/desktop/src/renderer/views/ChatWindow.tsx`, `ComputerFilterHeader.tsx`, `BotRow.tsx`.

## Desktop menu bar (tray)

Release update controls live in the tray's Settings → Updates submenu; the chat
window's Settings has the same controls on its Updates page (**Check for updates**,
which becomes **Update to <version>** once a release is found). The TUI action list
has **Check for updates**. Download and install go through electron-updater
(`apps/desktop/src/main/updates.ts`); there is no separate update window.

The tray menu is Electron's native `Menu` (`apps/desktop/src/main/tray.ts`), the only system menu in the desktop app. Its commands, submenus, checkboxes, usage-icon radio items, separators and keyboard navigation use system styling. Do not add custom cards, hover backgrounds, icon buttons or a window-style menu panel here.

The menu retains host installation/restart, bot conversation/stop actions, approval review, usage limits, remote-screen permissions, open-at-login and quit. Pairing opens a separate titled window because its QR needs a persistent scanning surface. The chat window retains its own UI conventions.

Usage limits appear directly in the top-level menu, grouped by provider with native section headers. Show each window's percentage, reset time when available, and the provider's last update; do not hide them in submenus. The account menu's
Usage row shows no number; it opens Settings' Usage page with the same per-provider bars (`UsageLimits`).

## Account menu and Settings (desktop)

The chat window's account menu copies Grok Bot's: Usage, Get Codync for mobile, Support, Settings, then the signed-in account and Sign out. Everything else is a page of **Settings**, laid out like ChatGPT's desktop settings (references in `reference/chatgpt-settings-*.png`): a sidebar of pages under small group labels (Personal: General, Voice chat, Usage; This Mac, or This computer on Linux: Computers, Updates) and a large page title over the page's sections. Manage computers and approval badges lead to the Computers page. The iPhone keeps its Computers & settings sheet. Source: `apps/desktop/src/renderer/views/settings/SettingsView.tsx`, account menu in `apps/desktop/src/renderer/views/ChatWindow.tsx`.

## iPhone and desktop code

The iPhone and the desktop no longer share UI code. `CodyncUI` (`apps/ios/Kit/`) is the iPhone app's own package; the desktop app (`apps/desktop/src/renderer/`) is a TypeScript port of the same screens with its own `BotStore`, chat items (`views/thread/chat-items.ts`) and Markdown blocks (`markdown-blocks.ts`).

- iPhone chrome lives in whole-file platform extensions (`ThreadView+iOS.swift`: `platformBody`, `platformChrome`, `header`). The iPhone conversation renders the newest page first and earlier ones as the reader scrolls up, follows the newest message until they scroll away (`ConversationFollow+iOS.swift`, with a round "Jump to latest" button), and reveals a reply steadily as it's written (`ChatRows+iOS.swift`, `MarkdownText+iOS.swift`).
- The desktop UI rules (custom controls, no system menus except the tray, icon-only buttons, fill or border) are implemented in `apps/desktop/src/renderer/components/` (`Controls.tsx`, `Overlay.tsx`, `Icon.tsx`).
- A change to one lands in the other and the TUI in the same change; see [native client parity](#native-client-parity).

## Shared application controls

Other custom iPhone screens use `Chrome.swift` and `Controls.swift`. The desktop equivalents are in `Overlay.tsx` (`Sheet`, `Dialog`, `AnchoredMenu`, `ModalHeader`) and `Controls.tsx` (`IconButton`, `DropdownMenu`, `ChoicePicker`, `Switch`, `CardForm`, `CardSection`, `ValueRow`).

| Need | Component |
|---|---|
| Modal or full-window overlay | `.codyncSheet` (system sheet), `.codyncOverlay` |
| Header outside the native bot navigation flow | `ModalHeader`, `ScreenHeader` |
| Icon action | `IconButton` |
| Tab selection | `TabBar` |
| Menu / confirmation | `DropdownMenu` / `.contextActions` (system menus), `.codyncDialog` |
| Toggle | `ToggleStyle.codync` |
| Form-like content | `CardForm`, `CardSection` (a small bold heading over a filled, rounded group; rows split by inset hairlines; a row is `ValueRow` with an optional one-line `detail`, and its picker is an outlined `ChoicePicker`) |

Avoid adding stock `Menu`, `Picker`, `Form`/`List` styling, switch toggles, alerts, `ProgressView`, `TabView` or system sheets to these flows. System presentation APIs inside the shared chrome implementation are implementation details, not permission to bypass the components in feature screens. Native WidgetKit/ActivityKit containers and OS authentication/permission flows remain system integrations.

No scroll bars: scroll indicators never show (`.scrollIndicators(.never)` on the iPhone app's root, so every scroll view inherits it; hidden scrollbars in `apps/desktop/src/renderer/styles/theme.css`).

Anything with a background fill gets no extra drawn border. Use `Palette`, `InterfaceMetrics` and `Motion`; visibility changes animate and honor Reduce Motion. Icon-only actions have an accessibility label and desktop help where applicable. Reserve text for actions an icon cannot clearly express.

## Motion and status

`CharacterAvatar` identifies a bot. `ThinkingOrb` conveys working, searching, listening or connecting and needs adjacent text or an accessible outer label. In-app animation pauses off-screen, when inactive, or with Reduce Motion. Widgets/activities use static frames.

On iPhone, provider identity and activity presentation are shared with widgets in `CodyncKit/Design`; use those components rather than re-creating mappings per target. The desktop draws them in `components/AgentIcon.tsx`, `Avatar.tsx` and `ThinkingOrb.tsx`. See [mobile widgets](mobile-widgets.md) for current rendering and previews.

The [2026-09-25 audit](../archive/ui-audit-2026-09-25.md) is historical. Its old line numbers and recommendations are not the current UI policy or a list of confirmed open bugs.

Model discovery keeps loading and refresh in one fixed-size slot beside the Model picker. Refresh retains the current catalog while loading, and loading does not add a separate row to the settings card. The Model label keeps its intrinsic width; the model picker takes the remaining width and truncates long names, with the full name available in its tooltip and menu.

## Desktop conversation details

The details inspector (`apps/desktop/src/renderer/views/thread/DetailsPanel.tsx`) uses a compact device summary instead of an empty screen preview. Keep the computer name, remote-screen state, and iPhone hint together; show the hint only when screen capture is ready. Use 16-point horizontal insets and 28-point section gaps. Its sections (computer, Routines, Agent) and the Settings tab (`BotSettingsForm`: Profile, Agent, Activity, Connectors, Skills, Memory) use the `CardSection` look: filled 16-point groups without borders, hairline-split rows, a note under each option.

Routines are a compact grouped list: name, a one-line schedule (or the live run state), and an on/off switch per row; tapping a row opens the same form as +, filled in, with delete and test run beside Save. On the desktop the form card fits its content. The header's chat button asks the bot (puts "I want a routine that " in the composer); + opens the form, where When to run is a type choice and cron is typed directly. The empty state is one line of text. A saved webhook routine's form shows the public URL and key as filled monospaced rows with copy, show/hide and replace-key (confirmed) icon buttons, and one caption on how to send and that deliveries pass through the Codync cloud. Agent metadata uses Runtime and Workspace labels for personal bots, or Project folder for explicitly configured projects; project paths remain selectable and wrap. Personal workspace paths stay out of the default details UI. Keep status and section headings readable in both appearances through `Palette`.

On iPhone, the routine editor keeps the full-width save action in a fixed footer on the sheet background. When required fields are empty, the footer explains why saving is unavailable.

New bots use automatically allocated personal workspaces. Settings offer Personal or an optional project folder; see [bot workspaces](../features/bot-workspaces.md).

Desktop menus (`AnchoredMenu` in `Overlay.tsx`) scroll vertically when their content exceeds 420 points or the available space beside the trigger. Menus open toward the side with more vertical room; short lists keep their content height. Menu width is capped at 320 points and constrained to the window, and long option titles remain available through help text. This applies to Agent, Model, and other choice menus on the desktop. On iOS every menu is the system one: `DropdownMenu` and `ChoicePicker` present the native `Menu` (choices as checkmarked toggles) and `.contextActions` the native `contextMenu` (reactions as a palette row), because an overlay anchored by global frame lands in the wrong place inside `.codyncSheet`, scroll views and the composer (the + menu opened mid-screen on iPhone, 2026-09-30). Each computer has its own marketplace; its header names the computer and, with several online, switches between them. A new bot's editor on iPhone has a Computer dropdown listing every computer in the current account, including computers hidden by the roster filter and offline computers. It remains a dropdown with one computer. Offline choices stay selectable and are labeled; creation waits until the selected computer is online. Switching computers resets the folder, model and connectors that belonged to the old computer.

## Reading conversations

A visible conversation registers its own read scope while its scene is active. New chat-visible entries (including final updates to an existing message ID) and later roster unread updates acknowledge that scope through the host. Opening the view, returning to the foreground, and reconnecting also acknowledge it. Never gate a receipt on the cached unread count: entry and roster events can arrive separately. Replies acknowledge only their own thread; leaving a view or backgrounding the scene unregisters it. The host remains authoritative and broadcasts the resulting unread state to all clients.

The iPhone conversation navigation title includes a compact second line with the connection label and the current connection route icon: cloud for Cloudflare, Wi-Fi for direct Wi-Fi/Tailscale. It follows the live route, not the preferred route setting; offline and reconnecting states replace the route icon. VoiceOver also reads the connection description.


## Native client parity

UI changes apply to iOS SwiftUI (`apps/ios/`, `apps/ios/Kit/Sources/CodyncUI/`), the
desktop app (`apps/desktop/src/renderer/`) and the terminal client (`host/src/tui/`)
together. Use each platform's controls while preserving the same actions, information
and states. The desktop app was ported view by view from the SwiftUI Mac app and
carries its behavior; the October 2026 parity changes brought the TUI up to it.

- Chat and threads show the latest non-final agent segment only while that lane is
  working and connected. Earlier narration, tools, empty text and `(pass)` stay out
  of chat. Completed replies remain visible.
- TUI keeps text and attachments in the composer until sending succeeds. A failed
  send can be retried with Enter; it reuses the nonce. A late response must not erase
  a draft edited while the request was running.
- TUI **C** opens a pending connection request in the current lane; selecting a request
  and pressing Enter opens that request. Credentials, connector installation/sign-in
  and hosted apps use the secure setup APIs. Ctrl+X cancels the request; Escape closes
  the editor without cancelling. Secrets are masked and never sent as chat text.
- TUI bot settings include **Use computer**, matching the iPhone and desktop apps. The
  remote-screen viewer and voice-call UI are not in the TUI.

### Verification

Run Rust format, Clippy and tests for `host/`, `npm run typecheck && npm test` in
`apps/desktop/`, and `xcodebuild test -scheme CodyncKit-Package` on an iOS simulator
from `apps/ios/Kit/`. Desktop UI checks use the `CODYNC_DEBUG_OPEN` and
`CODYNC_REMOTE_DEBUG` switches described in the
[desktop app doc](../architecture/desktop-app.md#development). Host tests cover TUI
draft recovery, lane-specific streaming, computer settings and complete
connection-request API flows. These checks do not authenticate real third-party accounts.
