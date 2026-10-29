import Foundation
import UserNotifications

/// Opens the bot a notification is about. The alerts are the host's pushes, which iOS mirrors
/// here; their `userInfo` names the account (`ctx`), computer and bot.
@MainActor
final class NotificationRouting: NSObject, UNUserNotificationCenterDelegate {
    private let store: WatchStore

    init(store: WatchStore) {
        self.store = store
        super.init()
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        // The iPhone's categories, so a mirrored alert shows the same action.
        let open = UNNotificationAction(identifier: "openConversation", title: "Open conversation", options: [.foreground])
        let review = UNNotificationAction(identifier: "reviewRequest", title: "Review request", options: [.foreground])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "done", actions: [open], intentIdentifiers: []),
            UNNotificationCategory(identifier: "needsInput", actions: [review], intentIdentifiers: []),
            UNNotificationCategory(identifier: "failed", actions: [open], intentIdentifiers: []),
        ])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let ctx = info["ctx"] as? String, let computerId = info["computerId"] as? String,
              let botId = info["botId"] as? String else { return }
        await store.open(context: ctx, computerId: computerId, botId: botId)
    }

    /// A banner for the chat on screen is silenced (the reply is already there); any other shows.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let info = notification.request.content.userInfo
        guard let computerId = info["computerId"] as? String, let botId = info["botId"] as? String else { return [.banner, .sound] }
        return await store.isChatOpen(computerId: computerId, botId: botId) ? [] : [.banner, .sound]
    }
}
