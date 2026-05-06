package com.subtitleedit.util

class CutPasteController {
    private var deletedIndicesDesc: List<Int> = emptyList()

    fun clear() {
        deletedIndicesDesc = emptyList()
    }

    fun markSingleCut(position: Int) {
        deletedIndicesDesc = listOf(position)
    }

    fun markMultiCut(positions: List<Int>) {
        deletedIndicesDesc = positions.distinct().sortedDescending()
    }

    fun hasPendingCut(): Boolean = deletedIndicesDesc.isNotEmpty()

    fun snapshotDeletedIndices(): Set<Int> = deletedIndicesDesc.toSet()

    fun adjustPastePositionAfterCut(originalPosition: Int): Int {
        if (deletedIndicesDesc.isEmpty()) return originalPosition
        val shifted = originalPosition - deletedIndicesDesc.count { it < originalPosition }
        return shifted.coerceAtLeast(0)
    }

    fun consumeDeletedIndicesDesc(): List<Int> {
        val result = deletedIndicesDesc
        deletedIndicesDesc = emptyList()
        return result
    }
}

