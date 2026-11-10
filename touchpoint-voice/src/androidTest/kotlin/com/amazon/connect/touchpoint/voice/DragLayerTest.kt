package com.amazon.connect.touchpoint.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Web's useDraggable on a device: the widget follows the finger from its handle, and stops 8 inside the layer. */
@RunWith(AndroidJUnit4::class)
class DragLayerTest {
    @get:Rule val rule = createComposeRule()

    private fun show() =
        rule.setContent {
            Box(Modifier.size(360.dp, 640.dp).testTag("layer")) {
                DragLayer(Alignment.BottomStart) { handle ->
                    Column(Modifier.testTag("widget")) {
                        Box(Modifier.testTag("handle")) { handle() }
                        Box(Modifier.size(48.dp, 200.dp))
                    }
                }
            }
        }

    private fun widget() = rule.onNodeWithTag("widget").getBoundsInRoot()

    @Test
    fun theWidgetFollowsTheFingerAllTheWay() {
        show()
        val before = widget()
        rule.onNodeWithTag("handle").performTouchInput {
            down(center)
            // Past the touch slop first, then the measured move, in steps, as a finger does.
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 1f))
            repeat(20) { moveBy(Offset(10f, -15f)) }
        }
        // Measured before letting go: let go, it snaps to the nearer side.
        rule.waitForIdle()
        val after = widget()
        with(rule.density) {
            assertEquals(200f, (after.left - before.left).toPx(), 2f)
            assertEquals(-300f, (after.top - before.top).toPx(), 2f)
        }
        rule.onNodeWithTag("handle").performTouchInput { up() }
    }

    @Test
    fun itStopsEightInsideTheLayerAndComesBackAtOnce() {
        show()
        rule.onNodeWithTag("handle").performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(100f, -100f)) }
            up()
        }
        rule.waitForIdle()
        val layer = rule.onNodeWithTag("layer").getBoundsInRoot()
        val after = widget()
        assertEquals((layer.right - 8.dp).value, after.right.value, 1f)
        assertEquals((layer.top + 8.dp).value, after.top.value, 1f)
    }
}
