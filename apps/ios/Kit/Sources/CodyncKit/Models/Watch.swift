import Foundation

// The iPhone <-> Apple Watch protocol (docs/plans/watch-app.md). Every message travels in a
// `WatchEnvelope`. Enums are written by hand as a `type` discriminator plus fields: an unknown
// `type` fails the decode (the receiver shows "Needs update") and unknown fields are ignored.

/// Size bounds the builders enforce.
public enum WatchLimits {
    /// Bots in a snapshot's roster.
    public static let roster = 40
    public static let chatEntries = 25
    /// Recent user-message nonces a chat carries, so the watch can confirm sends older than its entries.
    public static let recentNonces = 50
    public static let lastMessage = 140
    public static let activity = 80
    public static let entryText = 1_500
    public static let entryTitle = 300
    /// An encoded envelope stays below this: WatchConnectivity rejects larger payloads.
    public static let envelopeBytes = 48 * 1024
}

public enum WatchSide: Sendable {
    case phone, watch
}

/// Phone/watch compatibility, as `VersionMismatch` is for phone/host: each side names the
/// oldest version of the other it works with.
public enum WatchCompatibility {
    /// The oldest iPhone app the watch app works with.
    public static let minPhone = "2.7.1"
    /// The oldest watch app the iPhone app works with.
    public static let minWatch = "2.7.1"

    /// Checks a received envelope on `side` (running `version`); nil when both can talk.
    public static func check(_ header: WatchEnvelope.Header, on side: WatchSide, version: String = AppVersion.current) -> WatchMismatch? {
        if AppVersion.isBelow(version, header.minPeer) {
            return side == .phone ? .updatePhone(minimum: header.minPeer) : .updateWatch(minimum: header.minPeer)
        }
        switch side {
        case .phone: if AppVersion.isBelow(header.version, minWatch) { return .updateWatch(minimum: minWatch) }
        case .watch: if AppVersion.isBelow(header.version, minPhone) { return .updatePhone(minimum: minPhone) }
        }
        return nil
    }
}

/// Which app is too old.
public enum WatchMismatch: Equatable, Sendable {
    case updatePhone(minimum: String)
    case updateWatch(minimum: String)
}

public struct WatchEnvelope: Codable, Equatable, Sendable {
    /// The sender's app version.
    public var version: String
    /// The oldest app version on the other side the sender works with.
    public var minPeer: String
    public var message: WatchMessage

    public init(version: String, minPeer: String, message: WatchMessage) {
        self.version = version
        self.minPeer = minPeer
        self.message = message
    }

    public static func fromPhone(_ message: WatchMessage, version: String = AppVersion.current) -> WatchEnvelope {
        WatchEnvelope(version: version, minPeer: WatchCompatibility.minWatch, message: message)
    }

    public static func fromWatch(_ message: WatchMessage, version: String = AppVersion.current) -> WatchEnvelope {
        WatchEnvelope(version: version, minPeer: WatchCompatibility.minPhone, message: message)
    }

    /// The two fields a compatibility check needs, readable even when `message` isn't.
    public struct Header: Decodable, Equatable, Sendable {
        public var version: String
        public var minPeer: String

        public init(version: String, minPeer: String) {
            self.version = version
            self.minPeer = minPeer
        }
    }

    public static func header(of data: Data) -> Header? { try? JSONDecoder().decode(Header.self, from: data) }

    public func encode() throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> WatchEnvelope {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return try decoder.decode(WatchEnvelope.self, from: data)
    }
}

/// Which account and computer a request or state belongs to; a mismatch means the phone moved on.
public struct WatchScope: Codable, Hashable, Sendable {
    /// `SharedStore.Context.id`: `local` or the SHA-256 digest, never the raw Clerk id.
    public var context: String
    public var computer: String

    public init(context: String, computer: String) {
        self.context = context
        self.computer = computer
    }
}

public enum WatchMessage: Codable, Equatable, Sendable {
    case request(id: UUID, WatchRequest)
    case response(id: UUID, WatchResponse)
    case snapshot(WatchSnapshot)
    case chat(WatchChat)

    private enum Key: String, CodingKey { case type, id, request, response, snapshot, chat }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Key.self)
        switch try c.decode(String.self, forKey: .type) {
        case "request": self = .request(id: try c.decode(UUID.self, forKey: .id), try c.decode(WatchRequest.self, forKey: .request))
        case "response": self = .response(id: try c.decode(UUID.self, forKey: .id), try c.decode(WatchResponse.self, forKey: .response))
        case "snapshot": self = .snapshot(try c.decode(WatchSnapshot.self, forKey: .snapshot))
        case "chat": self = .chat(try c.decode(WatchChat.self, forKey: .chat))
        case let type: throw DecodingError.dataCorruptedError(forKey: .type, in: c, debugDescription: "Unknown message \(type)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        switch self {
        case let .request(id, request):
            try c.encode("request", forKey: .type); try c.encode(id, forKey: .id); try c.encode(request, forKey: .request)
        case let .response(id, response):
            try c.encode("response", forKey: .type); try c.encode(id, forKey: .id); try c.encode(response, forKey: .response)
        case let .snapshot(snapshot):
            try c.encode("snapshot", forKey: .type); try c.encode(snapshot, forKey: .snapshot)
        case let .chat(chat):
            try c.encode("chat", forKey: .type); try c.encode(chat, forKey: .chat)
        }
    }
}

public enum WatchRequest: Codable, Equatable, Sendable {
    case hello
    /// Extends the lease; `open` is the bot whose chat is on screen, if any.
    case renew(WatchScope, open: String?)
    case chat(WatchScope, botId: String)
    /// Also used for Resend: the nonce is the message's identity end to end.
    case send(WatchScope, botId: String, text: String, nonce: String)
    case respond(WatchScope, botId: String, entryId: String, optionId: String)
    case close(WatchScope)

    private enum Key: String, CodingKey { case type, scope, open, botId, text, nonce, entryId, optionId }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Key.self)
        switch try c.decode(String.self, forKey: .type) {
        case "hello": self = .hello
        case "renew": self = .renew(try c.decode(WatchScope.self, forKey: .scope), open: try c.decodeIfPresent(String.self, forKey: .open))
        case "chat": self = .chat(try c.decode(WatchScope.self, forKey: .scope), botId: try c.decode(String.self, forKey: .botId))
        case "send":
            self = .send(try c.decode(WatchScope.self, forKey: .scope), botId: try c.decode(String.self, forKey: .botId),
                         text: try c.decode(String.self, forKey: .text), nonce: try c.decode(String.self, forKey: .nonce))
        case "respond":
            self = .respond(try c.decode(WatchScope.self, forKey: .scope), botId: try c.decode(String.self, forKey: .botId),
                            entryId: try c.decode(String.self, forKey: .entryId), optionId: try c.decode(String.self, forKey: .optionId))
        case "close": self = .close(try c.decode(WatchScope.self, forKey: .scope))
        case let type: throw DecodingError.dataCorruptedError(forKey: .type, in: c, debugDescription: "Unknown request \(type)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        switch self {
        case .hello:
            try c.encode("hello", forKey: .type)
        case let .renew(scope, open):
            try c.encode("renew", forKey: .type); try c.encode(scope, forKey: .scope); try c.encodeIfPresent(open, forKey: .open)
        case let .chat(scope, botId):
            try c.encode("chat", forKey: .type); try c.encode(scope, forKey: .scope); try c.encode(botId, forKey: .botId)
        case let .send(scope, botId, text, nonce):
            try c.encode("send", forKey: .type); try c.encode(scope, forKey: .scope); try c.encode(botId, forKey: .botId)
            try c.encode(text, forKey: .text); try c.encode(nonce, forKey: .nonce)
        case let .respond(scope, botId, entryId, optionId):
            try c.encode("respond", forKey: .type); try c.encode(scope, forKey: .scope); try c.encode(botId, forKey: .botId)
            try c.encode(entryId, forKey: .entryId); try c.encode(optionId, forKey: .optionId)
        case let .close(scope):
            try c.encode("close", forKey: .type); try c.encode(scope, forKey: .scope)
        }
    }
}

public enum WatchResponse: Codable, Equatable, Sendable {
    case snapshot(WatchSnapshot)
    case chat(WatchChat)
    case accepted
    case failed(WatchFailure)

    private enum Key: String, CodingKey { case type, snapshot, chat, failure }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Key.self)
        switch try c.decode(String.self, forKey: .type) {
        case "snapshot": self = .snapshot(try c.decode(WatchSnapshot.self, forKey: .snapshot))
        case "chat": self = .chat(try c.decode(WatchChat.self, forKey: .chat))
        case "accepted": self = .accepted
        case "failed": self = .failed(try c.decode(WatchFailure.self, forKey: .failure))
        case let type: throw DecodingError.dataCorruptedError(forKey: .type, in: c, debugDescription: "Unknown response \(type)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        switch self {
        case let .snapshot(snapshot): try c.encode("snapshot", forKey: .type); try c.encode(snapshot, forKey: .snapshot)
        case let .chat(chat): try c.encode("chat", forKey: .type); try c.encode(chat, forKey: .chat)
        case .accepted: try c.encode("accepted", forKey: .type)
        case let .failed(failure): try c.encode("failed", forKey: .type); try c.encode(failure, forKey: .failure)
        }
    }
}

/// Why a request wasn't accepted. A failure from a newer phone reads as `unknown`.
public enum WatchFailure: String, Codable, Equatable, Sendable {
    case staleScope, noComputer, notFound, needsUpdate
    /// The phone can't act right now (in the background without time to hold the link).
    case unavailable
    case unknown

    public init(from decoder: Decoder) throws {
        self = Self(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
    }
}

/// The iPhone's connection to the computer, in the iPhone's words.
public enum WatchComputerState: String, Codable, Equatable, Sendable {
    case connecting, online, offline, noAccess, notPaired, needsUpdate
    /// A state from a newer phone: only updating the watch app can show it.
    case unknown

    public init(from decoder: Decoder) throws {
        self = Self(rawValue: try decoder.singleValueContainer().decode(String.self)) ?? .unknown
    }

    /// The iPhone's `BotStore.connectionLabel` wording.
    public var label: String {
        switch self {
        case .connecting: "Connecting…"
        case .online: "Connected"
        case .offline: "Offline"
        case .noAccess: "No access"
        case .notPaired: "Not paired"
        case .needsUpdate: "Needs update"
        case .unknown: "Update Codync on Apple Watch"
        }
    }
}

public struct WatchSnapshot: Codable, Equatable, Sendable {
    /// nil when the phone has no computer.
    public var scope: WatchScope?
    public var computerName: String?
    public var state: WatchComputerState
    /// The phone's link is live and caught up.
    public var fresh: Bool
    /// The roster in the phone's order.
    public var bots: [Bot]
    /// Members of listed groups that aren't in `bots`, for group avatars only.
    public var faces: [Bot]
    public var builtAt: Date

    public init(scope: WatchScope?, computerName: String?, state: WatchComputerState, fresh: Bool, bots: [Bot], faces: [Bot] = [], builtAt: Date) {
        self.scope = scope
        self.computerName = computerName
        self.state = state
        self.fresh = fresh
        self.bots = bots
        self.faces = faces
        self.builtAt = builtAt
    }

    /// `roster`: the bots the phone lists, in order. `all`: every bot it knows, for group faces.
    /// Scrubbed and capped to fit `WatchLimits.envelopeBytes`; faces go first, then the roster's tail.
    public static func build(scope: WatchScope?, computerName: String?, state: WatchComputerState, fresh: Bool,
                             roster: [Bot], all: [String: Bot], now: Date) -> WatchSnapshot {
        let listed = Array(roster.prefix(WatchLimits.roster)).map { $0.scrubbedForWatch() }
        var seen = Set(listed.map(\.id))
        var faces: [Bot] = []
        for group in listed where group.isGroup {
            for id in group.members {
                if let member = all[id], seen.insert(id).inserted { faces.append(member.scrubbedForWatch()) }
            }
        }
        var snapshot = WatchSnapshot(scope: scope, computerName: computerName, state: state, fresh: fresh,
                                     bots: listed, faces: faces, builtAt: now)
        func trim(to count: Int) {
            snapshot.bots = Array(listed.prefix(count))
            snapshot.faces = Array(faces.prefix(max(0, count - listed.count)))
        }
        let keep = WatchLimits.largest(upTo: listed.count + faces.count) { count in
            trim(to: count)
            return WatchLimits.fits(.snapshot(snapshot))
        }
        trim(to: keep)
        return snapshot
    }
}

public struct WatchChat: Codable, Equatable, Sendable {
    public var scope: WatchScope
    public var botId: String
    public var bot: Bot
    /// Chat entries, oldest first.
    public var entries: [Entry]
    /// Approval entry id -> the option whose answer is on its way.
    public var answering: [String: String]
    /// Nonces of the newest user messages, including ones older than `entries`.
    public var recentNonces: [String]
    public var fresh: Bool
    public var builtAt: Date

    public init(scope: WatchScope, botId: String, bot: Bot, entries: [Entry], answering: [String: String],
                recentNonces: [String] = [], fresh: Bool, builtAt: Date) {
        self.scope = scope
        self.botId = botId
        self.bot = bot
        self.entries = entries
        self.answering = answering
        self.recentNonces = recentNonces
        self.fresh = fresh
        self.builtAt = builtAt
    }

    /// `entries`: the bot's main chat, oldest first. Keeps the chat-visible newest 25, scrubbed,
    /// and drops the oldest until it fits `WatchLimits.envelopeBytes`.
    public static func build(scope: WatchScope, bot: Bot, entries: [Entry], answering: [String: String],
                             fresh: Bool, now: Date) -> WatchChat {
        let scrubbed = entries.filter(\.isChat).suffix(WatchLimits.chatEntries).map { $0.scrubbedForWatch() }
        let nonces = entries.filter { $0.kind == "user" }.suffix(WatchLimits.recentNonces).compactMap(\.data.clientNonce)
        var chat = WatchChat(scope: scope, botId: bot.id, bot: bot.scrubbedForWatch(), entries: scrubbed,
                             answering: answering, recentNonces: nonces, fresh: fresh, builtAt: now)
        let keep = WatchLimits.largest(upTo: scrubbed.count) { count in
            chat.entries = Array(scrubbed.suffix(count))
            return WatchLimits.fits(.chat(chat))
        }
        chat.entries = Array(scrubbed.suffix(keep))
        return chat
    }
}

extension WatchLimits {
    static func fits(_ message: WatchMessage) -> Bool {
        guard let size = try? WatchEnvelope.fromPhone(message).encode().count else { return false }
        return size <= envelopeBytes
    }

    /// The most items (0...`upTo`) for which `fits` holds, assuming fewer always fits.
    static func largest(upTo n: Int, fits: (Int) -> Bool) -> Int {
        var low = 0, high = n
        while low < high {
            let mid = (low + high + 1) / 2
            if fits(mid) { low = mid } else { high = mid - 1 }
        }
        return low
    }
}

extension Bot {
    /// What a watch row needs: identity, avatar and live state. Paths, commands and config stay on the phone.
    func scrubbedForWatch() -> Bot {
        var bot = self
        bot.description = ""
        bot.cwd = ""
        bot.command = nil
        bot.model = nil
        bot.connectors = []
        bot.skills = []
        bot.lastMessage = lastMessage.map { String($0.prefix(WatchLimits.lastMessage)) }
        bot.activity = String(activity.prefix(WatchLimits.activity))
        return bot
    }
}

extension Entry {
    /// Keeps the fields the watch draws; diffs, output, detail, locations, plans and connection requests stay on the phone.
    func scrubbedForWatch() -> Entry {
        var kept = EntryData(text: data.text.map { String($0.prefix(WatchLimits.entryText)) }, status: data.status, clientNonce: data.clientNonce)
        kept.final = data.final
        kept.title = data.title.map { String($0.prefix(WatchLimits.entryTitle)) }
        kept.toolKind = data.toolKind
        kept.options = data.options
        kept.selected = data.selected
        kept.style = data.style
        // The watch draws the row only: the bots and status, never the request or reply.
        if let message = data.botMessage {
            kept.text = nil
            kept.botMessage = BotMessage(sourceBotId: message.sourceBotId, targetBotId: message.targetBotId, text: "")
        }
        kept.author = data.author
        kept.callSeconds = data.callSeconds
        kept.attachments = data.attachments?.map { Attachment(id: $0.id, name: $0.name, size: 0) }
        var entry = self
        entry.data = kept
        return entry
    }
}
