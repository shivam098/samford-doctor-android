package com.samford.doctor.input

import com.samford.doctor.editor.ToolKind
import org.junit.Assert.assertEquals
import org.junit.Test

class InputRouterTest {
    private val router = InputRouter(palmTouchMajorPx = 200f)
    private fun sample(tool: PointerTool, buttons: Int = 0, major: Float = 30f, pointers: Int = 1, t: Long = 10_000) =
        InputSample(tool, buttons, major, pointers, t)

    @Test
    fun stylusWritesWithTheSelectedTool() {
        assertEquals(Route.INK, router.route(sample(PointerTool.STYLUS), ToolKind.PEN, false))
        assertEquals(Route.INK, router.route(sample(PointerTool.STYLUS), ToolKind.HIGHLIGHTER, false))
        assertEquals(Route.LASSO, router.route(sample(PointerTool.STYLUS), ToolKind.LASSO, false))
        assertEquals(Route.STAMP_TAP, router.route(sample(PointerTool.STYLUS), ToolKind.STAMP, false))
    }

    @Test
    fun sPenSideButtonAndEraserTipErase() {
        assertEquals(Route.ERASE, router.route(sample(PointerTool.STYLUS, buttons = InputRouter.BUTTON_STYLUS_PRIMARY), ToolKind.PEN, false))
        assertEquals(Route.ERASE, router.route(sample(PointerTool.STYLUS, buttons = InputRouter.BUTTON_SECONDARY), ToolKind.PEN, false))
        assertEquals(Route.ERASE, router.route(sample(PointerTool.ERASER_TIP), ToolKind.PEN, false))
    }

    @Test
    fun fingersNavigateUnlessFingerWritingIsOn() {
        assertEquals(Route.NAVIGATE, router.route(sample(PointerTool.FINGER), ToolKind.PEN, fingerWriting = false))
        assertEquals(Route.INK, router.route(sample(PointerTool.FINGER), ToolKind.PEN, fingerWriting = true))
        assertEquals(Route.NAVIGATE, router.route(sample(PointerTool.FINGER, pointers = 2), ToolKind.PEN, fingerWriting = true))
    }

    @Test
    fun palmIsRejectedWhilePenIsNearOrWhenContactIsLarge() {
        router.noteStylus(10_000)
        assertEquals(Route.IGNORE, router.route(sample(PointerTool.FINGER, t = 10_200), ToolKind.PEN, false))
        assertEquals(Route.NAVIGATE, router.route(sample(PointerTool.FINGER, t = 10_000 + InputRouter.PALM_WINDOW_MS + 1), ToolKind.PEN, false))
        assertEquals(Route.IGNORE, router.route(sample(PointerTool.FINGER, major = 400f, t = 20_000), ToolKind.PEN, true))
    }
}
