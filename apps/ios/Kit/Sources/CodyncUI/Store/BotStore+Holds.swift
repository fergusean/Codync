import CodyncKit
import Foundation

extension BotStore {
    /// A hold keeps the link (and, in the background, its events) alive on behalf of its owner,
    /// which gets the held bot's replies, approval requests and idle moments straight from the
    /// store's incoming events instead of waiting for a SwiftUI update. Holds stack: the link is
    /// released in the background only after the last one ends.
    /// Background holds keep pushes enabled; active voice calls own their separate suppression.
    func beginHold(_ id: UUID, botId: String?,
                   reply: @escaping @MainActor (Entry) -> Void = { _ in },
                   needsInput: @escaping @MainActor (Bot) -> Void = { _ in },
                   settled: @escaping @MainActor (String) -> Void = { _ in },
                   end: @escaping @MainActor () -> Void = {}) {
        guard !retired, holds[id] == nil else { return }
        holds[id] = Hold(botId: botId, startRev: rev, reply: reply, needsInput: needsInput, settled: settled, end: end)
        if streamTask == nil { restartStream() } else { syncEventsClient() }
    }

    /// Returns once the released link's transport has closed (`shutdown` waits, about a second at
    /// most, for the close frame to leave), so a caller about to be suspended can finish its
    /// background task after it.
    func endHold(_ id: UUID) async {
        let closing = releaseHold(id)
        await closing?.value
    }

    /// `endHold` without the wait, for a caller that can't suspend (an expiring background task):
    /// the hold is gone and the shutdown started; the returned task finishes with it.
    @discardableResult
    func releaseHold(_ id: UUID) -> Task<Void, Never>? {
        guard holds.removeValue(forKey: id) != nil, !isActive else { return nil }
        guard !hasBackgroundWork else {
            syncEventsClient()
            return nil
        }
        let closing = stopTransport()
        saveCache()
        return closing
    }

    /// What the events subscription tells the host it is: a phone while the app is active or a
    /// voice call needs it, otherwise nothing the host counts.
    var eventsClient: String? {
        isActive || !voiceCalls.isEmpty || callEnds > 0 ? "ios" : nil
    }

    /// Resubscribes (same link) when the host should see a different client.
    func syncEventsClient() {
        guard eventsTask != nil, eventsClient != subscribedClient else { return }
        restartEvents()
    }

    /// The link is up and the first catch-up has arrived: what the store shows is current.
    var isLive: Bool { live != nil && linkCaughtUp }

}
