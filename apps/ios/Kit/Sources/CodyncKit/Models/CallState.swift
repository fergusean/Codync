/// A voice call with the bot this phone is on, as its Live Activity shows it.
public enum CallState: String, Codable, Hashable, Sendable {
    case connecting, listening, speaking, paused
}
