package com.pixeldweller.instantpear.history

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

data class EditOp(
    val id: Long,
    val userId: String,
    val userName: String,
    val fileId: String,
    val timestamp: Long,
    val offset: Int,
    val oldText: String,
    val newText: String,
    val line: Int,
    var undone: Boolean = false
)

/**
 * One file's timeline.
 *
 * Conceptually: ops are either currently-applied to the document (`applied`) or
 * cached as an alternate branch (`displaced`). The `displaced` ops are anchored
 * at `forkPoint` (index in `applied` where they follow). Clicking a state on
 * either list restores that state; after a restore, what was previously applied
 * may land in `displaced` (overwriting any prior cached alt, per spec: only one
 * alternate edit history is kept).
 *
 * New edits do NOT clear `displaced` — the alt cache persists across new edits
 * and is only overwritten when a later restore displaces ops.
 */
class FileTimeline {
    val applied = mutableStateListOf<EditOp>()
    val displaced = mutableStateOf<List<EditOp>?>(null)
    val forkPoint = mutableStateOf(-1) // index in `applied` where `displaced` attaches

    /** Snapshot of document content at the moment the file was first shared. */
    val baseline = mutableStateOf<String?>(null)
    val baselineTimestamp = mutableStateOf(0L)
}
