package suwayomi.tachidesk.global.impl

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class JvmTouchTest {
    @Test
    fun `view resources expose CSS pixel metrics without Android runtime`() {
        val view = View(null)
        val metrics = view.resources.displayMetrics
        assertSame(metrics, view.resources.displayMetrics)
        assertEquals(1f, metrics.density)
        assertEquals(160, metrics.densityDpi)
        assertEquals(160f, metrics.xdpi)
    }

    @Test
    fun `touch event snapshots pointer data for asynchronous dispatch`() {
        val pointer =
            MotionEvent.PointerProperties().apply {
                id = 3
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        val coords =
            MotionEvent.PointerCoords().apply {
                x = 42f
                y = 88f
                pressure = 0.4f
            }
        val event =
            MotionEvent.obtain(
                100L,
                120L,
                MotionEvent.ACTION_MOVE,
                1,
                arrayOf(pointer),
                arrayOf(coords),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        coords.x = 99f
        assertEquals(MotionEvent.ACTION_MOVE, event.actionMasked)
        assertEquals(42f, event.x)
        assertEquals(88f, event.y)
        assertEquals(0.4f, event.pressure)
        assertEquals(3, event.getPointerId(0))
        event.recycle()
    }
}
