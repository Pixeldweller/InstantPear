package com.pixeldweller.instantpear.history

import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** A concrete "replace (offset, oldLength) with newText" edit to apply. */
data class InverseOp(
    val fileId: String,
    val offset: Int,
    val oldLength: Int,
    val newText: String
)

/**
 * Host-only. Tracks per-file edit timelines, supports restore-to-state (both
 * directions) and per-user Ctrl-Z with rebase against later foreign ops.
 */
class HistoryService {
    val timelines = mutableStateMapOf<String, FileTimeline>()
    private val nextId = AtomicLong(1)

    private val softCap = 100         // squash beyond this many applied ops
    private val hardCap = 5000        // absolute eviction cap

    fun get(fileId: String): FileTimeline =
        timelines.getOrPut(fileId) { FileTimeline() }

    /** Called once when the host first shares the file. No-op if baseline already set. */
    fun setBaselineIfAbsent(fileId: String, content: String) {
        val t = get(fileId)
        if (t.baseline.value != null) return
        t.baseline.value = content
        t.baselineTimestamp.value = System.currentTimeMillis()
    }

    /**
     * Replace the entire document with the baseline snapshot. All currently-applied
     * ops become the new displaced (alt) branch. Returns the single InverseOp to apply.
     * Returns null if no baseline was recorded or doc already at baseline state.
     */
    fun restoreToBaseline(fileId: String, currentDocLength: Int): InverseOp? {
        val t = timelines[fileId] ?: return null
        val base = t.baseline.value ?: return null
        if (t.applied.isEmpty()) return null // already at baseline

        // Move all currently-applied into displaced
        t.displaced.value = t.applied.toList()
        t.forkPoint.value = -1
        t.applied.clear()

        return InverseOp(
            fileId = fileId,
            offset = 0,
            oldLength = currentDocLength,
            newText = base
        )
    }

    fun findOp(fileId: String, opId: Long): EditOp? {
        val t = timelines[fileId] ?: return null
        t.applied.firstOrNull { it.id == opId }?.let { return it }
        return t.displaced.value?.firstOrNull { it.id == opId }
    }

    /** True if [opId] lives in the applied list. */
    fun isInApplied(fileId: String, opId: Long): Boolean =
        timelines[fileId]?.applied?.any { it.id == opId } ?: false

    /** Append a new edit. Does NOT clear [displaced] — alt cache persists across edits. */
    fun record(
        userId: String,
        userName: String,
        fileId: String,
        offset: Int,
        oldText: String,
        newText: String,
        line: Int
    ): EditOp {
        val t = get(fileId)
        val op = EditOp(
            id = nextId.getAndIncrement(),
            userId = userId,
            userName = userName,
            fileId = fileId,
            timestamp = System.currentTimeMillis(),
            offset = offset,
            oldText = oldText,
            newText = newText,
            line = line
        )
        t.applied.add(op)
        maybeSquash(t)
        // Hard cap: evict oldest if absolutely necessary.
        while (t.applied.size > hardCap) {
            t.applied.removeAt(0)
            // forkPoint must shift if it referred to the removed op
            if (t.forkPoint.value >= 0) t.forkPoint.value -= 1
        }
        return op
    }

    /**
     * Restore to state at an op that lives in `applied`. Reverse-apply ops
     * after [targetOpId]; those ops become the new `displaced` (overwriting any prior).
     */
    fun restoreToApplied(fileId: String, targetOpId: Long): List<InverseOp> {
        val t = timelines[fileId] ?: return emptyList()
        val idx = t.applied.indexOfFirst { it.id == targetOpId }
        if (idx < 0 || idx == t.applied.lastIndex) return emptyList()

        val tail = t.applied.subList(idx + 1, t.applied.size).toList()
        val inverses = tail.asReversed().map { op ->
            InverseOp(fileId, op.offset, op.newText.length, op.oldText)
        }

        t.displaced.value = tail
        t.forkPoint.value = idx
        repeat(t.applied.size - (idx + 1)) { t.applied.removeAt(t.applied.size - 1) }
        return inverses
    }

    /**
     * Restore to state at an op that lives in `displaced` (forward on the alt branch).
     * Reverse-apply everything after forkPoint in applied, then forward-apply displaced[0..target].
     * New applied = applied[0..forkPoint] + displaced[0..target]
     * New displaced = the ops just reversed from applied (anchored at same forkPoint)
     * Any displaced ops after the target are dropped (only one alt kept).
     */
    fun restoreToDisplaced(fileId: String, targetOpId: Long): List<InverseOp> {
        val t = timelines[fileId] ?: return emptyList()
        val disp = t.displaced.value ?: return emptyList()
        val targetIdx = disp.indexOfFirst { it.id == targetOpId }
        if (targetIdx < 0) return emptyList()
        val fork = t.forkPoint.value
        if (fork < -1 || fork >= t.applied.size) return emptyList()

        val toReverse = if (fork + 1 < t.applied.size) {
            t.applied.subList(fork + 1, t.applied.size).toList()
        } else emptyList()
        val toForward = disp.subList(0, targetIdx + 1)

        val inverses = mutableListOf<InverseOp>()
        // Reverse newest first
        toReverse.asReversed().forEach { op ->
            inverses.add(InverseOp(fileId, op.offset, op.newText.length, op.oldText))
        }
        // Forward-apply each displaced op
        toForward.forEach { op ->
            inverses.add(InverseOp(fileId, op.offset, op.oldText.length, op.newText))
        }

        // Swap lists
        repeat(t.applied.size - (fork + 1)) { t.applied.removeAt(t.applied.size - 1) }
        toForward.forEach { t.applied.add(it) }
        t.displaced.value = toReverse              // now cached as alt (overwrites)
        // forkPoint stays at same applied index — that's the divergence point in the
        // textual state that both branches share.

        return inverses
    }

    /**
     * Attempt per-user undo. Returns rebased inverse or null if refused
     * (overlapping foreign edit would make undo unsafe).
     */
    fun undoLastByUser(fileId: String, userId: String): InverseOp? {
        val t = timelines[fileId] ?: return null
        val idx = t.applied.indexOfLast { it.userId == userId && !it.undone }
        if (idx < 0) return null
        val target = t.applied[idx]

        var rangeStart = target.offset
        var rangeEnd = target.offset + target.newText.length

        for (i in idx + 1 until t.applied.size) {
            val op = t.applied[i]
            if (op.undone) continue

            val opOldEnd = op.offset + op.oldText.length
            val overlaps = op.offset < rangeEnd && opOldEnd > rangeStart
            if (overlaps) return null

            val delta = op.newText.length - op.oldText.length
            if (op.offset <= rangeStart) {
                rangeStart += delta
                rangeEnd += delta
            }
        }

        target.undone = true
        // Force recomposition (mutation of var on data class)
        t.applied[idx] = target
        return InverseOp(
            fileId = fileId,
            offset = rangeStart,
            oldLength = target.newText.length,
            newText = target.oldText
        )
    }

    /** Preview-only: what would [undoLastByUser] return, without mutating state. */
    fun peekUndoForUser(fileId: String, userId: String): EditOp? {
        val t = timelines[fileId] ?: return null
        val idx = t.applied.indexOfLast { it.userId == userId && !it.undone }
        if (idx < 0) return null
        val target = t.applied[idx]

        var rangeStart = target.offset
        var rangeEnd = target.offset + target.newText.length
        for (i in idx + 1 until t.applied.size) {
            val op = t.applied[i]
            if (op.undone) continue
            val opOldEnd = op.offset + op.oldText.length
            val overlaps = op.offset < rangeEnd && opOldEnd > rangeStart
            if (overlaps) return null
            val delta = op.newText.length - op.oldText.length
            if (op.offset <= rangeStart) { rangeStart += delta; rangeEnd += delta }
        }
        return target
    }

    fun clear() {
        timelines.clear()
        nextId.set(1)
    }

    // ----- squashing -----

    /**
     * Collapse pairs of consecutive same-user edits on same/neighboring lines
     * within the older region (indices below applied.size - softCap). Keeps
     * restore granularity for recent activity while bounding memory.
     */
    private fun maybeSquash(t: FileTimeline) {
        if (t.applied.size <= softCap) return
        val cutoff = t.applied.size - softCap
        var i = 0
        while (i < cutoff - 1 && i < t.applied.size - 1) {
            val a = t.applied[i]
            val b = t.applied[i + 1]
            val merged = tryMerge(a, b)
            if (merged != null) {
                t.applied[i] = merged
                t.applied.removeAt(i + 1)
                // forkPoint shifts if the removed op was at or before it
                if (t.forkPoint.value >= i + 1) t.forkPoint.value -= 1
                // don't advance i — try merging with next
            } else {
                i++
            }
        }
    }

    private fun tryMerge(a: EditOp, b: EditOp): EditOp? {
        if (a.userId != b.userId) return null
        if (a.undone || b.undone) return null
        if (abs(a.line - b.line) > 1) return null

        // Case 1: consecutive pure inserts at contiguous offset
        if (a.oldText.isEmpty() && b.oldText.isEmpty() &&
            b.offset == a.offset + a.newText.length) {
            return a.copy(
                newText = a.newText + b.newText,
                timestamp = a.timestamp,
                id = a.id
            )
        }

        // Case 2: consecutive backspace chain
        if (a.newText.isEmpty() && b.newText.isEmpty() &&
            b.offset + b.oldText.length == a.offset) {
            return a.copy(
                offset = b.offset,
                oldText = b.oldText + a.oldText,
                newText = "",
                timestamp = a.timestamp,
                id = a.id
            )
        }

        // Case 3: consecutive forward deletes (Delete key)
        if (a.newText.isEmpty() && b.newText.isEmpty() &&
            b.offset == a.offset) {
            return a.copy(
                oldText = a.oldText + b.oldText,
                newText = "",
                timestamp = a.timestamp,
                id = a.id
            )
        }

        return null
    }
}
