import CodyncKit
import Foundation
import Observation
import os
import WatchConnectivity

/// Why a request to the iPhone produced no response.
enum LinkError: Error, Equatable {
    /// The phone app answered with empty data: it is running but has no state to serve yet.
    case notReady
    case timeout
    case unreachable
    case undecodable
    /// One side is too old to talk to the other.
    case mismatch(WatchMismatch)
}

/// The watch's only channel: WatchConnectivity to the iPhone app. The delegate callbacks
/// arrive on a WC queue, so they decode what they were handed there and hop to the main actor.
@MainActor @Observable
final class WatchSessionLink: NSObject, WCSessionDelegate {
    /// The phone app is reachable right now (it can be woken in the background).
    private(set) var isReachable = false
    /// The phone must be unlocked once after a reboot before it can be reached.
    private(set) var needsUnlock = false
    private(set) var isActivated = false

    @ObservationIgnored weak var store: WatchStore?

    /// How long a request waits for the phone's reply.
    static let timeout: Duration = .seconds(15)

    private var session: WCSession? { WCSession.isSupported() ? WCSession.default : nil }

    func activate() {
        guard let session else { return }
        session.delegate = self
        session.activate()
        refresh(activated: session.activationState == .activated, needsUnlock: session.iOSDeviceNeedsUnlockAfterRebootForReachability,
                reachable: session.isReachable)
    }

    /// The snapshot the phone last published, available at cold launch before the phone answers.
    var receivedContext: Data? { session?.receivedApplicationContext["e"] as? Data }

    private func refresh(activated: Bool, needsUnlock: Bool, reachable: Bool) {
        isActivated = activated
        self.needsUnlock = needsUnlock
        let changed = reachable != isReachable
        isReachable = reachable
        if changed, reachable { store?.linkBecameReachable() }
    }

    // MARK: Requests

    /// Sends `request` and waits for the phone's response. The phone replies at once, from its
    /// cache, so the timeout only matters when the link itself stalls.
    func request(_ request: WatchRequest) async throws -> WatchResponse {
        guard let session, session.activationState == .activated, session.isReachable else { throw LinkError.unreachable }
        let id = UUID()
        let payload = try WatchEnvelope.fromWatch(.request(id: id, request)).encode()
        let reply: Data = try await withCheckedThrowingContinuation { continuation in
            // WC's reply and error handlers and the timeout race; the first one wins.
            let pending = OSAllocatedUnfairLock<CheckedContinuation<Data, Error>?>(initialState: continuation)
            @Sendable func finish(_ result: Result<Data, Error>) {
                let waiting = pending.withLock { slot in
                    defer { slot = nil }
                    return slot
                }
                waiting?.resume(with: result)
            }
            // WC calls these on its own queue: `@Sendable` keeps them from inheriting the main actor
            // (Swift 6 would trap on the isolation check).
            session.sendMessageData(payload, replyHandler: { @Sendable in finish(.success($0)) },
                                    errorHandler: { @Sendable in finish(.failure($0)) })
            Task {
                try? await Task.sleep(for: Self.timeout)
                finish(.failure(LinkError.timeout))
            }
        }
        guard !reply.isEmpty else { throw LinkError.notReady }
        if let header = WatchEnvelope.header(of: reply),
           let mismatch = WatchCompatibility.check(header, on: .watch) {
            throw LinkError.mismatch(mismatch)
        }
        guard case let .response(replyId, response)? = try? WatchEnvelope.decode(reply).message, replyId == id else {
            throw LinkError.undecodable
        }
        return response
    }

    // MARK: WCSessionDelegate

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        let context = session.receivedApplicationContext["e"] as? Data
        let unlock = session.iOSDeviceNeedsUnlockAfterRebootForReachability
        let reachable = session.isReachable
        Task { @MainActor in
            refresh(activated: activationState == .activated, needsUnlock: unlock, reachable: reachable)
            if let context { store?.receive(context) }
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        let unlock = session.iOSDeviceNeedsUnlockAfterRebootForReachability
        Task { @MainActor in refresh(activated: true, needsUnlock: unlock, reachable: reachable) }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        guard let data = applicationContext["e"] as? Data else { return }
        Task { @MainActor in store?.receive(data) }
    }

    /// A chat the phone pushed while the watch app is up.
    nonisolated func session(_ session: WCSession, didReceiveMessageData messageData: Data) {
        Task { @MainActor in store?.receive(messageData) }
    }

    /// A chat queued while the watch app was away.
    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        guard let data = userInfo["e"] as? Data else { return }
        Task { @MainActor in store?.receive(data) }
    }
}
