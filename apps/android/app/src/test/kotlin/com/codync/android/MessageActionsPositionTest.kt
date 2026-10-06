package com.codync.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageActionsPositionTest {
    private val viewport = Rect(0f, 80f, 400f, 650f)
    private val window = IntSize(400, 800)
    private val popup = IntSize(300, 230)
    private val longMessage = IntRect(16, -2400, 376, 3400)

    @Test fun aLongMessageUsesThePressInsteadOfItsOffscreenBottom() {
        val provider = MessageActionsPositionProvider(Offset(175f, 220f), viewport, 8)
        assertEquals(IntOffset(25, 228), provider.calculatePosition(longMessage, window, LayoutDirection.Ltr, popup))
    }

    @Test fun aPressNearTheComposerPlacesActionsAboveTheFinger() {
        val provider = MessageActionsPositionProvider(Offset(370f, 610f), viewport, 8)
        assertEquals(IntOffset(92, 372), provider.calculatePosition(longMessage, window, LayoutDirection.Ltr, popup))
    }

    @Test fun narrowKeyboardViewportsKeepActionsInsideTheVisibleChat() {
        val provider = MessageActionsPositionProvider(Offset(0f, 280f), Rect(0f, 80f, 320f, 350f), 8)
        assertEquals(IntOffset(8, 112), provider.calculatePosition(longMessage, IntSize(320, 800), LayoutDirection.Rtl, popup))
    }
}
