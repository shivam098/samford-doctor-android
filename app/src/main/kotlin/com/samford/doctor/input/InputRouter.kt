package com.samford.doctor.input

import com.samford.doctor.editor.ToolKind

enum class PointerTool { STYLUS, ERASER_TIP, FINGER, MOUSE, UNKNOWN }

/** What a new touch sequence will do. Decided once, at the first pointer-down. */
enum class Route { INK, ERASE, LASSO, STAMP_TAP, NAVIGATE, IGNORE }

data class InputSample(
    val tool: PointerTool,
    val buttonState: Int,
    val touchMajorPx: Float,
    val pointerCount: Int,
    val eventTimeMs: Long,
)

/**
 * Stylus-first input policy:
 *  - the S Pen writes with the selected tool; holding its side button erases temporarily;
 *  - fingers scroll and pinch-zoom, never write, unless finger writing is switched on;
 *  - palm rejection: finger contacts are ignored while the pen is on or hovering near the glass
 *    (within [PALM_WINDOW_MS]) and when the contact is palm-sized.
 * Pure logic (no Android types) so it is unit-tested directly.
 */
class InputRouter(var palmTouchMajorPx: Float) {
    private var lastStylusMs = Long.MIN_VALUE / 2

    fun noteStylus(eventTimeMs: Long) {
        lastStylusMs = eventTimeMs
    }

    fun stylusNear(eventTimeMs: Long): Boolean = eventTimeMs - lastStylusMs < PALM_WINDOW_MS

    fun route(sample: InputSample, tool: ToolKind, fingerWriting: Boolean): Route = when (sample.tool) {
        PointerTool.STYLUS -> {
            noteStylus(sample.eventTimeMs)
            if (isEraserButton(sample.buttonState)) Route.ERASE else toolRoute(tool)
        }
        PointerTool.ERASER_TIP -> {
            noteStylus(sample.eventTimeMs)
            Route.ERASE
        }
        PointerTool.MOUSE -> toolRoute(tool)
        PointerTool.FINGER, PointerTool.UNKNOWN -> when {
            stylusNear(sample.eventTimeMs) -> Route.IGNORE
            sample.touchMajorPx > palmTouchMajorPx -> Route.IGNORE
            fingerWriting && sample.pointerCount == 1 -> toolRoute(tool)
            else -> Route.NAVIGATE
        }
    }

    private fun toolRoute(tool: ToolKind): Route = when (tool) {
        ToolKind.PEN, ToolKind.HIGHLIGHTER -> Route.INK
        ToolKind.ERASER -> Route.ERASE
        ToolKind.LASSO -> Route.LASSO
        ToolKind.STAMP -> Route.STAMP_TAP
    }

    companion object {
        /** Same values as android.view.MotionEvent button constants. */
        const val BUTTON_SECONDARY = 2
        const val BUTTON_STYLUS_PRIMARY = 32
        const val BUTTON_STYLUS_SECONDARY = 64
        const val PALM_WINDOW_MS = 400L

        /** Samsung S Pen reports its side button as STYLUS_PRIMARY (some builds as SECONDARY). */
        fun isEraserButton(buttonState: Int): Boolean =
            buttonState and (BUTTON_STYLUS_PRIMARY or BUTTON_SECONDARY or BUTTON_STYLUS_SECONDARY) != 0
    }
}
