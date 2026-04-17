package com.pixeldweller.instantpear.history

data class EditOp(
    val id: Long,
    val userId: String,
    val userName: String,
    val fileId: String,
    val timestamp: Long,
    val offset: Int,
    val oldText: String,
    val newText: String,
    var undone: Boolean = false
)

class FileHistory {
    val main: MutableList<EditOp> = mutableListOf()
    var alt: List<EditOp>? = null
    var altForkedAt: Long? = null

    fun findById(id: Long): EditOp? = main.firstOrNull { it.id == id }
}
