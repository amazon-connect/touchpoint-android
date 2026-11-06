package com.amazon.connect.touchpoint.voice

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/** Web's useDraggable clamp: the widget stays wholly inside, from its anchor. */
class DraggableTest {
    private val rail = IntSize(100, 300)
    private val screen = IntSize(1000, 2000)

    private fun clamp(
        x: Int,
        y: Int,
        anchor: Alignment = Alignment.BottomStart,
        content: IntSize = rail,
        room: IntSize = screen,
    ) = Draggable.clamp(IntOffset(x, y), content, room, anchor, LayoutDirection.Ltr)

    @Test
    fun `inside, a drag goes where it is asked`() {
        assertEquals(IntOffset(250, -400), clamp(250, -400))
    }

    @Test
    fun `it stops at every edge, from a bottom-start anchor`() {
        assertEquals(IntOffset(0, 0), clamp(-50, 80))
        assertEquals(IntOffset(900, -1700), clamp(5000, -5000))
    }

    @Test
    fun `from a bottom-end anchor the room is to its start and above`() {
        assertEquals(IntOffset(-900, 0), clamp(-5000, 10, Alignment.BottomEnd))
        assertEquals(IntOffset(0, -1700), clamp(40, -9000, Alignment.BottomEnd))
    }

    @Test
    fun `let go, it snaps to the side its middle is nearer, at its height`() {
        // Bottom-start: the start edge is 0 across, the end 900.
        assertEquals(0, Draggable.snapX(IntOffset(300, -500), rail, screen, Alignment.BottomStart, LayoutDirection.Ltr))
        assertEquals(900, Draggable.snapX(IntOffset(500, -500), rail, screen, Alignment.BottomStart, LayoutDirection.Ltr))
        // Bottom-end: its own edge is 0, the far one -900.
        assertEquals(0, Draggable.snapX(IntOffset(-300, 0), rail, screen, Alignment.BottomEnd, LayoutDirection.Ltr))
        assertEquals(-900, Draggable.snapX(IntOffset(-600, 0), rail, screen, Alignment.BottomEnd, LayoutDirection.Ltr))
    }

    @Test
    fun `a widget bigger than its room stays at the anchor, and nothing measured yet is no offset`() {
        assertEquals(IntOffset(0, -100), clamp(300, -100, content = IntSize(1200, 300)))
        assertEquals(IntOffset.Zero, clamp(300, 300, content = IntSize.Zero))
    }
}
