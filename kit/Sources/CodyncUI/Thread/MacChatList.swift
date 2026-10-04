#if os(macOS)
import AppKit
import SwiftUI

/// SwiftUI owns message contents. AppKit owns wheel/momentum scrolling and the
/// document geometry; only rows within a screen of the viewport have hosting views.
struct MacChatList<Item: Identifiable & Equatable, Row: View>: NSViewRepresentable where Item.ID == String {
    let items: [Item]
    @Binding var following: Bool
    var revision: AnyHashable? = nil
    var nearTop: () -> Void = {}
    @ViewBuilder let row: (Item) -> Row

    func makeNSView(context: Context) -> ChatScrollView {
        let scroll = ChatScrollView()
        context.coordinator.attach(scroll)
        return scroll
    }

    func updateNSView(_ view: ChatScrollView, context: Context) {
        context.coordinator.update(self, environment: context.environment)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    static func dismantleNSView(_ view: ChatScrollView, coordinator: Coordinator) {
        view.changed = nil
        view.gesture = nil
        view.navigate = nil
        coordinator.cells.removeAll()
    }

    @MainActor final class Coordinator {
        final class Cell {
            let controller: NSHostingController<AnyView>
            let coordinates: MacChatCoordinates
            var generation = 0

            init(controller: NSHostingController<AnyView>, coordinates: MacChatCoordinates) {
                self.controller = controller
                self.coordinates = coordinates
            }
        }

        struct HeightReport {
            let height: CGFloat
            let width: CGFloat
            let generation: Int
        }

        struct Appearance: Equatable {
            let fontSize: Double
            let colorScheme: ColorScheme
            let contrast: ColorSchemeContrast
            let reduceMotion: Bool
            let enabled: Bool
            let locale: String
        }

        weak var scroll: ChatScrollView?
        var source: MacChatList?
        var environment = EnvironmentValues()
        var items: [Item] = []
        var layout = MacChatLayout()
        var cached: [String: CGFloat] = [:]
        var cells: [String: Cell] = [:]
        var width: CGFloat = 0
        var viewportSize: CGSize = .zero
        var fontSize: Double = 0
        var appearance: Appearance?
        var insets = EdgeInsets()
        var dirty = Set<String>()
        var nextGeneration = 0
        var pendingHeights: [String: HeightReport] = [:]
        var pendingAnchor: MacChatLayout.Anchor?
        var positionRequested = false
        var updating = false
        var follows = true
        var pending = false
        var arranging = false
        var refresh = false
        var reportedFollow = true
        var pendingFollowReport: Bool?
        var lastTopRequest: String?

        func attach(_ scroll: ChatScrollView) {
            self.scroll = scroll
            scroll.changed = { [weak self] in self?.viewportChanged() }
            scroll.gesture = { [weak self] before, event in
                guard let self else { return }
                if before, event.scrollingDeltaY > 0 { setFollowing(false) }
                if !before {
                    if event.scrollingDeltaY < 0, distanceToEnd < 32 { setFollowing(true) }
                    if event.phase == .ended || event.momentumPhase == .ended || event.phase.isEmpty {
                        requestEarlierIfNeeded()
                    }
                }
            }
            scroll.navigate = { [weak self] destination in
                guard let self, let scroll = self.scroll else { return }
                let end = max(0, layout.total - scroll.contentSize.height)
                let y: CGFloat
                switch destination {
                case .previous: y = scroll.contentView.bounds.minY - scroll.contentSize.height * 0.9
                case .next: y = scroll.contentView.bounds.minY + scroll.contentSize.height * 0.9
                case .first: y = 0
                case .last: y = end
                }
                setFollowing(y >= end)
                scroll.contentView.scroll(to: CGPoint(x: 0, y: min(end, max(0, y))))
                scroll.reflectScrolledClipView(scroll.contentView)
                schedule()
                requestEarlierIfNeeded()
            }
        }

        var distanceToEnd: CGFloat {
            guard let scroll else { return 0 }
            return max(0, layout.total - scroll.contentSize.height) - scroll.contentView.bounds.minY
        }

        func update(_ source: MacChatList, environment: EnvironmentValues) {
            updating = true
            defer { updating = false }
            let anchor = scroll.flatMap { layout.anchor(at: $0.contentView.bounds.minY) }
            let old = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
            let oldHeights = Dictionary(uniqueKeysWithValues: zip(layout.ids, layout.heights))
            let changed = source.items != items
            let typographyChanged = fontSize != environment.conversationTypography.pointSize
            let newAppearance = Appearance(fontSize: environment.conversationTypography.pointSize,
                colorScheme: environment.colorScheme, contrast: environment.colorSchemeContrast,
                reduceMotion: environment.accessibilityReduceMotion, enabled: environment.isEnabled,
                locale: environment.locale.identifier)
            refresh = refresh || appearance != newAppearance || self.source?.revision != source.revision
            appearance = newAppearance
            let insetsChanged = insets.top != environment.macChatInsets.top || insets.bottom != environment.macChatInsets.bottom
            insets = environment.macChatInsets
            self.source = source
            self.environment = environment
            fontSize = environment.conversationTypography.pointSize
            // A binding update from our own wheel handler is not a jump command.
            if let report = pendingFollowReport {
                // SwiftUI may send one update with the old binding before our
                // deferred wheel-state report arrives. It is not a jump command.
                if source.following == report { pendingFollowReport = nil }
            } else if source.following != reportedFollow {
                follows = source.following
                reportedFollow = source.following
                positionRequested = source.following
            }
            items = source.items
            let retained = Set(items.map(\.id))
            cached = cached.filter { retained.contains($0.key) }
            for item in items where old[item.id] != item {
                cached[item.id] = nil
                dirty.insert(item.id)
            }
            if typographyChanged { cached.removeAll() }
            if changed || typographyChanged || insetsChanged {
                if pendingAnchor == nil { pendingAnchor = anchor }
                layout.reset(ids: items.map(\.id), heights: items.map { cached[$0.id] ?? oldHeights[$0.id] ?? 100 })
                layout.setInsets(top: insets.top, bottom: insets.bottom)
                if changed { lastTopRequest = nil }
                positionRequested = true
            }
            schedule()
        }

        func viewportChanged() {
            guard !arranging, let scroll else { return }
            let visible = layout.visible(in: scroll.contentView.bounds, overscan: 0)
            if !updating, visible.contains(where: { cells[items[$0].id] == nil }) {
                // A large wheel/page movement must not present an uncovered viewport
                // while waiting for the next dispatch turn.
                arrange()
            } else {
                schedule()
            }
        }

        func schedule() {
            guard !pending, !arranging else { return }
            pending = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                pending = false
                arrange()
            }
        }

        func arrange() {
            guard !arranging, let scroll, let source, scroll.contentSize.width > 0 else { return }
            arranging = true
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            defer {
                CATransaction.commit()
                arranging = false
                if !pendingHeights.isEmpty { schedule() }
            }
            let anchor = pendingAnchor ?? layout.anchor(at: scroll.contentView.bounds.minY)
            pendingAnchor = nil
            let newWidth = max(1, min(820, scroll.contentSize.width) - 32)
            if abs(newWidth - width) > 0.5 {
                width = newWidth
                cached.removeAll()
                // Keep existing estimates until each row is measured at the new width.
                // In particular, opening an inspector must not collapse the document.
                refresh = true
            }
            let updateRoots = refresh
            var positionDirty = refresh || positionRequested || viewportSize != scroll.contentSize
            positionRequested = false
            viewportSize = scroll.contentSize
            refresh = false
            let reports = pendingHeights
            pendingHeights.removeAll()
            for (id, report) in reports {
                guard !dirty.contains(id), let cell = cells[id], cell.generation == report.generation,
                      abs(width - report.width) < 0.5, let index = layout.ids.firstIndex(of: id) else { continue }
                cached[id] = ceil(report.height)
                positionDirty = layout.measure(report.height, at: index) || positionDirty
            }
            var refreshed = Set<String>()
            var measuredIDs = Set<String>()
            // Measurement may reveal that a very tall estimated row covers the viewport.
            // Reconcile the visible range again before presenting the frame.
            while true {
                let previousSize = scroll.document.frame.size
                resizeDocument()
                if scroll.document.frame.size != previousSize { positionDirty = true }
                if positionDirty { restore(anchor) }
                let range = layout.visible(in: scroll.contentView.bounds, overscan: scroll.contentSize.height)
                var measured = false
                for index in range {
                    let item = items[index]
                    let cell: Cell
                    let created: Bool
                    if let existing = cells[item.id] { cell = existing; created = false }
                    else {
                        let coordinates = MacChatCoordinates()
                        let controller = NSHostingController(rootView: AnyView(EmptyView()))
                        controller.sizingOptions = []
                        // A row's intrinsic height must not depend on how far its
                        // hosting view has scrolled beneath the window's safe area.
                        (controller.view as? NSHostingView<AnyView>)?.safeAreaRegions = []
                        cell = Cell(controller: controller, coordinates: coordinates)
                        cells[item.id] = cell
                        controller.view.isHidden = true
                        scroll.document.addSubview(controller.view)
                        coordinates.view = controller.view
                        created = true
                    }
                    if (created || updateRoots || dirty.contains(item.id)) && refreshed.insert(item.id).inserted {
                        let measuredWidth = width
                        nextGeneration += 1
                        let generation = nextGeneration
                        cell.generation = generation
                        cell.controller.rootView = AnyView(source.row(item)
                            .environment(\.self, environment)
                            .environment(\.macChatWidth, width)
                            .environment(\.macChatCoordinates, cell.coordinates)
                            .frame(width: width, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { [weak self] height in
                                self?.heightChanged(height, id: item.id, width: measuredWidth, generation: generation)
                            })
                    }
                    if (created || cached[item.id] == nil || updateRoots || dirty.contains(item.id)), measuredIDs.insert(item.id).inserted {
                        let size = cell.controller.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
                        if size.height.isFinite, size.height > 0 {
                            cached[item.id] = ceil(size.height)
                            measured = layout.measure(size.height, at: index) || measured
                        }
                    }
                }
                positionDirty = positionDirty || measured
                if !measured { break }
            }
            resizeDocument()
            // Let AppKit keep its rubber-banding and momentum when only the viewport
            // moved. Correct the offset only after content geometry or a jump changed.
            if positionDirty { restore(anchor) }
            let range = layout.visible(in: scroll.contentView.bounds, overscan: scroll.contentSize.height)
            let visible = Set(range.map { items[$0].id })
            let x = (scroll.contentSize.width - width) / 2
            for index in range {
                if let view = cells[items[index].id]?.controller.view {
                    let frame = CGRect(x: x, y: layout.starts[index], width: width, height: layout.heights[index])
                    let prepare = view.isHidden || view.frame.size != frame.size || refreshed.contains(items[index].id)
                    if view.frame != frame { view.frame = frame }
                    if prepare {
                        view.layoutSubtreeIfNeeded()
                        view.isHidden = false
                        if view.window != nil { view.displayIfNeeded() }
                    }
                }
            }
            // Retire outgoing rows only after every replacement has its final frame
            // and a prepared display tree, within the same non-animated transaction.
            for id in Array(cells.keys) where !visible.contains(id) {
                cells.removeValue(forKey: id)?.controller.view.removeFromSuperview()
            }
            dirty.removeAll()
        }

        func heightChanged(_ height: CGFloat, id: String, width: CGFloat, generation: Int) {
            guard height.isFinite, height > 0, cells[id]?.generation == generation,
                  let index = layout.ids.firstIndex(of: id), abs(layout.heights[index] - ceil(height)) > 0.5 else { return }
            // Coalesce row-local changes; reports from retired or replaced roots can
            // never resize the current document, even if the same message is remounted.
            pendingHeights[id] = HeightReport(height: height, width: width, generation: generation)
            schedule()
        }

        func resizeDocument() {
            guard let scroll else { return }
            let size = CGSize(width: scroll.contentSize.width, height: max(layout.total, scroll.contentSize.height))
            if scroll.document.frame.size != size { scroll.document.setFrameSize(size) }
        }

        func restore(_ anchor: MacChatLayout.Anchor?) {
            guard let scroll else { return }
            let end = max(0, layout.total - scroll.contentSize.height)
            let y = follows ? end : anchor.flatMap { layout.offset(for: $0) } ?? scroll.contentView.bounds.minY
            let origin = CGPoint(x: 0, y: min(end, max(0, y)))
            if abs(scroll.contentView.bounds.minY - origin.y) > 0.5 {
                scroll.contentView.scroll(to: origin)
                scroll.reflectScrolledClipView(scroll.contentView)
            }
        }

        func setFollowing(_ value: Bool) {
            guard follows != value else { return }
            follows = value
            reportedFollow = value
            pendingFollowReport = value
            DispatchQueue.main.async { [weak self] in
                guard let self, follows == value else { return }
                if source?.following == value { pendingFollowReport = nil }
                else { source?.following = value }
            }
        }

        func requestEarlierIfNeeded() {
            guard let scroll, !follows, scroll.contentView.bounds.minY < scroll.contentSize.height / 2,
                  let first = items.dropFirst().first?.id ?? items.first?.id, lastTopRequest != first else { return }
            lastTopRequest = first
            DispatchQueue.main.async { [weak self] in self?.source?.nearTop() }
        }
    }
}

@MainActor final class ChatScrollView: NSScrollView {
    enum Destination { case previous, next, first, last }
    let document = ChatDocumentView()
    var changed: (() -> Void)?
    var gesture: ((Bool, NSEvent) -> Void)?
    var navigate: ((Destination) -> Void)?

    init() {
        super.init(frame: .zero)
        drawsBackground = false
        hasVerticalScroller = false
        hasHorizontalScroller = false
        verticalScrollElasticity = .allowed
        contentView.drawsBackground = false
        contentView.postsBoundsChangedNotifications = true
        documentView = document
        NotificationCenter.default.addObserver(self, selector: #selector(clipMoved),
                                              name: NSView.boundsDidChangeNotification, object: contentView)
        setAccessibilityLabel("Conversation transcript")
    }

    required init?(coder: NSCoder) { nil }

    @objc private func clipMoved() { changed?() }

    override func layout() {
        super.layout()
        changed?()
    }

    override func scrollWheel(with event: NSEvent) {
        gesture?(true, event)
        super.scrollWheel(with: event)
        gesture?(false, event)
    }

    override var acceptsFirstResponder: Bool { true }
    override func pageUp(_ sender: Any?) { navigate?(.previous) }
    override func pageDown(_ sender: Any?) { navigate?(.next) }
    override func scrollPageUp(_ sender: Any?) { navigate?(.previous) }
    override func scrollPageDown(_ sender: Any?) { navigate?(.next) }
    override func scrollToBeginningOfDocument(_ sender: Any?) { navigate?(.first) }
    override func scrollToEndOfDocument(_ sender: Any?) { navigate?(.last) }
}

@MainActor final class ChatDocumentView: NSView {
    override var isFlipped: Bool { true }

    override func accessibilityChildren() -> [Any]? {
        // Rows mounted while scrolling upward were added after newer rows.
        // VoiceOver must read visual order, not hosting-view creation order.
        NSAccessibility.unignoredChildren(from: subviews.sorted { $0.frame.minY < $1.frame.minY })
    }
}

/// Separate hosting roots need their menu anchors converted to window coordinates.
@MainActor final class MacChatCoordinates {
    weak var view: NSView?

    func windowFrame(_ frame: CGRect) -> CGRect {
        guard let view, let root = view.window?.contentView else { return frame }
        let result = view.convert(frame, to: root)
        return root.isFlipped ? result : CGRect(x: result.minX, y: root.bounds.height - result.maxY,
                                               width: result.width, height: result.height)
    }
}

extension EnvironmentValues {
    @Entry var macChatWidth: CGFloat? = nil
    @Entry var macChatCoordinates: MacChatCoordinates? = nil
}

enum MacTranscriptItem: Identifiable, Equatable {
    case message(ChatItem)
    case history(Bool)
    case intro
    case working(String?)

    var id: String {
        switch self {
        case .message(let item): "message-\(item.id)"
        case .history: "history"
        case .intro: "intro"
        case .working: "working"
        }
    }
}
#endif
