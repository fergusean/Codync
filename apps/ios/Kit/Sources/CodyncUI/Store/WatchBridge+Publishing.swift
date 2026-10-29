import CodyncKit
import Foundation
import Observation

extension WatchBridge {
    // MARK: state

    func scope(of store: BotStore) -> WatchScope {
        WatchScope(context: accounts.storage.id, computer: store.computer.id)
    }

    /// The iPhone's `connectionLabel` wording as a state.
    private func state(of store: BotStore) -> WatchComputerState {
        switch store.shownConnection {
        case .online: store.mismatch != nil ? .needsUpdate : .online
        case .connecting: .connecting
        case .computerOffline, .offline: .offline
        case .unauthorized: .noAccess
        case .unpaired: .notPaired
        }
    }

    func makeSnapshot() -> WatchSnapshot {
        _ = accounts.computers
        _ = accounts.selection
        guard let store = accounts.currentStore else {
            return .build(scope: nil, computerName: nil, state: .notPaired, fresh: false, roster: [], all: [:], now: .now)
        }
        let roster = RosterSection.lines(store.roster, folded: [], bot: { $0 }).compactMap { line -> Bot? in
            guard case let .bot(bot) = line else { return nil }
            return bot
        }
        return .build(scope: scope(of: store), computerName: store.hostName, state: state(of: store), fresh: store.isLive,
                      roster: roster, all: store.bots, now: .now)
    }

    func buildChat(_ store: BotStore, _ bot: Bot) -> WatchChat {
        .build(scope: scope(of: store), bot: bot, entries: store.chat(bot.id), answering: store.answering, fresh: store.isLive, now: .now)
    }

    /// The chat open on the watch.
    private func makeChat() -> WatchChat? {
        guard let open = openWatchChat, let bot = open.store.bots[open.botId] else { return nil }
        return buildChat(open.store, bot)
    }

    func encode(_ message: WatchMessage) -> Data? { try? WatchEnvelope.fromPhone(message).encode() }

    // MARK: observation

    /// Rebuilds what the watch shows under observation tracking and sends what changed.
    func refresh() {
        guard isAvailable else {
            lastSnapshot = nil
            lastChat = nil
            endAll()
            return
        }
        generation += 1
        let current = generation
        let (snapshot, chat) = withObservationTracking {
            (makeSnapshot(), makeChat())
        } onChange: { [weak self] in
            Task { @MainActor in self?.changed(current) }
        }
        publish(snapshot)
        if let chat, link.isReachable, !Self.same(lastChat, chat), let data = encode(.chat(chat)) {
            lastChat = chat
            if let store = accounts.currentStore { push(data, botId: chat.botId, store: store) }
        }
        endOfflineExchanges()
    }

    private func changed(_ observed: Int) {
        guard observed == generation, pending == nil else { return }
        pending = Task { [coalesce = durations.coalesce] in
            try? await Task.sleep(for: coalesce)
            guard !Task.isCancelled else { return }
            pending = nil
            refresh()
        }
    }

    func publish(_ snapshot: WatchSnapshot, force: Bool = false) {
        guard force || !Self.same(lastSnapshot, snapshot), let data = encode(.snapshot(snapshot)) else { return }
        lastSnapshot = snapshot
        link.updateApplicationContext(data)
    }

    /// Equal apart from `builtAt`, which always differs.
    private static func same(_ old: WatchSnapshot?, _ new: WatchSnapshot) -> Bool {
        guard var old else { return false }
        old.builtAt = new.builtAt
        return old == new
    }

    private static func same(_ old: WatchChat?, _ new: WatchChat) -> Bool {
        guard var old else { return false }
        old.builtAt = new.builtAt
        return old == new
    }
}
