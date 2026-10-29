import Foundation
import Observation

/// Only explicitly advanced time reaches the bridge's lease and exchange timers.
@Observable @MainActor
final class WatchTestScheduler {
    private struct Sleeper {
        let deadline: Duration
        let continuation: CheckedContinuation<Void, any Error>
    }

    private(set) var deadlines: [Duration] = []
    private(set) var pendingCount = 0
    @ObservationIgnored private var elapsed: Duration = .zero
    @ObservationIgnored private var sleepers: [UUID: Sleeper] = [:]

    func sleep(for duration: Duration) async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try Task.checkCancellation()
            guard duration > .zero else { return }
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let deadline = elapsed + duration
                sleepers[id] = Sleeper(deadline: deadline, continuation: continuation)
                deadlines.append(deadline)
                pendingCount = sleepers.count
            }
        } onCancel: {
            Task { @MainActor in self.cancel(id) }
        }
    }

    func advance(by duration: Duration) {
        precondition(duration >= .zero)
        elapsed += duration
        let ready = sleepers.filter { $0.value.deadline <= elapsed }
        for (id, sleeper) in ready {
            sleepers[id] = nil
            sleeper.continuation.resume()
        }
        pendingCount = sleepers.count
    }

    func cancelAll() {
        let waiting = Array(sleepers.values)
        sleepers.removeAll()
        pendingCount = 0
        for sleeper in waiting { sleeper.continuation.resume(throwing: CancellationError()) }
    }

    private func cancel(_ id: UUID) {
        guard let sleeper = sleepers.removeValue(forKey: id) else { return }
        pendingCount = sleepers.count
        sleeper.continuation.resume(throwing: CancellationError())
    }
}
