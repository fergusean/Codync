import CodyncKit
import CodyncUI
import SwiftUI

/// The chat window's Settings: pages in a sidebar, ChatGPT's settings layout
/// (`docs/design/reference/chatgpt-settings-*.png`).
enum SettingsPage: String, CaseIterable, Identifiable {
    case general, computers, voice, usage, updates

    var id: Self { self }

    var title: String {
        switch self {
        case .general: "General"
        case .computers: "Computers & devices"
        case .voice: "Voice chat"
        case .usage: "Usage"
        case .updates: "Updates"
        }
    }

    /// The sidebar's label, short enough for its width.
    var label: String { self == .computers ? "Computers" : title }

    /// The sidebar group it's listed under.
    var group: String {
        switch self {
        case .general, .voice, .usage: "Personal"
        case .computers, .updates: "This Mac"
        }
    }

    var icon: String {
        switch self {
        case .general: "gearshape"
        case .computers: "desktopcomputer"
        case .voice: "waveform"
        case .usage: "gauge.with.dots.needle.33percent"
        case .updates: "arrow.down.circle"
        }
    }
}

struct SettingsView: View {
    /// Kept here: the sheet's content doesn't follow the window's state once shown.
    @State private var page: SettingsPage
    let onSignIn: (AccountSession.SignInProvider) -> Void
    let onSignOut: () -> Void

    init(page: SettingsPage,
         onSignIn: @escaping (AccountSession.SignInProvider) -> Void, onSignOut: @escaping () -> Void) {
        _page = State(initialValue: page)
        self.onSignIn = onSignIn
        self.onSignOut = onSignOut
    }
    @Environment(HostController.self) private var host
    @Environment(\.dismissModal) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(Palette.border).frame(width: 1)
            VStack(alignment: .leading, spacing: 0) {
                Text(page.title)
                    .appFont(.system(size: 24, weight: .semibold))
                    .foregroundStyle(Palette.text)
                    .padding(.horizontal, 32)
                    .padding(.top, 30)
                    .padding(.bottom, 8)
                content
                    .padding(.horizontal, 18)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .id(page)
                    .transition(.opacity)
            }
        }
        .overlay(alignment: .topTrailing) {
            IconButton("Close", systemImage: "xmark") { dismiss() }
                .keyboardShortcut(.cancelAction)
                .padding(14)
        }
        .background(Palette.background)
        .animation(Motion.reduced(Motion.fade, reduceMotion), value: page)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(["Personal", "This Mac"], id: \.self) { group in
                Text(group)
                    .appFont(.system(size: 12, weight: .medium))
                    .foregroundStyle(Palette.tertiary)
                    .padding(.horizontal, 10)
                    .padding(.top, group == "Personal" ? 0 : 16)
                    .padding(.bottom, 4)
                ForEach(SettingsPage.allCases.filter { $0.group == group }) { item in
                    sidebarItem(item)
                }
            }
            Spacer()
        }
        .padding(10)
        .padding(.top, 20)
        .frame(width: 200)
    }

    private func sidebarItem(_ item: SettingsPage) -> some View {
        Button { page = item } label: {
            HStack(spacing: 10) {
                Image(systemName: item.icon).frame(width: 18).foregroundStyle(Palette.secondary)
                Text(item.label).lineLimit(1)
                Spacer(minLength: 4)
                if item == .computers && !host.approvals.isEmpty {
                    Text("\(host.approvals.count)").appFont(.system(size: 11, weight: .semibold)).foregroundStyle(Palette.secondary)
                }
            }
            .appFont(.system(size: 13))
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 10)
            .frame(height: 32)
            .background(item == page ? Palette.text.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(item == page ? .isSelected : [])
    }

    @ViewBuilder private var content: some View {
        switch page {
        case .general: GeneralSettings(onSignIn: onSignIn, onSignOut: onSignOut)
        case .computers: ComputersView(showsHeader: false)
        case .voice:
            if let store = host.store {
                VoiceChatSettingsView(showsHeader: false).environment(store)
            } else {
                CardForm {
                    Text("Voice keys are kept by this Mac's host, which isn't running.").foregroundStyle(Palette.secondary)
                }
            }
        case .usage: CardForm { UsageLimitsList() }
        case .updates: UpdateSettings()
        }
    }
}

/// Account, window and app preferences.
private struct GeneralSettings: View {
    let onSignIn: (AccountSession.SignInProvider) -> Void
    let onSignOut: () -> Void
    @Environment(HostController.self) private var host
    @Environment(AccountSession.self) private var account
    @AppStorage("sidebarCompact") private var compact = false
    @AppStorage(ConversationTypography.preferenceKey) private var fontSize = ConversationTypography.defaultSize
    @AppStorage(SharedStore.usageIconStyleKey, store: UserDefaults(suiteName: SharedStore.appGroup))
    private var usageIconStyle = UsageIconStyle.character.rawValue

    var body: some View {
        CardForm {
            CardSection("Account", footer: account.errorMessage) {
                HStack(spacing: 10) {
                    Image(systemName: "person.crop.circle")
                        .appFont(.system(size: 26))
                        .foregroundStyle(Palette.secondary)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(account.isSignedIn ? (account.email ?? "Signed in") : "Not signed in").lineLimit(1)
                        Text(account.isSignedIn ? "Reach your computers from anywhere" : "Sign in to reach your computers from anywhere")
                            .appFont(.caption).foregroundStyle(Palette.secondary)
                    }
                    Spacer(minLength: 8)
                    if account.isSignedIn {
                        Button(account.isBusy ? "Please wait…" : "Sign out", action: onSignOut)
                            .buttonStyle(.secondary)
                            .disabled(account.isBusy)
                    } else {
                        Button("Apple") { onSignIn(.apple) }.buttonStyle(.secondary).disabled(account.isBusy)
                        Button("Google") { onSignIn(.google) }.buttonStyle(.secondary).disabled(account.isBusy)
                    }
                }
            }
            CardSection("Appearance") {
                ValueRow("Text size") {
                    ChoicePicker(selection: $fontSize, options: ConversationTypography.sizes.map { size in
                        (size, size == ConversationTypography.defaultSize ? "\(Int(size)) pt (Default)" : "\(Int(size)) pt")
                    })
                }
                ValueRow("Usage icons") {
                    ChoicePicker(selection: $usageIconStyle, options: [
                        (UsageIconStyle.character.rawValue, "Character"), (UsageIconStyle.original.rawValue, "Original"),
                    ])
                }
                Toggle("Compact sidebar", isOn: $compact).toggleStyle(.codync)
            }
            CardSection("System") {
                Toggle("Open at login", isOn: Binding(get: { host.launchAtLogin }, set: { host.setLaunchAtLogin($0) }))
                    .toggleStyle(.codync)
            }
        }
    }
}

/// Every computer's usage limits, grouped by computer when there's more than one.
struct UsageLimitsList: View {
    @Environment(HostController.self) private var host

    var body: some View {
        let stores = host.accounts.computers.compactMap { host.accounts.store(for: $0.id) }
        let withUsage = stores.filter { !$0.usage.providers.isEmpty }
        if withUsage.isEmpty {
            Text("No usage information yet.").foregroundStyle(Palette.secondary)
        } else {
            ForEach(withUsage, id: \.computer.id) { store in
                if stores.count > 1 {
                    Label { Text(store.hostName) } icon: { ComputerBadge(store.computer, size: 16) }
                        .appFont(.headline)
                }
                UsageLimits(usage: store.usage)
            }
        }
    }
}

/// Sparkle's update check and preferences, the same as the menu bar's Updates menu.
private struct UpdateSettings: View {
    @Environment(UpdatesManager.self) private var updates

    var body: some View {
        @Bindable var updates = updates
        CardForm {
            CardSection("Codync", footer: footer) {
                ValueRow("Version", detail: updates.lastUpdateCheckDate.map { "Last checked \($0.formatted(date: .abbreviated, time: .shortened))" }) {
                    HStack(spacing: 10) {
                        Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                        Button(updates.availableVersion.map { "Update to \($0)" } ?? "Check for updates") {
                            updates.checkForUpdates()
                        }
                        .buttonStyle(.secondary)
                        .disabled(!updates.canCheckForUpdates && !updates.hasStagedUpdate)
                    }
                }
                Toggle("Automatically check for updates", isOn: $updates.automaticallyChecksForUpdates)
                    .toggleStyle(.codync)
                    .disabled(!updates.isSupported)
                Toggle("Automatically download and install", isOn: $updates.automaticallyDownloadsUpdates)
                    .toggleStyle(.codync)
                    .disabled(!updates.isSupported)
                if let error = updates.errorMessage {
                    HStack {
                        Text(error).foregroundStyle(Palette.warning).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 8)
                        Button("Retry") { updates.retryInstallation() }
                            .buttonStyle(.secondary)
                            .disabled(updates.preparingInstallation)
                    }
                }
            }
        }
    }

    private var footer: String? {
        if !updates.isSupported { return "Updates are available in release builds." }
        if let app = updates.waitingForApp { return "Waiting for iPhone app \(app) to pass App Store review." }
        return nil
    }
}
