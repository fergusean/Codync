import CodyncKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The message box under a chat or a thread: send, or stop while the bot (or the
/// group) is working there. In a group, typing `@` suggests its bots.
struct Composer: View {
    let botId: String
    /// Replies go to the thread on this message.
    var thread: String?
    /// Starts a voice call; while the box is empty it takes the send button's place.
    var onCall: (() -> Void)?
    /// Interrupts the bot while it is speaking in a voice call.
    var onInterrupt: (() -> Void)?
    @Environment(BotStore.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.conversationTypography) private var typography
    @State private var draft = ""
    /// Files going out with the next message.
    @State private var files: [OutgoingFile] = []
    @State private var pickingFiles = false
    @State private var pickingPhotos = false
    @State private var photoItems: [PhotosPickerItem] = []
    #if os(iOS)
        /// The clipboard's `changeCount` last pasted (or passed on), so one copy is offered once.
        @State private var pasteCount = -1
        @State private var clipboardTick = 0
    #else
        @State private var pasteMonitor: Any?
    #endif
    @FocusState private var focused: Bool
    /// One line of the field's text, following Dynamic Type: the send button centers on the last line.
    @ScaledMetric(relativeTo: .body) private var lineHeight = InterfaceMetrics.value(mac: 15, mobile: 22)

    private var bot: Bot? { model.bots[botId] }
    private var working: Bool { bot?.isWorking(in: botId, thread: thread) == true }

    private var isEmpty: Bool { draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && files.isEmpty }

    private var canSend: Bool {
        // Offline computers still take messages when the relay can hold them for it (text only).
        !isEmpty && (model.connection == .online || (model.canQueue && files.isEmpty))
    }

    /// Groups have no folder of their own to put files in.
    private var canAttach: Bool { bot?.isGroup == false }

    private var placeholder: String {
        let name = bot?.name ?? ""
        if thread != nil { return "Reply…" }
        if bot?.isGroup == true { return "Message \(name) · @ to ask one bot" }
        return working ? "Queue a message for \(name)" : "Ask \(name)"
    }

    /// The `@partial` being typed at the end, if any.
    private var mentionQuery: String? {
        guard bot?.isGroup == true, let at = draft.lastIndex(of: "@") else { return nil }
        let query = draft[draft.index(after: at)...]
        if at > draft.startIndex, !draft[draft.index(before: at)].isWhitespace { return nil }
        return query.contains(where: \.isNewline) || query.count > 24 ? nil : String(query)
    }

    private var suggestions: [Bot] {
        guard let query = mentionQuery, let bot else { return [] }
        return model.members(of: bot).filter {
            query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !suggestions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(suggestions) { member in
                            Button { mention(member) } label: {
                                HStack(spacing: 6) {
                                    CharacterAvatar(bot: member, size: 18, animated: false)
                                    Text(member.name).font(.subheadline).foregroundStyle(Palette.text)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(Palette.surface, in: Capsule())
                            }
                            .buttonStyle(PressScale())
                        }
                    }
                    .padding(.horizontal, 16)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            field
        }
        .animation(Motion.layout, value: suggestions.map(\.id))
    }

    private var fieldPadding: CGFloat { InterfaceMetrics.value(mac: 7, mobile: 10) }
    private var buttonSize: CGFloat { InterfaceMetrics.value(mac: 28, mobile: 34) }
    private var textLineHeight: CGFloat {
        #if os(macOS)
        ceil(typography.pointSize * 1.25)
        #else
        lineHeight
        #endif
    }

    private var field: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if canAttach { addButton }
            VStack(alignment: .leading, spacing: 0) {
                #if os(iOS)
                    let _ = clipboardTick
                    if clipboardOffer { pasteOffer.transition(.move(edge: .bottom).combined(with: .opacity)) }
                #endif
                if !files.isEmpty {
                    fileChips.transition(.move(edge: .bottom).combined(with: .opacity))
                }
                box
            }
            .composerSurface(in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
        .animation(Motion.layout, value: files.map(\.id))
        #if os(iOS)
            // The clipboard isn't observable: look again when typing starts or the app comes back.
            .onChange(of: focused) { _, _ in clipboardTick += 1 }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in clipboardTick += 1 }
            .onReceive(NotificationCenter.default.publisher(for: UIPasteboard.changedNotification)) { _ in clipboardTick += 1 }
        #else
            .onAppear(perform: installPasteMonitor)
            .onDisappear {
                if let pasteMonitor { NSEvent.removeMonitor(pasteMonitor) }
                pasteMonitor = nil
            }
        #endif
        .dropDestination(for: PickedFile.self) { picked, _ in
            guard canAttach else { return false }
            append(picked.map(\.file))
            return true
        }
        .fileImporter(isPresented: $pickingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { add(urls) }
        }
        #if os(iOS)
            .photosPicker(isPresented: $pickingPhotos, selection: $photoItems, maxSelectionCount: 10, matching: .images)
            .onChange(of: photoItems) { _, items in
                guard !items.isEmpty else { return }
                photoItems = []
                Task { await addPhotos(items) }
            }
        #endif
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 16)
        #if os(macOS)
            .frame(maxWidth: 820)
            .frame(maxWidth: .infinity)
        #endif
    }

    private var box: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(placeholder, text: $draft, axis: .vertical)
                .lineLimit(1...8)
                .font(typography.body)
                .textFieldStyle(.plain)
                .focused($focused)
                .padding(.vertical, fieldPadding)
                .sendOnReturn(submit)
            Group {
            if let onInterrupt, draft.isEmpty, files.isEmpty {
                Button(action: onInterrupt) {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 12, weight: .bold))
                        .frame(width: InterfaceMetrics.value(mac: 28, mobile: 34), height: InterfaceMetrics.value(mac: 28, mobile: 34))
                        .background(Palette.danger, in: Circle())
                        .foregroundStyle(.white)
                        .contentTransition(.symbolEffect(.replace))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Interrupt")
                .help("Interrupt")
            } else if working && draft.isEmpty {
                Button {
                    model.stop(botId)
                } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 12, weight: .bold))
                        .frame(width: InterfaceMetrics.value(mac: 28, mobile: 34), height: InterfaceMetrics.value(mac: 28, mobile: 34))
                        .background(Palette.accentFill, in: Circle())
                        .foregroundStyle(Palette.onAccent)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Stop")
                .help("Stop")
            } else if let onCall, draft.isEmpty, files.isEmpty {
                Button(action: onCall) {
                    Image(systemName: "waveform")
                        .font(.system(size: 15, weight: .bold))
                        .frame(width: buttonSize * 1.35, height: buttonSize)
                        .background(Palette.accentFill, in: Capsule())
                        .foregroundStyle(Palette.onAccent)
                }
                .buttonStyle(.plain)
                .transition(.opacity)
                .accessibilityLabel("Call")
                .help("Call")
            } else {
                Button(action: submit) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 14, weight: .bold))
                        .frame(width: InterfaceMetrics.value(mac: 28, mobile: 34), height: InterfaceMetrics.value(mac: 28, mobile: 34))
                        .background(canSend ? Palette.accentFill : Palette.accentDim, in: Circle())
                        .foregroundStyle(canSend ? Palette.onAccent : Palette.tertiary)
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .accessibilityLabel("Send")
                .help("Send")
            }
            }
            .animation(Motion.layout, value: onInterrupt != nil)
            .padding(.bottom, max(0, (textLineHeight + 2 * fieldPadding - buttonSize) / 2))
            .animation(Motion.layout, value: isEmpty)
        }
        .padding(.leading, InterfaceMetrics.value(mac: 14, mobile: 18))
        .padding(.trailing, 6)
        .padding(.vertical, InterfaceMetrics.value(mac: 4, mobile: 6))
        .onChange(of: model.routineDrafts[botId]) { _, value in
            guard thread == nil, let value else { return }
            draft = draft.isEmpty ? value : draft + "\n" + value
            model.routineDrafts.removeValue(forKey: botId)
            focused = true
        }
    }

    /// Photos or files (iPhone, a system menu), files (Mac); dropping files on the box works too.
    @ViewBuilder private var addButton: some View {
        #if os(iOS)
            DropdownMenu {
                var items = [MenuItem("Photos", icon: "photo.on.rectangle") { pickingPhotos = true },
                             MenuItem("Files", icon: "folder") { pickingFiles = true }]
                if UIPasteboard.general.hasImages || UIPasteboard.general.hasURLs {
                    items.append(MenuItem("Paste", icon: "doc.on.clipboard") { pasteFiles() })
                }
                return items
            } label: { addLabel }
                .accessibilityLabel("Add files")
        #else
            Button { pickingFiles = true } label: { addLabel }
                .buttonStyle(PressScale())
                .accessibilityLabel("Add files")
                .help("Add files")
        #endif
    }

    private var addLabel: some View {
        Image(systemName: "plus")
            .font(.system(size: InterfaceMetrics.value(mac: 13, mobile: 18), weight: .medium))
            .foregroundStyle(Palette.text)
            .frame(width: buttonSize + 2 * InterfaceMetrics.value(mac: 4, mobile: 6),
                   height: buttonSize + 2 * InterfaceMetrics.value(mac: 4, mobile: 6))
            .composerSurface(in: Circle())
            .contentShape(Circle())
    }

    #if os(iOS)
        private var pasteOffer: some View {
            HStack(spacing: 6) {
                Button(action: pasteFiles) {
                    Label("Paste image", systemImage: "doc.on.clipboard")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Palette.surface, in: Capsule())
                }
                .buttonStyle(PressScale())
                Button { pasteCount = UIPasteboard.general.changeCount } label: {
                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundStyle(Palette.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Don't paste")
            }
            .padding(.horizontal, 10)
            .padding(.top, 8)
        }
    #endif

    private var fileChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(files) { file in
                    HStack(spacing: 6) {
                        Image(systemName: AttachmentIcon.symbol(file.name))
                            .foregroundStyle(Palette.secondary)
                        Text(file.name)
                            .font(.footnote)
                            .foregroundStyle(Palette.text)
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .frame(maxWidth: 160, alignment: .leading)
                        Button {
                            files.removeAll { $0.id == file.id }
                        } label: {
                            Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundStyle(Palette.secondary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Remove \(file.name)")
                        .help("Remove")
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Palette.surface, in: Capsule())
                }
            }
            .padding(.horizontal, 10)
            .padding(.top, 8)
        }
    }

    private func add(_ urls: [URL]) {
        append(urls.compactMap { url in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            return (try? Data(contentsOf: url, options: .mappedIfSafe)).map { OutgoingFile.prepared(name: url.lastPathComponent, data: $0) }
        })
    }

    private func append(_ new: [OutgoingFile]) {
        guard canAttach else { return }
        for file in new {
            guard file.data.count <= OutgoingFile.maxSize else {
                model.lastError = "\(file.name) is larger than 100 MB."
                continue
            }
            files.append(file)
        }
    }

    /// Images (or files) on the clipboard become attachments.
    private func pasteFiles() {
        #if os(iOS)
            let providers = UIPasteboard.general.itemProviders
            pasteCount = UIPasteboard.general.changeCount
        #else
            let providers = (NSPasteboard.general.pasteboardItems ?? []).compactMap { item -> NSItemProvider? in
                if let url = item.string(forType: .fileURL).flatMap(URL.init(string:)) { return NSItemProvider(contentsOf: url) }
                for type in [NSPasteboard.PasteboardType.png, .tiff] {
                    if let data = item.data(forType: type) {
                        return NSItemProvider(item: data as NSData, typeIdentifier: type == .png ? UTType.png.identifier : UTType.tiff.identifier)
                    }
                }
                return nil
            }
        #endif
        Task { append(await PickedFile.load(providers)) }
    }

    #if os(iOS)
        /// Photos go out as JPEG: HEIC isn't something every agent can read.
        private func addPhotos(_ items: [PhotosPickerItem]) async {
            let stamp = Int(Date.now.timeIntervalSince1970)
            var picked: [OutgoingFile] = []
            for (i, item) in items.enumerated() {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let jpeg = ImageData.jpeg(data) else { continue }
                picked.append(OutgoingFile(name: "photo-\(stamp)-\(i + 1).jpg", data: jpeg))
            }
            append(picked)
        }

        /// A copied image not offered yet: shown as a "Paste image" chip while typing.
        private var clipboardOffer: Bool {
            canAttach && focused && UIPasteboard.general.hasImages && UIPasteboard.general.changeCount != pasteCount
        }
    #else
        /// ⌘V with a copied image or file (the text field alone would paste nothing, or just
        /// the file's name).
        private func installPasteMonitor() {
            guard pasteMonitor == nil else { return }
            pasteMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
                let consumed = MainActor.assumeIsolated {
                    guard focused, canAttach, event.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command,
                          event.charactersIgnoringModifiers == "v" else { return false }
                    let types = NSPasteboard.general.types ?? []
                    guard types.contains(.fileURL) || (!types.contains(.string) && (types.contains(.png) || types.contains(.tiff))) else { return false }
                    pasteFiles()
                    return true
                }
                return consumed ? nil : event
            }
        }
    #endif

    private func mention(_ member: Bot) {
        guard let at = draft.lastIndex(of: "@") else { return }
        draft = String(draft[..<at]) + "@\(member.name) "
        focused = true
    }

    private func submit() {
        guard canSend else { return }
        withAnimation(Motion.reduced(Motion.conversation, reduceMotion)) {
            model.send(draft, to: botId, thread: thread, files: files)
            draft = ""
            files = []
        }
    }
}
