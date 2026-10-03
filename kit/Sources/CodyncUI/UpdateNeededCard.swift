import CodyncKit
import SwiftUI

/// Updates this app: the App Store on iPhone, Sparkle on the Mac.
public struct AppUpdateAction: Sendable {
    let action: @MainActor @Sendable () -> Void

    public init(_ action: @escaping @MainActor @Sendable () -> Void) { self.action = action }

    @MainActor public func callAsFunction() { action() }
}

/// Updates a computer's host from this app, for the computers it can (the Mac's own host).
public struct HostUpdateAction: Sendable {
    let available: @MainActor @Sendable (BotStore) -> Bool
    let action: @MainActor @Sendable (BotStore) -> Void

    public init(available: @escaping @MainActor @Sendable (BotStore) -> Bool,
                _ action: @escaping @MainActor @Sendable (BotStore) -> Void) {
        self.available = available
        self.action = action
    }
}

public extension EnvironmentValues {
    @Entry var appUpdate: AppUpdateAction?
    @Entry var hostUpdate: HostUpdateAction?
}

/// Says which side has to update before this app and a computer can work together, and offers
/// the update where this app can start it. Shown instead of the composer and above the
/// computer's bots (docs/reference/compatibility.md).
public struct UpdateNeededCard: View {
    let store: BotStore
    let mismatch: VersionMismatch
    @Environment(\.appUpdate) private var appUpdate
    @Environment(\.hostUpdate) private var hostUpdate

    public init(store: BotStore, mismatch: VersionMismatch) {
        self.store = store
        self.mismatch = mismatch
    }

    public var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "arrow.down.circle")
                .font(.title3)
                .foregroundStyle(Palette.warning)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.text)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(Palette.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let action {
                    Button(action.title, action: action.run)
                        .buttonStyle(.primary)
                        .padding(.top, 4)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    var title: String { UpdateNeededText.title(mismatch, host: store.hostName) }
    var detail: String {
        UpdateNeededText.detail(mismatch, host: store.hostName, hostVersion: store.hostVersion?.version,
                                // With a button here, instructions for that computer would only confuse.
                                os: store.hello?.os, instructions: action == nil)
    }

    private var action: (title: String, run: () -> Void)? {
        switch mismatch {
        case .updateApp:
            guard let appUpdate else { return nil }
            return ("Update Codync", { appUpdate() })
        case .updateHost:
            guard let hostUpdate, hostUpdate.available(store) else { return nil }
            return ("Update \(store.hostName)", { hostUpdate.action(store) })
        }
    }
}

/// The words every client uses for a version mismatch (the Linux app and the terminal client
/// mirror them).
public enum UpdateNeededText {
    public static func title(_ mismatch: VersionMismatch, host: String) -> String {
        switch mismatch {
        case .updateApp: "Update this app"
        case .updateHost: "Update Codync on \(host)"
        }
    }

    public static func detail(_ mismatch: VersionMismatch, host: String, hostVersion: String?, os: String?,
                              instructions: Bool = true) -> String {
        switch mismatch {
        case let .updateApp(minimum):
            let runs = hostVersion.map { "\(host) runs Codync \($0) and" } ?? host
            return "\(runs) needs this app to be \(minimum) or newer. Your chats are safe on the computer."
        case let .updateHost(version, minimum):
            let how = switch os {
            case "macos": "Open Codync on that Mac and choose Check for Updates."
            case "linux": "Run codync-host update there, or use Check for updates in its Codync app."
            default: "Update Codync on that computer."
            }
            let needs = "\(host) runs Codync \(version); this app needs \(minimum) or newer."
            return instructions ? "\(needs) \(how)" : needs
        }
    }
}
