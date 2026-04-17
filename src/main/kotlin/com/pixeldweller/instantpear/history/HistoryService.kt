package com.pixeldweller.instantpear.history

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicLong

data class InverseOp(
    val fileId: String,
    val offset: Int,
    val oldLength: Int,
    val newText: String
)

class ReactiveFileHistory {
    val main = mutableStateListOf<EditOp>()
    val alt = mutableStateOf<List<EditOp>?>(null)
    var altForkedAt: Long? = null

    fun findById(id: Long): EditOp? = main.firstOrNull { it.id == id }
    fun indexOf(id: Long): Int = main.indexOfFirst { it.id == id }
}

/**
 * Host-only. Tracks per-file edit history, supports restore-to-state and
 * per-user Ctrl-Z with rebase against later foreign ops.
 */
class HistoryService {
    val histories = mutableStateMapOf<String, ReactiveFileHistory>()
    private val nextId = AtomicLong(1)
    private val maxOpsPerFile = 5000

    fun get(fileId: String): ReactiveFileHistory =
        histories.getOrPut(fileId) { ReactiveFileHistory() }

    /** Append a new edit to main. If an alt branch exists, a new non-restore edit drops it. */
    fun record(
        userId: String,
        userName: String,
        fileId: String,
        offset: Int,
        oldText: String,
        newText: String
    ): EditOp {
        val h = get(fileId)
        // New divergent edit invalidates stale alt
        if (h.alt.value != null) {
            h.alt.value = null
            h.altForkedAt = null
        }
        val op = EditOp(
            id = nextId.getAndIncrement(),
            userId = userId,
            userName = userName,
            fileId = fileId,
            timestamp = System.currentTimeMillis(),
            offset = offset,
            oldText = oldText,
            newText = newText
        )
        h.main.add(op)
        while (h.main.size > maxOpsPerFile) h.main.removeAt(0)
        return op
    }

    /**
     * Restore file to state at [targetId]. Returns inverse ops to apply in order.
     * Ops after [targetId] are moved to alt (overwriting any existing alt).
     */
    fun restore(fileId: String, targetId: Long): List<InverseOp> {
        val h = histories[fileId] ?: return emptyList()
        val idx = h.indexOf(targetId)
        if (idx < 0) return emptyList()
        if (idx == h.main.lastIndex) return emptyList()

        val tail = h.main.subList(idx + 1, h.main.size).toList()
        // Inverse-apply newest-first. For each tail op, oldLength = newText.length.
        // Shift offset by net length change of ops applied AFTER it (already reversed), which cancels
        // out because we undo newest first — the earlier ops' offsets are still valid against the
        // document state we produce as we go.
        val inverses = tail.asReversed().map { op ->
            InverseOp(
                fileId = fileId,
                offset = op.offset,
                oldLength = op.newText.length,
                newText = op.oldText
            )
        }

        h.alt.value = tail
        h.altForkedAt = targetId
        // Truncate main
        repeat(h.main.size - (idx + 1)) { h.main.removeAt(h.main.size - 1) }
        return inverses
    }

    /**
     * Attempt per-user undo. Returns rebased inverse op or null if refused
     * (overlapping foreign edit would make undo unsafe).
     */
    fun undoLastByUser(fileId: String, userId: String): InverseOp? {
        val h = histories[fileId] ?: return null
        val idx = h.main.indexOfLast { it.userId == userId && !it.undone }
        if (idx < 0) return null
        val target = h.main[idx]

        var rangeStart = target.offset
        var rangeEnd = target.offset + target.newText.length

        for (i in idx + 1 until h.main.size) {
            val op = h.main[i]
            if (op.undone) continue

            val opOldEnd = op.offset + op.oldText.length
            // Overlap against the range we need to delete (target's newText)
            val overlaps = op.offset < rangeEnd && opOldEnd > rangeStart
            if (overlaps) return null

            val delta = op.newText.length - op.oldText.length
            if (op.offset <= rangeStart) {
                rangeStart += delta
                rangeEnd += delta
            }
        }

        target.undone = true
        // Force recomposition (undone is var on data class, Compose won't see mutation).
        h.main[idx] = target
        return InverseOp(
            fileId = fileId,
            offset = rangeStart,
            oldLength = target.newText.length,
            newText = target.oldText
        )
    }

    fun clear() {
        histories.clear()
        nextId.set(1)
    }
}
