package com.samford.doctor.ink

import kotlinx.serialization.Serializable

/**
 * A reversible change to one page: the items removed and the items added (by id). Every edit
 * (stroke, erase, move, resize, stamp, undo, redo) is one delta, so the autosave journal is an
 * append-only list of deltas and undo is simply the inverse delta.
 */
@Serializable
data class PageDelta(
    val removedStrokes: List<InkStroke> = emptyList(),
    val addedStrokes: List<InkStroke> = emptyList(),
    val removedStamps: List<InkStamp> = emptyList(),
    val addedStamps: List<InkStamp> = emptyList(),
) {
    val isEmpty: Boolean
        get() = removedStrokes.isEmpty() && addedStrokes.isEmpty() && removedStamps.isEmpty() && addedStamps.isEmpty()

    fun inverse() = PageDelta(
        removedStrokes = addedStrokes,
        addedStrokes = removedStrokes,
        removedStamps = addedStamps,
        addedStamps = removedStamps,
    )

    /** Combines two consecutive deltas into one undo step (used for a whole eraser gesture). */
    fun then(next: PageDelta): PageDelta {
        val nextRemovedStrokeIds = next.removedStrokes.map { it.id }.toSet()
        val nextRemovedStampIds = next.removedStamps.map { it.id }.toSet()
        val addedHere = addedStrokes.map { it.id }.toSet()
        val addedStampsHere = addedStamps.map { it.id }.toSet()
        return PageDelta(
            // Items this delta added and the next removed cancel out.
            removedStrokes = removedStrokes + next.removedStrokes.filter { it.id !in addedHere },
            addedStrokes = addedStrokes.filter { it.id !in nextRemovedStrokeIds } + next.addedStrokes,
            removedStamps = removedStamps + next.removedStamps.filter { it.id !in addedStampsHere },
            addedStamps = addedStamps.filter { it.id !in nextRemovedStampIds } + next.addedStamps,
        )
    }

    companion object {
        fun addStroke(stroke: InkStroke) = PageDelta(addedStrokes = listOf(stroke))
        fun addStamp(stamp: InkStamp) = PageDelta(addedStamps = listOf(stamp))
    }
}

/** Applies [delta]: removes items whose ids are in the removed lists, then appends added items. */
fun InkPage.apply(delta: PageDelta): InkPage {
    if (delta.isEmpty) return this
    val removedStrokeIds = delta.removedStrokes.mapTo(HashSet()) { it.id }
    val removedStampIds = delta.removedStamps.mapTo(HashSet()) { it.id }
    val addedStrokeIds = delta.addedStrokes.mapTo(HashSet()) { it.id }
    val addedStampIds = delta.addedStamps.mapTo(HashSet()) { it.id }
    return copy(
        strokes = strokes.filter { it.id !in removedStrokeIds && it.id !in addedStrokeIds } + delta.addedStrokes,
        stamps = stamps.filter { it.id !in removedStampIds && it.id !in addedStampIds } + delta.addedStamps,
    )
}
