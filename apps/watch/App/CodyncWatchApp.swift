import CodyncKit
import SwiftUI

@main
@MainActor
struct CodyncWatchApp: App {
    @State private var store: WatchStore
    private let routing: NotificationRouting
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let store = WatchStore()
        _store = State(initialValue: store)
        routing = NotificationRouting(store: store)
    }

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $store.path) {
                RosterView()
                    .navigationDestination(for: String.self) { ChatView(botId: $0) }
            }
            .environment(store)
            .containerBackground(Palette.background, for: .navigation)
            // `hello` and the 10 s renewals run while the app is in front; leaving closes the lease.
            .onChange(of: scenePhase, initial: true) { _, phase in store.setActive(phase == .active) }
        }
    }
}
