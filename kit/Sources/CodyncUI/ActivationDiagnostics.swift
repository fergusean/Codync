#if os(macOS)
import AppKit
import OSLog

/// Opt-in breadcrumbs for intermittent AppKit activation/accessibility crashes.
/// Records lifecycle metadata only; never records draft text or account/bot names.
@MainActor
public enum ActivationDiagnostics {
    private static let log = Logger(subsystem: "com.pokai.Codync", category: "Activation")
    private static let enabled = ProcessInfo.processInfo.environment["CODYNC_ACTIVATION_DIAGNOSTICS"] == "1"
        || UserDefaults.standard.bool(forKey: "activationDiagnostics")
    private static var observers: [NSObjectProtocol] = []

    public static func start() {
        guard enabled, observers.isEmpty else { return }
        let events: [(Notification.Name, String)] = [
            (NSApplication.didBecomeActiveNotification, "app.active"),
            (NSApplication.willResignActiveNotification, "app.resigning"),
            (NSApplication.didResignActiveNotification, "app.inactive"),
            (NSWindow.didBecomeKeyNotification, "window.key"),
            (NSWindow.didResignKeyNotification, "window.resigned"),
            (NSWindow.didMiniaturizeNotification, "window.minimized"),
            (NSWindow.didDeminiaturizeNotification, "window.restored"),
            (NSMenu.didBeginTrackingNotification, "menu.tracking"),
            (NSMenu.didEndTrackingNotification, "menu.closed"),
        ]
        for (name, event) in events {
            let observer = NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { record(event) }
            }
            observers.append(observer)
        }
        record("diagnostics.started")
    }

    public static func record(_ event: String) {
        guard enabled else { return }
        let window = NSApp.keyWindow
        let responder = window?.firstResponder.map { String(describing: type(of: $0)) } ?? "none"
        log.notice("\(event, privacy: .public) active=\(NSApp.isActive) window=\(window?.windowNumber ?? -1) responder=\(responder, privacy: .public)")
    }
}
#endif
