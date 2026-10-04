#if os(macOS)
import Foundation

/// Geometry only: scrolling does not publish SwiftUI state or rebuild messages.
struct MacChatLayout {
    struct Anchor: Equatable {
        let id: String
        let offset: CGFloat
    }

    private(set) var ids: [String] = []
    private(set) var heights: [CGFloat] = []
    private(set) var starts: [CGFloat] = []
    private(set) var total: CGFloat = 20
    private var top: CGFloat = 8
    private var bottom: CGFloat = 12

    mutating func setInsets(top: CGFloat, bottom: CGFloat) {
        self.top = 8 + max(0, top)
        self.bottom = 12 + max(0, bottom)
        rebuild()
    }

    mutating func reset(ids: [String], heights: [CGFloat]) {
        precondition(ids.count == heights.count)
        self.ids = ids
        self.heights = heights.map { max(1, $0.isFinite ? $0 : 100) }
        rebuild()
    }

    mutating func measure(_ height: CGFloat, at index: Int) -> Bool {
        guard height.isFinite, height > 0, abs(heights[index] - height) > 0.5 else { return false }
        heights[index] = ceil(height)
        rebuild()
        return true
    }

    private mutating func rebuild() {
        var y = top
        starts = heights.map { height in
            defer { y += height }
            return y
        }
        total = y + bottom
    }

    func index(at y: CGFloat) -> Int? {
        guard !ids.isEmpty else { return nil }
        var lower = 0
        var upper = ids.count
        while lower < upper {
            let middle = (lower + upper) / 2
            if starts[middle] + heights[middle] <= y { lower = middle + 1 }
            else { upper = middle }
        }
        return min(lower, ids.count - 1)
    }

    func visible(in viewport: CGRect, overscan: CGFloat) -> Range<Int> {
        guard let first = index(at: viewport.minY - overscan),
              let last = index(at: viewport.maxY + overscan) else { return 0..<0 }
        return first..<(last + 1)
    }

    func anchor(at y: CGFloat) -> Anchor? {
        index(at: y).map { Anchor(id: ids[$0], offset: y - starts[$0]) }
    }

    func offset(for anchor: Anchor) -> CGFloat? {
        ids.firstIndex(of: anchor.id).map { starts[$0] + min(anchor.offset, heights[$0]) }
    }
}
#endif
