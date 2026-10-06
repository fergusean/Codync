import CodyncUI
import Foundation
import UIKit
import WatchConnectivity
import os

private let log = Logger(subsystem: "com.pokai.Codync.ios", category: "Watch")

/// `WCSession` for the `WatchBridge`. Delegate callbacks arrive on a background queue.
@MainActor
final class PhoneWatchLink: NSObject, WatchLink, WCSessionDelegate {
    private let session: WCSession?
    /// Answers a watch request (`WatchBridge.answer`); set once by the app store.
    var handler: (@MainActor (Data) -> Data)?
    var onChange: (@MainActor () -> Void)?

    override init() {
        session = WCSession.isSupported() ? .default : nil
        super.init()
        session?.delegate = self
        session?.activate()
    }

    var isActivated: Bool { session?.activationState == .activated }
    var isPaired: Bool { session?.isPaired ?? false }
    var isWatchAppInstalled: Bool { session?.isWatchAppInstalled ?? false }
    var isReachable: Bool { session?.isReachable ?? false }

    func updateApplicationContext(_ data: Data) {
        do { try session?.updateApplicationContext(["e": data]) } catch {
            log.error("watch context failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func sendMessage(_ data: Data, onFailure: @escaping @MainActor () -> Void) {
        // Called on WC's queue: `@Sendable` keeps it off the main actor (Swift 6 traps otherwise).
        session?.sendMessageData(data, replyHandler: nil) { @Sendable _ in
            Task { @MainActor in onFailure() }
        }
    }

    func transferUserInfo(_ data: Data, botId: String) {
        guard let session else { return }
        for transfer in session.outstandingUserInfoTransfers where transfer.userInfo["bot"] as? String == botId {
            transfer.cancel()
        }
        session.transferUserInfo(["e": data, "bot": botId])
    }

    func cancelTransfers() {
        session?.outstandingUserInfoTransfers.forEach { $0.cancel() }
    }

    // MARK: WCSessionDelegate

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        if let error { log.error("watch activation failed: \(error.localizedDescription, privacy: .public)") }
        changed()
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) { changed() }

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        // A new watch was paired: the session must be activated again to talk to it.
        session.activate()
        changed()
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) { changed() }
    nonisolated func sessionWatchStateDidChange(_ session: WCSession) { changed() }

    /// The watch waits for the reply, so it is produced on the main actor before returning.
    nonisolated func session(_ session: WCSession, didReceiveMessageData messageData: Data, replyHandler: @escaping (Data) -> Void) {
        let reply = DispatchQueue.main.sync { MainActor.assumeIsolated { self.handler?(messageData) ?? Data() } }
        replyHandler(reply)
    }

    private nonisolated func changed() {
        Task { @MainActor in onChange?() }
    }
}

/// UIKit effects for the `WatchBridge`.
@MainActor
final class PhoneWatchSystem: WatchSystem {
    private var tasks: [Int: UIBackgroundTaskIdentifier] = [:]
    private var next = 0

    var isAppActive: Bool { UIApplication.shared.applicationState == .active }
    var backgroundTimeRemaining: TimeInterval { UIApplication.shared.backgroundTimeRemaining }

    func beginBackgroundTask(expiration: @escaping @MainActor () -> Void) -> Int? {
        let id = UIApplication.shared.beginBackgroundTask(withName: "Watch") {
            // UIKit calls this on the main thread.
            MainActor.assumeIsolated { expiration() }
        }
        guard id != .invalid else { return nil }
        next += 1
        tasks[next] = id
        return next
    }

    func endBackgroundTask(_ token: Int) {
        guard let id = tasks.removeValue(forKey: token) else { return }
        UIApplication.shared.endBackgroundTask(id)
    }
}
