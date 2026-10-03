import Foundation
import Testing
@testable import CodyncKit
@testable import CodyncUI

@MainActor
struct ComposerDraftTests {
    private let suite = "ComposerDraftTests.\(UUID().uuidString)"

    private func store(_ computerId: String = UUID().uuidString) -> BotStore {
        let computer = Computer(id: computerId, name: "Fixture", signKey: "")
        let storage = SharedStore.Context(accountID: nil, suite: suite)
        return BotStore(computer: computer, route: .channel, clientKind: "ios", storage: storage) {
            FakeRemote(.connecting)
        }
    }

    private func retire(_ stores: BotStore...) {
        for store in stores { store.retire() }
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
    }

    @Test func leavingAndReturningRestoresExactTextForEachBot() {
        let store = store()
        defer { retire(store) }
        let first = "  First bot\nunfinished text 🐱\n"
        store.selection = "first"
        store.setComposerDraft(first, for: "first")
        store.selection = nil
        store.selection = "second"
        #expect(store.composerDraft(for: "second").isEmpty)
        store.setComposerDraft("Second bot", for: "second")
        store.selection = "first"
        #expect(store.composerDraft(for: "first") == first)
        #expect(store.composerDraft(for: "second") == "Second bot")
        store.setComposerDraft("", for: "first")
        #expect(store.composerDraft(for: "first").isEmpty)
        #expect(store.composerDraft(for: "second") == "Second bot")
    }

    @Test func mainChatAndEachThreadKeepSeparateDrafts() {
        let store = store()
        defer { retire(store) }
        store.setComposerDraft("Main chat", for: "bot")
        store.setComposerDraft("First reply", for: "bot", thread: "first")
        store.setComposerDraft("Second reply", for: "bot", thread: "second")
        store.setComposerDraft("Other bot reply", for: "other", thread: "first")
        #expect(store.composerDraft(for: "bot") == "Main chat")
        #expect(store.composerDraft(for: "bot", thread: "first") == "First reply")
        #expect(store.composerDraft(for: "bot", thread: "second") == "Second reply")
        #expect(store.composerDraft(for: "other", thread: "first") == "Other bot reply")
    }

    @Test func submittingClearsOnlyItsConversationAndKeepsTheLocalMessage() {
        let store = store()
        defer { retire(store) }
        store.setComposerDraft("  Send this  ", for: "first")
        store.setComposerDraft("Keep this", for: "second")
        store.setComposerDraft("Keep reply", for: "first", thread: "root")
        #expect(store.sendComposerDraft(to: "first"))
        #expect(store.composerDraft(for: "first").isEmpty)
        #expect(store.chat("first").last?.data.text == "Send this")
        #expect(store.composerDraft(for: "second") == "Keep this")
        #expect(store.composerDraft(for: "first", thread: "root") == "Keep reply")
        store.setComposerDraft("New unsent text", for: "first")
        #expect(store.sendComposerDraft(to: "first", thread: "root"))
        #expect(store.composerDraft(for: "first", thread: "root").isEmpty)
        #expect(store.composerDraft(for: "first") == "New unsent text")
        #expect(store.replies("first", root: "root").last?.data.text == "Keep reply")
    }

    @Test func whitespaceOnlySubmissionKeepsTheDraft() {
        let store = store()
        defer { retire(store) }
        store.setComposerDraft(" \n ", for: "bot")
        #expect(!store.sendComposerDraft(to: "bot"))
        #expect(store.composerDraft(for: "bot") == " \n ")
        #expect(store.chat("bot").isEmpty)
    }

    @Test func computersAndRetiredAccountStoresDoNotShareDrafts() {
        let first = store("first-computer")
        let second = store("second-computer")
        let otherAccount = store("first-computer")
        defer { retire(first, second, otherAccount) }
        first.setComposerDraft("Private draft", for: "same-bot-id")
        #expect(second.composerDraft(for: "same-bot-id").isEmpty)
        #expect(otherAccount.composerDraft(for: "same-bot-id").isEmpty)
        first.retire()
        #expect(first.composerDraft(for: "same-bot-id").isEmpty)
        first.setComposerDraft("Late view callback", for: "same-bot-id")
        #expect(first.composerDraft(for: "same-bot-id").isEmpty)
    }
}
