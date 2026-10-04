#if os(macOS)
import CodyncKit
import SwiftUI

extension ThreadView {
    var platformBody: some View {
        GeometryReader { geometry in
            HStack(spacing: 0) {
                conversation.frame(maxWidth: .infinity)
                if let openThread, geometry.size.width >= 680 {
                    HStack(spacing: 0) {
                        Rectangle().fill(Palette.border).frame(width: 1)
                        RepliesView(botId: botId, rootId: openThread.id) { closeThread() }
                            .frame(width: min(420, geometry.size.width * 0.45))
                    }
                    .id(openThread.id)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                } else if showSettings && geometry.size.width >= 680 {
                    HStack(spacing: 0) {
                        Rectangle().fill(Palette.border).frame(width: 1)
                        detailsPanel.frame(width: 292)
                    }
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .clipped()
            .onGeometryChange(for: CGFloat.self) {
                $0.size.width
            } action: {
                availableWidth = $0
            }
        }
        .ignoresSafeArea(.container, edges: .top)
        .animation(Motion.reduced(Motion.layout, reduceMotion), value: openThread)
        .codyncSheet(isPresented: $compactDetails) {
            detailsPanel.frame(width: 340, height: 600)
        }
        .codyncSheet(isPresented: Binding(get: { openThread != nil && availableWidth < 680 }, set: { if !$0 { openThread = nil } })) {
            if let openThread {
                RepliesView(botId: botId, rootId: openThread.id) { closeThread() }.frame(width: 440, height: 600)
            }
        }
    }

    /// The Mac's messages: every loaded message in a lazy stack, brought to the newest as it
    /// changes while the reader is at the bottom.
    @ViewBuilder var transcript: some View {
        let thread = model.chat(botId)
        let items = ChatItem.build(thread, streaming: bot?.isWorking(in: botId, thread: nil) == true && !model.isOffline)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if !model.historyComplete.contains(botId), thread.count >= 50 {
                        Button("Load earlier messages") { Task { await model.loadOlder(botId) } }
                            .buttonStyle(.plain)
                            .appFont(.footnote)
                            .foregroundStyle(Palette.secondary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    if items.isEmpty, let bot {
                        if bot.isGroup { GroupIntroCard(group: bot).padding(.top, 40) } else { IntroCard(bot: bot).padding(.top, 40) }
                    }
                    ForEach(items) { item in
                        row(item)
                            .id(item.id)
                            .transition(item.id == items.last?.id
                                ? .asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity)
                                : .identity)
                    }
                    // Offline, "working" is only what the computer last said; don't show it as live.
                    if let bot, bot.isWorking(in: botId, thread: nil), !model.isOffline {
                        WorkingIndicator(bot: bot, thinking: model.currentThinking(botId, thread: nil))
                            .padding(.top, 6)
                            .id("working")
                    }
                    Color.clear.frame(height: 8).id("bottom")
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .animation(Motion.reduced(Motion.conversation, reduceMotion), value: items.last?.id)
                .frame(maxWidth: 820)
                .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.never)
            .conversationInitialBottomAnchor()
            .scrollDismissesKeyboard(.interactively)
            .conversationScrollEdges()
            .conversationBottomObserver($isAtBottom)
            .onChange(of: items.last?.id) { _, _ in
                guard isAtBottom || items.last?.isUserMessage == true else { return }
                withAnimation(Motion.reduced(Motion.conversation, reduceMotion)) {
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }
            .onChange(of: items.last?.textContent) { _, _ in
                guard isAtBottom else { return }
                withAnimation(Motion.reduced(Motion.conversation, reduceMotion)) {
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }
            .onChange(of: bot?.isWorking) { _, _ in
                guard isAtBottom else { return }
                withAnimation(Motion.reduced(Motion.conversation, reduceMotion)) {
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }
            .onChange(of: typography.pointSize) { _, _ in
                guard isAtBottom else { return }
                proxy.scrollTo("bottom", anchor: .bottom)
            }
        }
    }

    func platformChrome(_ content: some View) -> some View {
        content
            // Grok's desktop chat: no title bar. The chat blurs and fades as it scrolls up, with the bot
            // in a floating solid pill and the actions as glass buttons on the right.
            .safeAreaInset(edge: .top, spacing: 0) {
                ZStack {
                    HStack(spacing: 8) {
                        Spacer(minLength: 0)
                        if !showSettings || availableWidth < 680 {
                            IconButton("Conversation details", systemImage: "chevron.left.2") { toggleDetails() }
                                .keyboardShortcut("i", modifiers: [.command, .option])
                                .frosted(in: Circle())
                                .transition(.opacity)
                        }
                    }
                    VStack(spacing: 2) {
                        header
                        if model.shownConnection != .online || model.mismatch != nil { connectionSubtitle }
                    }
                    .padding(.leading, 10)
                    .padding(.trailing, 16)
                    .padding(.vertical, 9)
                    .background(Palette.background, in: Capsule())
                    .shadow(color: .black.opacity(0.12), radius: 16, y: 4)
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 14)
                .background(alignment: .top) { TopFade() }
            }
    }

    var header: some View {
        Button(action: toggleDetails) { title }
            .buttonStyle(.plain)
            .accessibilityLabel("View conversation details")
            .help("View conversation details")
    }

    private func toggleDetails() {
        if availableWidth < 680 {
            compactDetails.toggle()
        } else {
            withAnimation(Motion.reduced(Motion.layout, reduceMotion)) { showSettings.toggle() }
        }
    }

    /// Its own view so it stays live inside the compact modal (which captures its content).
    private var detailsPanel: some View {
        DetailsPanel(botId: botId, routineId: routineId, editing: $editingDetails, editGroup: { editingGroup = true },
                     share: {
                         compactDetails = false
                         if let bot { templateRequest = EditorRequest(BotDraft(bot)) }
                     }) {
            withAnimation(Motion.reduced(Motion.layout, reduceMotion)) { showSettings = false }
            compactDetails = false
        }
        .id(routineRequest)
    }

    func presentRoutine() {
        editingDetails = false
        openThread = nil
        showSettings = true
        if availableWidth < 680 { compactDetails = true }
    }

    func editProfile(_ bot: Bot) {
        withAnimation(Motion.reduced(Motion.layout, reduceMotion)) {
            editingDetails = true
            showSettings = true
        }
        if availableWidth < 680 { compactDetails = true }
    }
}

/// A group's bots in the Mac inspector; clicking one opens its own chat.
private struct GroupMembersList: View {
    let group: Bot
    @Environment(BotStore.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(group.members.count) bots").appFont(.system(size: 13, weight: .semibold)).padding(.bottom, 6)
                ForEach(model.members(of: group)) { bot in
                    Button { model.selection = bot.id } label: {
                        HStack(spacing: 10) {
                            CharacterAvatar(bot: bot, size: 26)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(bot.name).appFont(.system(size: 13)).foregroundStyle(Palette.text)
                                Text(bot.isWorking ? (bot.activity.isEmpty ? "Working…" : bot.activity) : bot.folderName)
                                    .appFont(.system(size: 11)).foregroundStyle(Palette.tertiary).lineLimit(1)
                            }
                            Spacer()
                        }
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressScale())
                    .help("Open \(bot.name)'s own chat")
                }
            }
            .padding(.horizontal, 16)
        }
    }
}

/// The Mac inspector beside a conversation: the computer, the agent, and the bot's settings.
private struct DetailsPanel: View {
    let botId: String
    let routineId: String?
    @Binding var editing: Bool
    let editGroup: () -> Void
    /// Shares the bot as a template.
    let share: () -> Void
    let close: () -> Void
    @Environment(BotStore.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var bot: Bot? { model.bots[botId] }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                if editing {
                    roundButton("Back to details", "chevron.left") { setEditing(false) }
                    panelTitle("Settings")
                    Spacer()
                } else if bot?.isGroup == true {
                    panelTitle("Members")
                    Spacer()
                    roundButton("Edit group", "gearshape", action: editGroup)
                } else {
                    panelTitle("Details")
                    Spacer()
                    roundButton("Create template", "square.and.arrow.up", action: share)
                    roundButton("Bot settings", "gearshape") { setEditing(true) }
                }
                roundButton("Close details", "chevron.right.2", action: close)
                    .keyboardShortcut("i", modifiers: [.command, .option])
            }
            .padding(.horizontal, 12)
            .frame(height: 54)
            ZStack {
                if let bot, bot.isGroup {
                    GroupMembersList(group: bot)
                } else if editing {
                    BotSettingsPanel(botId: botId)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                } else {
                    details
                        .transition(.move(edge: .leading).combined(with: .opacity))
                }
            }
            .frame(maxHeight: .infinity, alignment: .top)
            .clipped()
        }
        .background(Palette.background)
    }

    private func setEditing(_ on: Bool) {
        withAnimation(Motion.reduced(Motion.layout, reduceMotion)) { editing = on }
    }

    private func panelTitle(_ title: String) -> some View {
        Text(title)
            .appFont(.system(size: 15, weight: .semibold))
            .foregroundStyle(Palette.text)
            .padding(.leading, 4)
    }

    /// A round action in the panel's header, frosted like the chat's title pill (Grok's style).
    private func roundButton(_ label: String, _ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .appFont(.system(size: 13, weight: .medium))
                .foregroundStyle(Palette.text)
                .frame(width: 32, height: 32)
                .frosted(in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
        .help(label)
    }

    private var details: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                computerSummary
                RoutinesView(botId: botId, initialId: routineId)
                if let bot { agentSummary(bot) }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 24)
        }
    }

    private var computerSummary: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                // Laptop, Mac mini, Linux…: the computer's own icon and color, as everywhere else.
                ComputerBadge(model.computer, size: 32)
                VStack(alignment: .leading, spacing: 4) {
                    Text(model.hostName)
                        .appFont(.system(size: 15, weight: .medium))
                        .foregroundStyle(Palette.text)
                        .lineLimit(2)
                    Text("Remote screen")
                        .appFont(.system(size: 13))
                        .foregroundStyle(Palette.secondary)
                }
            }
            if !model.isOffline {
                Label(computerStatus, systemImage: computerStatusSymbol)
                    .appFont(.system(size: 13))
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if screenReady {
                Text("View and control this Mac from your iPhone.")
                    .appFont(.system(size: 12))
                    .foregroundStyle(Palette.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, -8)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func agentSummary(_ bot: Bot) -> some View {
        CardSection("Agent", footer: bot.description.isEmpty ? nil : bot.description) {
            infoRow("Runtime") {
                HStack(spacing: 6) {
                    AgentIcon(registry: model.hello?.backends.first { $0.id == bot.backend }?.registry, size: 14)
                    Text(model.backendName(bot.backend))
                }
            }
            infoRow(bot.managedWorkspace ? "Workspace" : "Folder") {
                Text(bot.managedWorkspace ? "Personal" : bot.folderName)
                    .truncationMode(.middle)
                    .help(bot.managedWorkspace ? "Personal workspace, managed by Codync" : bot.cwd)
            }
            if let chosen = bot.model, !chosen.isEmpty {
                infoRow("Model") { Text(chosen).truncationMode(.middle) }
            }
        }
    }

    /// Label on the left, value on the right, one line.
    private func infoRow(_ label: String, @ViewBuilder value: () -> some View) -> some View {
        HStack(spacing: 12) {
            Text(label).foregroundStyle(Palette.secondary)
            Spacer(minLength: 8)
            value().foregroundStyle(Palette.text).lineLimit(1)
        }
        .appFont(.system(size: 13))
    }

    private var screenReady: Bool {
        !model.isOffline && model.screen?.enabled == true
            && model.screen?.connected == true && model.screen?.capture == true
    }

    private var computerStatusSymbol: String {
        guard let screen = model.screen, screen.enabled else { return "power" }
        if !screen.connected { return "arrow.triangle.2.circlepath" }
        if !screen.capture { return "exclamationmark.circle" }
        return screen.agentBot == botId ? "cursorarrow.motionlines" : "checkmark.circle"
    }

    private var computerStatus: String {
        guard let screen = model.screen, screen.enabled else { return "Remote screen is off" }
        guard screen.connected else { return "Connecting to computer…" }
        guard screen.capture else { return "Screen recording permission needed" }
        return screen.agentBot == botId ? "This bot is using your Mac" : "Ready to connect"
    }
}

/// The top edge of the chat: messages fade out gradually as they scroll under the title pill,
/// readable behind the pill and gone only at the very top.
private struct TopFade: View {
    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
                .mask(LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .center))
            LinearGradient(stops: [.init(color: Palette.background, location: 0),
                                   .init(color: Palette.background.opacity(0.6), location: 0.35),
                                   .init(color: Palette.background.opacity(0), location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea(edges: .top)
        .allowsHitTesting(false)
    }
}
#endif
